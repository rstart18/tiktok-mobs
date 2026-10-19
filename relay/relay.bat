@echo off
title TikTokMobs Relay
cd /d "%~dp0"

if not exist "relay.ps1" (
  echo No encuentro relay.ps1 en esta carpeta.
  echo Los dos archivos tienen que estar juntos.
  echo.
  pause
  exit /b 1
)

powershell -NoProfile -ExecutionPolicy Bypass -File "relay.ps1"

echo.
echo El relay se ha detenido.
pause
