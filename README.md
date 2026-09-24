# TECHBOT Openpay / TotalPOS Bridge

Bridge REST para el **BBVA TotalPOS Java SDK 1.1.13**, diseñado para ejecutarse en una mini-PC Windows junto al PinPad Openpay/TotalPOS y ser consumido por aplicaciones Android a través de la LAN.

**Versión actual del Bridge:** 1.2.0  
**Recovery Service:** 1.0.3  
**Paquete piloto Windows:** `pilot-1.2.0-r1.0.3`

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

## Recovery Service 1.0.3

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

La recuperación normal es **reactiva/on-demand**, no un watchdog periódico. Incluye exclusión mutua para impedir recuperaciones concurrentes, cooldown y verificación funcional posterior al reinicio.

La versión 1.0.3 incorpora además un **startup self-check de una sola ejecución**. Después de iniciar el servicio espera el período de gracia configurado (`startupGraceSeconds`, actualmente 60 s), comprueba la salud del Bridge y solo intenta recuperación si el Bridge no está saludable. No realiza sondeo periódico.

Recovery no debe utilizarse para reiniciar el Bridge ante rechazos comerciales o errores arbitrarios de pago.

## Integración recomendada desde la aplicación de quiosco

La aplicación cliente debe utilizar el Bridge de forma **reactiva**. No se recomienda ejecutar `/api/health` antes de cada venta, ya que agrega una llamada adicional a todas las operaciones normales sin aportar valor cuando el Bridge está funcionando correctamente.

### Flujo normal

La aplicación debe intentar la operación directamente contra el Bridge:

```text
POST /api/venta
      |
      +-- Respuesta HTTP recibida --> procesar normalmente
      |
      +-- Error de comunicación / timeout --> diagnosticar Bridge
```

Mientras el Bridge responda normalmente, no es necesario consultar Recovery ni ejecutar health checks adicionales.

### Cuándo diagnosticar y disparar Recovery

Ante un **error de comunicación** con el Bridge (por ejemplo, conexión rechazada, pérdida de conexión o timeout), la aplicación debe consultar:

```text
GET http://<IP_NUC>:9091/api/health
```

El Bridge se considera saludable únicamente si responde **HTTP 200** y el JSON contiene:

```json
{
  "status": "OK",
  "sdkInitialized": true
}
```

Si el health check no responde, produce timeout/error de conexión, devuelve un HTTP diferente de 200, `status != "OK"` o `sdkInitialized != true`, la aplicación puede solicitar recuperación mediante:

```http
POST http://<IP_NUC>:9092/openpay/recover
X-Techbot-Recovery-Key: <RECOVERY_KEY>
```

Después de una recuperación exitosa, debe verificarse nuevamente `/api/health` antes de considerar disponible el medio de pago.

### Regla crítica después de iniciar una venta

Un timeout o pérdida de comunicación **después de enviar `POST /api/venta` no significa necesariamente que la venta haya fallado**. La operación puede haber llegado a TotalPOS/Openpay y haber sido procesada aunque la aplicación no haya recibido la respuesta.

Por lo tanto:

```text
POST /api/venta
      |
      +-- respuesta recibida --> procesar resultado
      |
      +-- timeout / comunicación perdida
              |
              +--> GET /api/health
                      |
                      +-- Bridge saludable --> NO ejecutar Recovery
                      |                      conciliar/consultar la operación
                      |
                      +-- Bridge no saludable --> Recovery puede restaurar
                                                 la disponibilidad del Bridge
                                                 PERO NO repetir la venta
```

**Nunca ejecutar automáticamente una segunda `POST /api/venta` después de Recovery cuando la primera solicitud pudo haber sido enviada.** Primero debe determinarse el estado de la operación original mediante los mecanismos de consulta/conciliación disponibles.

Esta separación evita dobles cobros y permite que Recovery resuelva exclusivamente la indisponibilidad técnica del Bridge.

### Resumen para implementadores

