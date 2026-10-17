/*
 * Play Integrity Fix (inject-s) - ROM integration
 *
 * Copyright (C) 2026 wnduddld0513
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Replaces the module's config handling in webui/src/lib/PifConfig.ts and the
 * fetch of webui/src/lib/Update.ts (KOWX712/PlayIntegrityFix, tag v4.9-inject-s,
 * Copyright KOWX712): pif.prop is kept in Settings.Global instead of
 * /data/adb/pif.prop, and "Fetch" downloads module/pif.prop of the module's
 * repository, as the WebUI does, keeping the options chosen by the user.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License version 3 as published by the Free
 * Software Foundation. It is distributed WITHOUT ANY WARRANTY; see the GNU
 * General Public License (LICENSE) for details.
 */
package io.github.wnduddld0513.pif;

import android.app.ActivityManager;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.provider.Settings;

import es.chiteroman.playintegrityfix.PifConfig;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** State of the Play Integrity Fix page, kept in Settings.Global. */
final class PifStore {
    /** Read by PropImitationHooks: unset or 1 loads the module into GMS (DroidGuard) and the Play Store; only 0 turns it off. */
    static final String SWITCH_SETTING = "lenovo_pif_module";

    private static final int FETCH_JOB_ID = 1;

    // module/pif.prop of the module's repository (webui/src/constant.ts).
    private static final String FETCH_URL =
            "https://raw.githubusercontent.com/KOWX712/PlayIntegrityFix/inject_s/module/pif.prop";
    // The options stay as the user set them; everything else comes from the fetched file.
    private static final String[] OPTIONS = {
        PifConfig.SPOOF_BUILD, PifConfig.SPOOF_PROPS, PifConfig.SPOOF_VENDING_BUILD,
        PifConfig.SPOOF_PROVIDER, PifConfig.SPOOF_SIGNATURE, PifConfig.SPOOF_VENDING_SDK,
        "DEBUG",
    };

    private static final String[] TARGET_PACKAGES = {
        "com.google.android.gms", "com.android.vending",
    };

    private PifStore() {}

    static boolean isEnabled(Context context) {
        return Settings.Global.getInt(context.getContentResolver(), SWITCH_SETTING, 1) != 0;
    }

    static void setEnabled(Context context, boolean enabled) {
        Settings.Global.putInt(context.getContentResolver(), SWITCH_SETTING, enabled ? 1 : 0);
        stopTargets(context);
        scheduleFetchIfNeeded(context);
    }

    /** Whether a profile (a fingerprint) is stored or set up, so the module has something to apply. */
    static boolean hasProfile(Context context) {
        return PifConfig.isValid(text(context));
    }

    /** First run: with the switch on and no profile, fetch it as soon as the network is up. */
    static void scheduleFetchIfNeeded(Context context) {
        if (!isEnabled(context) || hasProfile(context)) {
            return;
        }
        final JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        scheduler.schedule(new JobInfo.Builder(FETCH_JOB_ID,
                new ComponentName(context, PifFetchService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setBackoffCriteria(30000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .build());
    }

    /** The pif.prop in effect. */
    static String text(Context context) {
        return PifConfig.readConfigText(context);
    }

    static boolean hasCustom(Context context) {
        final String custom = Settings.Global.getString(context.getContentResolver(),
                PifConfig.CONFIG_SETTING);
        return custom != null && !custom.isBlank();
    }

    /** The profile field of the pif.prop in effect, or the "not fetched yet" text. */
    static String profileValue(Context context, String value) {
        return hasProfile(context) && value != null ? value
                : context.getString(R.string.pif_not_fetched);
    }

    static void setOption(Context context, String key, boolean value) {
        write(context, PifConfig.withValue(text(context), key, Boolean.toString(value)));
    }

    static void write(Context context, String text) {
        Settings.Global.putString(context.getContentResolver(), PifConfig.CONFIG_SETTING, text);
        stopTargets(context);
    }

    /** Drops the stored profile and options, restoring the shipped Pixel 9 defaults. */
    static void reset(Context context) {
        Settings.Global.putString(context.getContentResolver(), PifConfig.CONFIG_SETTING, "");
        stopTargets(context);
        scheduleFetchIfNeeded(context);
    }

    /**
     * Downloads the module's pif.prop and applies it to the current one, keeping the
     * user's options. Blocking; call it from a worker thread.
     */
    static void fetch(Context context) throws IOException {
        final HttpURLConnection connection = (HttpURLConnection) new URL(FETCH_URL).openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(10000);
        final String fetched;
        try (InputStream in = connection.getInputStream()) {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
            fetched = out.toString(StandardCharsets.UTF_8);
        } finally {
            connection.disconnect();
        }
        if (!PifConfig.isValid(fetched)) {
            throw new IOException("Invalid pif.prop");
        }

        String merged = text(context);
        for (Map.Entry<String, String> entry : PifConfig.parseRaw(fetched).entrySet()) {
            if (!isOption(entry.getKey())) {
                merged = PifConfig.withValue(merged, entry.getKey(), entry.getValue());
            }
        }
        write(context, merged);
    }

    private static boolean isOption(String key) {
        for (String option : OPTIONS) {
            if (option.equals(key)) return true;
        }
        return false;
    }

    /** Google Play services and the Play Store read the new state when they start. */
    private static void stopTargets(Context context) {
        final ActivityManager am = context.getSystemService(ActivityManager.class);
        for (String pkg : TARGET_PACKAGES) {
            am.forceStopPackage(pkg);
        }
    }
}
