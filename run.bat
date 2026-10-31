@echo off
setlocal enabledelayedexpansion
:: run.bat - Launcher for Talk-To-Text (By Efroni), Windows desktop dictation.
::
:: Prefers the prebuilt standalone exe (no Python needed at all). Only falls
:: back to running windows\dictate.py from source if that exe isn't present,
:: and even then resolves a real Python interpreter instead of assuming the
:: bare `python` command works - on many machines it resolves to the Windows
:: Store "App Execution Alias" stub instead of a real interpreter.
::
:: NOTE: the exe's filename contains literal parentheses, e.g.
:: "Talk-To-Text-(By-Efroni)-Windows-Desktop.exe". cmd.exe's block parser
:: ( if ... ( ... ) ) scans for matching parens across the WHOLE block text
:: at parse time, before %percent% substitution - so a %VAR% holding a path
:: with "(...)" inside a block would break that scan. Delayed-expansion
:: !bang! variables substitute later, at execution time, which sidesteps it;
:: control flow below also sticks to single-line ifs/gotos rather than
:: multi-line ( blocks ) wherever such a path is involved, for the same reason.
cd /d "%~dp0"

set "EXE=dist\windows\Talk-To-Text-(By-Efroni)-Windows-Desktop.exe"
if exist "!EXE!" goto :run_exe

echo No prebuilt exe found at "!EXE!" - running from source instead.
goto :resolve_python

:run_exe
echo Starting Talk-To-Text (By Efroni)...
start "" "!EXE!"
goto :eof

:resolve_python
set "PYCMD="

:: 1) py launcher pinned to 3.14, if installed
py -3.14 --version >nul 2>&1
if not !errorlevel! equ 0 goto :try_py
set "PYCMD=py -3.14"
goto :found

:try_py
:: 2) py launcher, any default version
py --version >nul 2>&1
if not !errorlevel! equ 0 goto :try_python
set "PYCMD=py"
goto :found

:try_python
:: 3) bare `python` - but reject the Microsoft Store alias stub, which exits
::    0 while printing "Python was not found; run without arguments to
::    install..." instead of a version string.
set "PYVER="
for /f "delims=" %%V in ('python --version 2^>^&1') do set "PYVER=%%V"
echo !PYVER! | findstr /i "Python was not found" >nul
if !errorlevel! equ 0 goto :scan_installs
python --version >nul 2>&1
if not !errorlevel! equ 0 goto :scan_installs
set "PYCMD=python"
goto :found

:scan_installs
:: 4) scan common per-user install locations for a real python.exe
for %%D in ("%LocalAppData%\Programs\Python" "%AppData%\Python") do (
    if exist "%%~D" (
        for /f "delims=" %%P in ('dir /b /s /a-d "%%~D\python.exe" 2^>nul') do (
            if not defined PYCMD set "PYCMD="%%P""
        )
    )
)
if defined PYCMD goto :found

echo ERROR: No working Python interpreter found (tried py -3.14, py, python,
echo        and scanned %%LocalAppData%%\Programs\Python and %%AppData%%\Python^).
echo        Install Python from https://python.org, or build the standalone
echo        exe with windows\build_exe.ps1 so this script never needs Python.
pause
exit /b 1

:found
echo Using Python: !PYCMD!

echo Installing dependencies...
!PYCMD! -m pip install --upgrade pip >nul 2>&1
!PYCMD! -m pip install -r windows\requirements.txt >nul 2>&1
if !errorlevel! equ 0 goto :have_deps
echo WARNING: pip install had issues, but continuing...

:have_deps
if exist windows\dictate.py goto :launch
echo ERROR: windows\dictate.py not found.
pause
exit /b 1

:launch
echo Starting Talk-To-Text (By Efroni)...
!PYCMD! windows\dictate.py

echo Talk-To-Text (By Efroni) has exited.
pause
