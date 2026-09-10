@echo off
setlocal EnableExtensions

REM TECHBOT Openpay Bridge - instalador unificado Windows
REM Instala/configura dos servicios independientes:
REM   TotalPosBridge
REM   TechbotOpenpayRecovery
REM Ejecutar como Administrador desde el paquete de distribucion.

net session >nul 2>&1
if %ERRORLEVEL% neq 0 goto :err_admin

set "ROOT=C:\bridge"
set "RECOVERY_DIR=%ROOT%\recovery"
set "BRIDGE_SERVICE=TotalPosBridge"
set "RECOVERY_SERVICE=TechbotOpenpayRecovery"
set "NEEDS_CONFIG=0"

REM Resolver NSSM: paquete -> instalacion existente -> PATH.
set "NSSM=%~dp0nssm.exe"
if not exist "%NSSM%" if exist "%ROOT%\nssm.exe" set "NSSM=%ROOT%\nssm.exe"
if not exist "%NSSM%" set "NSSM=nssm.exe"
"%NSSM%" --version >nul 2>&1
if %ERRORLEVEL% neq 0 goto :err_nssm

REM Resolver Java: PATH primero para evitar un JAVA_HOME legacy, luego JAVA_HOME.
set "JAVA_EXE="
for /f "delims=" %%i in ('where java 2^>nul') do if not defined JAVA_EXE set "JAVA_EXE=%%i"
if not defined JAVA_EXE if not "%JAVA_HOME%"=="" set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
if not defined JAVA_EXE goto :err_java
if not exist "%JAVA_EXE%" goto :err_java
"%JAVA_EXE%" -version 2>&1 | findstr /R /C:"version \"1[7-9]" /C:"version \"[2-9][0-9]" >nul
if %ERRORLEVEL% neq 0 goto :err_java17

if not exist "%~dp0totalpos-bridge.jar" goto :err_bridge_jar
if not exist "%~dp0openpay-recovery-service.jar" goto :err_recovery_jar
if not exist "%~dp0application.yaml.example" goto :err_bridge_example
if not exist "%~dp0recovery.properties.example" goto :err_recovery_example

if not exist "%ROOT%" mkdir "%ROOT%"
if not exist "%RECOVERY_DIR%" mkdir "%RECOVERY_DIR%"
if not exist "%ROOT%\sdk-data" mkdir "%ROOT%\sdk-data"

REM Detener antes de reemplazar JARs para evitar archivos bloqueados y mezclar versiones.
"%NSSM%" stop %RECOVERY_SERVICE% >nul 2>&1
"%NSSM%" stop %BRIDGE_SERVICE% >nul 2>&1

copy /Y "%~dp0totalpos-bridge.jar" "%ROOT%\totalpos-bridge.jar" >nul
if %ERRORLEVEL% neq 0 goto :err_copy
copy /Y "%~dp0openpay-recovery-service.jar" "%RECOVERY_DIR%\openpay-recovery-service.jar" >nul
if %ERRORLEVEL% neq 0 goto :err_copy

REM Preservar configuracion existente en actualizaciones.
REM En instalacion limpia crearla desde ejemplos y NO arrancar con placeholders.
if not exist "%ROOT%\application.yaml" (
    copy /Y "%~dp0application.yaml.example" "%ROOT%\application.yaml" >nul
    set "NEEDS_CONFIG=1"
)
if not exist "%RECOVERY_DIR%\recovery.properties" (
    copy /Y "%~dp0recovery.properties.example" "%RECOVERY_DIR%\recovery.properties" >nul
    set "NEEDS_CONFIG=1"
)

if exist "%~dp0nssm.exe" copy /Y "%~dp0nssm.exe" "%ROOT%\nssm.exe" >nul

REM Reinstalacion controlada de wrappers NSSM. Los datos y configuraciones permanecen.
"%NSSM%" remove %RECOVERY_SERVICE% confirm >nul 2>&1
"%NSSM%" remove %BRIDGE_SERVICE% confirm >nul 2>&1

"%NSSM%" install %BRIDGE_SERVICE% "%JAVA_EXE%" -jar "%ROOT%\totalpos-bridge.jar"
if %ERRORLEVEL% neq 0 goto :err_install_bridge
"%NSSM%" set %BRIDGE_SERVICE% AppDirectory "%ROOT%"
"%NSSM%" set %BRIDGE_SERVICE% DisplayName "TECHBOT Openpay TotalPOS Bridge"
"%NSSM%" set %BRIDGE_SERVICE% Description "TECHBOT REST bridge for BBVA TotalPOS/Openpay SDK."
"%NSSM%" set %BRIDGE_SERVICE% Start SERVICE_AUTO_START
"%NSSM%" set %BRIDGE_SERVICE% AppStdout "%ROOT%\service.out.log"
"%NSSM%" set %BRIDGE_SERVICE% AppStderr "%ROOT%\service.err.log"
"%NSSM%" set %BRIDGE_SERVICE% AppRotateFiles 1
"%NSSM%" set %BRIDGE_SERVICE% AppRotateBytes 10485760
"%NSSM%" set %BRIDGE_SERVICE% AppRestartDelay 5000
"%NSSM%" set %BRIDGE_SERVICE% AppThrottle 60000
"%NSSM%" set %BRIDGE_SERVICE% AppStopMethodConsole 5000

