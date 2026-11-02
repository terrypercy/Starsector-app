# Starsector watchdog (Claude Captain)
# Runs every 2 minutes from Task Scheduler. If Starsector's game window has been "Not responding"
# for 3 checks in a row (about 4-6 minutes), it ends the frozen game and opens the Starsector launcher again.
# It never restarts the computer and never touches any other program.
# Log: %LOCALAPPDATA%\StarsectorWatchdog\watchdog.log

$GameDir   = 'C:\Program Files (x86)\Fractal Softworks\Starsector'
$Launcher  = Join-Path $GameDir 'starsector.exe'
$StateDir  = Join-Path $env:LOCALAPPDATA 'StarsectorWatchdog'
$Strikes   = Join-Path $StateDir 'strikes.txt'
$Log       = Join-Path $StateDir 'watchdog.log'
$Needed    = 3

New-Item -ItemType Directory -Force -Path $StateDir | Out-Null
function Write-Log($msg) { Add-Content -Path $Log -Value ("{0}  {1}" -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $msg) }

# Starsector's game runs in the bundled java.exe and has a window titled "Starsector ..."
$game = Get-Process -Name java -ErrorAction SilentlyContinue |
        Where-Object { $_.Path -like "$GameDir*" -and $_.MainWindowTitle -like 'Starsector*' }

if (-not $game) { Set-Content -Path $Strikes -Value 0; exit 0 }

$frozen = $game | Where-Object { -not $_.Responding }
if (-not $frozen) { Set-Content -Path $Strikes -Value 0; exit 0 }

$n = 0
if (Test-Path $Strikes) { [int]::TryParse((Get-Content $Strikes -Raw).Trim(), [ref]$n) | Out-Null }
$n++
Set-Content -Path $Strikes -Value $n
Write-Log "game not responding (check $n of $Needed)"

if ($n -ge $Needed) {
    foreach ($p in $frozen) {
        Write-Log "ending frozen Starsector (pid $($p.Id))"
        Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue
    }
    Set-Content -Path $Strikes -Value 0
    Start-Sleep -Seconds 5
    if (Test-Path $Launcher) {
        Start-Process -FilePath $Launcher -WorkingDirectory $GameDir
        Write-Log "launcher reopened"
    }
}
