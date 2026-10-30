param(
    [Parameter(Mandatory=$true)][string]$Python,
    [Parameter(Mandatory=$true)][string]$Root,
    [Parameter(Mandatory=$true)][ValidateSet('dust','metering')][string]$Consumer
)
$env:PYTHONUTF8 = '1'
$env:PYTHONIOENCODING = 'utf-8'
$env:PYTHONDONTWRITEBYTECODE = '1'
$Host.UI.RawUI.WindowTitle = "Shared Training - $Consumer"
& $Python (Join-Path $PSScriptRoot 'monitor.py') --root $Root --consumer $Consumer
