import os
import torch
import whisperx
import gc
from faster_whisper import WhisperModel
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
from typing import List, Optional
import logging
import traceback
import re
import difflib
from langdetect import detect, DetectorFactory
from contextlib import asynccontextmanager
import subprocess
import shutil

DetectorFactory.seed = 0

# Configure logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("transcriber")

# Global model references
models = {
    "device": "cpu" if os.getenv("FORCE_CPU", "false").lower() == "true" else ("cuda" if torch.cuda.is_available() else "cpu")
}

def clear_vram():
    if models["device"] == "cuda":
        gc.collect()
        torch.cuda.empty_cache()
        torch.cuda.synchronize()

def get_whisper_model():
    model_name = "medium"
    logger.info(f"Loading Faster-Whisper model ({model_name}) on {models['device']}...")
    return WhisperModel(
        model_name, 
        device=models["device"], 
        compute_type="auto",
        download_root="/root/.cache/faster-whisper"
    )

@asynccontextmanager
async def lifespan(app: FastAPI):
    logger.info(f"Transcriber starting on {models['device']}")
    yield
    clear_vram()

app = FastAPI(lifespan=lifespan)

@app.get("/health")
async def health():
    return {"status": "ok", "device": models["device"]}

class TranscribeRequest(BaseModel):
    path: str
    artist: Optional[str] = ""
    title: Optional[str] = ""
    lyrics: Optional[str] = None

class LyricChar(BaseModel):
    char: str
    startTime: int # ms
    endTime: int # ms

class LyricWord(BaseModel):
    text: str
    startTime: int # ms
    endTime: int # ms
    chars: List[LyricChar]

class LyricLine(BaseModel):
    startTime: int # ms
    endTime: int # ms
    words: List[LyricWord]

class SyncedLyrics(BaseModel):
    lines: List[LyricLine]

def clean_lyric_text(text):
    text = re.sub(r"\[[\s\S]*?\]", "", text)
    text = re.sub(r"^\d+\s+Contributors.*$", "", text, flags=re.MULTILINE)
    text = re.sub(r"^.*?Lyrics$", "", text, flags=re.MULTILINE)
    lines = [line.strip() for line in text.split("\n") if line.strip()]
    return lines

def isolate_vocals(audio_path: str) -> str:
    logger.info("Step 0: Isolating vocals (Ultra Precision Mode)...")
    output_dir = "/tmp/demucs"
    if os.path.exists(output_dir):
        shutil.rmtree(output_dir)
    os.makedirs(output_dir, exist_ok=True)

    command = [
        "demucs",
        "--two-stems", "vocals",
        "-o", output_dir,
        "--device", models["device"],
        audio_path
    ]
    
    try:
        subprocess.run(command, check=True, capture_output=True)
        filename = os.path.splitext(os.path.basename(audio_path))[0]
        vocal_path = os.path.join(output_dir, "htdemucs", filename, "vocals.wav")
        
        if os.path.exists(vocal_path):
            logger.info("Vocal isolation complete.")
            return vocal_path
        else:
            raise Exception("Demucs output not found at expected path.")
    except Exception as e:
        logger.error(f"Vocal isolation failed: {e}")
        return audio_path

