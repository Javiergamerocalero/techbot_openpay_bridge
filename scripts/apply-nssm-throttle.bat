@echo off
REM Aplica AppThrottle + AppStopMethodConsole a una instalacion EXISTENTE
REM del servicio TotalPosBridge, sin reinstalar el servicio ni tocar
REM ninguna otra configuracion.
REM
REM Uso: ejecutar como Administrador.
REM
REM Cambios que aplica:
REM   AppThrottle=60000         evita loop de restart cada 5s si la JVM
REM                             falla deterministicamente al arrancar.
REM   AppStopMethodConsole=5000 da 5s de gracia al JVM para cerrar limpio
REM                             (menos chances de corromper SQLite en el
REM                             proximo apagado controlado).
REM
REM Este script NO reinstala el servicio ni modifica la aplicacion ni el
REM jar — solo ajusta 2 flags de NSSM. Luego se puede sobrescribir el jar
REM aparte con el nuevo build que incluye el SelfHeal de boot.

setlocal

net session >nul 2>&1
if %ERRORLEVEL% neq 0 (
    echo ERROR: Este script debe ejecutarse como Administrador.
    exit /b 1
)

set "SERVICE_NAME=TotalPosBridge"

set "NSSM_PATH=%~dp0nssm.exe"
if not exist "%NSSM_PATH%" set "NSSM_PATH=nssm.exe"

echo Aplicando AppThrottle a %SERVICE_NAME%...
"%NSSM_PATH%" set %SERVICE_NAME% AppThrottle 60000
if %ERRORLEVEL% neq 0 (
    echo ERROR: no se pudo setear AppThrottle. ?El servicio existe?
    exit /b 1
)

echo Aplicando AppStopMethodConsole a %SERVICE_NAME%...
"%NSSM_PATH%" set %SERVICE_NAME% AppStopMethodConsole 5000
if %ERRORLEVEL% neq 0 (
    echo ERROR: no se pudo setear AppStopMethodConsole.
    exit /b 1
)

echo.
echo Cambios aplicados. Los flags entran en efecto en el proximo (re)start.
echo Para reiniciar el servicio con los nuevos flags:
echo   nssm restart %SERVICE_NAME%
echo.

endlocal
exit /b 0
