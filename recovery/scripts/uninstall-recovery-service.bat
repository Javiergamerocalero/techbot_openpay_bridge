@echo off
setlocal

REM TECHBOT Openpay Recovery Service uninstaller.
REM Run as Administrator.
REM Removes only TechbotOpenpayRecovery. TotalPosBridge is never touched.

net session >nul 2>&1
if %ERRORLEVEL% neq 0 goto :err_not_admin

set "RECOVERY_DIR=C:\bridge\recovery"
set "SERVICE_NAME=TechbotOpenpayRecovery"

set "NSSM_PATH=%~dp0nssm.exe"
if not exist "%NSSM_PATH%" set "NSSM_PATH=C:\bridge\nssm.exe"
if not exist "%NSSM_PATH%" set "NSSM_PATH=nssm.exe"

sc.exe query %SERVICE_NAME% >nul 2>&1
if %ERRORLEVEL% neq 0 goto :not_installed

echo Stopping %SERVICE_NAME%...
"%NSSM_PATH%" stop %SERVICE_NAME% >nul 2>&1
sc.exe stop %SERVICE_NAME% >nul 2>&1

echo Removing %SERVICE_NAME%...
"%NSSM_PATH%" remove %SERVICE_NAME% confirm
if %ERRORLEVEL% neq 0 goto :err_remove

echo.
echo Service removed.
echo Files kept in %RECOVERY_DIR% (jar, recovery.properties and logs).
echo Delete that folder by hand if you also want to remove the configuration.
echo TotalPosBridge was NOT modified.
endlocal
exit /b 0

:not_installed
echo %SERVICE_NAME% is not installed. Nothing to do.
endlocal
exit /b 0

:err_not_admin
echo ERROR: Run this script as Administrator.
endlocal
exit /b 1

:err_remove
echo ERROR: Could not remove the service. Verify nssm.exe and Administrator privileges.
endlocal
exit /b 1
