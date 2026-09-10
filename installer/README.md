# TECHBOT Openpay Bridge - instalador unificado

Este directorio prepara el paquete Windows que instala los dos procesos requeridos por la solucion Openpay/TotalPOS:

- `TotalPosBridge`: Bridge REST hacia el SDK TotalPOS/Openpay.
- `TechbotOpenpayRecovery`: servicio independiente de diagnostico y recuperacion controlada del Bridge.

Los servicios permanecen separados intencionalmente. Recovery debe seguir operativo aunque el JVM de `TotalPosBridge` este detenido o bloqueado.

## Estructura del ZIP

```text
install-openpay-bridge.bat
update-openpay-bridge.bat
uninstall-openpay-bridge.bat
totalpos-bridge.jar
openpay-recovery-service.jar
application.yaml.example
recovery.properties.example
nssm.exe
PACKAGE-MANIFEST.txt
README.md
```

## Puertos estandarizados

- `9091`: TotalPosBridge (`/api/health`)
- `9092`: TechbotOpenpayRecovery (`/health`, `/openpay/status`, `/openpay/recover`)
- `9090`: no se utiliza ni modifica; queda reservado para otros servicios existentes, por ejemplo Izipay.

## Instalacion limpia

Ejecutar `install-openpay-bridge.bat` como Administrador. Si no existen configuraciones locales, el instalador copia los archivos `.example` a:

```text
C:\bridge\application.yaml
C:\bridge\recovery\recovery.properties
```

En ese caso instala los wrappers de servicio pero no inicia los servicios y devuelve codigo 2. Editar ambos archivos, reemplazar placeholders y ejecutar nuevamente el instalador.

## Actualizacion

`update-openpay-bridge.bat` exige que ya existan las dos configuraciones locales y reutiliza el instalador unificado.

La actualizacion:

- detiene ambos servicios;
- reemplaza los JAR;
- preserva `application.yaml` y `recovery.properties`;
- reinstala/configura los wrappers NSSM sin duplicarlos;
- inicia primero `TotalPosBridge` y luego Recovery;
- consulta `http://127.0.0.1:9091/api/health` y `http://127.0.0.1:9092/health`.

## Desinstalacion

`uninstall-openpay-bridge.bat` elimina `TechbotOpenpayRecovery` y `TotalPosBridge`, pero por defecto conserva todos los archivos, configuraciones, logs, SQLite y datos SDK en `C:\bridge`.

## Generacion automatica del ZIP

Desde PowerShell, en la raiz del repositorio:

```powershell
.\installer\build-package.ps1 -Version 1.0.0
```

El script:

1. compila `TotalPosBridge` con Maven;
2. compila y ejecuta tests de Recovery;
3. localiza `nssm.exe` en `installer\`, `C:\bridge` o PATH;
4. arma `dist\TECHBOT-Openpay-Bridge-<version>`;
5. genera el ZIP;
6. genera un archivo `.sha256.txt` con SHA-256.

Tambien puede indicarse NSSM explicitamente:

```powershell
.\installer\build-package.ps1 -Version 1.0.0 -NssmPath C:\bridge\nssm.exe
```

## Seguridad

`recovery.properties` debe contener una API key unica de al menos 24 caracteres y distinta de `CHANGE_ME`. No almacenar claves reales en Git ni incorporarlas al ZIP generico.

El instalador no abre globalmente TCP 9092. La regla de Windows Firewall debe agregarse/restringirse cuando se conozca la IP o subred autorizada del kiosco Android. Antes de produccion debe verificarse que no exista otra regla amplia que permita 9092.

## Validacion antes de produccion

El paquete unificado debe probarse en dos escenarios:

1. actualizacion sobre una instalacion existente, verificando preservacion de configuracion y recuperacion funcional;
2. instalacion limpia en Windows.

Tambien queda pendiente validar Android -> Recovery por LAN y la restriccion efectiva de Firewall.

No fusionar esta rama a `main` hasta completar las pruebas y aprobacion explicita.
