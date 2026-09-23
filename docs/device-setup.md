# Preparar el proyector (Magcubic HY300 Pro / Allwinner H713)

Guía para habilitar ADB por red en el proyector. Es el requisito previo para
instalar y depurar el receptor AirPlay.

Hardware objetivo:

| | |
|---|---|
| Modelo | HY300Pro |
| SoC | Allwinner H713 (4x Cortex-A53, Mali-G31 MP2) |
| Android | 11 (API 30) |
| RAM / Almacenamiento | 1 GB / 8 GB |
| Panel nativo | 1280 x 720 |

> El kernel de estos equipos suele compilarse en 32 bits aunque el Cortex-A53 sea
> capaz de 64 bits. No asumas el ABI: confírmalo con `Get-DeviceProfile.ps1`.

---

## 1. Desbloquear opciones de desarrollador

El launcher de fábrica oculta la app de Ajustes real de Android, así que hay dos
caminos según lo que te deje hacer tu firmware.

### Camino A: desde la pantalla "About device"

1. Entra a **Settings → About device** (la pantalla que muestra Product Model,
   Android Version, Serial Number).
2. Pulsa repetidamente sobre **Product Model** (el renglón que dice `HY300Pro`).
   En algunos firmwares el renglón sensible es **Version** o **Build number**.
   Son **7 pulsaciones**; a partir de la tercera suele aparecer un aviso de
   "faltan N pasos".
3. Debe aparecer el toast **"You are now a developer"**.
4. Vuelve atrás y entra a **Settings → System → Developer options**.

### Camino B: si el launcher no expone los Ajustes reales

Firmware muy recortado no trae acceso a la app de Ajustes de Android. En ese caso:

1. Instala **Projectivy Launcher** desde la tienda que traiga el proyector
   (normalmente Aptoide).
2. Dentro de Projectivy, baja hasta **Settings**; la segunda entrada abre los
   **Ajustes nativos de Android**.
3. Desde ahí sigue el Camino A.

---

## 2. Habilitar depuración

Dentro de **Developer options** activa:

- **USB Debugging**. En este firmware **ese interruptor es el que abre ADB por
  red** en el puerto 5555; no existe un toggle separado de depuración
  inalámbrica. Si tu versión sí muestra *ADB Debugging* o *Network ADB* por
  separado, activa ese.
- **Stay awake** / *Pantalla activa mientras se carga*, para que no se apague
  durante las pruebas.

> **Efecto secundario a tener presente.** En el H713 activar la depuración
> reconfigura el puerto USB, así que lo que conectes físicamente (teclado, mouse,
> adaptadores) puede dejar de funcionar mientras esté activa. Por eso trabajamos
> por red y no por cable.

Algunos firmwares no levantan el demonio hasta el siguiente arranque. Si no
aparece, reinicia el proyector antes de darlo por perdido.

### Si no logras habilitar ADB

No es un bloqueo para usar SmartCast, solo para depurar cómodamente. Puedes
instalar los APK sin ADB:

1. Compila con `cd android; ./gradlew.bat assembleDebug`.
2. Copia `android/receiver/build/outputs/apk/debug/receiver-debug.apk` a una
   memoria USB.
3. Conéctala al proyector y ábrela con su explorador de archivos, aceptando la
   instalación de orígenes desconocidos.

Lo que pierdes por esa vía es la configuración automática (exención de Doze,
pantalla siempre encendida, autoarranque), que tendrías que ajustar a mano en los
Ajustes de Android.

---

## 3. Obtener la IP

En el proyector: **Settings → Network / Wi-Fi → (tu red) → Avanzado**. Anota la
dirección IPv4, algo tipo `192.168.0.x`.

Si no la encuentras en la interfaz, el script la descubre solo.

---

## 4. Conectar desde la PC

Con el proyector encendido y en la **misma red** que tu PC:

```powershell
# Con IP conocida
./scripts/Connect-Projector.ps1 -Ip 192.168.0.42

# O dejando que barra la red
./scripts/Connect-Projector.ps1
```

El script verifica que el dispositivo conectado sea realmente el proyector antes
de reportar éxito, para no confundirlo con un celular que tengas en depuración.

Luego levanta el perfil del hardware:

```powershell
./scripts/Get-DeviceProfile.ps1
```

Eso escribe `docs/hardware-profile.md` con el ABI real, los decodificadores de
video disponibles y la RAM libre. Son los datos que definen la configuración del
receptor.

---

## Problemas comunes

**El script no encuentra nada en el puerto 5555**

- Confirma que ambos equipos estén en la misma SSID. Las redes de invitados y el
  aislamiento de clientes (*AP isolation*) bloquean el tráfico entre dispositivos.
- Reinicia el proyector después de activar ADB Debugging: varios firmwares no
  levantan el demonio hasta el siguiente arranque.

**"Product Model" no responde a las pulsaciones**

Tu firmware no expone el interruptor de desarrollador en esa pantalla. Usa el
Camino B con Projectivy Launcher.

**`adb connect` dice `failed to authenticate`**

El diálogo de autorización RSA se está mostrando en la pantalla del proyector.
Acéptalo con el control remoto y marca *Permitir siempre*.

**El proyector aparece como `offline`**

```powershell
adb disconnect
adb kill-server
./scripts/Connect-Projector.ps1 -Ip <ip>
```
