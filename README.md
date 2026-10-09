# DeckStarter

A one-stop Android app for getting [DroidDeck](#droiddeck-source) running on a handheld or phone.

DeckStarter is an **unofficial** helper. It isn't affiliated with the DroidDeck project or Valve, and it
doesn't contain DroidDeck, Steam or Proton. It always installs DroidDeck from the official project's
GitHub releases, so you get the genuine build.

## What it does

1. **Checks your device** against DroidDeck's requirements: Android 9+, a 64-bit ARM CPU, a Qualcomm
   Adreno 730 or newer GPU (read live from the GPU driver), Vulkan 1.1, RAM and free storage.
2. **Installs DroidDeck and keeps it updated.** It finds the newest release on GitHub, picks the right
   APK, downloads it, verifies its SHA-256 checksum against the one GitHub publishes, and hands it to
   the Android installer. When a newer release comes out, the same button updates it.
3. **Finishes setup.** Shortcuts to open DroidDeck, make it the home screen, exempt it from battery
   saving, manage its permissions and storage, and visit the official project page.

DroidDeck itself downloads Steam, Proton and GPU drivers on its first launch.

## Getting the APK

Every push builds `DeckStarter.apk` with GitHub Actions (**Actions → Build APK → artifact
`DeckStarter-apk`**). Pushing a tag such as `v0.1.0` also attaches the APK to a GitHub release.

Install it on the device, open it, and follow steps 1–3 on screen. Android will ask once for
permission to let DeckStarter install apps.

## DroidDeck source

DeckStarter needs the official DroidDeck repository name (`owner/name`). You can set it in any of these
places, listed from highest priority:

- in the app: **Settings → Official DroidDeck GitHub repository**
- at build time: repository variable `DROIDDECK_REPO` (**Settings → Secrets and variables → Actions →
  Variables**)
- in `gradle.properties`: `droiddeckRepo=owner/name`

DeckStarter only downloads from `github.com` release assets of that repository.

## Signing (for updates of DeckStarter itself)

Without a signing key, CI signs with a throwaway debug key. Each build then has a different signature,
so to update DeckStarter you would have to uninstall the previous build first. To keep one key, create
a keystore once:

```sh
keytool -genkeypair -v -keystore release.jks -alias deckstarter -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 release.jks   # copy the output
```

Then add these repository secrets: `DECKSTARTER_KEYSTORE_BASE64`, `DECKSTARTER_KEYSTORE_PASSWORD`,
`DECKSTARTER_KEY_ALIAS` and `DECKSTARTER_KEY_PASSWORD`. Never commit the keystore.

## Building locally

You need JDK 17 and the Android SDK (platform 35):

```sh
./gradlew assembleRelease -PdroiddeckRepo=owner/name
```

The app is plain Java on the Android framework, with no third-party libraries.
