#!/usr/bin/env python3
import sys
import time
import requests
import pygame
import os
import argparse
import json
import hashlib
import shutil
import re
import textwrap

# Use Green for active characters, White for inactive
HL = "\033[1;32m"
END = "\033[0m"

def get_cache_path(audio_path, lyrics_text):
    cache_dir = os.path.join(os.path.dirname(__file__), ".cache")
    os.makedirs(cache_dir, exist_ok=True)
    content = f"{audio_path}_{lyrics_text if lyrics_text else ''}"
    h = hashlib.md5(content.encode()).hexdigest()
    return os.path.join(cache_dir, f"{h}.json")

def test_sync(local_audio_path, artist, title, lyrics_path=None, service_audio_path=None, use_cache=True):
    if not os.path.exists(local_audio_path):
        print(f"Error: Local audio file not found: {local_audio_path}")
        return

    lyrics_text = None
    if lyrics_path and os.path.exists(lyrics_path):
        with open(lyrics_path, "r") as f:
            lyrics_text = f.read()

    cache_path = get_cache_path(local_audio_path, lyrics_text)
    data = None
    if use_cache and os.path.exists(cache_path):
        print(f"--- USING CACHED SYNC DATA ---")
        try:
            with open(cache_path, "r") as f:
                data = json.load(f)
        except: pass

    if data is None:
        remote_path = service_audio_path if service_audio_path else local_audio_path
        print(f"--- REQUESTING SYNC FROM SERVICE ---")
        url = "http://localhost:8000/transcribe"
        payload = {"path": remote_path, "artist": artist, "title": title, "lyrics": lyrics_text}
        try:
            response = requests.post(url, json=payload, timeout=900)
            response.raise_for_status()
            data = response.json()
            with open(cache_path, "w") as f:
                json.dump(data, f)
        except Exception as e:
            print(f"Error connecting to service: {e}")
            if hasattr(e, 'response') and e.response is not None:
                print(f"Response: {e.response.text}")
            return

    print(f"--- STARTING PLAYBACK ---")
    print(f"Controls: [LEFT/RIGHT] Adjust offset (100ms) | [SPACE] Pause | [ESC] Quit")
    
    pygame.init()
    # We need a small window to capture key events on Linux
    pygame.display.set_mode((200, 100))
    pygame.mixer.init()
    pygame.mixer.music.load(local_audio_path)
    pygame.mixer.music.play()

    lines = data.get("lines", [])
    last_rendered_lines = 0
    last_line_idx = -1
    offset_ms = 150 # Default offset to handle reported pygame latency
    paused = False

    # Clear screen once and move to top
    sys.stdout.write("\033[2J\033[H")
    sys.stdout.flush()

    try:
        running = True
        while running:
            for event in pygame.event.get():
                if event.type == pygame.QUIT:
                    running = False
                if event.type == pygame.KEYDOWN:
                    if event.key == pygame.K_LEFT:
                        offset_ms -= 100
                    if event.key == pygame.K_RIGHT:
                        offset_ms += 100
                    if event.key == pygame.K_SPACE:
                        paused = not paused
                        if paused: pygame.mixer.music.pause()
                        else: pygame.mixer.music.unpause()
                    if event.key == pygame.K_ESCAPE:
                        running = False

            if not pygame.mixer.music.get_busy() and not paused:
                break

            current_ms = pygame.mixer.music.get_pos() + offset_ms
            
            active_line = None
            current_line_idx = -1
            for idx, line in enumerate(lines):
                if line["startTime"] <= current_ms <= line["endTime"]:
                    active_line = line
                    current_line_idx = idx
                    break
            
            # Move cursor back to the start of the lyric block
            if last_rendered_lines > 0:
                sys.stdout.write(f"\033[{last_rendered_lines}F")

            output = ""
            if not active_line:
                output = f"[{current_ms:06d}ms] (Offset: {offset_ms:+}ms) ..."
            else:
                output = f"[{current_ms:06d}ms] (Offset: {offset_ms:+}ms) "
                for word in active_line["words"]:
                    word_str = ""
                    if "chars" in word and word["chars"]:
                        for char_info in word["chars"]:
                            c = char_info['char']
                            if char_info["startTime"] <= current_ms <= char_info["endTime"]:
                                word_str += f"{HL}{c}{END}"
                            elif char_info["endTime"] < current_ms:
                                word_str += f"{HL}{c}{END}"
                            else:
                                word_str += c
                    else:
                        if word["startTime"] <= current_ms <= word["endTime"]:
                            word_str = f"{HL}{word['text']}{END}"
                        else:
                            word_str = word["text"]
                    output += word_str + " "

            # Calculate wrapped height
            cols, _ = shutil.get_terminal_size((80, 20))
            raw_text = output.replace(HL, "").replace(END, "")
            wrapped = textwrap.wrap(raw_text, width=cols-2)
            num_lines = len(wrapped) if wrapped else 1
            
            # Clear and print
            sys.stdout.write("\033[J") # Clear from cursor to bottom
            sys.stdout.write(output + "\n")
            sys.stdout.flush()
            
            last_rendered_lines = num_lines
            last_line_idx = current_line_idx
            time.sleep(0.01)
            
    except KeyboardInterrupt:
        pass
    finally:
        pygame.mixer.music.stop()
        pygame.quit()

    print("\n--- SYNC TEST FINISHED ---")

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Test synchronized lyrics with character-level highlighting.")
    parser.add_argument("audio", help="Local path to the audio file for playback")
    parser.add_argument("artist", help="Artist name")
    parser.add_argument("title", help="Song title")
    parser.add_argument("--lyrics", help="Path to local LRC/text file", default=None)
    parser.add_argument("--service-path", help="Path the service sees (e.g. /data/music.flac)", default=None)
    parser.add_argument("--path-map", help="Automatically map local path prefix to service prefix (e.g. /home/typ/Music:/data)", default=None)
    parser.add_argument("--no-cache", action="store_true", help="Disable caching and force a new sync")

    args = parser.parse_args()
    
    sp = None
    if args.path_map and ":" in args.path_map:
        lr, rr = args.path_map.split(":", 1)
        if args.audio.startswith(lr): sp = args.audio.replace(lr, rr, 1)
    
    test_sync(args.audio, args.artist, args.title, args.lyrics, sp, not args.no_cache)
