"""Talk→To→Text (By Efroni) - Windows 11 background dictation.

- Ctrl+Shift+Space  : start / stop recording. On stop, the audio is transcribed
                      locally and typed into the focused window.
- Ctrl+Shift+L      : switch language (Hebrew / English).
- Ctrl+Shift+Q      : quit.

Transcription tries faster-whisper's "tiny" model first (small enough to load
instantly, no multi-hundred-MB download stall on first run). If that model
can't be loaded or fails to transcribe for any reason, this falls back to
Google's free Web Speech API (via the `speech_recognition` package) rather
than hanging or crashing. A tray icon shows Ready / Listening / Transcribing
status as non-blocking notifications.

Text is delivered via the clipboard and a Ctrl+V keystroke; the previous clipboard
contents are restored afterwards.
"""

import ctypes
import io
import sys
import threading
import time

import keyboard
import numpy as np
import pyperclip
import pystray
import sounddevice as sd
from faster_whisper import WhisperModel
from PIL import Image, ImageDraw

SAMPLE_RATE = 16000
MODEL_NAME = "tiny"  # smallest Whisper model - loads fast, no heavy download stall
LANGUAGES = {"en": "English", "he": "Hebrew"}
# faster-whisper uses bare ISO-639-1 codes; the Google Web Speech fallback
# (via speech_recognition) wants a BCP-47 locale tag.
GOOGLE_LANG_TAGS = {"en": "en-US", "he": "he-IL"}

HOTKEY_TOGGLE = "ctrl+shift+space"
HOTKEY_LANGUAGE = "ctrl+shift+l"
HOTKEY_QUIT = "ctrl+shift+q"

APP_TITLE = "Talk→To→Text (By Efroni)"

_state_lock = threading.Lock()
_recording = False
_frames: list[np.ndarray] = []
_stream: sd.InputStream | None = None
_language = "en"
_model: WhisperModel | None = None
_whisper_broken = False  # set once faster-whisper has failed, to stop retrying it
_tray: pystray.Icon | None = None


def log(msg: str) -> None:
    # Plain ASCII arrow here: --windowed builds have no real console, and when
    # run from source some Windows terminals are still on a legacy codepage
    # that can't encode the brand glyph (U+2192).
    print(f"[Talk-To-Text] {msg}", flush=True)


def notify(msg: str) -> None:
    """Show a blocking message box - reserved for real errors the user must see."""
    threading.Thread(
        target=lambda: ctypes.windll.user32.MessageBoxW(0, msg, APP_TITLE, 0x40),
        daemon=True,
    ).start()


def status(msg: str) -> None:
    """Non-blocking status ping (tray balloon when available, log line always)."""
    log(msg)
    if _tray is not None:
        try:
            _tray.notify(msg, title=APP_TITLE)
        except Exception:
            pass


