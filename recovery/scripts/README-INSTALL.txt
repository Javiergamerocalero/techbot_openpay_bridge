TECHBOT OPENPAY RECOVERY SERVICE v1.0.0
Instalacion en mini-PC Windows
=======================================================================

Que hace
-----------------------------------------------------------------------
Servicio Windows independiente que vigila el TotalPOS Bridge y permite
reiniciarlo de forma controlada desde el kiosco, sin que nadie tenga que
ir hasta la maquina.

Es un servicio SEPARADO del Bridge. No modifica su instalacion: lo unico
que hace sobre el es detenerlo y arrancarlo cuando se invoca /openpay/recover.


Requisitos
-----------------------------------------------------------------------
- Windows con el TotalPOS Bridge ya instalado como servicio TotalPosBridge.
- Java 17 o superior instalado y accesible (java.exe en el PATH o JAVA_HOME).
  Comprobar con:  java -version
- nssm.exe (el mismo que usa el Bridge). Ver el apartado NSSM.
- Ejecutar el instalador como Administrador.


Carpeta de instalacion recomendada
-----------------------------------------------------------------------
  C:\bridge\recovery

Queda al lado del Bridge (C:\bridge) pero en su propia carpeta, para que
se puedan actualizar o desinstalar por separado.


NSSM
-----------------------------------------------------------------------
El instalador busca nssm.exe en este orden:
  1. La misma carpeta donde esta install-recovery-service.bat
  2. C:\bridge\nssm.exe
  3. El PATH del sistema

En una mini-PC que ya tiene el Bridge instalado, normalmente ya esta en
C:\bridge\nssm.exe y no hay que hacer nada. Si no estuviera, copiar
nssm.exe junto a los scripts antes de instalar.


Instalacion paso a paso
-----------------------------------------------------------------------
1. Descomprimir el ZIP en una carpeta temporal.

2. Crear la carpeta C:\bridge\recovery y copiar dentro:
     - openpay-recovery-service.jar
     - recovery.properties.example

3. Renombrar recovery.properties.example a recovery.properties.

4. Editar recovery.properties y poner una clave propia en apiKey.
   Debe tener al menos 24 caracteres aleatorios. El servicio SE NIEGA A
   ARRANCAR si se deja CHANGE_ME o si la clave es mas corta.

   Para generar una clave desde PowerShell:
     [Convert]::ToBase64String((1..24 | % {Get-Random -Max 256}))

5. Abrir CMD como Administrador, ir a la carpeta de los scripts y ejecutar:
     install-recovery-service.bat

   El instalador verifica que exista Java, el jar, la configuracion y que
   la clave haya sido cambiada. Si algo falta, avisa y no instala nada.

6. El servicio queda instalado como TechbotOpenpayRecovery, con arranque
   automatico al encender la maquina.


Puerto y cortafuegos
-----------------------------------------------------------------------
El servicio escucha en el puerto 9092.

Por defecto escucha en todas las interfaces (bindAddress=0.0.0.0) para que
el kiosco Android pueda alcanzarlo por la red local.

IMPORTANTE: restringir en el Firewall de Windows la entrada TCP 9092 a la
IP o subred del kiosco. Sin esa restriccion, cualquier equipo de la red
puede intentar usar el endpoint (necesitaria ademas la clave, pero no
conviene dejarlo expuesto).

Si el kiosco corre en la MISMA maquina, poner bindAddress=127.0.0.1 en
recovery.properties y no hace falta abrir nada en el cortafuegos.


Donde se configura la clave
-----------------------------------------------------------------------
En C:\bridge\recovery\recovery.properties, campo apiKey.

Quien llame a /openpay/status o /openpay/recover debe enviarla en la
cabecera:
     X-Techbot-Recovery-Key: <la clave>

Tras cambiar la clave hay que reiniciar el servicio:
     nssm restart TechbotOpenpayRecovery


Como verificar que quedo bien
-----------------------------------------------------------------------
1) Que el servicio esta vivo (no necesita clave):

     curl http://127.0.0.1:9092/health

   Respuesta esperada:
     {"ok":true,"service":"TechbotOpenpayRecovery","version":"1.0.0"}

2) Estado del Bridge (necesita clave):

     curl http://127.0.0.1:9092/openpay/status -H "X-Techbot-Recovery-Key: LA_CLAVE"

   Con el Bridge sano responde HTTP 200 y ok:true.
   Con el Bridge caido responde HTTP 503 e indica el motivo, por ejemplo
   connection_refused o sdk_initialized:false.

   Se considera sano SOLO si el Bridge responde status OK y sdkInitialized
   en true. No basta con que el puerto conteste.

3) Probar la recuperacion (necesita clave, es POST):

     curl -X POST http://127.0.0.1:9092/openpay/recover -H "X-Techbot-Recovery-Key: LA_CLAVE"

   Respuestas posibles:
     200 con "bridge_already_healthy"  -> el Bridge estaba bien, no se toco nada
     200 con "recovered"               -> se reinicio y quedo sano
     429 con "restart_cooldown_NNNs"   -> hubo un intento hace poco, espera
     409 recovery_in_progress          -> ya hay otra recuperacion en curso
     503 bridge_unhealthy_after_restart-> se reinicio pero sigue mal
     500 service_restart_failed        -> no se pudo reiniciar el servicio
     401                               -> falta la clave o es incorrecta

   Para probar el caso real: detener el Bridge (nssm stop TotalPosBridge),
   llamar a /openpay/recover y comprobar que vuelve a arrancar y responde
   "recovered".


Protecciones que trae
-----------------------------------------------------------------------
- El unico servicio Windows que puede tocar es TotalPosBridge, y va fijo
  en el codigo. No se puede cambiar por configuracion.
- /openpay/recover no acepta ningun parametro ni comando. No hay forma de
  pedirle que ejecute otra cosa.
- La direccion del Bridge que consulta solo puede ser de la propia maquina.
- Solo una recuperacion a la vez.
- Enfriamiento de 120 segundos entre intentos, tambien cuando el intento
  anterior fallo, para que un servicio roto no entre en bucle de reinicios.
- La clave se compara en tiempo constante.


Logs
-----------------------------------------------------------------------
  C:\bridge\recovery\recovery.out.log    (salida normal)
  C:\bridge\recovery\recovery.err.log    (errores)

Rotan solos al llegar a 5 MB.

Para ver los ultimos movimientos desde PowerShell:
     Get-Content C:\bridge\recovery\recovery.out.log -Tail 30 -Wait

Cada recuperacion deja registro de por que se considero caido el Bridge,
si se reinicio y cuanto tardo.


Desinstalar
-----------------------------------------------------------------------
Abrir CMD como Administrador y ejecutar:
     uninstall-recovery-service.bat

Quita el servicio TechbotOpenpayRecovery y deja los archivos y logs en
C:\bridge\recovery por si hacen falta. Borrar esa carpeta a mano si se
quiere eliminar tambien la configuracion.

El TotalPosBridge no se toca en ningun momento.


Actualizar a una version nueva
-----------------------------------------------------------------------
     nssm stop TechbotOpenpayRecovery
     copiar el jar nuevo sobre C:\bridge\recovery\openpay-recovery-service.jar
     nssm start TechbotOpenpayRecovery

La configuracion se conserva.
