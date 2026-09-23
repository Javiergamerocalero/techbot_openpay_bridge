# TECHBOT Openpay / TotalPOS Bridge

Bridge REST para el **BBVA TotalPOS Java SDK 1.1.13**, diseñado para ejecutarse en una mini-PC Windows junto al PinPad Openpay/TotalPOS y ser consumido por aplicaciones Android a través de la LAN.

**Versión actual del Bridge:** 1.2.0  
**Recovery Service:** 1.0.2  
**Paquete de instalación Windows:** 1.0.2

El SDK TotalPOS se mantiene dentro de una JVM en Windows. El Bridge inicializa y conserva la instancia del SDK y expone por HTTP/JSON las operaciones necesarias para pagos, anulaciones, QR, cierre de turno, carga de llaves, consultas, reportes, vouchers y diagnóstico.

## Arquitectura

```text
Android Kiosk
    |
    | HTTP/JSON - LAN
    v
TotalPosBridge :9091
    |
    +--> TotalPOS SDK 1.1.13
    |       |
    |       +--> PinPad Openpay / TotalPOS
    |       +--> Host BBVA / Openpay
    |
    +--> SQLite (transacciones del turno)
    +--> logs SDK

Android / soporte
    |
    | HTTP - LAN
    v
TechbotOpenpayRecovery :9092
    |
    +--> diagnóstico de TotalPosBridge
    +--> recuperación controlada del servicio
```

Los servicios Windows son independientes:

- `TotalPosBridge`: operación de pagos.
- `TechbotOpenpayRecovery`: diagnóstico y recuperación del Bridge.

Recovery permanece separado deliberadamente para poder actuar cuando el proceso Java del Bridge esté detenido o no responda.

## Funcionalidades del Bridge 1.2.0

### Pagos con tarjeta

`POST /api/venta`

Soporta venta regular con chip/contactless/swipe y manejo de promociones/cuotas. Cuando no se especifican cuotas, el Bridge consulta la tarjeta antes de autorizar y permite al SDK determinar si corresponde una modalidad promocional disponible.

Ejemplo mínimo:

```json
{
  "importe": "100.00"
}
```

Campos disponibles:

- `importe`: obligatorio.
- `propina`: opcional.
- `cuotas`: opcional.
- `sinIntereses`: opcional; junto con cuotas solicita modalidad sin intereses.

Solo las operaciones aprobadas por el SDK se persisten como transacciones confirmadas.

### QR

- `POST /api/venta-qr`
- `POST /api/venta-qr/cancelar`
- `POST /api/anulacion-qr`

### Anulación de tarjeta

`POST /api/anulacion-tarjeta`

### Carga de llaves

`POST /api/carga-llaves`

Permite ejecutar la inicialización/carga de llaves requerida por el PinPad. Si una venta detecta que el PinPad requiere llaves, el Bridge responde con HTTP 409 y no continúa con la venta.

### Cierre de turno y reportes

- `POST /api/cierre-turno`
- `GET /api/reportes/firma`
- `GET /api/reportes/totalizado`
- `GET /api/reportes/detallado`
- `GET /api/reportes/cierre/operaciones`
- `GET /api/reportes/cierre/resumen`

### Consulta y conciliación

- `GET /api/consultar/{idTransaccion}`
- `GET /api/transactions/turno-actual`

El Bridge mantiene en SQLite las operaciones aprobadas del turno. Esto permite consultar el estado local y evita depender únicamente de la respuesta inmediata de una operación de pago.

**Seguridad transaccional:** si una venta pudo llegar al autorizador pero se perdió la respuesta, no debe repetirse ciegamente. Debe consultarse/conciliarse primero para evitar un doble cobro.

### Voucher y metadatos

`GET /api/transactions/{id}/voucher`

Genera el voucher desde la transacción persistida e incorpora los metadatos disponibles de la operación, incluyendo información promocional cuando corresponde.

Opcionalmente puede enviarse `razonSocial` como query parameter.

### Logs

- `GET /api/logs/files`
- `GET /api/logs/{filename}`

Permiten consultar los logs nativos generados por TotalPOS, manteniendo la trazabilidad requerida para soporte y certificación.

### Health

`GET /api/health`

Devuelve HTTP 200 únicamente cuando el SDK está inicializado correctamente.

Ejemplo:

```json
{
  "status": "OK",
  "bridgeVersion": "1.2.0",
  "sdkInitialized": true
}
```

## Recovery Service 1.0.2

Servicio Windows independiente en el puerto `9092`.

Endpoints:

| Método | Ruta | Protección | Descripción |
|---|---|---|---|
| GET | `/health` | Pública | Salud del Recovery Service |
| GET | `/openpay/status` | API key | Diagnóstico del Bridge |
| POST | `/openpay/recover` | API key | Recuperación controlada de `TotalPosBridge` |

Los endpoints protegidos utilizan el header:

```text
X-Techbot-Recovery-Key
```

Recovery considera saludable al Bridge solamente cuando `/api/health` devuelve HTTP 200, `status=OK` y `sdkInitialized=true`.

