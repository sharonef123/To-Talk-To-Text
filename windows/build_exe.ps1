# Build dist/windows/Talk-To-Text-(By-Efroni)-Windows-Desktop.exe (single
# portable file, icon bundled) plus a standalone dist/windows/run.bat.
# Usage (from the project root):  powershell -ExecutionPolicy Bypass -File windows\build_exe.ps1

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
$Win = Join-Path $Root "windows"
$Build = Join-Path $Root "build\windows"
$Dist = Join-Path $Root "dist\windows"
$Icon = Join-Path $Build "app.ico"
$AppName = "Talk-To-Text-(By-Efroni)-Windows-Desktop"

# Resolve a real Python interpreter. On many Windows machines the bare `python`
# command resolves to the Microsoft Store "App Execution Alias" stub (not a real
# interpreter) unless the actual install is first on PATH, so prefer the `py`
# launcher when it is present.
$PythonCmd = $null
foreach ($candidate in @("py", "python")) {
    $cmd = Get-Command $candidate -ErrorAction SilentlyContinue
    if ($cmd) { $PythonCmd = $candidate; break }
}
if (-not $PythonCmd) {
    throw "No working Python interpreter found on PATH (tried 'py' and 'python')."
}

function Invoke-Python {
    param([string[]]$PyArgs)
    & $PythonCmd @PyArgs
    if ($LASTEXITCODE -ne 0) {
        throw "Command failed (exit $LASTEXITCODE): $PythonCmd $($PyArgs -join ' ')"
    }
}

New-Item -ItemType Directory -Force -Path $Build, $Dist | Out-Null

Invoke-Python @("-m", "pip", "install", "--upgrade", "-r", (Join-Path $Win "requirements.txt"))

# Convert the 128px PNG into a multi-size .ico for the exe icon
Invoke-Python @("-c", "from PIL import Image; Image.open(r'$Root\icons\icon128.png').save(r'$Icon', sizes=[(16,16),(32,32),(48,48),(128,128)])")

# dictate.py only ever calls speech_recognition's recognize_google (plain
# urllib HTTP calls - no heavy deps of its own). But speech_recognition's own
# __init__.py unconditionally *tries* to import ALL of its optional engine
# backends (recognize_whisper, recognize_faster_whisper, recognize_openai,
# recognize_groq, recognize_cohere_api, recognize_sphinx) so it can monkey-
# patch whichever ones succeed onto the Recognizer class. Those backends'
# own torch/faster_whisper/openai/etc. imports are all TYPE_CHECKING-guarded
# or lazy-inside-the-function, so excluding the heavy packages below from
# the bundle is safe - it can't affect recognize_google, which lives in the
# same try/except but has no such dependency itself - and keeps the onefile
# build from pulling in multiple hundred MB of unrelated ML frameworks that
# happen to be installed in this shared/global site-packages.
$ExcludeModules = @(
    "torch", "torchvision", "torchaudio", "torchmetrics",
    "pytorch_lightning", "lightning", "tensorflow",
    "sklearn", "pandas", "matplotlib", "sympy", "sqlalchemy",
    "networkx", "grpc", "pdfminer", "fontTools", "pygments",
    "transformers", "fairseq", "opennmt_py", "eole",
    "faster_whisper", "ctranslate2", "whisper", "openai", "groq",
    "cohere", "vosk", "pocketsphinx"
)
$ExcludeArgs = @()
foreach ($m in $ExcludeModules) { $ExcludeArgs += @("--exclude-module", $m) }

$PyInstallerArgs = @(
    "-m", "PyInstaller",
    "--noconfirm", "--onefile", "--windowed",
    "--name", $AppName,
    "--icon", $Icon,
    "--distpath", $Dist,
    "--workpath", (Join-Path $Build "work"),
    "--specpath", $Build
)
$PyInstallerArgs += $ExcludeArgs
$PyInstallerArgs += (Join-Path $Win "dictate.py")

Invoke-Python $PyInstallerArgs

$ExePath = Join-Path $Dist "$AppName.exe"
if (-not (Test-Path $ExePath)) {
    throw "Build reported success but $ExePath was not found."
}

# A minimal standalone launcher next to the exe - this folder travels on its
# own (e.g. zipped up for someone else), so it only ever double-clicks the
# exe sitting beside it; it never searches for or shells out to Python.
$RunBatPath = Join-Path $Dist "run.bat"
@"
@echo off
cd /d "%~dp0"
start "" "$AppName.exe"
"@ | Set-Content -Path $RunBatPath -Encoding ASCII

Write-Host "Built: $ExePath"
Write-Host "Built: $RunBatPath"