- No hacer health check antes de cada venta.
- Operar normalmente contra el Bridge mientras responda.
- Ante un problema de comunicación, usar `/api/health` como diagnóstico.
- Disparar `/openpay/recover` únicamente cuando el Bridge no esté saludable.
- Recovery restaura disponibilidad; **no determina si una venta incierta debe repetirse**.
- Si una venta ya fue enviada y se pierde su respuesta, marcarla como pendiente de consulta/conciliación.
- No reintentar automáticamente una venta cuyo resultado sea incierto.
- No disparar Recovery por rechazos comerciales o errores de negocio devueltos normalmente por el Bridge.

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
- espera la transición de los servicios Windows desde `StartPending` hasta `Running` antes de declarar error;
- valida ambos health endpoints y las versiones esperadas;
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
.\installer\build-package.ps1 -Version pilot-1.2.0-r1.0.3
```

GitHub Actions genera además el ZIP y su SHA-256.

## Estado del piloto y validación realizada

El paquete piloto `pilot-1.2.0-r1.0.3`, con Bridge 1.2.0 y Recovery 1.0.3, ha sido validado en laboratorio y en una NUC de producción.

Validaciones completadas:

- instalación y migración desde Bridge 1.1.9 preservando la configuración existente;
- servicios `TotalPosBridge` y `TechbotOpenpayRecovery` en modo `Automatic`;
- Bridge 1.2.0 con SDK inicializado correctamente;
- Recovery 1.0.3 operativo en el puerto 9092;
- coexistencia con el servicio Izipay en el puerto 9090;
- política NSSM `Restart`, `AppRestartDelay=5000` y `AppThrottle=60000`;
- recuperación automática por NSSM ante caída del proceso Java del Bridge;
- recuperación controlada mediante Recovery cuando el servicio Bridge está detenido;
- startup self-check después del período de gracia, sin reinicio cuando el Bridge ya está saludable;
- preservación del JAR anterior como `C:\bridge\totalpos-bridge.previous.jar`;
- validación remota de `/api/health`, `/health` y `/openpay/status`;
- generación correcta del paquete mediante GitHub Actions.

Durante el piloto se corrigió el verificador del instalador para tolerar la transición normal de Windows `StartPending -> Running`. El verificador espera ahora a que ambos servicios alcancen `Running` antes de continuar con las comprobaciones de health, versión y puertos.

### Validación pendiente antes de cerrar el piloto

Permanece pendiente una prueba física end-to-end en la NUC de producción:

1. realizar una transacción real con el PinPad A35;
2. provocar un corte abrupto de energía de la NUC;
3. encender nuevamente sin intervención manual sobre los servicios;
4. esperar el arranque y el startup self-check;
5. comprobar que Bridge y Recovery estén saludables;
6. realizar una nueva transacción real con el A35.

Hasta completar satisfactoriamente esta prueba, el paquete se mantiene como **piloto** y no se considera cerrada la validación de producción.

### Build piloto vigente

El build vigente fue generado desde la rama `pilot/openpay-bridge-1.2.0-recovery-1.0.3` e incorpora las correcciones detectadas durante el piloto.

- Bridge: **1.2.0**
- Recovery: **1.0.3**
- Build GitHub Actions: **#20**
- Commit: `13f2b5fcea031d9f54404a2b9b0ac794b3b5857c`
- Artifact: `TECHBOT-Openpay-Bridge-pilot-1.2.0-r1.0.3`
- SHA-256 del artifact: `89947fda8b7138d2c918b42f72ec8707f171fd53f34a221694228e4c3286aaa6`

## Seguridad operativa

Nunca reintentar automáticamente una venta cuando exista posibilidad de que el autorizador haya recibido la operación y la respuesta se haya perdido. Primero debe consultarse o conciliarse la transacción.

Recovery está diseñado para recuperar indisponibilidad técnica del Bridge, no para resolver rechazos comerciales ni para repetir pagos.

La API key de Recovery debe ser única por instalación y mantenerse fuera del repositorio.
