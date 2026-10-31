# Build dist/To-Talk-To-Text-Windows.exe (single portable file, icon bundled).
# Usage (from the project root):  powershell -ExecutionPolicy Bypass -File windows\build_exe.ps1

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
$Win = Join-Path $Root "windows"
$Build = Join-Path $Root "build\windows"
$Dist = Join-Path $Root "dist"
$Icon = Join-Path $Build "app.ico"

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

# ctranslate2 ships model-conversion helpers (ctranslate2.converters.*) that
# pull in torch/tensorflow/transformers etc. dictate.py only does inference
# (never model conversion), so these - and other heavy frameworks that end up
# on PATH in a shared/global site-packages but are never imported by this
# script - are excluded to keep the onefile build from ballooning to 300+ MB.
$ExcludeModules = @(
    "torch", "torchvision", "torchaudio", "torchmetrics",
    "pytorch_lightning", "lightning", "tensorflow",
    "sklearn", "pandas", "matplotlib", "sympy", "sqlalchemy",
    "networkx", "grpc", "pdfminer", "fontTools", "pygments",
    "transformers", "fairseq", "opennmt_py", "eole"
)
$ExcludeArgs = @()
foreach ($m in $ExcludeModules) { $ExcludeArgs += @("--exclude-module", $m) }

$PyInstallerArgs = @(
    "-m", "PyInstaller",
    "--noconfirm", "--onefile", "--windowed",
    "--name", "To-Talk-To-Text-Windows",
    "--icon", $Icon,
    "--distpath", $Dist,
    "--workpath", (Join-Path $Build "work"),
    "--specpath", $Build,
    "--collect-data", "faster_whisper",
    "--collect-binaries", "ctranslate2"
)
$PyInstallerArgs += $ExcludeArgs
$PyInstallerArgs += (Join-Path $Win "dictate.py")

Invoke-Python $PyInstallerArgs

$ExePath = Join-Path $Dist "To-Talk-To-Text-Windows.exe"
if (-not (Test-Path $ExePath)) {
    throw "Build reported success but $ExePath was not found."
}
Write-Host "Built: $ExePath"
