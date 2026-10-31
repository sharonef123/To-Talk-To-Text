#!/usr/bin/env python3
"""System-wide speech-to-text dictation utility for Windows 11.

- Global hotkey (Ctrl+Shift+Space) to start/stop dictation
- System tray icon shows recording state
- Local speech-to-text via faster-whisper
- Hebrew ('he') / English ('en') language toggle
- Transcribed text pasted into focused field via Ctrl+V
"""

import os
import sys
import json
import time
import threading
import logging
import tempfile
import base64
from datetime import datetime

# --- Core libraries ---
import sounddevice as sd
import numpy as np
from faster_whisper import WhisperModel

# --- Tray / hotkey ---
import pystray
from pystray import MenuItem as Item
from PIL import Image

# --- Clipboard / keyboard ---
import pyperclip
import keyboard

# --- Global state ---
PAUSE_KEY = "<ctrl>+<shift>+space"
LANGUAGE_TOGGLE = "<ctrl>+<shift>+l"  # secondary toggle for language

# Language codes for faster-whisper
LANG_EN = "en"
LANG_HE = "he"

# Audio settings
SAMPLE_RATE = 16000
CHANNELS = 1
DTYPE = "int16"

# Default language (detected from browser locale or English)
current_lang = LANG_EN
is_recording = False
audio_frames = []
icon_image_idle = None
icon_image_recording = None

# Lock for thread-safe state updates
state_lock = threading.Lock()


def detect_language_code() -> str:
    """Determine language code from system locale or default to English."""
    try:
        # Try reading locale
        with open("/systemlocale", "r") as f:
            locale = f.read().strip().lower()
        if "he" in locale or "iw" in locale:
            return LANG_HE
    except Exception:
        pass
    return LANG_EN


def load_icon_from_text(text: str, width: int = 64, height: int = 64) -> Image.Image:
    """Generate a simple monochrome icon from text using PIL.
    Creates a minimalist 'mic' glyph representation.
    """
    img = Image.new("RGB", (width, height), (30, 30, 30))
    # Very simple: just return a blank-ish icon
    # In a real app, you'd use PIL drawing or pre-made icons
    return img


def create_icons() -> tuple:
    """Create idle and recording tray icons.
    Returns (idle_img, recording_img) as PIL Images.
    """
    # Create simple icons: grey circle for idle, red circle for recording
    idle = Image.new("RGB", (64, 64), (50, 50, 50))
    recording = Image.new("RGB", (64, 64), (220, 50, 50))
    # Draw a simple mic shape on recording icon
    from PIL import ImageDraw
    draw = ImageDraw.Draw(recording)
    # Simple mic body
    draw.rectangle((28, 22, 36, 42), fill=(255, 255, 255))
    # Mic cap
    draw.rectangle((32, 18, 32, 22), fill=(255, 255, 255))
    return idle, recording


def get_current_language() -> str:
    """Return the human-readable language name."""
    return "Hebrew" if current_lang == LANG_HE else "English"


def set_language(lang_code: str):
    """Switch the dictation language globally."""
    global current_lang
    with state_lock:
        current_lang = lang_code
    lang_name = get_current_language()
    logging.info(f"Language set to {lang_name} ({lang_code})")


def toggle_language():
    """Toggle between Hebrew and English."""
    global current_lang
    with state_lock:
        if current_lang == LANG_HE:
            current_lang = LANG_EN
        else:
            current_lang = LANG_HE
        lang_name = get_current_language()
        logging.info(f"Language toggled to {lang_name} ({current_lang})")


def inject_text(text: str):
    """Inject transcribed text into the currently focused field via clipboard + Ctrl+V."""
    try:
        # Clean up the text
        text = text.strip()
        if not text:
            return

        # Set clipboard text
        pyperclip.copy(text)

        # Simulate Ctrl+V to paste
        # Press Ctrl first, then V, then release Ctrl
        keyboard.press("ctrl")
        keyboard.press("v")
        keyboard.release("v")
        keyboard.release("ctrl")

        logging.info(f"Injected text: '{text}'")
    except Exception as e:
        logging.error(f"Failed to inject text: {e}")


