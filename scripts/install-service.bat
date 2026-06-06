@echo off
REM Install the TotalPOS Bridge as a Windows service named "TotalPosBridge".
REM
REM Prerequisites:
REM   - JDK 17 installed and JAVA_HOME pointing to it.
REM   - NSSM (Non-Sucking Service Manager) installed. Either put nssm.exe on
REM     PATH or hardcode NSSM_PATH below.
REM   - totalpos-bridge.jar + application.yaml already copied to BRIDGE_DIR.
REM
REM Run this script as Administrator.
REM
REM NOTE: this script avoids parenthesized IF blocks because JAVA_HOME on
REM Windows often contains paths like "C:\Program Files (x86)\..." whose
REM literal (x86) breaks batch's nested-IF parser.

setlocal

REM ─── Verificar privilegios de Administrador ─────────────────────
REM `net session` requiere permisos elevados. Si no los tenemos, abortamos
REM antes de invocar NSSM (que falla en silencio sin devolver errorlevel).
net session >nul 2>&1
if %ERRORLEVEL% neq 0 goto :err_not_admin

set "BRIDGE_DIR=C:\bridge"
set "SERVICE_NAME=TotalPosBridge"

REM Look for nssm.exe in this script's directory first (so the operator can
REM just drop nssm.exe next to install-service.bat and it Just Works),
REM then fall back to PATH lookup.
set "NSSM_PATH=%~dp0nssm.exe"
if not exist "%NSSM_PATH%" set "NSSM_PATH=nssm.exe"

REM ─── Resolve JAVA_EXE ────────────────────────────────────────────
REM Prioridad:
REM   1. Lo que diga `where java` (el mismo java.exe que se usa al ejecutar
REM      `java -version` manualmente desde una ventana de PowerShell). Esto
REM      respeta el PATH del usuario.
REM   2. %JAVA_HOME%\bin\java.exe como fallback.
REM
REM Este orden es CRITICO en mini PCs donde JAVA_HOME apunta a un JDK
REM legacy (e.g. el JDK 8 que viene empaquetado con TechPark Container en
REM "C:\Program Files (x86)\Park\container\bin\java\jdk") pero hay un
REM JDK 17 separado en el PATH para el bridge. Tomar JAVA_HOME en ese
REM caso tira UnsupportedClassVersionError al arrancar.

set "JAVA_EXE="
for /f "delims=" %%i in ('where java 2^>nul') do (
    if not defined JAVA_EXE set "JAVA_EXE=%%i"
)

if not defined JAVA_EXE (
    if "%JAVA_HOME%"=="" goto :err_no_java_home
    set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
)

if not exist "%JAVA_EXE%" goto :err_java_not_found

REM ─── Verificar que sea JDK 17 o superior ────────────────────────
REM El bridge se compilo con JDK 17 (class file v61). Si la JVM es Java 8
REM (class v52), tira UnsupportedClassVersionError al cargar com/.../Main.
REM Detectamos eso aca para no tener que ir a buscarlo a los logs.

"%JAVA_EXE%" -version 2>&1 | findstr /R /C:"version \"1[7-9]" /C:"version \"[2-9][0-9]" >nul
if %ERRORLEVEL% neq 0 goto :err_wrong_java_version

REM ─── Verify artifacts in BRIDGE_DIR ──────────────────────────────
if not exist "%BRIDGE_DIR%\totalpos-bridge.jar" goto :err_no_jar
if not exist "%BRIDGE_DIR%\application.yaml" goto :err_no_yaml

echo Installing %SERVICE_NAME% as a Windows service...
echo   Java:        "%JAVA_EXE%"
echo   Jar:         "%BRIDGE_DIR%\totalpos-bridge.jar"
echo   Config:      "%BRIDGE_DIR%\application.yaml"
echo   Working dir: "%BRIDGE_DIR%"
echo.

"%NSSM_PATH%" install %SERVICE_NAME% "%JAVA_EXE%" -jar "%BRIDGE_DIR%\totalpos-bridge.jar"
if %ERRORLEVEL% neq 0 goto :err_nssm_install