"%NSSM%" install %RECOVERY_SERVICE% "%JAVA_EXE%" -Drecovery.config="%RECOVERY_DIR%\recovery.properties" -jar "%RECOVERY_DIR%\openpay-recovery-service.jar"
if %ERRORLEVEL% neq 0 goto :err_install_recovery
"%NSSM%" set %RECOVERY_SERVICE% AppDirectory "%RECOVERY_DIR%"
"%NSSM%" set %RECOVERY_SERVICE% DisplayName "TECHBOT Openpay Recovery Service"
"%NSSM%" set %RECOVERY_SERVICE% Description "TECHBOT controlled recovery API for TotalPosBridge."
"%NSSM%" set %RECOVERY_SERVICE% Start SERVICE_AUTO_START
"%NSSM%" set %RECOVERY_SERVICE% AppStdout "%RECOVERY_DIR%\recovery.out.log"
"%NSSM%" set %RECOVERY_SERVICE% AppStderr "%RECOVERY_DIR%\recovery.err.log"
"%NSSM%" set %RECOVERY_SERVICE% AppRotateFiles 1
"%NSSM%" set %RECOVERY_SERVICE% AppRotateBytes 5242880
"%NSSM%" set %RECOVERY_SERVICE% AppRestartDelay 5000
"%NSSM%" set %RECOVERY_SERVICE% AppThrottle 60000
"%NSSM%" set %RECOVERY_SERVICE% AppStopMethodConsole 5000

REM No abrir 9092 globalmente. La regla LAN se agregara despues con IP/subred autorizada.

if "%NEEDS_CONFIG%"=="1" goto :fresh_config_required

REM Actualizacion / reinstalacion con configuracion existente: arrancar y validar.
"%NSSM%" start %BRIDGE_SERVICE%
timeout /t 6 /nobreak >nul
"%NSSM%" start %RECOVERY_SERVICE%
timeout /t 3 /nobreak >nul

echo.
echo ============================================================
echo TECHBOT Openpay Bridge instalado/actualizado.
echo Bridge:   http://127.0.0.1:9091/api/health
echo Recovery: http://127.0.0.1:9092/health
echo ============================================================
echo.
echo [Bridge health]
curl -s --max-time 5 http://127.0.0.1:9091/api/health
echo.
echo [Recovery health]
curl -s --max-time 5 http://127.0.0.1:9092/health
echo.
echo.
echo NOTA: antes de produccion, restringir TCP 9092 a la IP/subred del kiosco.
endlocal
exit /b 0

:fresh_config_required
echo.
echo ============================================================
echo Instalacion preparada, pero es una instalacion LIMPIA.
echo Los servicios NO se han iniciado porque se crearon configuraciones
echo de ejemplo que contienen placeholders.
echo.
echo Edite:
echo   %ROOT%\application.yaml
echo   %RECOVERY_DIR%\recovery.properties
echo.
echo Requisitos minimos:
echo   - Bridge en puerto 9091.
echo   - Recovery apuntando a http://127.0.0.1:9091/api/health.
echo   - apiKey real de al menos 24 caracteres, distinta de CHANGE_ME.
echo   - Credenciales/COM del comercio correctamente configurados.
echo.
echo Luego vuelva a ejecutar este instalador. La segunda ejecucion
echo preservara ambos archivos y arrancara los servicios.
echo ============================================================
endlocal
exit /b 2

:err_admin
echo ERROR: ejecutar este instalador como Administrador.
goto :fail
:err_nssm
echo ERROR: no se encontro una copia funcional de nssm.exe.
goto :fail
:err_java
echo ERROR: no se encontro Java.
goto :fail
:err_java17
echo ERROR: se requiere Java 17 o superior.
goto :fail
:err_bridge_jar
echo ERROR: falta totalpos-bridge.jar junto al instalador.
goto :fail
:err_recovery_jar
echo ERROR: falta openpay-recovery-service.jar junto al instalador.
goto :fail
:err_bridge_example
echo ERROR: falta application.yaml.example junto al instalador.
goto :fail
:err_recovery_example
echo ERROR: falta recovery.properties.example junto al instalador.
goto :fail
:err_copy
echo ERROR: no se pudieron copiar los binarios a C:\bridge.
goto :fail
:err_install_bridge
echo ERROR: no se pudo instalar TotalPosBridge.
goto :fail
:err_install_recovery
echo ERROR: no se pudo instalar TechbotOpenpayRecovery.
goto :fail
:fail
endlocal
exit /b 1
