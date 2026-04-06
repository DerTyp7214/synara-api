import os
import torch
import whisperx
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

DetectorFactory.seed = 0

# Configure logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("transcriber")

# Global model references
models = {
    "whisper": None,
    "device": "cuda" if torch.cuda.is_available() else "cpu"
}

@asynccontextmanager
async def lifespan(app: FastAPI):
    # Load model on startup
    logger.info(f"Using device: {models['device']}")
    logger.info("Loading Faster-Whisper model (large-v3)...")
    try:
        models["whisper"] = WhisperModel(
            "large-v3", 
            device=models["device"], 
            compute_type="auto"
        )
        logger.info(f"Model loaded successfully using compute_type: auto")
    except Exception as e:
        logger.error(f"Failed to load model: {e}")
    yield
    # Clean up on shutdown
    models["whisper"] = None

app = FastAPI(lifespan=lifespan)

@app.get("/health")
async def health():
    if models["whisper"] is None:
        raise HTTPException(status_code=503, detail="Model is still loading")
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

@app.post("/transcribe", response_model=SyncedLyrics)
async def transcribe(request: TranscribeRequest):
    if models["whisper"] is None:
        raise HTTPException(status_code=503, detail="Model not loaded yet")
        
    if not os.path.exists(request.path):
        logger.error(f"File not found: {request.path}")
        raise HTTPException(status_code=404, detail="File not found")

    try:
        logger.info(f"Processing: {request.title} by {request.artist}")
        
        is_alignment_only = request.lyrics is not None and len(request.lyrics.strip()) > 0
        official_lines = []
        language = None
        
        if is_alignment_only:
            official_lines = clean_lyric_text(request.lyrics)
            try:
                language = detect(" ".join(official_lines))
                logger.info(f"Language detected from lyrics text: {language}")
            except:
                pass
        
        prompt = f"Lyrics for the song {request.title} by {request.artist}."
        if official_lines:
            prompt += " " + " ".join(official_lines)[:200]

        logger.info(f"Step 1: Timing capture (Speed optimization: {is_alignment_only})...")
        segments_generator, info = models["whisper"].transcribe(
            request.path, 
            vad_filter=True,
            language=language,
            beam_size=1 if is_alignment_only else 15,
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

        if not ai_segments:
            return SyncedLyrics(lines=[])

        final_segments = ai_segments
        if official_lines:
            logger.info("Step 2: Mapping official text to detected timestamps...")
            new_segments = []
            ai_texts = [s["text"] for s in ai_segments]
            
            for i, off_line in enumerate(official_lines):
                start_idx = max(0, i - 3)
                end_idx = min(len(ai_texts), i + 4)
                window = ai_texts[start_idx:end_idx]
                
                matches = difflib.get_close_matches(off_line, window, n=1, cutoff=0.1)
                if matches:
                    actual_idx = start_idx + window.index(matches[0])
                    new_segments.append({
                        "start": ai_segments[actual_idx]["start"],
                        "end": ai_segments[actual_idx]["end"],
                        "text": off_line
                    })
            
            if new_segments:
                final_segments = new_segments

        logger.info(f"Step 3: Phoneme alignment for {len(final_segments)} lines...")
        audio = whisperx.load_audio(request.path)
        model_a, metadata = whisperx.load_align_model(language_code=language, device=models["device"])
        result = whisperx.align(final_segments, model_a, metadata, audio, models["device"], return_char_alignments=True)

        lines = []
        for segment in result["segments"]:
            words = []
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
                    
                    words.append(LyricWord(
                        text=word_text,
                        startTime=word_start,
                        endTime=word_end,
                        chars=chars
                    ))
            
            if words:
                lines.append(LyricLine(
                    startTime=int(segment["start"] * 1000),
                    endTime=int(segment["end"] * 1000),
                    words=words
                ))

        logger.info(f"Sync complete. Efficiency optimized.")
        return SyncedLyrics(lines=lines)

    except Exception as e:
        logger.error(f"Sync failed: {str(e)}")
        logger.error(traceback.format_exc())
        raise HTTPException(status_code=500, detail=str(e))

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)
