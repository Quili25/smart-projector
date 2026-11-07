# Versioning (`MAJOR.MINOR.PATCH`)

Canonical file: [`VERSION`](../VERSION). Tags: `vX.Y.Z`.

## Commit markers (merge to `main`)

```text
version: patch   # default
version: minor
version: major
skip release
```

## Pipeline

```text
staging  →  CI + LAN quality gates (Hermes)
main     →  CI → bump VERSION → tag → pack → GitHub Release
```

Do not reuse a published `X.Y.Z`. Promote to `main` only after LAN gates PASS.

## Qué versión llevan los APK

Las dos apps de SmartCast se versionan y publican **como un solo build**. Hablan el
mismo protocolo de cable, así que dejarlas desfasadas es justo lo que rompe el
espejo: el receptor rechazaría al emisor con `REJECTED_VERSION`.

### versionName

Sale de `VERSION`, no de una numeración propia. Gradle no decide bumps: eso le toca
al workflow de release, que es quien bumpea el archivo y pone el tag.

| Dónde está HEAD | versionName |
|---|---|
| sobre el tag `vX.Y.Z` que coincide con `VERSION` | `X.Y.Z` |
| cualquier commit posterior | `X.Y.(Z+1)-beta.N` |

`N` cuenta los commits desde el tag de release. Se usa el **siguiente patch** para
que el nombre ordene después del release sobre el que está construido; cuál sea el
bump real lo resuelve `scripts/detect-version-bump.ps1` al llegar a `main`.

`./gradlew.bat printVersion` imprime lo que produciría el commit actual.

### versionCode

Es `git rev-list --count HEAD`.

Android se niega a instalar una actualización con un `versionCode` menor o igual al
instalado, así que el número **tiene que crecer siempre**. El conteo de commits lo
garantiza mientras la historia de `staging` y `main` no se reescriba: los promotes
a `main` son fast-forward, nunca rebases.

Por eso `scripts/Publish-Lan.ps1` se niega a publicar un build cuyo `versionCode` no
supere al que ya está en la página LAN.

> Cualquier entorno que llegue a compilar los APK necesita la historia completa.
> Un clon superficial (`actions/checkout` sin `fetch-depth: 0`) reportaría 1 commit
> y produciría un `versionCode` de 1. Hoy solo se compila en esta máquina y en el
> hub, los dos con clones completos.

## Firma

Ambas apps se firman con la misma llave de release (`android/keystore.properties`,
fuera de git). Esa llave es la única forma de actualizar lo que ya está instalado:
si se pierde, la única salida es desinstalar y volver a empezar, perdiendo el
emparejamiento. Hay que respaldarla fuera de la máquina de desarrollo.

Los builds de debug usan un `applicationId` con sufijo `.debug` y no consultan
actualizaciones, precisamente porque su firma no coincide con la de release.
