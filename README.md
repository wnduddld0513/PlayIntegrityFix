# Play Integrity Fix — ROM build (branch `seventeen`)

This branch is a fork of [KOWX712/PlayIntegrityFix](https://github.com/KOWX712/PlayIntegrityFix)
(`v4.9-inject-s`) that runs the module inside the ROM, **without root, Magisk or Zygisk**.
It is used by the PixelOS build for the Lenovo Yoga Tab Plus (TB520FU, `lapis`).
The module itself (`module/`, `zygisk/`, `webui/`) is kept as upstream; the ROM parts
are in [`rom/`](rom) and [`Android.bp`](Android.bp).

## How it works

- **Loading.** The ROM framework (`PropImitationHooks.loadPifModule()` in
  `frameworks/base`, Apache-2.0) loads `/system_ext/framework/PlayIntegrityFixEntry.jar`
  into `com.google.android.gms.unstable` (DroidGuard) and `com.android.vending`
  and calls `RomEntry.init()`, in place of the Zygisk entry. The ROM's built-in
  Play Integrity spoofing is used instead whenever the switch is off, the jar is
  missing or no device profile has been fetched yet.
- **Spoofing.** The same as the module: build fields (`spoofBuild`,
  `spoofVendingBuild`, `spoofVendingSdk`), system properties through the module's
  property virtualizer (`spoofProps`, `libpif_propvirt`), the keystore provider
  (`spoofProvider`, skipped while the ROM has its own keybox) and the signature
  (`spoofSignature`).
- **Device profile.** None is shipped. After the first boot the app downloads
  `module/pif.prop` from the upstream `inject_s` branch as soon as a network is
  available (retrying with backoff), keeps the chosen options, stores the result
  and restarts Play services and the Play Store.
- **Settings.** Settings > Advanced features > Experimental features > Play
  Integrity Fix: a main switch (on by default, `Settings.Global lenovo_pif_module`),
  the option switches, the active profile, Fetch, View/Edit `pif.prop` and Reset.
  Every change restarts Play services and the Play Store. The page is translated
  into the locales of the AOSP Settings app.

Default options (`rom/etc/pif.prop`): `spoofBuild`, `spoofProps`, `spoofProvider`
and `spoofVendingBuild` on; `spoofSignature`, `spoofVendingSdk` and `DEBUG` off.

Not included, because they need root: the module scripts that reset boot and
lock state properties (`post-fs-data.sh`, `service.sh`), the Zygisk denylist
handling and the TrickyStore / TEESimulator integration.

## Modules

| Module | Installed as |
| --- | --- |
| `PlayIntegrityFixSettings` | `/system_ext/priv-app` settings app (`io.github.wnduddld0513.pif`), platform signed; pulls in the modules below |
| `PlayIntegrityFixEntry` | `/system_ext/framework/PlayIntegrityFixEntry.jar` |
| `libpif_propvirt` | native property virtualizer (`pif_config.cpp`, `property_virtualizer.cpp`, unmodified) |
| `pif.prop.rom` | `/system_ext/etc/PlayIntegrityFix/pif.prop` (default options) |
| `privapp-permissions-pif.xml` | privileged permissions of the app |

## Building it into a ROM

Add the repository to the local manifest and the app to the product packages:

```xml
<project name="wnduddld0513/PlayIntegrityFix" path="packages/apps/PlayIntegrityFix" remote="github" revision="seventeen" />
```

```makefile
PRODUCT_PACKAGES += PlayIntegrityFixSettings
```

The framework hook comes from the ROM (`frameworks/base` of
[lenovo-sm8650](https://github.com/lenovo-sm8650/android_frameworks_base/tree/seventeen)).
Without it the package is installed but does nothing.

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
- **spoofProps**: spoof prop when gms read from system prop, enable when not using [TrickyStore](https://github.com/5ec1cff/TrickyStore) or [TEESimulator](https://github.com/JingMatrix/TEESimulator).
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
