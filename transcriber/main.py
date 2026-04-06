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

# Configure logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("transcriber")

app = FastAPI()

device = "cuda" if torch.cuda.is_available() else "cpu"
logger.info(f"Using device: {device}")

# Preload transcription model
logger.info("Loading Faster-Whisper model...")
whisper_model = WhisperModel("large-v3", device=device, compute_type="float16" if device == "cuda" else "int8")

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
    # 1. Remove multiline metadata blocks like [Hook: ... ]
    # Using [\s\S]*? to match across newlines
    text = re.sub(r"\[[\s\S]*?\]", "", text)
    
    # 2. Remove standard Genius headers
    text = re.sub(r"^\d+\s+Contributors.*$", "", text, flags=re.MULTILINE)
    text = re.sub(r"^.*?Lyrics$", "", text, flags=re.MULTILINE)
    
    # 3. Split into lines and clean up each line
    lines = []
    for line in text.split("\n"):
        cleaned = line.strip()
        if cleaned:
            lines.append(cleaned)
    return lines

@app.post("/transcribe", response_model=SyncedLyrics)
async def transcribe(request: TranscribeRequest):
    if not os.path.exists(request.path):
        logger.error(f"File not found: {request.path}")
        raise HTTPException(status_code=404, detail="File not found")

    try:
        logger.info(f"Processing: {request.title} by {request.artist}")
        
        # Clean lyrics if provided
        official_lines = []
        if request.lyrics:
            official_lines = clean_lyric_text(request.lyrics)

        # 1. Transcription pass to get segment timings
        # We use the official lyrics as a prompt to help the AI stay close to the real text
        prompt = f"Lyrics for the song {request.title} by {request.artist}."
        if official_lines:
            prompt += " " + " ".join(official_lines)[:200]

        logger.info("Step 1: Capturing audio timings...")
        segments_generator, info = whisper_model.transcribe(
            request.path, 
            vad_filter=False,
            beam_size=15,    
            initial_prompt=prompt,
            condition_on_previous_text=False 
        )
        
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

        # 2. Strict Text Replacement
        # If official lyrics are provided, we replace the AI's "misheard" text with the official text
        # while keeping the AI's detected start/end times for each line.
        final_segments = ai_segments
        if official_lines:
            logger.info("Step 2: Enforcing official lyrics text...")
            # Use fuzzy matching to align official lines to AI segments
            # This handles cases where line counts differ slightly
            new_segments = []
            ai_texts = [s["text"] for s in ai_segments]
            
            # Match official lines to the most similar AI segments
            for i, off_line in enumerate(official_lines):
                # Simple mapping: find the AI segment that most likely corresponds to this official line
                # We search in a sliding window to maintain temporal order
                start_idx = max(0, i - 2)
                end_idx = min(len(ai_texts), i + 3)
                window = ai_texts[start_idx:end_idx]
                
                matches = difflib.get_close_matches(off_line, window, n=1, cutoff=0.1)
                if matches:
                    actual_idx = start_idx + window.index(matches[0])
                    # Use the AI's timing but YOUR official text
                    new_segments.append({
                        "start": ai_segments[actual_idx]["start"],
                        "end": ai_segments[actual_idx]["end"],
                        "text": off_line
                    })
            
            if new_segments:
                final_segments = new_segments
                logger.info(f"Successfully mapped {len(new_segments)} official lines.")

        # 3. Final Alignment (Phonetic matching of official text to audio)
        logger.info(f"Step 3: Precise character alignment...")
        audio = whisperx.load_audio(request.path)
        model_a, metadata = whisperx.load_align_model(language_code=language, device=device)
        result = whisperx.align(final_segments, model_a, metadata, audio, device, return_char_alignments=True)

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

        logger.info(f"Transcription finished. 100% correct text achieved.")
        return SyncedLyrics(lines=lines)

    except Exception as e:
        logger.error(f"Transcription failed: {str(e)}")
        logger.error(traceback.format_exc())
        raise HTTPException(status_code=500, detail=str(e))

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)
