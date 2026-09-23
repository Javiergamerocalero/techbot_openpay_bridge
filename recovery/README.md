# TECHBOT Openpay Recovery Service

Servicio Windows independiente para verificar y recuperar `TotalPosBridge` desde la aplicación Android del quiosco sin depender de TECHBOT Links.

## Objetivo

El proceso de recuperación se ejecuta fuera de `totalpos-bridge.jar`. De esta forma, si el Bridge o el SDK TotalPOS quedan colgados después de un corte de energía, el Recovery Service sigue disponible y puede reiniciar el servicio Windows `TotalPosBridge` de forma controlada.

## Endpoints

| Método | Endpoint | Autenticación | Uso |
|---|---|---|---|
| GET | `/health` | No | Liveness del Recovery Service |
| GET | `/openpay/status` | `X-Techbot-Recovery-Key` | Verifica `/api/health` del Bridge |
| POST | `/openpay/recover` | `X-Techbot-Recovery-Key` | Reinicia `TotalPosBridge` solo si el Bridge no está saludable |

El endpoint del Bridge usado por defecto es:

```text
http://127.0.0.1:9090/api/health
```

Se considera saludable únicamente cuando responde HTTP 2xx, `status=OK` y `sdkInitialized=true`.

## Seguridad

`/openpay/status` y `/openpay/recover` requieren una clave estática configurada en `recovery.properties`. El servicio no acepta nombres de servicios, rutas, comandos, argumentos de PowerShell ni comandos NSSM enviados por Android. El único servicio Windows que puede controlar es el configurado localmente, por defecto `TotalPosBridge`.

Adicionalmente, el puerto TCP 9092 debe restringirse en Windows Firewall a la IP o subred del quiosco Android. No debe publicarse en Internet.

## Protecciones de recuperación

- Verifica salud antes de reiniciar; si el Bridge está sano no hace nada.
- Solo permite una recuperación concurrente.
- Aplica cooldown entre reinicios, por defecto 120 segundos.
- Espera que el servicio Windows llegue a `RUNNING`.
- Después del restart vuelve a consultar `/api/health` hasta 30 segundos.
- Devuelve error si el Bridge sigue degradado después del reinicio.

## Compilación

Requiere JDK 17+ y Maven:

```bash
cd recovery
mvn clean package
```

Salida:

```text
recovery/target/openpay-recovery-service.jar
```

## Instalación en la mini-PC

1. Crear `C:\bridge\recovery`.
2. Copiar `openpay-recovery-service.jar` a esa carpeta.
3. Copiar `recovery.properties.example` como `C:\bridge\recovery\recovery.properties`.
4. Cambiar `apiKey=CHANGE_ME` por una clave aleatoria de al menos 24 caracteres.
5. Copiar/ejecutar `scripts/install-recovery-service.bat` como Administrador.
6. Crear una regla de Windows Firewall para permitir TCP 9092 únicamente desde el quiosco Android.

Validación local:

```powershell
curl.exe http://127.0.0.1:9092/health
```

Estado del Bridge:

```powershell
curl.exe -H "X-Techbot-Recovery-Key: SU_CLAVE" http://127.0.0.1:9092/openpay/status
```

Recuperación controlada:

```powershell
curl.exe -X POST -H "X-Techbot-Recovery-Key: SU_CLAVE" http://127.0.0.1:9092/openpay/recover
```

## Integración Android

La app debe invocar `/openpay/recover` únicamente ante errores técnicos compatibles con pérdida de comunicación o Bridge/SDK degradado. Un rechazo de tarjeta, cancelación de usuario o respuesta comercial de Openpay no debe disparar un restart.

La recuperación del Bridge tampoco significa que sea seguro reenviar una venta. Si el resultado de una transacción es incierto, la app debe consultar primero el estado de esa operación para evitar un doble cobro.
