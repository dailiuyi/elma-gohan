@echo off
setlocal

cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0output\database-guide\start_dashboard.ps1" %*
set "dashboard_exit_code=%ERRORLEVEL%"

if not "%dashboard_exit_code%"=="0" (
  echo.
  echo Failed to start the ELMA dashboard. Exit code: %dashboard_exit_code%
  pause
)

endlocal & exit /b %dashboard_exit_code%