def make_tray_icon(recording: bool) -> Image.Image:
    """Draw a small dot icon - grey idle, red while recording. No bundled asset
    needed, so this looks identical whether run from source or frozen by PyInstaller."""
    size = 64
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    color = (201, 52, 58, 255) if recording else (76, 139, 245, 255)
    draw.ellipse((4, 4, size - 4, size - 4), fill=color)
    draw.rectangle((size // 2 - 6, 16, size // 2 + 6, 40), fill=(255, 255, 255, 255))
    draw.arc((size // 2 - 16, 24, size // 2 + 16, 52), start=0, end=180, fill=(255, 255, 255, 255), width=4)
    return img


def update_tray(recording: bool) -> None:
    if _tray is None:
        return
    try:
        _tray.icon = make_tray_icon(recording)
        _tray.title = f"{APP_TITLE} - {'Listening…' if recording else 'Ready'}"
    except Exception:
        pass


def get_model() -> WhisperModel | None:
    global _model, _whisper_broken
    if _whisper_broken:
        return None
    if _model is None:
        try:
            log(f"Loading Whisper model '{MODEL_NAME}' (first run downloads it)...")
            _model = WhisperModel(MODEL_NAME, device="auto", compute_type="int8")
            status("Ready")
        except Exception as exc:
            log(f"Whisper model unavailable ({exc}); will use Google Web Speech instead.")
            _whisper_broken = True
            status("Ready (online fallback)")
            return None
    return _model


def _audio_callback(indata, frames, time_info, status_flags) -> None:
    if status_flags:
        log(f"audio status: {status_flags}")
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
    update_tray(True)
    status(f"Listening... ({LANGUAGES[_language]})")


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
    update_tray(False)
    # Transcribe off the hotkey thread so the keyboard hook stays responsive.
    threading.Thread(target=_transcribe_and_type, args=(audio, _language), daemon=True).start()


def _transcribe_with_whisper(audio: np.ndarray, language: str) -> str | None:
    model = get_model()
    if model is None:
        return None
    try:
        segments, _ = model.transcribe(audio, language=language, beam_size=5, vad_filter=True)
        return "".join(seg.text for seg in segments).strip()
    except Exception as exc:
        log(f"Whisper transcription failed ({exc}); falling back to Google Web Speech.")
        return None


def _transcribe_with_google(audio: np.ndarray, language: str) -> str | None:
    try:
        import speech_recognition as sr
    except Exception as exc:
        log(f"speech_recognition unavailable: {exc}")
        return None
    try:
        pcm16 = (np.clip(audio, -1.0, 1.0) * 32767).astype(np.int16)
        audio_data = sr.AudioData(pcm16.tobytes(), SAMPLE_RATE, 2)
        recognizer = sr.Recognizer()
        tag = GOOGLE_LANG_TAGS.get(language, "en-US")
        return recognizer.recognize_google(audio_data, language=tag).strip()
    except Exception as exc:
        log(f"Google Web Speech fallback failed: {exc}")
        return None


def _transcribe_and_type(audio: np.ndarray, language: str) -> None:
    if audio.size < SAMPLE_RATE // 4:  # under 0.25 s - ignore accidental taps
        log("Recording too short, ignored.")
        return
    status("Transcribing...")
    text = _transcribe_with_whisper(audio, language)
    if not text:
        text = _transcribe_with_google(audio, language)
    if not text:
        log("No speech detected.")
        status("Ready")
        return
    type_text(text + " ")
    status("Ready")


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
    status(f"Language: {LANGUAGES[_language]}")


def quit_app() -> None:
    if _tray is not None:
        _tray.stop()
    sys.exit(0)


def build_tray() -> pystray.Icon:
    menu = pystray.Menu(
        pystray.MenuItem("Toggle dictation (Ctrl+Shift+Space)", lambda: toggle_recording(), default=True),
        pystray.MenuItem("Switch language (Ctrl+Shift+L)", lambda: toggle_language()),
        pystray.MenuItem("Quit (Ctrl+Shift+Q)", lambda: quit_app()),
    )
    return pystray.Icon("talk-to-text", make_tray_icon(False), f"{APP_TITLE} - Ready", menu)


def main() -> None:
    global _tray
    log("Talk-To-Text (By Efroni) running.")
    log(f"{HOTKEY_TOGGLE}: start/stop | {HOTKEY_LANGUAGE}: language | {HOTKEY_QUIT}: quit")
    keyboard.add_hotkey(HOTKEY_TOGGLE, toggle_recording, suppress=True)
    keyboard.add_hotkey(HOTKEY_LANGUAGE, toggle_language, suppress=True)
    keyboard.add_hotkey(HOTKEY_QUIT, quit_app, suppress=True)
    # Preload the model in the background so the first dictation is not delayed.
    threading.Thread(target=get_model, daemon=True).start()

    _tray = build_tray()
    _tray.run()  # blocks the main thread; keeps the process alive and shows the tray icon


if __name__ == "__main__":
    main()
