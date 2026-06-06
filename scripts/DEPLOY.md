# Bridge TotalPOS — guía para instalar en la mini PC

Hola Javier,

Te paso este paquete con todo lo necesario para que el SDK que ya tenés
corriendo en la mini PC quede expuesto como un servicio HTTP, así desde
el kiosko Flutter podemos invocar las operaciones por red.

La idea es simple: corre en Windows como un servicio Java, levanta el
SDK al arrancar (igual que hace tu app actual), y queda escuchando en
un puerto (por defecto el 9090) para recibir las peticiones del kiosko.

Cualquier duda durante la instalación, me avisas y lo vemos juntos.

---

## Lo que tienes que tener antes de empezar

**JDK 17 o superior**. Si tu setup actual del SDK ya corre, lo más
probable es que ya lo tengas. Para confirmar, abre una consola y haz:

```
java -version
```

Si dice `17.x.x` o más, listo. Si no, lo bajas de
https://adoptium.net/ (Temurin, gratuito). Después de instalarlo, asegurate
de que `JAVA_HOME` esté seteado:

```
setx JAVA_HOME "C:\Program Files\Java\jdk-17"
```

(Cerrar y reabrir el CMD para que tome el cambio.)

**NSSM** (es lo que vamos a usar para que el bridge quede como servicio
de Windows con auto-arranque). Lo bajas del sitio oficial https://nssm.cc/download,
descomprimes el zip, y copias `nssm.exe` a `C:\Windows\System32\` para
que esté en el PATH. Para verificar:

```
nssm
```

Si te muestra la ayuda del comando, ya está.

**El PinPad debe estar conectado y funcionando**, igual que como lo
tienes ahora con la app del SDK. No tocamos nada del hardware ni del
pareo — el bridge usa el mismo PinPad que ya tienes andando.

---

## Pasos para instalar

### 1. Crear la carpeta del bridge

```
mkdir C:\bridge
```

### 2. Copiar los archivos del paquete

Descomprime el zip que te mandé y mueve los 4 archivos a `C:\bridge\`:

- `totalpos-bridge.jar` (el ejecutable, pesa 27 MB)
- `application.yaml.example` (la plantilla de configuración)
- `install-service.bat`
- `uninstall-service.bat`

### 3. Editar el archivo de configuración

Primero hacemos una copia de la plantilla y la abrimos:

```
cd C:\bridge
copy application.yaml.example application.yaml
notepad application.yaml
```

Ahí dentro hay un montón de valores que tenés que reemplazar con los
reales de la afiliación del cliente. Los importantes son:

- **`afiliacion`** — el número de afiliación que les dio BBVA
- **`idAplicacion`** y **`claveSecreta`** — las credenciales OAuth que les dio E-Global
- **`hostUrl`** — la URL del host autorizador (la que usas hoy en tu setup del SDK; suele ser la de test `https://itg.bbvatalam.egltest.egl-cloud.com` o la de producción)
- **`pinpadConexion`** — el puerto COM si es por USB (ej. `COM4`) o la IP si es WiFi (ej. `192.168.18.212`)
- **`pinpadPuerto`** — sólo si es WiFi, normalmente `5000`
- **`numeroTerminal`** — el número de terminal en la afiliación, típicamente `"1"`
- **`pinpadAndroid`** — déjalo en `false` (estás en Windows, no Android)

Los valores deben coincidir con los que ya estás usando en tu setup
actual. Si me pasas el config actual del SDK, te confirmo cuáles
corresponden a qué campo.

### 4. Crear la carpeta donde el SDK guarda sus datos

```
mkdir C:\bridge\sdk-data
```

