"""Talk→To→Text (By Efroni) - Windows 11 background dictation.

A small frameless, always-on-top floating pill (bottom-right of the screen)
mirrors the Chrome extension's UI: a mic button that glows red while
recording, a HE/EN language toggle, and a minimize button.

- Ctrl+Shift+Space  : start / stop recording. On stop, the audio is transcribed
                      and typed into the focused window.
- Ctrl+Shift+L      : switch language (Hebrew / English).
- Ctrl+Shift+Q      : quit.

Transcription uses Google's free Web Speech API (via the `speech_recognition`
package) - no local model to download, so there is never a multi-hundred-MB
stall on first run; it just needs an internet connection. Text is delivered
via the clipboard and a Ctrl+V keystroke; the previous clipboard contents are
restored afterwards.
"""

import sys
import threading
import time
import tkinter as tk

import keyboard
import numpy as np
import pyperclip
import sounddevice as sd
import speech_recognition as sr

SAMPLE_RATE = 16000
LANGUAGES = {"en": "English", "he": "Hebrew"}
# speech_recognition's Google Web Speech backend wants a BCP-47 locale tag.
GOOGLE_LANG_TAGS = {"en": "en-US", "he": "he-IL"}

HOTKEY_TOGGLE = "ctrl+shift+space"
HOTKEY_LANGUAGE = "ctrl+shift+l"
HOTKEY_QUIT = "ctrl+shift+q"

APP_TITLE = "Talk→To→Text (By Efroni)"

COLOR_BG = "#14171c"
COLOR_BORDER = "#2a2e35"
COLOR_TEXT = "#e6e8eb"
COLOR_MUTED = "#9aa1ab"
COLOR_MIC_IDLE = "#1f2329"
COLOR_MIC_ON = "#c9343a"
COLOR_ACCENT = "#4c8bf5"

_state_lock = threading.Lock()
_recording = False
_frames: list[np.ndarray] = []
_stream: sd.InputStream | None = None
_language = "en"

pill: "Pill | None" = None


def log(msg: str) -> None:
    print(f"[Talk-To-Text] {msg}", flush=True)


def notify_error(msg: str) -> None:
    """Blocking message box - reserved for real errors the user must see."""
    import ctypes
    threading.Thread(
        target=lambda: ctypes.windll.user32.MessageBoxW(0, msg, APP_TITLE, 0x10),
        daemon=True,
    ).start()


def ui_call(fn) -> None:
    """Run `fn` on the Tk main thread - hotkey callbacks fire on a different
    thread, and Tkinter widgets may only ever be touched from the thread that
    created them."""
    if pill is not None:
        try:
            pill.root.after(0, fn)
            return
        except Exception:
            pass
    fn()


