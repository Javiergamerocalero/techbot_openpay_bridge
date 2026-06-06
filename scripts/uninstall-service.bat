@echo off
REM Remove the TotalPosBridge Windows service. Run as Administrator.
setlocal
set "SERVICE_NAME=TotalPosBridge"

REM Look for nssm.exe next to this script first, then fall back to PATH.
set "NSSM_PATH=%~dp0nssm.exe"
if not exist "%NSSM_PATH%" set "NSSM_PATH=nssm.exe"

"%NSSM_PATH%" stop %SERVICE_NAME%
"%NSSM_PATH%" remove %SERVICE_NAME% confirm

echo.
echo Done. Service %SERVICE_NAME% removed.
endlocal
