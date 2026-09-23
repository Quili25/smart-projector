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
