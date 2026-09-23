# smart-projector

Casting para el proyector **Magcubic HY300 Pro** (Allwinner H713, Android 11),
sin anuncios, sin marca de agua y sin límite de tiempo.

## Qué incluye

**SmartCast** — espejo de pantalla propio para Android, en dos apps:

| Módulo | Dónde vive | Qué hace |
|---|---|---|
| `android/receiver` | Proyector | Escucha, decodifica H.264 por hardware y pinta a pantalla completa |
| `android/sender` | Celular Android | Captura la pantalla, codifica H.264 y la transmite |
| `android/protocol` | Compartido | Formato de cable, handshake y emparejamiento |

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

Otras limitaciones actuales: solo un emisor a la vez, y el audio todavía no se
transmite (el protocolo ya lo contempla).

## Requisitos

- ADB habilitado en el proyector — ver [docs/device-setup.md](docs/device-setup.md)
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
./gradlew.bat :protocol:test          # tests del protocolo
./gradlew.bat assembleDebug           # ambos APKs
```

El módulo `protocol` es una librería JVM pura, así que el formato de cable y la
lógica de emparejamiento se prueban sin emulador ni dispositivo.

## Ramas

`staging` → `main`. Todo entra por `staging`; `main` solo recibe código validado
en el hardware real. Ver [docs/VERSIONING.md](docs/VERSIONING.md).

## Licencia

Las apps SmartCast son código propio. El receptor AirPlay deriva de UxPlay y de
[android-airplay-server](https://github.com/jqssun/android-airplay-server), ambos
**GPL-3.0**; cualquier binario de AirPlay distribuido desde aquí hereda esa licencia.
