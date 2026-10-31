#!/usr/bin/env python3
"""Package the Chrome extension (source lives in chrome/) into dist/chrome/.

Produces, directly inside an isolated dist/chrome/ directory:
  - Talk-To-Text-(By-Efroni)-Chrome-Extension.zip  (for the Chrome Web Store)
  - manifest.json, background.js, content.js, icons/  (the same files,
    unpacked, so chrome://extensions -> "Load unpacked" can point straight at
    dist/chrome/ with no unzip step)

Usage:  python build_chrome.py
"""

import json
import shutil
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent
SRC = ROOT / "chrome"
DIST_CHROME = ROOT / "dist" / "chrome"
OUT = DIST_CHROME / "Talk-To-Text-(By-Efroni)-Chrome-Extension.zip"
FILES = ["manifest.json", "background.js", "content.js"]
DIRS = ["icons"]


def main() -> None:
    manifest = json.loads((SRC / "manifest.json").read_text(encoding="utf-8"))

    if DIST_CHROME.exists():
        shutil.rmtree(DIST_CHROME)
    DIST_CHROME.mkdir(parents=True)

    with zipfile.ZipFile(OUT, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        for name in FILES:
            zf.write(SRC / name, name)
        for d in DIRS:
            for path in sorted((SRC / d).rglob("*")):
                if path.is_file():
                    zf.write(path, path.relative_to(SRC).as_posix())

    for name in FILES:
        shutil.copy2(SRC / name, DIST_CHROME / name)
    for d in DIRS:
        shutil.copytree(SRC / d, DIST_CHROME / d)

    print(f"Built {OUT} (v{manifest['version']}, {OUT.stat().st_size} bytes)")
    print(f"Unpacked extension ready to load directly from {DIST_CHROME}")


if __name__ == "__main__":
    main()
