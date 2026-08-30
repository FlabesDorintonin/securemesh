@echo off
setlocal
set "GRADLE_VERSION=8.13"
set "GRADLE_SHA256=20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78"
set "CACHE=%USERPROFILE%\.gradle\securemesh-bootstrap\gradle-%GRADLE_VERSION%"
set "ZIP=%USERPROFILE%\.gradle\securemesh-bootstrap\gradle-%GRADLE_VERSION%-bin.zip"
set "GRADLE_BAT=%CACHE%\gradle-%GRADLE_VERSION%\bin\gradle.bat"
if exist "%GRADLE_BAT%" goto run
if not exist "%USERPROFILE%\.gradle\securemesh-bootstrap" mkdir "%USERPROFILE%\.gradle\securemesh-bootstrap"
echo Downloading Gradle %GRADLE_VERSION%...
powershell -NoProfile -ExecutionPolicy Bypass -Command "$ProgressPreference='SilentlyContinue'; Invoke-WebRequest -Uri 'https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-bin.zip' -OutFile '%ZIP%'; $h=(Get-FileHash -Algorithm SHA256 '%ZIP%').Hash.ToLower(); if($h -ne '%GRADLE_SHA256%'){ Write-Error ('Gradle checksum mismatch: '+$h); exit 2 }; New-Item -ItemType Directory -Force -Path '%CACHE%' | Out-Null; Expand-Archive -Force '%ZIP%' '%CACHE%'"
if errorlevel 1 exit /b %errorlevel%
:run
call "%GRADLE_BAT%" %*
exit /b %errorlevel%
