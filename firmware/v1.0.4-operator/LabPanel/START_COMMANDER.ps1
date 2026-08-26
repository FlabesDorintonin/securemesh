$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
Write-Host "SecureMesh Commander UI 1.0.4 -> http://localhost:8765" -ForegroundColor Green
Start-Process "http://localhost:8765"
if (Get-Command py -ErrorAction SilentlyContinue) { py -3 serve.py }
elseif (Get-Command python -ErrorAction SilentlyContinue) { python serve.py }
else { throw "Python 3 not found" }
