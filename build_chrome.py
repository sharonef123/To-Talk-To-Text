#!/usr/bin/env python3
"""Package the Chrome extension into dist/To-Talk-To-Text-Chrome.zip.

Usage:  python build_chrome.py
"""

import json
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent
DIST = ROOT / "dist"
OUT = DIST / "To-Talk-To-Text-Chrome.zip"
FILES = ["manifest.json", "background.js", "content.js"]
DIRS = ["icons"]


def main() -> None:
    manifest = json.loads((ROOT / "manifest.json").read_text(encoding="utf-8"))
    DIST.mkdir(exist_ok=True)
    if OUT.exists():
        OUT.unlink()

    with zipfile.ZipFile(OUT, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        for name in FILES:
            zf.write(ROOT / name, name)
        for d in DIRS:
            for path in sorted((ROOT / d).rglob("*")):
                if path.is_file():
                    zf.write(path, path.relative_to(ROOT).as_posix())

    print(f"Built {OUT} (v{manifest['version']}, {OUT.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
