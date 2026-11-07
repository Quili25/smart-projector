# Decisiones técnicas

Registro de las decisiones de arquitectura y su razón. Orden cronológico.

---

## D1 — Google Cast certificado queda descartado

**Decisión:** no se implementa "Chromecast built-in".

**Razón:** es un programa de licenciamiento de Google. Requiere certificación del
dispositivo, Widevine L1 y un certificado de dispositivo firmado por Google. El
stack receptor es cerrado. No hay forma legítima de habilitarlo en hardware no
certificado.

**Alternativas abiertas evaluadas**, por si se retoman:

- **DIAL sobre SSDP + YouTube Lounge API** — hace que el botón Cast de la app de
  YouTube (iOS y Android) vea el proyector. Es lo que implementan `kodicast`,
  `script.tubecast` y `ytcast-open`. No cubre Netflix, Disney+ ni espejo.
- **DLNA / UPnP renderer** — barato de implementar, sirve para apps tipo VLC o
  LocalCast. No da espejo de pantalla.
- **Espejo de pantalla desde Android sin app emisora** — inviable. Miracast
  requiere soporte de *sink* del fabricante, que este firmware no trae.

---

## D2 — AirPlay como protocolo principal

**Decisión:** el receptor implementa AirPlay 2.

**Razón:** es el único protocolo de casting con un stack de recepción completo en
software libre. [UxPlay](https://github.com/FDH2/UxPlay) implementa RAOP,
handshake FairPlay, descifrado y sincronización A/V, todo con ingeniería inversa
ya madura y probada.

---

## D3 — Partir de `android-airplay-server` en vez de portar UxPlay desde cero

**Decisión:** el receptor se basa en
[jqssun/android-airplay-server](https://github.com/jqssun/android-airplay-server)
(GPL-3.0).

**Razón:** ese proyecto ya resolvió los tres problemas caros del port a Android:

1. **mDNS.** UxPlay usa la API `dns_sd.h` de Bonjour/Avahi, que Android no trae.
   El proyecto la sustituye con un shim (`android_dnssd_shim.c`) sobre
   `NsdManager`.
2. **OpenSSL.** El NDK no expone `libcrypto` (está bloqueado por namespace desde
   Android 7). Cross-compilarlo a mano en Windows es inviable. El proyecto usa
   el submódulo `viaduck/openssl-cmake`, que lo compila vía CMake.
3. **Render.** Pipeline MediaCodec + EGL sobre `SurfaceView`, con `AudioTrack` y
   fallback de ALAC por software.

Además ya trae `BootReceiver` para autoarranque y soporte de navegación para
Android TV, que es justo lo que necesita un proyector manejado con control remoto.

**Costo aceptado:** el binario distribuido queda bajo GPL-3.0.

---

## D4 — Compilar en CI, no en local

**Decisión:** el APK se compila en GitHub Actions sobre Ubuntu.

**Razón:** el build de FFmpeg (decodificador ALAC por software) se hace con
`configure` + `make`, que son scripts POSIX. No corren en Windows nativo y esta
máquina no tiene WSL instalado. Montar WSL solo para esto agrega una dependencia
pesada al flujo; CI en Ubuntu ya resuelve el problema y encaja con el pipeline de
release existente.

---

## D5 — Sin root, al menos en la primera etapa

**Decisión:** la app se instala por ADB como app de usuario normal.

**Razón:** decisión de producto de Omar. El root (app en `/system/priv-app`,
sobrevive a factory reset, privilegios persistentes) exige flashear firmware por
FEL con PhoenixUSBPro, con riesgo real de brickear el equipo.

**Implicaciones a mitigar sin root:**

- El autoarranque depende de `BOOT_COMPLETED`, que es menos confiable que una app
  de sistema.
- El servicio puede ser matado por presión de memoria. Con ~444 MB libres de 1 GB
  esto es un riesgo concreto, no teórico. Se mitiga con foreground service y
  exclusión de optimización de batería.
- Una restauración de fábrica borra la instalación.

---

## D6 — Validar en hardware antes de invertir en el fork de AirPlay

**Decisión:** para la parte de iOS, primero instalar el APK de release upstream
(v0.0.31, universal, incluye `armeabi-v7a`) y probar espejo real desde un
dispositivo Apple.

**Razón:** hay tres incógnitas que ninguna cantidad de análisis resuelve y que
determinan si el proyecto es viable: si el H713 expone un decodificador H.264 por
hardware a MediaCodec, si 444 MB libres alcanzan para sostener el espejo, y si
mDNS se propaga bien en la red. Si el hardware no da, conviene saberlo antes de
construir el fork customizado.

---

## D11 — "USB Debugging" es el interruptor de ADB por red en el H713

**Hallazgo:** el firmware de estos proyectores no expone un toggle separado de
depuración por red. El interruptor etiquetado **USB Debugging** es el que abre el
puerto 5555 por Wi-Fi, según lo documentado para el HY310 y L018 (mismo OEM
Hotack, mismo SoC).

**Advertencia:** en el H713 activar ese modo reconfigura el puerto USB y deja de
funcionar lo que se conecte físicamente (teclado, mouse, adaptadores).

**Respaldo si falla:** ADB solo se necesita para depurar cómodo. Para instalar
basta copiar el APK a una USB y abrirlo con el explorador de archivos.

---

## D12 — Espejo propio para Android, en vez de una app de terceros

**Decisión:** construir emisor y receptor propios.

**Razón:** decisión de producto de Omar. Las opciones gratuitas (AirScreen,
LetsView, ApowerMirror) imponen marca de agua, cortes por tiempo y SDKs de
publicidad que solicitan permisos que un receptor de pantalla no justifica, en un
aparato que vive permanentemente conectado a la red doméstica y a la pantalla.

Como instalamos ambos extremos, no dependemos de certificar nada con Google ni
Apple, y podemos autenticar el emparejamiento en serio.

---

## D13 — Protocolo propio sobre TCP

**Decisión:** H.264 sobre una conexión TCP, descubrimiento por mDNS.

**Razón:** para un emisor Android no existe alternativa estándar. Miracast exige
soporte de *sink* del fabricante, que este firmware no tiene, y Google Cast está
licenciado. Un protocolo propio es además más simple y confiable que emular uno
ajeno cuando controlamos los dos extremos.

TCP sobre UDP: en una LAN la latencia añadida es pequeña frente al pipeline de
codificación y decodificación, y perder un fragmento de una trama H.264 produce
artefactos mucho peores que esperar una retransmisión.

**Seguridad:** el receptor escucha en la red local, así que valida longitudes
antes de reservar memoria, rechaza tramas mayores al límite, verifica un número
mágico para descartar escáneres de puertos, y exige un PIN comparado en tiempo
constante con rotación tras varios intentos fallidos.

---

## D14 — El receptor es una sola Activity

**Decisión:** una única pantalla que alterna entre estado de espera y superficie
de video, en vez de dos Activities.

**Razón:** desde Android 10 un servicio en segundo plano no puede lanzar una
Activity. Un receptor con pantalla de espejo separada dependería de esa capacidad
justo cuando llega una conexión. Con una sola pantalla el problema desaparece: la
que ya está abierta es la que renderiza. Además consume menos memoria, lo que
importa con ~444 MB libres.

El `SurfaceView` permanece siempre montado, nunca `GONE`: solo posee una
superficie nativa mientras está adjunto y medido, y el decodificador la necesita
antes de que llegue la primera trama.

---

## D15 — El espejo no servirá para Netflix, Prime ni Disney+

**Hallazgo, no decisión.** Esas apps marcan su ventana con `FLAG_SECURE`, y tanto
Android como iOS entregan negro a cualquier captura de pantalla. Ninguna app de
espejo puede evitarlo; las que prometen lo contrario dependen de que el servicio
no aplique la protección.

**Camino para esos servicios:** instalarlos directamente en el proyector, que es
un Android 11. Ojo: sin certificación Widevine L1, Netflix suele limitarse a
definición estándar.

---

## D16 — Los APK se publican en la LAN, no se commitean

**Decisión:** `scripts/Publish-Lan.ps1` sube los dos APK firmados al nginx del
Debian (`/mnt/Z/lan-apk/public/smart-projector/`), igual que `gym-tracker` y
`meal-plan`. Git no guarda binarios.

**Razón:** un APK son ~6 MB por build y git los guarda para siempre; en unas
decenas de publicaciones el repo pesaría más que todo el código. Además un binario
commiteado no dice con qué llave se firmó ni de qué commit salió, mientras que el
`latest.json` publicado lleva commit, tamaño y SHA-256.

Reusar el servidor que ya existe también evita montar infraestructura nueva: basta
crear un subdirectorio, porque ese nginx sirve todo el árbol público.

**Compatibilidad:** el esquema de `latest.json` se mantiene idéntico al de las
otras apps de la casa (`schemaVersion` 1), así que los clientes son
intercambiables.

---

## D17 — La actualización la instala el navegador, no la app

**Decisión:** cuando hay un build nuevo, la app abre la URL del APK con
`ACTION_VIEW`. El navegador lo descarga y se lo pasa al instalador de Android.
Ninguna de las dos pide `REQUEST_INSTALL_PACKAGES`.

**Razón:** ese permiso convierte a la app en un instalador silencioso. El receptor
vive encendido en la red de la casa y consulta un manifiesto por HTTP plano; si
alguien lograra responder por el servidor, la diferencia entre "abre una descarga"
y "instala lo que le manden" es toda la diferencia. El costo es un toque extra del
usuario, que además ve lo que está instalando.

Defensas que sí lleva el cliente:

- El manifiesto solo puede nombrar un archivo suelto junto a sí mismo, resuelto
  contra la URL del propio manifiesto, así que no puede redirigir a otro host.
- Nada de redirecciones HTTP, cuerpo limitado a 16 KB y tiempos de espera cortos.
- HTTP plano permitido únicamente para el host del hub, vía
  `network_security_config.xml`.

**Lo que no hace:** verificar el SHA-256 del APK descargado, porque la descarga
ocurre fuera de la app. Quien firma verifica el hash al subir, y Android rechaza
cualquier APK que no venga firmado con la misma llave que el instalado. Esa
comprobación de firma es la que realmente protege la actualización.

**Adaptación al proyector:** no hay dónde tocar un banner con un control remoto,
así que el receptor pinta el aviso en la pantalla de espera y lo descarga con la
tecla **OK**. Si el firmware no trae navegador, muestra la URL para instalarlo a
mano en vez de ofrecer un botón muerto.

---

## D18 — Debug y release son aplicaciones distintas

**Decisión:** los builds de debug llevan el sufijo `.debug` en el `applicationId`
y traen la consulta de actualizaciones apagada.

**Razón:** Android rechaza una actualización firmada con otra llave. Sin el
sufijo, un APK de debug instalado durante el desarrollo bloquearía la instalación
del APK publicado, y la única salida sería desinstalar y perder el emparejamiento.
Separarlos deja que convivan, y apagar la consulta evita ofrecerle al usuario una
descarga que el sistema va a rechazar.
