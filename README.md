# smart-projector

Casting para el proyector **Magcubic HY300 Pro** (Allwinner H713, Android 11),
sin anuncios, sin marca de agua y sin límite de tiempo.

## Qué incluye

**SmartCast** — espejo de pantalla propio para Android, en dos apps:

| Módulo | Dónde vive | Qué hace |
|---|---|---|
| `android/receiver` | Proyector | Escucha, decodifica H.264 por hardware y pinta a pantalla completa; reproduce el audio |
| `android/sender` | Celular Android | Captura la pantalla (H.264) y el audio de las apps (AAC) y los transmite |
| `android/protocol` | Compartido | Formato de cable, handshake y emparejamiento |
| `android/updates` | Compartido | Consulta de actualizaciones contra la página LAN |

**AirPlay** — para iPhone, iPad y Mac no hace falta app emisora: iOS ya lo trae.
Se usa un receptor open source existente, ver [docs/decisions.md](docs/decisions.md).

## Cómo funciona SmartCast

El celular descubre el proyector por mDNS (`_smartcast._tcp`), se empareja con un
código de 6 dígitos que aparece en pantalla, y a partir de ahí transmite video
H.264 por una conexión TCP en tu red local.

La captura nunca pasa por el CPU: `MediaProjection` dibuja en una `Surface` que
alimenta directamente al codificador por hardware, y del otro lado `MediaCodec`
decodifica hacia la superficie de la pantalla.

**El emparejamiento por PIN no es decorativo.** Cualquiera conectado a tu Wi-Fi
puede alcanzar el puerto del receptor; sin autenticación, cualquier invitado
podría aventar imagen a tu pantalla. Es justo lo que las apps gratuitas no hacen.

## Limitaciones conocidas

**Netflix, Prime Video y Disney+ van a dar pantalla negra.** Esas apps marcan su
ventana como protegida por DRM y el sistema operativo entrega negro a cualquier
captura, en Android y en iOS. No es algo que ninguna app de espejo pueda evitar.
Para esos servicios, instálalos directamente en el proyector.

Todo lo demás se espeja bien: YouTube, fotos, navegador, juegos, presentaciones.

**Audio.** Se transmite lo que reproducen las apps (videos, música, juegos), no
el micrófono ni las llamadas. Requiere Android 10 o superior y el permiso de
audio que la app pide al compartir; sin él, o en Android 9 y anteriores, se
comparte solo la pantalla. Las apps que bloquean la captura (algunas con DRM)
aportan silencio. Mientras se comparte audio, el volumen multimedia del celular
queda en silencio para que no suene en dos lugares, y las teclas de volumen del
celular no lo reactivan: el volumen se ajusta con el control del proyector. Al
detener, el celular recupera el último volumen elegido. Las notificaciones y
llamadas siguen sonando en el celular.

Otras limitaciones actuales: solo un emisor a la vez.

## Distribución y actualizaciones

Las apps se descargan de la página LAN que sirve el Debian de la casa, el mismo
nginx que usan `gym-tracker` y `meal-plan`:

<http://192.168.0.165:8088/smart-projector/>

Los APK **no viven en git**: `scripts/Publish-Lan.ps1` compila las versiones de
release firmadas y las sube junto con un `latest.json` por app. Cada app instalada
consulta ese manifiesto y avisa cuando hay un build más nuevo. En el celular el
aviso es un banner que se toca; en el proyector es una línea en la pantalla de
espera y se descarga con **OK** del control.

Ninguna de las dos instala paquetes por su cuenta: el APK se le entrega al
navegador, que a su vez se lo entrega al instalador de Android. Por eso ninguna
pide `REQUEST_INSTALL_PACKAGES`, un permiso que convierte a la app en un vector de
instalación silenciosa si alguien logra responder por el servidor.

El manifiesto solo puede nombrar un archivo suelto junto a sí mismo, así que un
`latest.json` manipulado no puede mandar al dispositivo a otro host.

```powershell
# Una sola vez: crea la llave de firma (guárdala fuera de esta máquina)
./scripts/Publish-Lan.ps1 -InitKeystore

# Publicar el HEAD de staging, previo PASS de los gates
./scripts/Publish-Lan.ps1
```

## Requisitos

Para **usar** las apps no hace falta ADB: se instalan desde
<http://192.168.0.165:8088/smart-projector/>, ver [docs/device-setup.md](docs/device-setup.md).

Para **desarrollar** en el proyector:

- ADB habilitado — ver la misma guía
- Android SDK platform-tools (`adb`) y JDK 17
- PowerShell 5.1+

No se necesita Android Studio, WSL ni CI: el proyecto compila con el wrapper de
Gradle incluido.

## Uso

```powershell
# 1. Conectar al proyector (descubre la IP si no se especifica)
./scripts/Connect-Projector.ps1

# 2. Levantar el perfil real del hardware (ABI, decoders, RAM)
./scripts/Get-DeviceProfile.ps1

# 3. Compilar e instalar ambas apps
./scripts/Deploy-SmartCast.ps1 -ReceiverSerial 192.168.0.42:5555 -SenderSerial <serial-del-celular>
```

El paso 3 deja el proyector configurado para no apagar pantalla, no congelarse
por Doze y poder abrirse solo tras reiniciar.

Después: abre SmartCast en el celular, elige el proyector y escribe una sola vez
el código que aparece proyectado.

### Para iPhone

```powershell
./scripts/Deploy-AirPlay.ps1
```

Luego, en el iPhone: **Centro de Control → Duplicar pantalla**.

## Desarrollo

```powershell
cd android
./gradlew.bat test                    # protocol, updates y el versionado
./gradlew.bat assembleDebug           # ambos APKs
./gradlew.bat printVersion            # la versión que produciría este commit
```

El módulo `protocol` es una librería JVM pura, así que el formato de cable y la
lógica de emparejamiento se prueban sin emulador ni dispositivo.

Los builds de debug llevan el sufijo `.debug` en su `applicationId` y traen la
consulta de actualizaciones apagada: su firma es distinta a la de release, así que
el APK de la página LAN nunca podría instalarse encima. Pueden convivir con el
build publicado sin pisarlo.

## Ramas

`staging` → `main`. Todo entra por `staging`; `main` solo recibe código validado
en el hardware real. Ver [docs/VERSIONING.md](docs/VERSIONING.md).

## Licencia

Las apps SmartCast son código propio. El receptor AirPlay deriva de UxPlay y de
[android-airplay-server](https://github.com/jqssun/android-airplay-server), ambos
**GPL-3.0**; cualquier binario de AirPlay distribuido desde aquí hereda esa licencia.