def transcribe_audio(audio_np: np.ndarray) -> str:
    """Transcribe audio numpy array using faster-whisper.
    Returns the detected text.
    """
    global current_lang

    # Write audio to a temp file faster-whisper can read
    try:
        with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as tmp:
            tmp_path = tmp.name
            # faster-whisper expects WAV format; write using soundfile or export
            # We'll use numpy to write a simple WAV
            import struct
            # Ensure float32 or int16
            audio_int16 = (audio_np * 32767).astype(np.int16)
            with open(tmp_path, "wb") as f:
                # RIFF header
                f.write(b"RIFF")
                f.write(struct.pack("<I", 36 + len(audio_int16) * 2 - 8))
                f.write(b"WAVE")
                f.write(b"fmt ")
                f.write(struct.pack("<I", 16))
                f.write(struct.pack("<H", 1))  # PCM
                f.write(struct.pack("<H", CHANNELS))
                f.write(struct.pack("<I", SAMPLE_RATE))
                f.write(struct.pack("<I", SAMPLE_RATE * CHANNELS * 2))  # byte rate
                f.write(struct.pack("<H", CHANNELS * 2))  # block align
                f.write(struct.pack("<H", 16))  # bits per sample
                f.write(audio_int16.tobytes())
                f.write(b"data")
                f.write(struct.pack("<I", len(audio_int16) * 2))
                f.write(audio_int16.tobytes())

        # Transcribe with faster-whisper
        model = WhisperModel("tiny", device="cpu", compute_type="int8")
        segments, info = model.transcribe(
            tmp_path,
            language=current_lang,
            beam_size=5,
            vad_filter=True,
        )

        text_parts = []
        for segment in segments:
            text_parts.append(segment.text)

        combined = " ".join(text_parts).strip()

        # Clean up: remove timestamps or extra whitespace
        combined = " ".join(combined.split())

        os.unlink(tmp_path)
        return combined

    except Exception as e:
        logging.error(f"Transcription error: {e}")
        # Fallback: try without temp file or return error
        return ""


def record_audio_stop_event() -> tuple:
    """Record audio frames until the stop event is set.
    Returns (audio_np, duration) or (None, 0) if interrupted.
    """
    global is_recording, audio_frames

    audio_frames = []
    prev_level = 0

    with sd.InputStream(
        samplerate=SAMPLE_RATE,
        channels=CHANNELS,
        dtype=DTYPE,
        blocksize=1024,
    ) as stream:
        while is_recording:
            try:
                data, overflowed = stream.read(1024)
                if overflowed:
                    continue
                audio_frames.append(data)
            except Exception as e:
                logging.debug(f"Stream read error: {e}")
                break

    if not audio_frames:
        return None, 0

    # Combine all frames
    combined = b"".join(audio_frames)
    audio_array = np.frombuffer(combined, dtype=np.int16)
    # Normalize to float [-1, 1]
    audio_float = audio_array.astype(np.float32) / 32767.0
    return audio_float, len(audio_frames) / SAMPLE_RATE


def start_dictation_cycle():
    """One complete cycle: record -> transcribe -> inject -> loop check."""
    global is_recording, audio_frames

    with state_lock:
        is_recording = True
        audio_frames = []

    logging.info("=== Dictation started ===")

    # Record audio while hotkey is held
    audio_float, duration = record_audio_stop_event()

    with state_lock:
        is_recording = False

    logging.info(f"=== Dictation stopped after {duration:.2f}s ===")

    if audio_float is None or len(audio_float) < SAMPLE_RATE * 0.5:
        logging.info("Too little audio captured, listening again...")
        # Recursive retry: try again immediately
        start_dictation_cycle()
        return

    # Transcribe
    text = transcribe_audio(audio_float)
    if not text:
        logging.warning("No speech detected")
        # Still listen again
        start_dictation_cycle()
        return

    logging.info(f"Transcribed: '{text}'")

    # Inject into focused field
    inject_text(text)

    # Loop: if still active, listen again
    with state_lock:
        if is_recording:
            start_dictation_cycle()


