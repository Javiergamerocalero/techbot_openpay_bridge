# TECHBOT Openpay Bridge - instalador unificado

Este directorio prepara el paquete Windows que instala los dos procesos requeridos por la solucion Openpay/TotalPOS:

- `TotalPosBridge`: Bridge REST hacia el SDK TotalPOS/Openpay.
- `TechbotOpenpayRecovery`: servicio independiente de diagnostico y recuperacion controlada del Bridge.

Los servicios permanecen separados intencionalmente. Recovery debe seguir operativo aunque el JVM de `TotalPosBridge` este detenido o bloqueado.

## Estructura esperada del paquete

Colocar junto a `install-openpay-bridge.bat`:

```text
install-openpay-bridge.bat
totalpos-bridge.jar
openpay-recovery-service.jar
application.yaml
recovery.properties
nssm.exe
```

`nssm.exe` tambien puede existir previamente en `C:\bridge` o en PATH.

## Puertos estandarizados

- `9091`: TotalPosBridge (`/api/health`)
- `9092`: TechbotOpenpayRecovery (`/health`, `/openpay/status`, `/openpay/recover`)
- `9090`: no se utiliza ni modifica; queda reservado para otros servicios existentes, por ejemplo Izipay.

## Seguridad

`recovery.properties` debe contener una API key unica de al menos 24 caracteres y distinta de `CHANGE_ME`. No almacenar claves reales en Git.

El instalador no abre globalmente TCP 9092. La regla de Windows Firewall debe agregarse/restringirse cuando se conozca la IP o subred autorizada del kiosco Android. Antes de produccion debe verificarse que no exista otra regla amplia que permita 9092.

## Actualizaciones

El instalador reemplaza los JAR, pero preserva `C:\bridge\application.yaml` y `C:\bridge\recovery\recovery.properties` si ya existen. Esto evita destruir configuraciones locales o secretos durante una actualizacion.

## Validacion pendiente antes de produccion

La logica Windows de Recovery ya puede probarse independientemente. El paquete unificado debe someterse despues a una instalacion limpia y una actualizacion sobre una instalacion existente. Tambien queda pendiente validar Android -> Recovery por LAN y la restriccion efectiva de Firewall.

No fusionar esta rama a `main` hasta completar las pruebas y aprobacion explicita.