La recuperación es **reactiva/on-demand**, no un watchdog periódico. Incluye exclusión mutua para impedir recuperaciones concurrentes, cooldown y verificación funcional posterior al reinicio.

Recovery no debe utilizarse para reiniciar el Bridge ante rechazos comerciales o errores arbitrarios de pago.

## Endpoints del Bridge

| Método | Ruta | Descripción |
|---|---|---|
| GET | `/api/health` | Estado Bridge/SDK |
| POST | `/api/venta` | Venta con tarjeta |
| POST | `/api/venta-qr` | Venta QR |
| POST | `/api/venta-qr/cancelar` | Cancela visualización QR |
| POST | `/api/anulacion-tarjeta` | Anulación tarjeta |
| POST | `/api/anulacion-qr` | Anulación QR |
| POST | `/api/cierre-turno` | Cierre de turno |
| POST | `/api/carga-llaves` | Carga de llaves del PinPad |
| GET | `/api/consultar/{idTransaccion}` | Consulta una transacción |
| GET | `/api/transactions/turno-actual` | Transacciones aprobadas del turno |
| GET | `/api/transactions/{id}/voucher` | Voucher de transacción persistida |
| GET | `/api/reportes/firma` | Reporte de firma |
| GET | `/api/reportes/totalizado` | Reporte totalizado |
| GET | `/api/reportes/detallado` | Reporte detallado |
| GET | `/api/reportes/cierre/operaciones` | Operaciones de turno cerrado |
| GET | `/api/reportes/cierre/resumen` | Resumen de turno cerrado |
| GET | `/api/logs/files` | Lista logs SDK |
| GET | `/api/logs/{filename}` | Descarga log SDK |

## Configuración

El Bridge lee `application.yaml` al iniciar. La configuración incluye:

- host/puerto REST;
- ruta de trabajo del SDK;
- conexión y timeout del PinPad;
- host BBVA/Openpay;
- afiliación, moneda y terminal;
- credenciales del SDK;
- operador;
- proxy opcional;
- logs;
- SQLite.

Consultar `application.yaml.example` para el esquema completo.

Recovery utiliza `C:\bridge\recovery\recovery.properties`.

**No almacenar credenciales reales ni API keys en Git.**

## Instalador unificado Windows

El paquete Windows instala:

```text
C:\bridge\
  totalpos-bridge.jar
  application.yaml
  nssm.exe
  bridge.db
  sdk-data\
  recovery\
    openpay-recovery-service.jar
    recovery.properties
```

Servicios:

- `TotalPosBridge`
- `TechbotOpenpayRecovery`

Puertos estándar:

- `9091`: Bridge.
- `9092`: Recovery.
- `9090`: no se modifica; queda disponible para otros servicios.

### Instalación limpia

Ejecutar como Administrador:

```bat
install-openpay-bridge.bat
```

Si no existen las configuraciones, se crean a partir de las plantillas y el instalador solicita completarlas antes de iniciar los servicios.

### Actualización

```bat
update-openpay-bridge.bat
```

La actualización:

- detiene Recovery y luego Bridge;
- actualiza los binarios;
- **preserva `application.yaml` y `recovery.properties` existentes**;
- conserva credenciales, afiliación, terminal y configuración particular de cada equipo;
- utiliza NSSM persistente en `C:\bridge\nssm.exe`;
- reinstala/configura los wrappers sin duplicarlos;
- inicia primero Bridge y después Recovery;
- valida ambos health endpoints;
- soporta actualización ejecutada incluso desde `C:\bridge`.

### Desinstalación

`uninstall-openpay-bridge.bat` elimina los servicios. Por defecto conserva configuración, logs, SQLite y datos del SDK.

## Build

Requiere Maven y JDK 17+.

```bash
mvn package
```

Genera:

```text
target/totalpos-bridge.jar
```

El paquete Windows se genera mediante:

```powershell
.\installer\build-package.ps1 -Version 1.0.2
```

GitHub Actions genera además el ZIP y su SHA-256.

## Validación realizada

El paquete 1.0.2 y Bridge 1.2.0 fueron validados en Windows con:

- instalación limpia;
- arranque automático después de reiniciar Windows;
- actualización sobre instalación existente;
- preservación de configuraciones;
- NSSM persistente en `C:\bridge\nssm.exe`;
- funcionamiento después de retirar la carpeta fuente del instalador;
- actualización in-place desde `C:\bridge`;
- recuperación de un Bridge detenido mediante Recovery;
- comprobación de integridad de artefactos y binarios instalados;
- inicialización del SDK y comunicación con PinPad.

## Seguridad operativa

Nunca reintentar automáticamente una venta cuando exista posibilidad de que el autorizador haya recibido la operación y la respuesta se haya perdido. Primero debe consultarse o conciliarse la transacción.

Recovery está diseñado para recuperar indisponibilidad técnica del Bridge, no para resolver rechazos comerciales ni para repetir pagos.

La API key de Recovery debe ser única por instalación y mantenerse fuera del repositorio.