def on_hotkey_toggle():
    """Called when Ctrl+Shift+Space is pressed - toggle recording state."""
    global is_recording

    with state_lock:
        is_recording = not is_recording
        recording_state = is_recording

    lang_name = get_current_language()
    logging.info(f"Hotkey pressed. Recording: {recording_state}, Language: {lang_name}")

    # Update tray icon
    if icon_image_idle and icon_image_recording:
        if recording_state:
            tray_icon.icon = icon_image_recording
        else:
            tray_icon.icon = icon_image_idle

    if recording_state:
        # Start a new dictation cycle in a thread
        threading.Thread(target=start_dictation_cycle, daemon=True).start()
    else:
        logging.info("Dictation paused by user")


def on_language_toggle():
    """Called when Ctrl+Shift+L is pressed - toggle language."""
    toggle_language()
    lang_name = get_current_language()
    logging.info(f"Language switched to {lang_name}")


def create_menu() -> pystray.Menu:
    """Create the system tray menu."""
    return pystray.Menu(
        Item("Toggle Dictation", on_hotkey_toggle, default=True),
        Item("Language: " + get_current_language(), lambda: None, enabled=False),
        Item("Hebrew", lambda: set_language(LANG_HE), checked=lambda item: current_lang == LANG_HE),
        Item("English", lambda: set_language(LANG_EN), checked=lambda item: current_lang == LANG_EN),
        Item("Exit", lambda: tray_icon.quit()),
    )


def setup_logging():
    """Configure logging to console and a rotating file."""
    log_dir = os.path.join(os.environ.get("APPDATA", "."), "dictate")
    os.makedirs(log_dir, exist_ok=True)
    log_file = os.path.join(log_dir, "dictate.log")

    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s [%(levelname)s] %(message)s",
        handlers=[
            logging.FileHandler(log_file, encoding="utf-8"),
            logging.StreamHandler(sys.stdout),
        ],
    )


def main():
    global tray_icon, icon_image_idle, icon_image_recording

    setup_logging()
    logging.info("Starting dictate.py utility...")

    # Create icons
    icon_image_idle, icon_image_recording = create_icons()

    # Initialize language from detected locale
    set_language(detect_language_code())
    logging.info(f"Initial language: {get_current_language()} ({current_lang})")

    # Create tray menu
    menu = create_menu()

    # Create tray icon
    tray_icon = pystray.Icon(
        "dictate",
        icon_image_idle,
        "Dictation Tool",
        menu,
    )

    # Set up hotkeys
    # Register Ctrl+Shift+Space for toggle
    keyboard.add_hotkey(PAUSE_KEY, on_hotkey_toggle, suppress=True)
    # Register Ctrl+Shift+L for language toggle
    keyboard.add_hotkey(LANGUAGE_TOGGLE, on_language_toggle, suppress=True)

    logging.info("Hotkeys registered:")
    logging.info(f"  Ctrl+Shift+Space: Toggle dictation")
    logging.info(f"  Ctrl+Shift+L: Toggle language ({get_current_language()})")

    # Initial icon update
    with state_lock:
        current_state = is_recording
    if current_state and icon_image_recording:
        tray_icon.icon = icon_image_recording
    else:
        tray_icon.icon = icon_image_idle

    # Run the tray icon (blocking)
    try:
        tray_icon.run()
    except KeyboardInterrupt:
        logging.info("Interrupted by user")
    finally:
        # Clean up hotkeys
        keyboard.unhook_all()
        logging.info("Cleanup complete, exiting.")


if __name__ == "__main__":
    main()