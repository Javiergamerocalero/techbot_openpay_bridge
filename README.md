# TotalPOS Bridge

REST wrapper for the **BBVA TotalPOS Java SDK 1.1.13** that runs as a service
on a Windows mini-PC alongside the PinPad. Exposes the SDK's transactional
operations (venta, anulación, QR, cierre de turno, carga de llaves, reportes)
over HTTP/JSON so the kiosk Flutter app (`notaria_paino`) can drive them
across the LAN.

The SDK is a plain JVM library — it has no native API of its own. The bridge
loads it at boot, holds the `Interfaz` singleton initialized, and translates
incoming REST calls into SDK invocations.

## Why it exists

The TotalPOS SDK can't run on Android (its `RESTEasy` HTTP stack crashes on
ART with `Unable to instantiate MessageBodyReader`). On a JVM (Windows, Mac,
Linux) it works perfectly. By moving SDK execution to a Windows mini-PC and
talking to it from Android via REST, we get:

- The SDK's native log file in the exact format BBVA cert reviewers expect.
- No noisy retry storms from the SDK's internal `reverso` thread.
- Drastically simpler kiosk client (Flutter just makes HTTP calls).

## Architecture

```
┌─────────────────────┐         HTTP/JSON          ┌──────────────────────┐         TCP/USB
│  Kiosk (Android)    │  ─────────────────────▶   │ Mini PC (Windows)    │  ─────────────▶ PinPad
│  notaria_paino app  │   POST /api/venta          │ JDK 17               │
│                     │   POST /api/venta-qr       │ totalpos-bridge.jar  │         HTTPS
│                     │   POST /api/cierre-turno   │   ↳ totalpos-sdk     │  ─────────────▶ BBVA host
│                     │   …                        │                      │
└─────────────────────┘                            └──────────────────────┘
```

## Build

Requires Maven and JDK 17+ on the developer machine.

```bash
mvn package
```

Produces `target/totalpos-bridge.jar` — single executable fat jar that bundles
the TotalPOS SDK and all dependencies.

## Run locally

```bash
cp application.yaml.example application.yaml
# edit application.yaml with real credentials
java -jar target/totalpos-bridge.jar
```

Health check:

```bash
curl http://localhost:9090/api/health
```

## Install as Windows service

See [`scripts/install-service.bat`](scripts/install-service.bat). Uses
[NSSM](https://nssm.cc/) to register the bridge as an auto-start Windows
service named `TotalPosBridge`.

## Endpoints

| Method | Path | Description |
|---|---|---|
| GET  | `/api/health` | Bridge + SDK + PinPad status |
| POST | `/api/venta` | Sale with card (chip/contactless/swipe) |
| POST | `/api/venta-qr` | QR sale (Yape / PLIN / etc.) |
| POST | `/api/venta-qr/cancelar` | Stop showing the QR on the PinPad |
| POST | `/api/anulacion-tarjeta` | Card sale void |
| POST | `/api/anulacion-qr` | QR sale void |
| POST | `/api/cierre-turno` | Close the shift on the host |
| POST | `/api/carga-llaves` | Load encryption keys into the PinPad |
| GET  | `/api/consultar/{id}` | Lookup a transaction by UUID |
| GET  | `/api/reportes/totalizado?moneda=SOLES` | Shift totals |
| GET  | `/api/reportes/detallado?moneda=SOLES` | Itemized shift report |
| GET  | `/api/reportes/cierre/operaciones?idTurno=X` | Closed shift detail |
| GET  | `/api/reportes/cierre/resumen?idTurno=X` | Closed shift summary |
| GET  | `/api/transactions/turno-actual` | Local list of approved sales this shift |
| GET  | `/api/logs/files` | Index of SDK log files |
| GET  | `/api/logs/{filename}` | Download an SDK log file |

## Configuration

All runtime parameters live in `application.yaml` (next to the jar). See
[`application.yaml.example`](application.yaml.example) for the full schema.