"%NSSM_PATH%" set %SERVICE_NAME% AppDirectory "%BRIDGE_DIR%"
"%NSSM_PATH%" set %SERVICE_NAME% DisplayName "TotalPOS Bridge"
"%NSSM_PATH%" set %SERVICE_NAME% Description "REST wrapper for the BBVA TotalPOS SDK. Serves the kiosk Flutter app over LAN."
"%NSSM_PATH%" set %SERVICE_NAME% Start SERVICE_AUTO_START

REM Redirect stdout/stderr to log files in BRIDGE_DIR. NSSM rotates them at 10 MB.
"%NSSM_PATH%" set %SERVICE_NAME% AppStdout "%BRIDGE_DIR%\service.out.log"
"%NSSM_PATH%" set %SERVICE_NAME% AppStderr "%BRIDGE_DIR%\service.err.log"
"%NSSM_PATH%" set %SERVICE_NAME% AppRotateFiles 1
"%NSSM_PATH%" set %SERVICE_NAME% AppRotateBytes 10485760

REM On any non-zero exit, restart after 5 seconds.
"%NSSM_PATH%" set %SERVICE_NAME% AppRestartDelay 5000

echo.
echo Service installed. Starting...
"%NSSM_PATH%" start %SERVICE_NAME%

echo.
echo Done. Verify with: curl http://localhost:9090/api/health
echo To stop:    nssm stop %SERVICE_NAME%
echo To remove:  nssm remove %SERVICE_NAME% confirm

endlocal
exit /b 0

REM ─── Error labels ───────────────────────────────────────────────
:err_no_java_home
echo ERROR: JAVA_HOME no esta definido. Configuralo apuntando a tu JDK 17:
echo   setx JAVA_HOME "C:\Program Files\Java\jdk-17"
echo Luego cerra esta ventana, abri una nueva como Administrador y reintenta.
endlocal
exit /b 1

:err_java_not_found
echo ERROR: No se encontro java.exe en:
echo   "%JAVA_EXE%"
echo Verifica que JAVA_HOME apunta a una instalacion valida de JDK 17.
endlocal
exit /b 1

:err_no_jar
echo ERROR: No se encontro "%BRIDGE_DIR%\totalpos-bridge.jar"
echo Copia el jar a %BRIDGE_DIR% antes de instalar el servicio.
endlocal
exit /b 1

:err_no_yaml
echo ERROR: No se encontro "%BRIDGE_DIR%\application.yaml"
echo Copia application.yaml.example a %BRIDGE_DIR%\application.yaml y editalo primero.
endlocal
exit /b 1

:err_nssm_install
echo ERROR: nssm install fallo. Verifica que:
echo   - "%NSSM_PATH%" existe (probar: nssm --version)
echo   - Esta ventana esta abierta como Administrador
endlocal
exit /b 1

:err_wrong_java_version
echo ============================================================
echo  ERROR: version de Java incompatible.
echo ============================================================
echo.
echo  El bridge fue compilado con JDK 17. La version que se va a
echo  usar para el servicio no es 17 o superior:
echo.
"%JAVA_EXE%" -version 2>&1
echo.
echo  Ruta: "%JAVA_EXE%"
echo.
echo  Como arreglarlo:
echo    1. Instalar Adoptium Temurin JDK 17 desde:
echo         https://adoptium.net/temurin/releases/?version=17
echo    2. Durante la instalacion, marcar "Add to PATH" y
echo       "Set JAVA_HOME variable".
echo    3. Cerrar TODAS las ventanas de PowerShell, abrir una
echo       nueva como Administrador y reintentar.
echo.
echo  Verificacion previa (en la ventana nueva):
echo    java -version    [debe decir 17.x.x o superior]
echo    where java       [debe apuntar al JDK 17]
echo.
endlocal
exit /b 1

:err_not_admin
echo ============================================================
echo  ERROR: este script necesita privilegios de ADMINISTRADOR.
echo ============================================================
echo.
echo  Como abrir PowerShell como Administrador:
echo    1. Cerra esta ventana.
echo    2. Click en el menu Inicio.
echo    3. Escribi "PowerShell" en el buscador.
echo    4. Click DERECHO sobre "Windows PowerShell".
echo    5. Seleccionar "Ejecutar como administrador".
echo    6. Aceptar la ventana de UAC que aparece.
echo    7. La barra de titulo debe decir "Administrador: Windows PowerShell".
echo    8. Reintenta:
echo         cd C:\bridge
echo         .\install-service.bat
echo.
endlocal
exit /b 1
