@echo off
setlocal EnableExtensions

REM TECHBOT Openpay Bridge - desinstalador seguro
REM Elimina solo los servicios Windows. Por defecto conserva configuracion,
REM logs, base SQLite y datos SDK bajo C:\bridge.

net session >nul 2>&1
if %ERRORLEVEL% neq 0 goto :err_admin

set "ROOT=C:\bridge"
set "BRIDGE_SERVICE=TotalPosBridge"
set "RECOVERY_SERVICE=TechbotOpenpayRecovery"
set "NSSM=%~dp0nssm.exe"
if not exist "%NSSM%" if exist "%ROOT%\nssm.exe" set "NSSM=%ROOT%\nssm.exe"
if not exist "%NSSM%" set "NSSM=nssm.exe"

where "%NSSM%" >nul 2>&1
if %ERRORLEVEL% neq 0 if not exist "%NSSM%" goto :err_nssm

echo Deteniendo servicios...
"%NSSM%" stop %RECOVERY_SERVICE% >nul 2>&1
"%NSSM%" stop %BRIDGE_SERVICE% >nul 2>&1

echo Eliminando wrappers de servicio...
"%NSSM%" remove %RECOVERY_SERVICE% confirm >nul 2>&1
"%NSSM%" remove %BRIDGE_SERVICE% confirm >nul 2>&1

echo.
echo ============================================================
echo Servicios eliminados:
echo   %BRIDGE_SERVICE%
echo   %RECOVERY_SERVICE%
echo.
echo Se han CONSERVADO los archivos y datos de %ROOT%.
echo Para una reinstalacion, vuelva a ejecutar install-openpay-bridge.bat.
echo ============================================================
endlocal
exit /b 0

:err_admin
echo ERROR: ejecutar este desinstalador como Administrador.
goto :fail
:err_nssm
echo ERROR: no se encontro nssm.exe junto al desinstalador, en C:\bridge ni en PATH.
goto :fail
:fail
endlocal
exit /b 1
