# smart-projector

Casting nativo para el proyector **Magcubic HY300 Pro** (Allwinner H713, Android 11).

Convierte el proyector en un receptor **AirPlay 2**: espejo de pantalla, video y
audio desde iPhone, iPad y Mac, sin apps intermediarias en el emisor y con
arranque automático al encender.

## Estado

En desarrollo. Ver [docs/decisions.md](docs/decisions.md) para el registro de
decisiones técnicas y [docs/device-setup.md](docs/device-setup.md) para preparar
el hardware.

## Por qué AirPlay y no Chromecast

"Chromecast built-in" es un programa de licenciamiento de Google: exige
certificación del dispositivo, Widevine L1 y un certificado firmado por Google.
No se puede añadir a hardware no certificado por ningún medio legítimo.

AirPlay, en cambio, tiene un stack de recepción completamente implementado en
software libre ([UxPlay](https://github.com/FDH2/UxPlay)), así que es la única
vía que da espejo de pantalla real y estable en este equipo.

Alternativas abiertas para el lado Google, si más adelante se quieren:

| Protocolo | Qué da | Qué no da |
|---|---|---|
| DIAL + YouTube Lounge API | Botón Cast de YouTube desde iOS/Android | Netflix, Disney+, espejo de pantalla |
| DLNA / UPnP | Reproducir archivos desde apps tipo VLC o LocalCast | Espejo de pantalla, apps de streaming |

## Requisitos

- Proyector con **ADB por red** habilitado — ver [docs/device-setup.md](docs/device-setup.md)
- Android SDK platform-tools (`adb`) en la PC
- PowerShell 5.1+

## Uso

```powershell
# 1. Conectar al proyector (descubre la IP si no se especifica)
./scripts/Connect-Projector.ps1

# 2. Levantar el perfil real del hardware (ABI, decoders, RAM)
./scripts/Get-DeviceProfile.ps1

# 3. Instalar y configurar el receptor AirPlay
./scripts/Deploy-AirPlay.ps1
```

El paso 3 descarga una release fijada de upstream, **verifica su SHA-256** antes
de instalar, y deja el proyector configurado para no apagar pantalla ni congelar
el servicio por Doze.

Después: en el iPhone o Mac, **Centro de Control → Duplicar pantalla**. El
proyector aparece como destino AirPlay.

## Ramas

`staging` → `main`. Todo el trabajo entra por `staging`; `main` solo recibe
código ya validado en el hardware real. Ver [docs/VERSIONING.md](docs/VERSIONING.md).

## Quality gates (LAN)

```powershell
./scripts/push-and-wait.ps1 -ProjectId smart-projector
```

## Licencia

El receptor AirPlay deriva de UxPlay y de
[android-airplay-server](https://github.com/jqssun/android-airplay-server), ambos
**GPL-3.0**. Cualquier binario distribuido desde este repositorio hereda esa
licencia.
