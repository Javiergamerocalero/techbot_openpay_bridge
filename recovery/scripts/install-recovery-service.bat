@echo off
setlocal

REM TECHBOT Openpay Recovery Service installer.
REM Run as Administrator.

net session >nul 2>&1
if %ERRORLEVEL% neq 0 goto :err_not_admin

set "RECOVERY_DIR=C:\bridge\recovery"
set "SERVICE_NAME=TechbotOpenpayRecovery"
set "JAR_PATH=%RECOVERY_DIR%\openpay-recovery-service.jar"
set "CFG_PATH=%RECOVERY_DIR%\recovery.properties"

set "NSSM_PATH=%~dp0nssm.exe"
if not exist "%NSSM_PATH%" set "NSSM_PATH=C:\bridge\nssm.exe"
if not exist "%NSSM_PATH%" set "NSSM_PATH=nssm.exe"

set "JAVA_EXE="
for /f "delims=" %%i in ('where java 2^>nul') do (
    if not defined JAVA_EXE set "JAVA_EXE=%%i"
)
if not defined JAVA_EXE (
    if "%JAVA_HOME%"=="" goto :err_no_java
    set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
)

if not exist "%JAVA_EXE%" goto :err_no_java
if not exist "%JAR_PATH%" goto :err_no_jar
if not exist "%CFG_PATH%" goto :err_no_config

findstr /C:"apiKey=CHANGE_ME" "%CFG_PATH%" >nul
if %ERRORLEVEL% equ 0 goto :err_default_key

echo Installing %SERVICE_NAME%...
"%NSSM_PATH%" install %SERVICE_NAME% "%JAVA_EXE%" -Drecovery.config="%CFG_PATH%" -jar "%JAR_PATH%"
if %ERRORLEVEL% neq 0 goto :err_nssm

"%NSSM_PATH%" set %SERVICE_NAME% AppDirectory "%RECOVERY_DIR%"
"%NSSM_PATH%" set %SERVICE_NAME% DisplayName "TECHBOT Openpay Recovery Service"
"%NSSM_PATH%" set %SERVICE_NAME% Description "Local watchdog and controlled recovery API for TotalPosBridge."
"%NSSM_PATH%" set %SERVICE_NAME% Start SERVICE_AUTO_START
"%NSSM_PATH%" set %SERVICE_NAME% AppStdout "%RECOVERY_DIR%\recovery.out.log"
"%NSSM_PATH%" set %SERVICE_NAME% AppStderr "%RECOVERY_DIR%\recovery.err.log"
"%NSSM_PATH%" set %SERVICE_NAME% AppRotateFiles 1
"%NSSM_PATH%" set %SERVICE_NAME% AppRotateBytes 5242880
"%NSSM_PATH%" set %SERVICE_NAME% AppRestartDelay 5000
"%NSSM_PATH%" set %SERVICE_NAME% AppThrottle 60000
"%NSSM_PATH%" set %SERVICE_NAME% AppStopMethodConsole 5000

"%NSSM_PATH%" start %SERVICE_NAME%

echo.
echo Installed and started.
echo Local check:
echo   curl http://127.0.0.1:9092/health
echo.
echo IMPORTANT: Restrict inbound TCP 9092 in Windows Firewall to the Android kiosk IP/subnet.
endlocal
exit /b 0

:err_not_admin
echo ERROR: Run this script as Administrator.
endlocal
exit /b 1

:err_no_java
echo ERROR: Java 17+ was not found.
endlocal
exit /b 1

:err_no_jar
echo ERROR: Missing %JAR_PATH%
endlocal
exit /b 1

:err_no_config
echo ERROR: Missing %CFG_PATH%
echo Copy recovery.properties.example to recovery.properties and configure apiKey.
endlocal
exit /b 1

:err_default_key
echo ERROR: recovery.properties still contains apiKey=CHANGE_ME.
echo Set a random key of at least 24 characters before installing.
endlocal
exit /b 1

:err_nssm
echo ERROR: NSSM installation failed. Verify nssm.exe and Administrator privileges.
endlocal
exit /b 1
