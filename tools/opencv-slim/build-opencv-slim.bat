@echo off
setlocal

powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0build-opencv-slim.ps1" %*
exit /b %ERRORLEVEL%
