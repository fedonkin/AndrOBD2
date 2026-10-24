@echo off
setlocal
cd /d "%~dp0"
echo Open AndrOBD2 on your connected phone and reproduce the crash first.
echo USB debugging must be enabled and authorized.
echo.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\collect_startup_crash.ps1" %*
echo.
echo Close this window with [X].
:hold
 timeout /t 86400 /nobreak >nul
 goto hold