class Pill:
    """Frameless, always-on-top floating control - mic toggle, language
    toggle, minimize - mirroring the Chrome extension's pill UI."""

    def __init__(self) -> None:
        self.root = tk.Tk()
        self.root.title(APP_TITLE)
        self.root.overrideredirect(True)
        self.root.attributes("-topmost", True)
        self.root.configure(bg=COLOR_BORDER)

        self.minimized = False
        self._drag = {"x": 0, "y": 0}

        self.outer = tk.Frame(self.root, bg=COLOR_BORDER, padx=1, pady=1)
        self.outer.pack()
        self.body = tk.Frame(self.outer, bg=COLOR_BG)
        self.body.pack()

        self.mic_canvas = tk.Canvas(
            self.body, width=34, height=34, bg=COLOR_BG, highlightthickness=0
        )
        self.mic_oval = self.mic_canvas.create_oval(2, 2, 32, 32, fill=COLOR_MIC_IDLE, outline="")
        self.mic_canvas.create_text(17, 17, text="●", fill=COLOR_TEXT, font=("Segoe UI", 11))
        self.mic_canvas.pack(side="left", padx=(8, 6), pady=8)
        self.mic_canvas.bind("<Button-1>", lambda _e: toggle_recording())

        self.lang_label = tk.Label(
            self.body, text=LANGUAGES[_language][:2].upper(), fg=COLOR_TEXT, bg=COLOR_MIC_IDLE,
            font=("Segoe UI", 9, "bold"), padx=8, pady=4, cursor="hand2",
        )
        self.lang_label.pack(side="left", padx=(0, 6))
        self.lang_label.bind("<Button-1>", lambda _e: toggle_language())

        self.status_label = tk.Label(
            self.body, text="Ready", fg=COLOR_MUTED, bg=COLOR_BG, font=("Segoe UI", 9),
        )
        self.status_label.pack(side="left", padx=(0, 6))

        self.min_button = tk.Label(
            self.body, text="—", fg=COLOR_MUTED, bg=COLOR_BG, font=("Segoe UI", 10),
            padx=6, cursor="hand2",
        )
        self.min_button.pack(side="left", padx=(0, 8))
        self.min_button.bind("<Button-1>", lambda _e: self.toggle_minimize())

        for widget in (self.body, self.outer):
            widget.bind("<Button-1>", self._drag_start)
            widget.bind("<B1-Motion>", self._drag_move)

        self.root.update_idletasks()
        self._place_bottom_right()

    def _place_bottom_right(self) -> None:
        w = self.root.winfo_reqwidth()
        h = self.root.winfo_reqheight()
        sw = self.root.winfo_screenwidth()
        sh = self.root.winfo_screenheight()
        self.root.geometry(f"{w}x{h}+{sw - w - 24}+{sh - h - 72}")

    def _drag_start(self, event) -> None:
        self._drag["x"] = event.x_root - self.root.winfo_x()
        self._drag["y"] = event.y_root - self.root.winfo_y()

    def _drag_move(self, event) -> None:
        x = event.x_root - self._drag["x"]
        y = event.y_root - self._drag["y"]
        self.root.geometry(f"+{x}+{y}")

    def toggle_minimize(self) -> None:
        self.minimized = not self.minimized
        if self.minimized:
            self.lang_label.pack_forget()
            self.status_label.pack_forget()
            self.min_button.pack_forget()
        else:
            self.lang_label.pack(side="left", padx=(0, 6), after=self.mic_canvas)
            self.status_label.pack(side="left", padx=(0, 6), after=self.lang_label)
            self.min_button.pack(side="left", padx=(0, 8), after=self.status_label)
        # Re-fit the window to its new content size without snapping it back
        # to a fixed corner - wherever the user dragged it stays put.
        x, y = self.root.winfo_x(), self.root.winfo_y()
        self.root.update_idletasks()
        w, h = self.root.winfo_reqwidth(), self.root.winfo_reqheight()
        self.root.geometry(f"{w}x{h}+{x}+{y}")

    def show(self) -> None:
        self.root.deiconify()
        if self.minimized:
            self.toggle_minimize()

    def set_recording(self, recording: bool) -> None:
        self.mic_canvas.itemconfig(self.mic_oval, fill=COLOR_MIC_ON if recording else COLOR_MIC_IDLE)

    def set_language(self, lang: str) -> None:
        self.lang_label.config(text=LANGUAGES[lang][:2].upper())

    def set_status(self, text: str) -> None:
        self.status_label.config(text=text)


def status(msg: str) -> None:
    log(msg)
    ui_call(lambda: pill.set_status(msg) if pill else None)


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
    ui_call(lambda: (pill.show(), pill.set_recording(True)) if pill else None)
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
    ui_call(lambda: pill.set_recording(False) if pill else None)
    # Transcribe off the hotkey thread so the keyboard hook stays responsive.
    threading.Thread(target=_transcribe_and_type, args=(audio, _language), daemon=True).start()


def _transcribe_with_google(audio: np.ndarray, language: str) -> str | None:
    try:
        pcm16 = (np.clip(audio, -1.0, 1.0) * 32767).astype(np.int16)
        audio_data = sr.AudioData(pcm16.tobytes(), SAMPLE_RATE, 2)
        recognizer = sr.Recognizer()
        tag = GOOGLE_LANG_TAGS.get(language, "en-US")
        return recognizer.recognize_google(audio_data, language=tag).strip()
    except sr.UnknownValueError:
        return None
    except sr.RequestError as exc:
        log(f"Google Web Speech request failed: {exc}")
        notify_error(f"Couldn't reach Google's speech service:\n{exc}\n\nCheck your internet connection.")
        return None
    except Exception as exc:
        log(f"Transcription failed: {exc}")
        return None


def _transcribe_and_type(audio: np.ndarray, language: str) -> None:
    if audio.size < SAMPLE_RATE // 4:  # under 0.25 s - ignore accidental taps
        log("Recording too short, ignored.")
        status("Ready")
        return
    status("Transcribing...")
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
    ui_call(lambda: pill.set_language(_language) if pill else None)
    status(f"Language: {LANGUAGES[_language]}")


def quit_app() -> None:
    if pill is not None:
        ui_call(pill.root.quit)
    else:
        sys.exit(0)


def main() -> None:
    global pill
    log("Talk-To-Text (By Efroni) running.")
    log(f"{HOTKEY_TOGGLE}: start/stop | {HOTKEY_LANGUAGE}: language | {HOTKEY_QUIT}: quit")

    pill = Pill()
    keyboard.add_hotkey(HOTKEY_TOGGLE, toggle_recording, suppress=True)
    keyboard.add_hotkey(HOTKEY_LANGUAGE, toggle_language, suppress=True)
    keyboard.add_hotkey(HOTKEY_QUIT, quit_app, suppress=True)

    pill.root.mainloop()  # blocks the main thread; this is the real UI loop


if __name__ == "__main__":
    main()
