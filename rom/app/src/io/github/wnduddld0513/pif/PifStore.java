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
 * repository, as the WebUI does, keeping the options chosen by the user. The
 * automatic update replaces the module's autopif_ota.sh: it downloads the same
 * file, records the first download as the baseline without replacing the profile
 * shipped with the ROM, and applies a later change upstream.
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
import android.util.Log;

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

    /** Unset or 1 downloads and applies the profile whenever it changes upstream. */
    static final String AUTO_UPDATE_SETTING = "lenovo_pif_auto_update";
    // The pif.prop last downloaded, to apply an update only once it changed upstream.
    private static final String FETCHED_SETTING = "lenovo_pif_fetched";

    // At boot, when turned on, and periodically: upstream regenerates the file once a day.
    private static final int UPDATE_JOB_ID = 1;
    private static final int PERIODIC_UPDATE_JOB_ID = 2;

    // device_prop/<product>.prop of the bot branch of the module's repository, as Specter
    // reads it; the mirrors are tried in this order. %s is the product.
    private static final String[] FETCH_URLS = {
        "https://fastly.jsdelivr.net/gh/KOWX712/PlayIntegrityFix@bot/device_prop/%s.prop",
        "https://raw.githubusercontent.com/KOWX712/PlayIntegrityFix/bot/device_prop/%s.prop",
        "https://gh.sevencdn.com/https://raw.githubusercontent.com/KOWX712/PlayIntegrityFix/bot/device_prop/%s.prop",
    };
    private static final String DEFAULT_PRODUCT = "tokay_beta";
    // device_list.json of the same branch: the Canary devices Specter offers
    private static final String[] LIST_URLS = {
        "https://fastly.jsdelivr.net/gh/KOWX712/PlayIntegrityFix@bot/device_list.json",
        "https://raw.githubusercontent.com/KOWX712/PlayIntegrityFix/bot/device_list.json",
        "https://gh.sevencdn.com/https://raw.githubusercontent.com/KOWX712/PlayIntegrityFix/bot/device_list.json",
    };
    /** The product chosen on the Specter page (e.g. tokay_beta); unset or empty follows the profile in effect. */
    static final String DEVICE_SETTING = "lenovo_pif_device";
    private static final String TAG = "PifStore";
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
        scheduleUpdates(context);
    }

    static boolean isAutoUpdate(Context context) {
        return Settings.Global.getInt(context.getContentResolver(), AUTO_UPDATE_SETTING, 1) != 0;
    }

    static void setAutoUpdate(Context context, boolean enabled) {
        Settings.Global.putInt(context.getContentResolver(), AUTO_UPDATE_SETTING, enabled ? 1 : 0);
        scheduleUpdates(context);
    }

    /** Whether a profile (a fingerprint) is stored or set up, so the module has something to apply. */
    static boolean hasProfile(Context context) {
        return PifConfig.isValid(text(context));
    }

    /**
     * With the switch and the automatic update on, checks for an update once online and
     * then periodically; otherwise cancels the checks.
     */
    static void scheduleUpdates(Context context) {
        final JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (!isEnabled(context) || !isAutoUpdate(context)) {
            scheduler.cancel(UPDATE_JOB_ID);
            scheduler.cancel(PERIODIC_UPDATE_JOB_ID);
            return;
        }
        final ComponentName service = new ComponentName(context, PifFetchService.class);
        scheduler.schedule(new JobInfo.Builder(UPDATE_JOB_ID, service)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setBackoffCriteria(30000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .build());
        scheduler.schedule(new JobInfo.Builder(PERIODIC_UPDATE_JOB_ID, service)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(SpecterSync.intervalMs(context))
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
        Settings.Global.putInt(context.getContentResolver(), AUTO_UPDATE_SETTING, 1);
        Settings.Global.putString(context.getContentResolver(), FETCHED_SETTING, "");
        Settings.Global.putString(context.getContentResolver(), DEVICE_SETTING, "");
        SpecterSync.resetDefaults(context);
        stopTargets(context);
        // The scheduled job runs the first update again as soon as the device is online
        scheduleUpdates(context);
    }

    /**
     * Downloads the module's pif.prop and applies it to the current one, keeping the
     * user's options. Blocking; call it from a worker thread.
     */
    static void fetch(Context context) throws IOException {
        apply(context, download(context));
    }

    /**
     * The automatic update: the first check only records the downloaded pif.prop as the
     * baseline, so the profile shipped with the ROM stays; afterwards applies the module's
     * pif.prop when it changed upstream since the last download, so a profile edited or
     * reset by the user stays until then. Blocking; call it from a worker thread.
     */
    static void update(Context context) throws IOException {
        final String fetched = download(context);
        final String baseline = Settings.Global.getString(context.getContentResolver(),
                FETCHED_SETTING);
        if (baseline == null || baseline.isBlank()) {
            // First boot, or the setting was cleared: pif.prop in effect is still the one
            // shipped with the ROM. Only remember what upstream has, to detect its next
            // change; applying it here would replace the shipped profile.
            Settings.Global.putString(context.getContentResolver(), FETCHED_SETTING, fetched);
            return;
        }
        if (!fetched.equals(baseline)) {
            apply(context, fetched);
        }
    }

    /** The device of the current profile when it is a *_beta one (google/tokay_beta/tokay:... -> tokay_beta). */
    private static String product(Context context) {
        final String chosen = device(context);
        if (!chosen.isEmpty()) {
            return chosen;
        }
        final String fingerprint = PifConfig.parseRaw(text(context)).get("FINGERPRINT");
        if (fingerprint != null) {
            final String[] parts = fingerprint.split("/");
            if (parts.length > 1 && parts[1].endsWith("_beta")) {
                return parts[1];
            }
        }
        return DEFAULT_PRODUCT;
    }

    static String device(Context context) {
        final String value = Settings.Global.getString(context.getContentResolver(), DEVICE_SETTING);
        return value == null ? "" : value;
    }

    static void setDevice(Context context, String product) {
        Settings.Global.putString(context.getContentResolver(), DEVICE_SETTING, product);
    }

    /** {model, product} of the Canary devices on offer; throws naming the last failing URL. */
    static java.util.List<String[]> deviceList() throws IOException {
        IOException last = null;
        for (String url : LIST_URLS) {
            try {
                final org.json.JSONArray array = new org.json.JSONArray(get(url));
                final java.util.List<String[]> devices = new java.util.ArrayList<>();
                for (int i = 0; i < array.length(); i++) {
                    final org.json.JSONObject entry = array.getJSONObject(i);
                    devices.add(new String[] {entry.getString("model"), entry.getString("product")});
                }
                return devices;
            } catch (IOException | org.json.JSONException e) {
                Log.w(TAG, "Fetching " + url + " failed", e);
                last = new IOException(url + ": " + e, e);
            }
        }
        throw last;
    }

    /** The first mirror that downloads a valid file; throws naming the last failing URL. */
    private static String download(Context context) throws IOException {
        final String product = product(context);
        IOException last = null;
        for (String template : FETCH_URLS) {
            final String url = String.format(template, product);
            try {
                final String fetched = get(url);
                if (!PifConfig.isValid(fetched)) {
                    throw new IOException("Invalid pif.prop");
                }
                return fetched;
            } catch (IOException e) {
                Log.w(TAG, "Fetching " + url + " failed", e);
                last = new IOException(url + ": " + e, e);
            }
        }
        throw last;
    }

    private static String get(String url) throws IOException {
        final HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(10000);
        connection.setRequestProperty("User-Agent", "PlayIntegrityFix");
        try (InputStream in = connection.getInputStream()) {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
            return out.toString(StandardCharsets.UTF_8);
        } finally {
            connection.disconnect();
        }
    }

    /** Merges the downloaded pif.prop into the current one; restarts the targets if it changed. */
    private static void apply(Context context, String fetched) {
        final String current = text(context);
        String merged = current;
        for (Map.Entry<String, String> entry : PifConfig.parseRaw(fetched).entrySet()) {
            if (!isOption(entry.getKey())) {
                merged = PifConfig.withValue(merged, entry.getKey(), entry.getValue());
            }
        }
        Settings.Global.putString(context.getContentResolver(), FETCHED_SETTING, fetched);
        if (!merged.equals(current)) {
            write(context, merged);
        }
    }

    private static boolean isOption(String key) {
        for (String option : OPTIONS) {
            if (option.equals(key)) return true;
        }
        return false;
    }

    /** Google Play services and the Play Store read the new state when they start. */
    static void stopTargets(Context context) {
        final ActivityManager am = context.getSystemService(ActivityManager.class);
        for (String pkg : TARGET_PACKAGES) {
            am.forceStopPackage(pkg);
        }
    }
}
