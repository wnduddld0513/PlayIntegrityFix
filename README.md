# Play Integrity Fix — ROM build for PixelOS 17.0 (branch `seventeen`)

This branch is a fork of [KOWX712/PlayIntegrityFix](https://github.com/KOWX712/PlayIntegrityFix)
(`v4.9-inject-s`) for the PixelOS 17.0 (Android 17) build of the Lenovo Yoga Tab Plus (TB520FU).
PixelOS 17.0 has its own Play Integrity spoofing and a TrickyStore port in `frameworks/base`
(Settings > System > Developer options > Play Integrity spoofing). This branch does not replace
them: it adds **Specter**, which sets them up on a fresh install and keeps them up to date,
**without root, Magisk or Zygisk**. The module itself (`module/`, `zygisk/`, `webui/`) is kept as
upstream; the ROM parts are in [`rom/`](rom) and [`Android.bp`](Android.bp).

## What PixelOS does and what Specter adds

| | PixelOS (Developer options) | Specter |
| --- | --- | --- |
| Play Integrity profile | applies the profile, spoofing options, manual import, Fetch | applies a profile on the first boot, follows newer Pixel builds, replaces a Canary profile before it expires (about 7 days ahead), leaves a manually imported profile alone |
| Keybox | signs the key attestation with the keybox, manual import | installs a keybox when none is present, checks it against Google's revocation list and the catalog, replaces a revoked one, optional keybox address, Fetch keybox now |
| Target apps | default list, newly installed apps | keeps Google Play services and the Play Store in the list |
| Security patch | the patch level of the attestation | sets it from the profile, the device, the bulletin or a fixed value |
| After a change | restarts Google Play services | restarts Google Play services and the Play Store, optionally clears the Play Store |

Specter runs at boot (the first pass sets up a fresh install) and then every 12 hours by default.
It honors the two switches of the PixelOS page: with Play Integrity Fix off it leaves the profile,
with TrickyStore off it leaves the keybox, the targets and the patch.

## Settings

Settings > Advanced features > Experimental features > *Integrity*:

- **Specter**: this app's page (`io.github.wnduddld0513.pif/.SpecterActivity`): main switch,
  automatic updates and interval, Update now, the steps, the active profile and Fetch profile now,
  the preferred and blocked devices, the keybox state, Fetch keybox now, the keybox provider and
  address, Check now, the security patch source, Apply now, ADB and the ROM fingerprint.
- **Play Integrity Fix** and **TrickyStore**: the PixelOS pages of Developer options.

The page is translated into the locales of the AOSP Settings app.

## Modules

| Module | Installed as |
| --- | --- |
| `PlayIntegrityFixSettings` | `/system_ext/priv-app` app (`io.github.wnduddld0513.pif`), platform signed, system uid |
| `privapp-permissions-pif.xml` | privileged permissions of the app (`WRITE_SECURE_SETTINGS`, `FORCE_STOP_PACKAGES`, `CLEAR_APP_USER_DATA`) |

## Requirements on the ROM (PixelOS 17.0 / Android 17)

| Where | What |
| --- | --- |
| `frameworks/base` ([lenovo-sm8650, `seventeen`](https://github.com/lenovo-sm8650/android_frameworks_base/tree/seventeen)) | the PixelOS spoofing (`SpoofManager`, `PlayIntegritySpoofService`, `TrickyStoreService`, `PixelDeviceRepository`); `SpoofManager` honors the `spoof_pif_enabled` and `spoof_trickystore_enabled` switches |
| `packages/apps/Settings` | the PixelOS spoofing pages (`com.android.settings.development.spoof`) |
| `hardware/lenovo` ([`seventeen`](https://github.com/lenovo-sm8650/android_hardware_lenovo/tree/seventeen)) | `custom/custom.mk` adds `PlayIntegrityFixSettings` to `PRODUCT_PACKAGES`; the *Integrity* category of Experimental features |

## Building it into a ROM

1. Add the repository to the local manifest:

   ```xml
   <project name="wnduddld0513/PlayIntegrityFix" path="packages/apps/PlayIntegrityFix" remote="github" revision="seventeen" />
   ```

2. Add the app to the product packages (it pulls in the privapp permissions through `required`):

   ```makefile
   PRODUCT_PACKAGES += PlayIntegrityFixSettings
   ```

3. Build with the usual ROM build (`m` / `brunch`). The app is `platform_apis`, signed with the
   `platform` certificate, runs as `android.uid.system` and is installed to
   `/system_ext/priv-app`. Its page is exported with `WRITE_SECURE_SETTINGS` as permission, so only
   the system uid (Settings) can open it.

## Settings keys

| Key | Meaning |
| --- | --- |
| `Settings.Global specter` | Specter main switch, on unless 0 |
| `Settings.Global pif_device` | the device chosen for the profile |
| `Settings.System spoof_pif_enabled`, `spoof_trickystore_enabled` | the PixelOS switches, on unless 0 |
| `Settings.Secure spoof_pif_config`, `spoof_trickystore_keybox`, `spoof_trickystore_target`, `spoof_trickystore_patch` | the PixelOS spoofing state that Specter writes |
| `Settings.Secure spoof_trickystore_keybox_source` | `specter` for a keybox installed by Specter, `user` for one imported by hand |

## License

GPL-3.0, like upstream (see [`LICENSE`](LICENSE)). The origin of every file in
`rom/` is listed in [`rom/NOTICE.md`](rom/NOTICE.md). All credit for the
spoofing logic goes to the upstream authors listed below.

---

## Upstream module: Play Integrity Fix

This module tries to fix Play Integrity verdicts to get a valid attestation.

### NOTE

This module is not made to hide root, nor to avoid detections in other apps. It only serves to pass Device verdict in the Play Integrity tests and certify your device.

### Tutorial

You will need root and Zygisk. Enable Magisk's built-in Zygisk or use [ZygiskNext](https://github.com/Dr-TSNG/ZygiskNext) / [NeoZygisk](https://github.com/JingMatrix/NeoZygisk) / [ReZygisk](https://github.com/PerformanC/ReZygisk).

### Options

- **spoofBuild**: spoof fingerprint field, enabled by default.
- **spoofProvider**: custom keystore provider, enable when not using [TrickyStore](https://github.com/5ec1cff/TrickyStore) or [TEESimulator](https://github.com/JingMatrix/TEESimulator).
- **spoofProps**: spoof prop when gms read from system prop, enabled by default.
- **spoofSignature**: spoof rom signature, enable when your rom is signed by testkey. You can check your rom signature by running this command in terminal.
  ```sh
  unzip -l /system/etc/security/otacerts.zip | grep -oE "testkey|releasekey"
  ```
- **spoofVendingSdk**: spoof sdk version to 32 to Play Store if your device runs Android 13 or higher, this option will not take effect if your device is running Android 12 or lower.
  - Known issue: 
    - Back gesture/nav button from within the Play Store exits directly to homescreen for all
    - Blank account sign-in status and broken app updates for ROMs A14+
    - Incorrect app variants may be served for all
    - Full Play Store crashes for some setups
  - pixel_beta fingerprint can no longer get device verdict even on legacy check, so enabling this option won't help you to get device verdict anymore.

### Variety

- **inject-vending**: Based on the official inject branch, with the added spoofVendingSdk option.
- **inject-manual**: Based on inject-vending, with auto config (detect TrickyStore and ROM signature) removed.
- **inject-s**: Based on inject-manual, lightweight since it dropped the JSON format (pif.json -> pif.prop).

> [!NOTE]
> **inject-vending** and **inject-manual** branches are discontinued, but the branches will not be removed.

> [!WARNING]
> Do not use third-party tools to fetch fingerprints to avoid conflicts.
> The WebUI provides full control for configurable options.

#### About official PIF by chiteroman

- The [official PIF by chiteroman](https://github.com/chiteroman/PlayIntegrityFix) has been removed from GitHub.
  - Official untouched main branch: https://github.com/KOWX712/PlayIntegrityFix/tree/main
  - Official untouched inject branch: https://github.com/KOWX712/PlayIntegrityFix/tree/inject
  - All tags from the official PIF repo are also preserved in this repo.

### Acknowledgments

- [kdrag0n](https://github.com/kdrag0n/safetynet-fix) & [Displax](https://github.com/Displax/safetynet-fix) for the original idea.
- This project is forked from the official chiteroman's PIF repo.
- [osm0sis](https://github.com/osm0sis) for his original [autopif2.sh](https://github.com/osm0sis/PlayIntegrityFork/blob/main/module/autopif2.sh) script, and [backslashxx](https://github.com/backslashxx) & [KOWX712](https://github.com/KOWX712) for improving it ([action.sh](https://github.com/chiteroman/PlayIntegrityFix/blob/main/module/action.sh)).
