param(
    [Parameter(Mandatory=$true)][string]$Python,
    [Parameter(Mandatory=$true)][string]$Root,
    [Parameter(Mandatory=$true)][string]$Pool,
    [Parameter(Mandatory=$true)][string]$DustRoot,
    [Parameter(Mandatory=$true)][string]$Snapshot,
    [Parameter(Mandatory=$true)][string]$MeteringParent,
    [Parameter(Mandatory=$true)][string]$OldCache,
    [string]$RuntimeName = 'runtime-snapshot'
)
$ErrorActionPreference = 'Stop'
$stateRoot = (Resolve-Path -LiteralPath $Root).Path
$recordPath = Join-Path $stateRoot 'controller-process.json'
if (Test-Path -LiteralPath $recordPath) {
    $record = Get-Content -LiteralPath $recordPath -Raw | ConvertFrom-Json
    if (Get-Process -Id $record.pid -ErrorAction SilentlyContinue) {
        throw "Controller PID $($record.pid) is still present; inspect it before starting a second queue."
    }
}
$scriptPath = Join-Path (Join-Path $stateRoot $RuntimeName) 'tools/shared-training/coordinator.py'
$resolved = @($scriptPath, $stateRoot, $Pool, $DustRoot, $Snapshot, $MeteringParent, $OldCache) |
    ForEach-Object { (Resolve-Path -LiteralPath $_).Path }
$pythonExecutable = (Resolve-Path -LiteralPath $Python).Path
$arguments = @('-B','-u', ('"' + $resolved[0] + '"'))
$names = @('root','pool','dust-root','snapshot','metering-parent','old-cache')
for ($index = 0; $index -lt $names.Length; $index++) {
    $arguments += @('--' + $names[$index], ('"' + $resolved[$index+1] + '"'))
}
$env:PYTHONUTF8 = '1'
$env:PYTHONIOENCODING = 'utf-8'
$env:PYTHONDONTWRITEBYTECODE = '1'
$child = Start-Process -FilePath $pythonExecutable -ArgumentList $arguments -WorkingDirectory $stateRoot `
    -WindowStyle Hidden -RedirectStandardOutput (Join-Path $stateRoot 'coordinator.stdout.log') `
    -RedirectStandardError (Join-Path $stateRoot 'coordinator.stderr.log') -PassThru
[pscustomobject]@{pid=$child.Id;startedAt=(Get-Date).ToString('o');runtime=$scriptPath;root=$stateRoot} |
    ConvertTo-Json | Set-Content -LiteralPath $recordPath -Encoding utf8
Get-Content -LiteralPath $recordPath -Raw
