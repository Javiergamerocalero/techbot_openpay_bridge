@echo off
setlocal EnableExtensions

REM TECHBOT Openpay Bridge - actualizacion segura desde una instalacion existente.
REM Conserva application.yaml, bridge.db y sdk-data.
REM En equipos antiguos sin Recovery, prepara recovery.properties y se detiene
REM antes de modificar el Bridge productivo.

net session >nul 2>&1
if %ERRORLEVEL% neq 0 goto :err_admin

set "ROOT=C:\bridge"
set "RECOVERY_DIR=%ROOT%\recovery"

if not exist "%ROOT%\application.yaml" goto :err_no_bridge_config
if not exist "%ROOT%\totalpos-bridge.jar" goto :err_no_bridge_jar
if not exist "%~dp0recovery.properties.example" goto :err_recovery_example

REM Migracion desde Bridge 1.1.x: crear solamente la configuracion de Recovery.
REM No detener servicios, no reemplazar JARs y no tocar application.yaml.
if not exist "%RECOVERY_DIR%\recovery.properties" (
    if not exist "%RECOVERY_DIR%" mkdir "%RECOVERY_DIR%"
    if %ERRORLEVEL% neq 0 goto :err_recovery_dir
    copy /Y "%~dp0recovery.properties.example" "%RECOVERY_DIR%\recovery.properties" >nul
    if %ERRORLEVEL% neq 0 goto :err_recovery_config
    echo.
    echo ============================================================
    echo MIGRACION PREPARADA - EL BRIDGE PRODUCTIVO NO FUE MODIFICADO.
    echo.
    echo Se creo:
    echo   C:\bridge\recovery\recovery.properties
    echo.
    echo Edite ese archivo y reemplace:
    echo   apiKey=CHANGE_ME
    echo por una apiKey local real de al menos 24 caracteres.
    echo.
    echo NO modifique C:\bridge\application.yaml.
    echo Luego ejecute nuevamente update-openpay-bridge.bat.
    echo ============================================================
    endlocal
    exit /b 2
)

REM No continuar si Recovery conserva la clave de ejemplo.
findstr /C:"apiKey=CHANGE_ME" "%RECOVERY_DIR%\recovery.properties" >nul 2>&1
if %ERRORLEVEL% equ 0 goto :config_placeholder

REM La instalacion unificada realiza backup del JAR anterior antes de sustituirlo,
REM conserva configuraciones/datos y valida Bridge 1.2.0 + Recovery 1.0.3.
call "%~dp0install-openpay-bridge.bat"
set "RC=%ERRORLEVEL%"
endlocal & exit /b %RC%

:err_admin
echo ERROR: ejecutar este actualizador como Administrador.
goto :fail
:err_no_bridge_config
echo ERROR: no existe C:\bridge\application.yaml.
echo Este actualizador requiere una instalacion existente.
goto :fail
:err_no_bridge_jar
echo ERROR: no existe C:\bridge\totalpos-bridge.jar.
echo No se modifico la instalacion.
goto :fail
:err_recovery_example
echo ERROR: falta recovery.properties.example junto al actualizador.
goto :fail
:err_recovery_dir
echo ERROR: no se pudo crear C:\bridge\recovery.
goto :fail
:err_recovery_config
echo ERROR: no se pudo crear recovery.properties.
goto :fail
:config_placeholder
echo ERROR: C:\bridge\recovery\recovery.properties aun contiene apiKey=CHANGE_ME.
echo Configure una apiKey local real de al menos 24 caracteres y vuelva a ejecutar.
goto :fail
:fail
endlocal
exit /b 1
