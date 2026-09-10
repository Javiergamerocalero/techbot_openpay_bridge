@echo off
setlocal

REM TECHBOT Openpay Bridge - actualizacion segura
REM Requiere una instalacion existente con configuraciones locales.

if not exist "C:\bridge\application.yaml" goto :err_no_bridge_config
if not exist "C:\bridge\recovery\recovery.properties" goto :err_no_recovery_config

call "%~dp0install-openpay-bridge.bat"
set "RC=%ERRORLEVEL%"
endlocal & exit /b %RC%

:err_no_bridge_config
echo ERROR: no existe C:\bridge\application.yaml.
echo Para una instalacion limpia use install-openpay-bridge.bat.
endlocal
exit /b 1

:err_no_recovery_config
echo ERROR: no existe C:\bridge\recovery\recovery.properties.
echo Para una instalacion limpia use install-openpay-bridge.bat.
endlocal
exit /b 1
