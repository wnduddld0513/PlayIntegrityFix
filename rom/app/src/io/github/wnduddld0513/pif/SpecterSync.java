/*
 * Play Integrity Fix (inject-s) - ROM integration
 *
 * Copyright (C) 2026 wnduddld0513
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Specter keeps the PixelOS spoofing (Play Integrity profile and TrickyStore) set up and up
 * to date, which the PixelOS page leaves to the user: one pass (the steps of Specter's
 * action.sh that need no root) restarts Google Play services and the Play Store, keeps them
 * among the TrickyStore target apps, sets the security patch, checks the keybox and takes a
 * new one when it is missing or revoked, and applies or refreshes the Play Integrity
 * profile. The first pass runs once the device is online after the first boot or a reset,
 * then the pass repeats every 6, 12 or 24 hours.
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
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

final class SpecterSync {
    /** Settings.Global: unset or 1, Specter is on; only 0 turns it off. */
    static final String SWITCH_SETTING = "specter";

    static final String PREFS = "specter";
    private static final String KEY_AUTO = "auto_update";
    private static final String KEY_FIRST_DONE = "first_update_done";
    // Keys of the preferences of the Specter page, which the page stores in PREFS itself
    static final String KEY_STEP_GMS = "step_gms";
    static final String KEY_STEP_TARGETS = "step_targets";
    static final String KEY_STEP_PATCH = "step_patch";
    static final String KEY_STEP_KEYBOX = "step_keybox";
    static final String KEY_STEP_PIF = "step_pif";
    static final String KEY_GMS_STOP = "gms_stop";
    static final String KEY_GMS_CLEAR = "gms_clear";
    static final String KEY_PREFERRED = "pif_preferred";
    static final String KEY_BLOCKED = "pif_blocked";
    static final String KEY_PROVIDER = "keybox_provider";
    static final String KEY_KEYBOX_URL = "keybox_url_value";
    static final String KEY_PATCH_SOURCE = "patch_source";
    static final String KEY_PATCH_DEVICE = "patch_src_device";
    static final String KEY_PATCH_BULLETIN = "patch_src_bulletin";
    static final String KEY_PATCH_SYNTHETIC = "patch_src_synthetic";
    static final String KEY_ADB_DEV = "adb_dev";
    static final String KEY_ADB_USB = "adb_usb";
    static final String KEY_INTERVAL = "interval_hours";

    static final String SOURCE_PROFILE = "profile";
    static final String SOURCE_DEVICE = "device";
    static final String SOURCE_BULLETIN = "bulletin";
    static final String SOURCE_SYNTHETIC = "synthetic";
    static final String SOURCE_MANUAL = "manual";
    static final String PROVIDER_AUTO = "auto";

    // The first pass once online, retried until it succeeds, and the periodic pass
    private static final int FIRST_JOB_ID = 1;
    private static final int PERIODIC_JOB_ID = 2;

    private static final String TAG = "SpecterSync";
    private static final long HOUR_MS = 60 * 60 * 1000L;

    private SpecterSync() {}

    static boolean isEnabled(Context context) {
        return Settings.Global.getInt(context.getContentResolver(), SWITCH_SETTING, 1) != 0;
    }

    static void setEnabled(Context context, boolean enabled) {
        Settings.Global.putInt(context.getContentResolver(), SWITCH_SETTING, enabled ? 1 : 0);
        schedule(context);
    }

    static boolean isAutoUpdate(Context context) {
        return prefs(context).getBoolean(KEY_AUTO, true);
    }

    static void setAutoUpdate(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_AUTO, enabled).apply();
        schedule(context);
    }

    /** The period of the automatic update: 6, 12 (default) or 24 hours. */
    static long intervalMs(Context context) {
        long hours;
        try {
            hours = Long.parseLong(prefs(context).getString(KEY_INTERVAL, "12"));
        } catch (NumberFormatException e) {
            hours = 12;
        }
        return hours * HOUR_MS;
    }

    /** The address keyboxes are taken from instead of the catalog; "" uses the catalog. */
    static String keyboxUrl(Context context) {
        return prefs(context).getString(KEY_KEYBOX_URL, "").trim();
    }

    static void setKeyboxUrl(Context context, String url) {
        prefs(context).edit().putString(KEY_KEYBOX_URL, url == null ? "" : url.trim()).apply();
    }

    /**
     * Schedules the first pass (until it has succeeded once) and the periodic pass while
     * Specter and its automatic update are on; otherwise cancels them.
     */
    static void schedule(Context context) {
        final JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (!isEnabled(context) || !isAutoUpdate(context)) {
            scheduler.cancel(FIRST_JOB_ID);
            scheduler.cancel(PERIODIC_JOB_ID);
            return;
        }
        final ComponentName service = new ComponentName(context, PifFetchService.class);
        if (!isFirstUpdateDone(context)) {
            scheduler.schedule(new JobInfo.Builder(FIRST_JOB_ID, service)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setBackoffCriteria(30000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                    .setPersisted(true)
                    .build());
        }
        scheduler.schedule(new JobInfo.Builder(PERIODIC_JOB_ID, service)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(intervalMs(context))
                .setPersisted(true)
                .build());
    }

    /** Whether the pass that every fresh or reset setup runs once has succeeded. */
    static boolean isFirstUpdateDone(Context context) {
        return prefs(context).getBoolean(KEY_FIRST_DONE, false);
    }

    /**
     * The scheduled pass: the first one sets everything up (profile, keybox, security patch,
     * targets) without clearing any data and is recorded only when it succeeds, so the job
     * retries it until the device is online; the later ones refresh what changed and restart
     * Google Play services only then. Blocking; call it from a worker thread.
     */
    static void scheduledPass(Context context) throws IOException {
        if (!isEnabled(context) || !isAutoUpdate(context)) return;
        if (!isFirstUpdateDone(context)) {
            run(context, false, false);
            prefs(context).edit().putBoolean(KEY_FIRST_DONE, true).apply();
            context.getSystemService(JobScheduler.class).cancel(FIRST_JOB_ID);
            return;
        }
        final SharedPreferences prefs = prefs(context);
        boolean changed = align(context, false);
        if (prefs.getBoolean(KEY_STEP_PIF, true) && Spoof.isPifOn(context)) {
            // Like Specter's autopif: with preferred or blocked devices every pass picks again
            if (pickDevice(context)) {
                PifStore.fetch(context);
                changed = true;
            } else {
                changed |= PifStore.update(context);
            }
        }
        if (changed && prefs.getBoolean(KEY_GMS_STOP, true)) {
            PifStore.stopTargets(context);
        }
        applyAdb(context);
    }

    /**
     * What every change on the Specter page runs: the steps that are on, in the order of
     * Specter's action.sh. Blocking; call it from a worker thread. Throws if the profile
     * cannot be fetched. Does nothing while Specter is off.
     */
    static void propagate(Context context) throws IOException {
        if (!isEnabled(context)) return;
        run(context, true, false);
    }

    /** propagate, installing a new keybox even if the stored one is fine. */
    static void propagateReplacingKeybox(Context context) throws IOException {
        if (!isEnabled(context)) return;
        run(context, true, true);
    }

    /** The defaults after a reset: every Specter setting back to its default; the first pass runs again. */
    static void resetDefaults(Context context) {
        prefs(context).edit().clear().apply();
        Settings.Global.putInt(context.getContentResolver(), SWITCH_SETTING, 1);
        schedule(context);
    }

    /** Turns off developer options and USB debugging as the toggles of the ADB category say; both are off by default. */
    static void applyAdb(Context context) {
        final SharedPreferences prefs = prefs(context);
        try {
            if (prefs.getBoolean(KEY_ADB_DEV, false)) {
                Settings.Global.putInt(context.getContentResolver(),
                        Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0);
            }
            if (prefs.getBoolean(KEY_ADB_USB, false)) {
                Settings.Global.putInt(context.getContentResolver(), Settings.Global.ADB_ENABLED, 0);
            }
        } catch (Exception e) {
            Log.w(TAG, "Turning off the debugging settings failed", e);
        }
    }

    /**
     * One full pass. The profile is applied when there is none, or fetched for the chosen
     * device when this is a manual pass; a profile imported by hand stays on automatic passes.
     */
    private static void run(Context context, boolean manual, boolean replaceKeybox)
            throws IOException {
        final SharedPreferences prefs = prefs(context);
        try {
            if (manual && prefs.getBoolean(KEY_STEP_GMS, true)) {
                restartGms(context, prefs.getBoolean(KEY_GMS_CLEAR, false));
            }
            boolean changed = false;
            if (prefs.getBoolean(KEY_STEP_PIF, true) && Spoof.isPifOn(context)) {
                if (pickDevice(context) || manual) {
                    PifStore.fetch(context);
                    changed = true;
                } else {
                    changed = PifStore.update(context);
                }
            }
            // After the profile, so that the patch can follow it
            changed |= align(context, replaceKeybox);
            if (!manual && changed && prefs.getBoolean(KEY_GMS_STOP, true)) {
                PifStore.stopTargets(context);
            }
        } finally {
            applyAdb(context);
        }
    }

    /** Force-stops Google Play services and the Play Store as the options say, and clears the Play Store's data if asked. */
    private static void restartGms(Context context, boolean clear) {
        if (prefs(context).getBoolean(KEY_GMS_STOP, true)) {
            PifStore.stopTargets(context);
        }
        if (clear) {
            context.getSystemService(ActivityManager.class)
                    .clearApplicationUserData(KeyboxStore.VENDING, null);
        }
    }

    /**
     * Preferred and blocked devices, as pif_apply_preferred of Specter: one of the preferred
     * ones that are not blocked, else with only blocked ones any other device on offer.
     * Returns whether a device was picked; without preferred or blocked devices the choice
     * stays as it is.
     */
    private static boolean pickDevice(Context context) {
        final Set<String> preferred = prefs(context).getStringSet(KEY_PREFERRED, new HashSet<>());
        final Set<String> blocked = prefs(context).getStringSet(KEY_BLOCKED, new HashSet<>());
        if (preferred.isEmpty() && blocked.isEmpty()) return false;
        final List<String> pool = new ArrayList<>(preferred);
        pool.removeAll(blocked);
        if (pool.isEmpty()) {
            for (String[] device : PifStore.deviceList(context)) {
                if (!blocked.contains(device[1])) pool.add(device[1]);
            }
        }
        if (pool.isEmpty()) return false;
        PifStore.setDevice(context, pool.get(new Random().nextInt(pool.size())));
        return true;
    }

    /**
     * Keeps Google Play services and the Play Store among the TrickyStore target apps, sets
     * the security patch from the chosen source, then checks the revocation (unknown while
     * offline) and takes a keybox if there is none or it is revoked. Skipped while
     * TrickyStore is off on the PixelOS page. Returns whether anything changed.
     */
    private static boolean align(Context context, boolean replaceKeybox) {
        if (!Spoof.isTrickyStoreOn(context)) return false;
        final SharedPreferences prefs = prefs(context);
        boolean changed = false;
        if (prefs.getBoolean(KEY_STEP_TARGETS, true)) {
            final Map<String, String> targets = Spoof.targets(context);
            boolean added = false;
            for (String pkg : new String[] {KeyboxStore.GMS, KeyboxStore.VENDING}) {
                final String mode = targets.get(pkg);
                // "-" skips the app: put it back in the default mode
                if (mode == null || "-".equals(mode)) {
                    targets.put(pkg, "");
                    added = true;
                }
            }
            if (added) {
                Spoof.setTargets(context, targets);
                changed = true;
            }
        }
        if (prefs.getBoolean(KEY_STEP_PATCH, true)) {
            final String patch = resolvePatch(context);
            if (patch != null && !Spoof.patch(context).equals(patch)) {
                Spoof.setPatch(context, patch);
                changed = true;
            }
        }
        if (prefs.getBoolean(KEY_STEP_KEYBOX, true)) {
            try {
                try {
                    KeyboxStore.checkRevocation(context);
                } catch (Exception e) {
                    // Offline or no keybox: the revocation stays as it was
                }
                final boolean fixedProvider = !PROVIDER_AUTO.equals(
                        prefs.getString(KEY_PROVIDER, PROVIDER_AUTO));
                if (replaceKeybox || KeyboxStore.needsKeybox(context)
                        || (fixedProvider && Spoof.isKeyboxFromSpecter(context))) {
                    KeyboxStore.fetchOnline(context);
                    changed = true;
                }
            } catch (Exception e) {
                Log.w(TAG, "Keeping the keybox as it is", e);
            }
        }
        return changed;
    }

    /**
     * The security patch TrickyStore gets: Profile takes the one of the Play Integrity
     * profile (as "Fetch" on the PixelOS page does); Device, Bulletin and Synthetic are
     * tried from the chosen source on in that order, skipping the ones that are off; null
     * leaves it as it is (Manual, or no source gave a date).
     */
    private static String resolvePatch(Context context) {
        final SharedPreferences prefs = prefs(context);
        final String source = prefs.getString(KEY_PATCH_SOURCE, SOURCE_PROFILE);
        if (SOURCE_PROFILE.equals(source)) {
            final String patch = Spoof.pifConfig(context).optString("SECURITY_PATCH", "");
            return patch.matches("\\d{4}-\\d{2}-\\d{2}") ? patch : null;
        }
        final String[] chain = {SOURCE_DEVICE, SOURCE_BULLETIN, SOURCE_SYNTHETIC};
        final String[] toggles = {KEY_PATCH_DEVICE, KEY_PATCH_BULLETIN, KEY_PATCH_SYNTHETIC};
        int start = -1;
        for (int i = 0; i < chain.length; i++) {
            if (chain[i].equals(source)) start = i;
        }
        if (start < 0) return null;
        for (int i = start; i < chain.length; i++) {
            if (!prefs.getBoolean(toggles[i], true)) continue;
            try {
                final String patch = chain[i].equals(SOURCE_DEVICE) ? Build.VERSION.SECURITY_PATCH
                        : chain[i].equals(SOURCE_BULLETIN) ? KeyboxStore.latestSecurityPatch()
                        : LocalDate.now().toString().substring(0, 7) + "-05";
                if (patch != null && patch.matches("\\d{4}-\\d{2}-\\d{2}")) return patch;
            } catch (Exception e) {
                Log.w(TAG, "Security patch source " + chain[i] + " not available", e);
            }
        }
        return null;
    }

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
