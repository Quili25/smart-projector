# Versionado

Las dos apps de SmartCast se versionan y publican **como un solo build**. Hablan el
mismo protocolo de cable, así que dejarlas desfasadas es justo lo que rompe el
espejo: el receptor rechazaría al emisor con `REJECTED_VERSION`.

## versionName

Sale del último tag de release alcanzable desde `HEAD` más los commits hechos
después, siguiendo [Conventional Commits](https://www.conventionalcommits.org/):

| Commits desde el tag | Resultado |
|---|---|
| ninguno (HEAD está tageado) | `1.2.0` |
| alguno con `!` o `BREAKING CHANGE:` | `2.0.0-beta.N` |
| alguno con `feat:` | `1.3.0-beta.N` |
| cualquier otra cosa | `1.2.1-beta.N` |

`N` es el número de commits desde el tag. Un repo sin tags arranca en `0.0.0`.

La lógica vive en `android/buildSrc` y está cubierta por tests; `./gradlew.bat
printVersion` imprime lo que produciría el commit actual.

## versionCode

Es `git rev-list --count HEAD`.

Android se niega a instalar una actualización con un `versionCode` menor o igual al
instalado, así que el número **tiene que crecer siempre**. El conteo de commits lo
garantiza mientras la historia de `staging` y `main` no se reescriba: los promotes
a `main` son fast-forward, nunca rebases.

Por eso `scripts/Publish-Lan.ps1` se niega a publicar un build cuyo `versionCode` no
supere al que ya está en la página LAN.

## Tags de release

Un release se marca con un tag `vMAJOR.MINOR.PATCH` sobre el commit promovido a
`main`. A partir de ahí los commits siguientes vuelven a ser `-beta.N` del
siguiente número.

## Ramas

`staging` → `main`. Todo entra por `staging` y pasa por los gates del Debian; `main`
solo recibe código ya validado en el hardware real.

## Firma

Ambas apps se firman con la misma llave de release (`android/keystore.properties`,
fuera de git). Esa llave es la única forma de actualizar lo que ya está instalado:
si se pierde, la única salida es desinstalar y volver a empezar, perdiendo el
emparejamiento. Hay que respaldarla fuera de la máquina de desarrollo.

Los builds de debug usan un `applicationId` con sufijo `.debug` y no consultan
actualizaciones, precisamente porque su firma no coincide con la de release.
