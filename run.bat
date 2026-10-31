@echo off
:: run.bat - Silent installer and launcher for dictate.py
echo Setting up dictate dictation utility...

:: Check if Python is available
python --version >nul 2>&1
if %errorlevel% neq 0 (
    echo ERROR: Python not found in PATH.
    pause
    exit /b 1
)

:: Upgrade pip first
python -m pip install --upgrade pip >nul 2>&1

:: Install requirements silently
echo Installing dependencies...
python -m pip install -r requirements.txt >nul 2>&1
if %errorlevel% neq 0 (
    echo WARNING: pip install had issues, but continuing...
)

:: Check if dictate.py exists
if not exist dictate.py (
    echo ERROR: dictate.py not found in current directory.
    pause
    exit /b 1
)

:: Launch the utility silently (minimized console window)
echo Starting dictate...
python dictate.py

echo dictate has exited.
pause