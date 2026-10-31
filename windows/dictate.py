"""To Talk To Text (By Efroni) - Windows 11 background dictation.

- Ctrl+Shift+Space  : start / stop recording. On stop, the audio is transcribed
                      locally and typed into the focused window.
- Ctrl+Shift+L      : switch language (Hebrew / English).
- Ctrl+Shift+Q      : quit.

Transcription uses faster-whisper (free, offline after the first model download).
Text is delivered via the clipboard and a Ctrl+V keystroke; the previous clipboard
contents are restored afterwards.
"""

import ctypes
import sys
import threading
import time

import keyboard
import numpy as np
import pyperclip
import sounddevice as sd
from faster_whisper import WhisperModel

SAMPLE_RATE = 16000
MODEL_NAME = "small"  # "base" is faster on weak hardware; "medium" is more accurate
LANGUAGES = {"en": "English", "he": "Hebrew"}

HOTKEY_TOGGLE = "ctrl+shift+space"
HOTKEY_LANGUAGE = "ctrl+shift+l"
HOTKEY_QUIT = "ctrl+shift+q"

_state_lock = threading.Lock()
_recording = False
_frames: list[np.ndarray] = []
_stream: sd.InputStream | None = None
_language = "en"
_model: WhisperModel | None = None


def log(msg: str) -> None:
    print(f"[dictate] {msg}", flush=True)


def notify(msg: str) -> None:
    """Show a small message box without blocking the hotkey thread."""
    threading.Thread(
        target=lambda: ctypes.windll.user32.MessageBoxW(0, msg, "To Talk To Text", 0x40),
        daemon=True,
    ).start()


def get_model() -> WhisperModel:
    global _model
    if _model is None:
        log(f"Loading Whisper model '{MODEL_NAME}' (first run downloads it)...")
        _model = WhisperModel(MODEL_NAME, device="auto", compute_type="int8")
    return _model


def _audio_callback(indata, frames, time_info, status) -> None:
    if status:
        log(f"audio status: {status}")
    with _state_lock:
        if _recording:
            _frames.append(indata.copy())


def start_recording() -> None:
    global _recording, _stream
    with _state_lock:
        if _recording:
            return
        _frames.clear()
        _stream = sd.InputStream(
            samplerate=SAMPLE_RATE, channels=1, dtype="float32", callback=_audio_callback
        )
        _stream.start()
        _recording = True
    log(f"Recording ({LANGUAGES[_language]})...")


def stop_recording_and_type() -> None:
    global _recording, _stream
    with _state_lock:
        if not _recording:
            return
        _recording = False
        _stream.stop()
        _stream.close()
        _stream = None
        audio = np.concatenate(_frames).flatten() if _frames else np.zeros(0, np.float32)
    # Transcribe off the hotkey thread so the keyboard hook stays responsive.
    threading.Thread(target=_transcribe_and_type, args=(audio, _language), daemon=True).start()


def _transcribe_and_type(audio: np.ndarray, language: str) -> None:
    if audio.size < SAMPLE_RATE // 4:  # under 0.25 s - ignore accidental taps
        log("Recording too short, ignored.")
        return
    try:
        segments, _ = get_model().transcribe(
            audio, language=language, beam_size=5, vad_filter=True
        )
        text = "".join(seg.text for seg in segments).strip()
    except Exception as exc:  # keep the tool alive on engine errors
        log(f"Transcription failed: {exc}")
        notify(f"Transcription failed:\n{exc}")
        return
    if not text:
        log("No speech detected.")
        return
    type_text(text + " ")


def type_text(text: str) -> None:
    """Paste text into the active window via the clipboard, then restore the clipboard."""
    try:
        previous = pyperclip.paste()
    except Exception:
        previous = None
    pyperclip.copy(text)
    time.sleep(0.05)
    keyboard.send("ctrl+v")
    time.sleep(0.25)
    if previous is not None:
        try:
            pyperclip.copy(previous)
        except Exception:
            pass
    log(f"Typed {len(text)} characters.")


def toggle_recording() -> None:
    if _recording:
        stop_recording_and_type()
    else:
        start_recording()


def toggle_language() -> None:
    global _language
    _language = "he" if _language == "en" else "en"
    log(f"Language: {LANGUAGES[_language]}")
    notify(f"Dictation language: {LANGUAGES[_language]}")


def main() -> None:
    log("To Talk To Text running.")
    log(f"{HOTKEY_TOGGLE}: start/stop | {HOTKEY_LANGUAGE}: language | {HOTKEY_QUIT}: quit")
    keyboard.add_hotkey(HOTKEY_TOGGLE, toggle_recording, suppress=True)
    keyboard.add_hotkey(HOTKEY_LANGUAGE, toggle_language, suppress=True)
    keyboard.add_hotkey(HOTKEY_QUIT, lambda: sys.exit(0), suppress=True)
    # Preload the model in the background so the first dictation is not delayed.
    threading.Thread(target=get_model, daemon=True).start()
    keyboard.wait()


if __name__ == "__main__":
    main()
