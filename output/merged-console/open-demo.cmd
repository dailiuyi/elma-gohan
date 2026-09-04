@echo off
setlocal

cd /d "%~dp0"
where python >nul 2>&1 && python assemble.py
start "" "%~dp0index.html"

endlocal
