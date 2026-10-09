# DeckStarter

A one-stop Android app for getting [DroidDeck](https://github.com/Droid-Deck/DroidDeck) running on a handheld or phone.

DeckStarter is an **unofficial** helper. It isn't affiliated with the DroidDeck project or Valve, and it
doesn't contain DroidDeck, Steam or Proton. It always installs DroidDeck from the official project's
GitHub releases, so you get the genuine build.

## What it does

1. **Checks your device** against DroidDeck's README requirements: Android 9+, a 64-bit ARM CPU, an
   Adreno 730+ or 8xx GPU (read live from the GPU driver; 6xx is flagged as experimental; 710, Mali,
   Xclipse and PowerVR as unsupported), and about 4.1 GB free for the runtime plus room for games.
2. **Installs DroidDeck and keeps it updated.** It finds the newest release of
   `Droid-Deck/DroidDeck`, picks the build you chose, downloads it and verifies it twice before
   handing it to the Android installer:
   - the SHA-256 checksum GitHub publishes for the file, and
   - that the APK is signed with DroidDeck's release key (`keystore/release-signer.sha256` in their
     repo). An APK signed with any other key is refused.

   When a newer release comes out, the same button updates it.
3. **Finishes setup.** Walks through turning off **Restrict child processes** in Developer options
   (required before Steam launches), then shortcuts to open DroidDeck, make it the home screen,
   exempt it from battery saving, manage its permissions and storage, the project page, and the
   official DroidDeck Discord for help.

DroidDeck itself installs its Linux runtime and downloads Steam on first launch.

### DroidDeck builds

Each DroidDeck release ships the same app under four package names, because some phones only give
their performance modes to apps with certain names. DeckStarter installs the **Standard** build
(`com.droiddeck.launcher`) unless you pick another in Settings: PUBG (`com.tencent.ig`), AnTuTu
(`com.antutu.benchmark.full`) or Ludashi (`com.ludashi.benchmark`). Each installs as a separate app
with its own data.

## Getting the APK

Every push builds `DeckStarter.apk` with GitHub Actions (**Actions → Build APK → artifact
`DeckStarter-apk`**). Pushing a tag such as `v0.1.0` also attaches the APK to a GitHub release.

Install it on the device, open it, and follow steps 1–3 on screen. Android will ask once for
permission to let DeckStarter install apps.

## DroidDeck source

DeckStarter is built with `Droid-Deck/DroidDeck` as its source. The signature check only applies to
that repository. If the project ever moves, you can change the source in any of these places, listed
from highest priority:

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