@app.post("/transcribe", response_model=SyncedLyrics)
async def transcribe(request: TranscribeRequest):
    if not os.path.exists(request.path):
        logger.error(f"File not found: {request.path}")
        raise HTTPException(status_code=404, detail="File not found")

    vocal_audio_path = request.path
    temp_files = []

    try:
        logger.info(f"Processing: {request.title} by {request.artist}")
        is_ultra_mode = request.lyrics is not None and len(request.lyrics.strip()) > 0
        official_lines = []
        language = None
        
        # --- STAGE 0: VOCAL ISOLATION ---
        if is_ultra_mode:
            vocal_audio_path = isolate_vocals(request.path)
            if vocal_audio_path != request.path:
                temp_files.append(os.path.dirname(os.path.dirname(vocal_audio_path)))
            clear_vram()

        if request.lyrics:
            official_lines = clean_lyric_text(request.lyrics)
            try:
                language = detect(" ".join(official_lines))
                logger.info(f"Language detected from lyrics text: {language}")
            except:
                pass
        
        prompt = f"Lyrics for the song {request.title} by {request.artist}."
        if official_lines:
            prompt += " " + " ".join(official_lines)[:200]

        # --- STAGE 1: TIMING CAPTURE ---
        logger.info(f"Step 1: Timing capture (Precision mode)...")
        whisper_model = get_whisper_model()
        
        segments_generator, info = whisper_model.transcribe(
            vocal_audio_path, 
            vad_filter=False if is_ultra_mode else True,
            language=language,
            beam_size=5 if is_ultra_mode else 15,
            initial_prompt=prompt,
            condition_on_previous_text=False 
        )
        
        if language is None:
            language = info.language
            
        ai_segments = []
        for s in segments_generator:
            ai_segments.append({
                "start": s.start,
                "end": s.end,
                "text": s.text
            })

        del whisper_model
        clear_vram()

        if not ai_segments:
            logger.warning("No audio segments detected.")
            return SyncedLyrics(lines=[])

        # --- STAGE 2: GLOBAL MAPPING ---
        final_segments = ai_segments
        if official_lines:
            logger.info(f"Step 2: Globally aligning {len(official_lines)} lines using character-weighting...")
            new_segments = []
            ai_texts = [s["text"].lower().strip() for s in ai_segments]
            off_texts = [l.lower().strip() for l in official_lines]
            
            matcher = difflib.SequenceMatcher(None, off_texts, ai_texts)
            for tag, i1, i2, j1, j2 in matcher.get_opcodes():
                if tag in ('equal', 'replace'):
                    off_subset = official_lines[i1:i2]
                    ai_subset = ai_segments[j1:j2]
                    if not ai_subset: continue
                        
                    start_t = ai_subset[0]["start"]
                    end_t = ai_subset[-1]["end"]
                    
                    if len(off_subset) == 1:
                        new_segments.append({"start": start_t, "end": end_t, "text": off_subset[0]})
                    elif len(off_subset) > 1:
                        # Join multiple official lines into ONE unit for the aligner.
                        # This lets the phoneme aligner decide the split point instead of us guessing.
                        joined_text = " ".join(off_subset)
                        new_segments.append({"start": start_t, "end": end_t, "text": joined_text})
            
            if new_segments:
                final_segments = new_segments

        # --- STAGE 3: PHONEME ALIGNMENT ---
        logger.info(f"Step 3: Phoneme alignment for {len(final_segments)} lines...")
        audio = whisperx.load_audio(vocal_audio_path)
        
        try:
            model_a, metadata = whisperx.load_align_model(
                language_code=language, 
                device=models["device"]
            )
            result = whisperx.align(final_segments, model_a, metadata, audio, models["device"], return_char_alignments=True)
            del model_a
            clear_vram()
        except Exception as e:
            if models["device"] == "cuda":
                logger.warning(f"Alignment failed on CUDA ({e}), falling back to CPU...")
                model_a, metadata = whisperx.load_align_model(
                    language_code=language, 
                    device="cpu"
                )
                result = whisperx.align(final_segments, model_a, metadata, audio, "cpu", return_char_alignments=True)
                del model_a
            else:
                raise e

        # --- STAGE 4: RECONSTRUCT LINES ---
        # Map the aligned words back to their original line structure
        lines = []
        all_aligned_words = []
        for segment in result["segments"]:
            for word_info in segment.get("words", []):
                if "start" in word_info and "end" in word_info:
                    word_start = int(word_info["start"] * 1000)
                    word_end = int(word_info["end"] * 1000)
                    word_text = word_info["word"]
                    
                    chars = []
                    if "chars" in word_info:
                        for char_info in word_info["chars"]:
                            if "start" in char_info and "end" in char_info:
                                chars.append(LyricChar(
                                    char=char_info["char"],
                                    startTime=int(char_info["start"] * 1000),
                                    endTime=int(char_info["end"] * 1000)
                                ))
                    
                    if not chars and len(word_text) > 0:
                        char_duration = (word_end - word_start) / len(word_text)
                        for i, char in enumerate(word_text):
                            chars.append(LyricChar(
                                char=char, 
                                startTime=int(word_start + (i * char_duration)), 
                                endTime=int(word_start + ((i + 1) * char_duration))
                            ))
                    
                    all_aligned_words.append(LyricWord(
                        text=word_text,
                        startTime=word_start,
                        endTime=word_end,
                        chars=chars
                    ))

        if is_ultra_mode and official_lines:
            # Re-group words into original lines based on word count
            current_word_ptr = 0
            for off_line in official_lines:
                line_words = []
                # Number of words in the official line
                off_words_count = len(off_line.split())
                
                for _ in range(off_words_count):
                    if current_word_ptr < len(all_aligned_words):
                        line_words.append(all_aligned_words[current_word_ptr])
                        current_word_ptr += 1
                
                if line_words:
                    lines.append(LyricLine(
                        startTime=line_words[0].startTime,
                        endTime=line_words[-1].endTime,
                        words=line_words
                    ))
        else:
            # Fallback for transcription mode: preserve AI segment boundaries
            current_word_ptr = 0
            for segment in result["segments"]:
                seg_words = []
                for _ in range(len(segment.get("words", []))):
                    if current_word_ptr < len(all_aligned_words):
                        seg_words.append(all_aligned_words[current_word_ptr])
                        current_word_ptr += 1
                if seg_words:
                    lines.append(LyricLine(
                        startTime=seg_words[0].startTime,
                        endTime=seg_words[-1].endTime,
                        words=seg_words
                    ))

        logger.info(f"Sync complete. Final lines: {len(lines)}")
        return SyncedLyrics(lines=lines)

    except Exception as e:
        logger.error(f"Sync failed: {str(e)}")
        logger.error(traceback.format_exc())
        raise HTTPException(status_code=500, detail=str(e))
    finally:
        for temp_dir in temp_files:
            try:
                if os.path.exists(temp_dir):
                    shutil.rmtree(temp_dir)
            except:
                pass
        clear_vram()

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)
