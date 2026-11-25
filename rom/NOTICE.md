# ROM integration (branch `seventeen`)

Fork of [KOWX712/PlayIntegrityFix](https://github.com/KOWX712/PlayIntegrityFix) (tag
`v4.9-inject-s`), licensed GPL-3.0 (see `LICENSE`). The module needs root and Zygisk. On
PixelOS 17.0 the spoofing itself is done by the ROM (the Play Integrity spoofing and the
TrickyStore port of `frameworks/base`, Settings > System > Developer options); this
directory only carries the Specter page that sets it up and keeps it up to date.

| File | Source |
| --- | --- |
| `rom/app/src/.../SpecterSync.java`, `KeyboxStore.java`, `PatchDialog.java` | Java port of the update pass of [Specter](https://github.com/wnduddld0513/specter/tree/seventeen) (keybox catalog, revocation check, security patch sources, GMS restart), writing to the PixelOS settings instead of the TrickyStore files |
| `rom/app/src/.../PifStore.java` | the Play Integrity profile, picked from the Pixel builds of `PixelDeviceRepository` (PixelOS) in the format of the PixelOS settings page |
| `rom/app/src/.../Spoof.java` | access to the `Settings.Secure` keys of the PixelOS spoofing and its two pages |
| `rom/app/src/.../SpecterFragment.java`, `res/xml/specter_settings.xml` | the page; options after Specter's WebUI |
| `rom/app/res/values*/strings.xml` | strings after Specter and `webui/public/locales/strings` |

Not included: the module itself (`module/`, `zygisk/`, `webui/`, kept as upstream), its
scripts that need root (`post-fs-data.sh`, `service.sh`) and the Zygisk denylist handling.
The earlier ROM entry jar, property virtualizer and default `pif.prop` were dropped when
PixelOS brought its own spoofing.
