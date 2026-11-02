@echo off
REM Switches on the Starsector watchdog: a Task Scheduler job that runs every 2 minutes while you are logged in.
REM Keep starsector_watchdog.ps1 in the SAME folder as this file. To switch it off later run:  schtasks /delete /tn "Starsector Watchdog" /f
set "PS1=%~dp0starsector_watchdog.ps1"
if not exist "%PS1%" (echo starsector_watchdog.ps1 not found next to this file & pause & exit /b 1)
schtasks /create /tn "Starsector Watchdog" /sc minute /mo 2 /f /tr "powershell.exe -NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File \"%PS1%\""
if errorlevel 1 (echo Could not create the task. & pause & exit /b 1)
echo Starsector Watchdog is ON. It checks every 2 minutes and restarts Starsector if it stays frozen.
pause
