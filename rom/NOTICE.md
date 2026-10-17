# ROM integration (branch `seventeen`)

Fork of [KOWX712/PlayIntegrityFix](https://github.com/KOWX712/PlayIntegrityFix) (tag
`v4.9-inject-s`), licensed GPL-3.0 (see `LICENSE`). The module needs root and Zygisk; this
directory runs the same logic inside the ROM instead.

| File | Source |
| --- | --- |
| `rom/entry/.../CustomKeyStoreSpi.java`, `CustomProvider.java`, `CustomPackageInfoCreator.java` | `zygisk/src/main/java/...` (chiteroman, KOWX712), header only |
| `rom/entry/.../EntryPoint.java` | `zygisk/src/main/java/.../EntryPoint.java`, adapted (no HiddenApiBypass, map instead of JSON, Unsafe fallback) |
| `rom/entry/.../PifConfig.java` | Java port of `pif_config.cpp` and the pif.prop handling of `webui/src/lib/PROP.ts`, `PifConfig.ts` |
| `rom/entry/.../RomEntry.java` | replaces the routing of `zygisk.cpp` (preAppSpecialize/postAppSpecialize, `doSpoofVending`) |
| `zygisk/src/main/cpp/pif_config.cpp`, `property_virtualizer.cpp` | built unmodified into `libpif_propvirt`; `rom/native/pif_jni.cpp` is the JNI glue |
| `rom/etc/pif.prop` | the option keys of `module/pif.prop` (no device profile; it is fetched on first run) |
| `rom/app/**` | settings page after `webui/src/data/spoofConfig.ts`, `App.tsx`, `lib/Update.ts`; strings after `webui/public/locales/strings` (translations of ar, id, pl, tr, zh-CN, zh-TW reused) |

Intentionally not included (they depend on root-only tooling): the module scripts
(`post-fs-data.sh`, `service.sh`: resetprop of verified boot / lock state props),
the Zygisk denylist handling, the TrickyStore / TEESimulator integration
(`autopif.sh`, `security_patch.sh`). The ROM-side hook is the Apache-2.0 code in
frameworks/base `PropImitationHooks.loadPifModule()`; it only loads this jar.
