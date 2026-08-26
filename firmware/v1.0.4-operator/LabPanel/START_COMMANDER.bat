@echo off
setlocal
cd /d "%~dp0"
echo.
echo ================================================
echo   SecureMesh Commander UI 1.0.4
echo   http://localhost:8765
echo ================================================
echo.
where py >nul 2>nul
if %errorlevel%==0 (
  start "" "http://localhost:8765"
  py -3 serve.py
  goto :eof
)
where python >nul 2>nul
if %errorlevel%==0 (
  start "" "http://localhost:8765"
  python serve.py
  goto :eof
)
echo Python 3 not found. Install Python or run any local HTTPS/localhost server in this folder.
pause