Dentro de esa carpeta el SDK va a ir guardando sus logs nativos
(`sdk-data\totalpos\logs\`), los reversos pendientes, el caché de BINes,
etc. Son los mismos archivos que ya genera tu setup actual, solo que
ahora viven adentro de `C:\bridge\sdk-data\`.

### 5. Probarlo a mano antes de instalarlo como servicio

Importante hacer esta prueba primero, porque si hay algo mal en la
configuración nos enteramos enseguida en consola en vez de tener que
revisar logs del servicio.

```
cd C:\bridge
java -jar totalpos-bridge.jar
```

Después de unos 5 segundos deberías ver algo así:

```
TotalPOS Bridge v1.0.0 starting…
INFO  - Loaded config from C:\bridge\application.yaml
INFO  - Initializing TotalPOS SDK…
INFO  - TotalPOS SDK initialized successfully
INFO  - Bridge listening on http://0.0.0.0:9090
```

Si llegaste a la línea `TotalPOS SDK initialized successfully` y la
siguiente `Bridge listening on...`, ya está corriendo.

Para confirmar que responde, abre otro CMD y haz:

```
curl http://localhost:9090/api/health
```

Esto te debería devolver un JSON con `"sdkInitialized": true`. Si lo ves,
todo bien — cierra el bridge con `Ctrl+C` y pasamos al siguiente paso.

**Si te falla en este punto**, el error más común es que el PinPad no
responde en el puerto que pusiste. Si ves `No se ha logrado abrir
conexión en "COM4"` (o en una IP), revisa el `pinpadConexion` en el
yaml. Cualquier otro error pasamelo y lo vemos.

### 6. Abrir el puerto en el firewall

Para que el kiosko pueda alcanzar al bridge desde la red, hay que
abrirle el puerto en el firewall de Windows. Desde un CMD como
administrador:

```
netsh advfirewall firewall add rule name="TotalPOS Bridge" dir=in action=allow protocol=TCP localport=9090
```

(Si cambiaste el puerto en el yaml, ajusta el número en el comando.)

### 7. Instalarlo como servicio Windows

Ahora sí, lo dejamos corriendo permanentemente como servicio. Abre un
CMD **como administrador** y ejecuta:

```
cd C:\bridge
install-service.bat
```

Al final del output deberías ver `Service installed. Starting…`. Para
verificar que quedó corriendo:

```
sc query TotalPosBridge
```

Debe decir `STATE: 4 RUNNING`. También aparece en `services.msc` como
"TotalPOS Bridge" con tipo de inicio "Automático" — eso significa que
se va a levantar solito cada vez que reinicies la máquina.

Para detener / arrancar / desinstalar después:

```
nssm stop TotalPosBridge
nssm start TotalPosBridge
nssm restart TotalPosBridge
uninstall-service.bat
```

---

## Dónde quedan los logs

Hay tres archivos distintos de logs que te van a servir para
diagnosticar cualquier cosa:

- **`C:\bridge\logs\bridge-YYYY-MM-DD.log`** — Logs del bridge. HTTP
  requests entrando, errores, init del SDK. Es el primero que conviene
  mirar si algo no funciona.

- **`C:\bridge\service.out.log`** y **`service.err.log`** — Stdout y
  stderr del servicio según NSSM. Útil si el bridge ni siquiera arrancó.

- **`C:\bridge\sdk-data\totalpos\logs\totalpos_sdk_java_<afil>_<fecha>.log`**
  — Los logs nativos del SDK, idénticos a los que genera tu setup
  actual. Estos son los que BBVA va a auditar para la certificación.

---

## Para confirmar que el kiosko puede llegar al bridge

Una vez que esté como servicio y corriendo, hacé esta prueba final
desde el kiosko Android (debe estar en la misma red LAN que la PC):

```
curl http://<IP-DE-LA-PC>:9090/api/health
```

Reemplaza `<IP-DE-LA-PC>` con la IP fija de la mini PC en la red local
(la sacas con `ipconfig` en la PC; suele ser algo como `192.168.18.X`).

Si te devuelve el mismo JSON con `sdkInitialized: true`, listo — el
kiosko ya puede hablarle al bridge. Ahí me das el OK y yo arranco con
el cliente del kiosko para que use estos endpoints.

---

## Qué me tienes que confirmar después

Cuando termines la instalación, pasame estos datos para que yo arranque
del lado del kiosko:

1. La **IP fija** que quedó la PC en la red del kiosko.
2. **`java -version`** (para tener registrada la versión que usaste).
3. La **salida del `curl /api/health`** desde la PC y desde el kiosko
   (para confirmar que ambos pueden hablarle).
4. Si tuviste que **ajustar algún valor** del `application.yaml`
   respecto a la plantilla (por ejemplo cambio de puerto, IP del
   PinPad distinta, etc.).
5. Cómo está conectado el **PinPad** — USB-COM o WiFi — y el valor
   exacto que pusiste en `pinpadConexion`.

Con eso yo termino la integración del lado del kiosko en uno o dos
días y hacemos la prueba completa de venta end-to-end.

---

## Si algo no funciona

Los problemas más comunes y qué suelen significar:

- **`java: command not found`** → JDK no instalado o no en el PATH.
- **`No se ha logrado abrir conexión en "COM4"`** (o IP) → El PinPad
  no responde en el puerto configurado. Revisa el `pinpadConexion` del
  yaml y que el PinPad esté encendido.
- **`Formato del parámetro [numeroTerminal] inválido`** → Te falta el
  `numeroTerminal` en el yaml. Ponle `"1"` (con comillas).
- **El `/api/health` responde 503** → El SDK no terminó de inicializar.
  Mira `service.err.log` o `bridge-*.log` para ver el detalle.
- **`nssm: command not found`** → NSSM no está en el PATH. Verifica que
  `nssm.exe` esté en `C:\Windows\System32\`.
- **El servicio aparece como "Stopped" después de instalarlo** → Algo
  falla en el init del SDK. Los archivos `service.err.log` y
  `bridge-*.log` te dicen qué.
- **El kiosko no alcanza al bridge** → Casi seguro firewall.
  Verifica que corriste el comando del paso 6.

Cualquier cosa que no caiga en esta lista, me pasas el contenido de
`service.err.log` y `bridge-*.log` y lo miro.
