param(
    [Parameter(Mandatory=$true)][string]$Python,
    [Parameter(Mandatory=$true)][string]$Root,
    [switch]$ReplaceExisting
)
$monitorScript = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot 'monitor.ps1')).Path
$stateRoot = (Resolve-Path -LiteralPath $Root).Path
$pythonExecutable = (Resolve-Path -LiteralPath $Python).Path
$powershellExecutable = (Get-Command pwsh).Source
$terminalCommand = Get-Command wt.exe -ErrorAction SilentlyContinue
$monitorPython = Join-Path $PSScriptRoot 'monitor.py'
if ($ReplaceExisting) {
    # Only our observer wrappers/children qualify. Never stop the queue or workers.
    $existing = @(Get-CimInstance Win32_Process -ErrorAction Stop)
    $wrappers = @($existing | Where-Object {
        $_.Name -eq 'pwsh.exe' -and $_.CommandLine -and
        $_.CommandLine.IndexOf($monitorScript,[StringComparison]::OrdinalIgnoreCase) -ge 0 -and
        $_.CommandLine.IndexOf($stateRoot,[StringComparison]::OrdinalIgnoreCase) -ge 0
    })
    foreach ($wrapper in $wrappers) {
        $children = @($existing | Where-Object {
            $_.ParentProcessId -eq $wrapper.ProcessId -and $_.Name -eq 'python.exe' -and
            $_.CommandLine.IndexOf($monitorPython,[StringComparison]::OrdinalIgnoreCase) -ge 0
        })
        foreach ($child in $children) {
            foreach ($grandchild in @($existing | Where-Object {
                $_.ParentProcessId -eq $child.ProcessId -and $_.Name -eq 'python.exe' -and
                $_.CommandLine.IndexOf($monitorPython,[StringComparison]::OrdinalIgnoreCase) -ge 0
            })) {
                try { Stop-Process -Id $grandchild.ProcessId -ErrorAction Stop } catch { }
            }
            try { Stop-Process -Id $child.ProcessId -ErrorAction Stop } catch { }
        }
        try { Stop-Process -Id $wrapper.ProcessId -ErrorAction Stop } catch { }
    }
}
$processes = @()
foreach ($consumer in @('dust','metering')) {
    $arguments = @('-NoLogo','-NoProfile','-File', ('"' + $monitorScript + '"'),
                   '-Python', ('"' + $pythonExecutable + '"'),
                   '-Root', ('"' + $stateRoot + '"'), '-Consumer', $consumer)
    # Explicitly use two new windows, rather than the terminal's default tab group.
    if ($terminalCommand) {
        $terminalArguments = @('-w','new','--size','112,34','new-tab','--title',
                               ('"Shared Training - ' + $consumer.ToUpper() + '"'),
                               '-d',('"' + $stateRoot + '"'),('"' + $powershellExecutable + '"')) + $arguments
        $child = Start-Process -FilePath $terminalCommand.Source -ArgumentList $terminalArguments `
            -WorkingDirectory $stateRoot -WindowStyle Normal -PassThru
        $terminalHost = 'WindowsTerminal'
    } else {
        $child = Start-Process -FilePath $powershellExecutable -ArgumentList $arguments `
            -WorkingDirectory $stateRoot -WindowStyle Normal -PassThru
        $terminalHost = 'PowerShell'
    }
    $processes += [pscustomobject]@{consumer=$consumer;launcherPid=$child.Id;host=$terminalHost;startedAt=(Get-Date).ToString('o')}
}
$processes | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $stateRoot 'monitor-processes.json') -Encoding utf8
$processes | ConvertTo-Json -Compress
