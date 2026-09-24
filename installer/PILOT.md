# Piloto TECHBOT Openpay Bridge

Paquete piloto validado para:

- TotalPosBridge 1.2.0
- TechbotOpenpayRecovery 1.0.3
- Java 17+
- TotalPOS: TCP 9091
- Recovery: TCP 9092
- NSSM: AppExit=Restart, AppRestartDelay=5000 ms, AppThrottle=60000 ms
- Startup self-check de Recovery habilitable por recovery.properties

## Seguridad y configuracion

El paquete NO contiene una API key real. En una instalacion limpia se generan los archivos locales a partir de los ejemplos y el instalador se detiene con codigo 2 hasta que se configuren.

No versionar recovery.properties con credenciales reales.

Este instalador piloto no crea, habilita, deshabilita ni modifica reglas de Windows Firewall.

## Generar paquete

Desde PowerShell en la raiz de esta rama:

```powershell
.\installer\build-package.ps1
```

Para indicar NSSM:

```powershell
.\installer\build-package.ps1 -NssmPath C:\bridge\nssm.exe
```

Salida esperada:

```text
dist\TECHBOT-Openpay-Bridge-pilot-1.2.0-r1.0.3.zip
dist\TECHBOT-Openpay-Bridge-pilot-1.2.0-r1.0.3.zip.sha256.txt
```

## Instalacion limpia en el NUC piloto

1. Descomprimir el ZIP en una carpeta local.
2. Ejecutar install-openpay-bridge.bat como Administrador.
3. En la primera ejecucion, editar:
   - C:\bridge\application.yaml
   - C:\bridge\recovery\recovery.properties
4. Configurar la API key local y los parametros reales de TotalPOS.
5. Ejecutar nuevamente install-openpay-bridge.bat como Administrador.
6. El instalador no termina correctamente si Bridge/SDK/Recovery/versiones/puertos no pasan la validacion.

## Criterio de aceptacion

La verificacion exige:

- TotalPosBridge = Running / Automatic
- TechbotOpenpayRecovery = Running / Automatic
- 9091 y 9092 en LISTEN
- /api/health: status=OK
- sdkInitialized=true
- bridgeVersion=1.2.0
- /health Recovery: ok=true
- Recovery version=1.0.3

Luego del despliegue realizar la prueba de campo: operacion normal, corte abrupto de energia, encendido sin intervencion, espera del startup self-check y una nueva transaccion Openpay exitosa.
