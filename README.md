# OF-Android

Fork of [OpenFluxAndroid](https://github.com/p1neappleXpress/OpenFluxAndroid)
(the Android client for [OpenFlux](https://github.com/p1neappleXpress/OpenFlux))
with the **Mail.ru Docs** transport (`mailru`) added — upstream's Android
app only exposed `yandex`/`vyandex`/`max`, even though the OpenFlux core
already supports `cupsonline` and `mailru` too.

All upstream disclaimers in the OpenFlux README apply here as well: this is
a network-stack research tool, not something the author (or this fork)
endorses using to bypass any platform's rules, and use is entirely the
user's own responsibility.

## What changed vs upstream

- `TransportType.kt` / `AddTunFragment.kt` / `dropdown_transport_menu.xml` /
  `strings.xml` — added `mailru` as a selectable transport. It reuses the
  same "document URL" field as `yandex`/`vyandex` (the core's
  `mailru.NewMailruDocsTransport` takes the same `--url` flag), so no new
  UI fields were needed.
- The bundled `libp1npplydtransport.so` (the openflux core binary,
  packaged as a fake native library so Android's installer extracts it)
  was stale — built from an old `universal-bypass-tool`-era checkout that
  predates the `cupsonline`/`mailru` packages entirely (verified via
  `strings` against the committed binary). `.github/workflows/release.yml`
  rebuilds it fresh from upstream `OpenFlux` on every tagged release, so
  it always matches whatever transports upstream currently ships, instead
  of relying on a binary blob committed once and never updated.
- `app/build.gradle.kts`: `release` build type now reuses the debug
  signing config. This is sideloaded via [Obtainium](https://github.com/ImranR98/Obtainium),
  not distributed through the Play Store, so there's no real release
  identity to protect — Android still refuses a truly unsigned APK, so it
  needs *some* signature, and the auto-generated debug key is the
  simplest one that doesn't require managing a keystore secret.
- Only `arm64-v8a` gets a rebuilt core binary — same scope as upstream's
  own `build_android.sh`, which only ever targeted `arm64-v8a`.

## Cutting a release

Push a tag matching `v*` (e.g. `git tag v1.0.0-mailru && git push origin v1.0.0-mailru`,
or trigger `.github/workflows/release.yml` manually via `workflow_dispatch`).
The workflow checks out upstream `OpenFlux`, cross-compiles the core binary
with the Android NDK, verifies the resulting binary actually contains the
`mailru` transport, builds the APK, and attaches it to a GitHub Release.

## Installing via Obtainium

In Obtainium, "Add App" → paste this repo's URL
(`https://github.com/scp-oss/OF-Android`) → it will track this repo's
Releases page and offer the APK asset from the latest tagged release,
same as any other Obtainium-tracked app.
