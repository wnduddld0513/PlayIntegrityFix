/*
 * Play Integrity Fix (inject-s) - ROM integration
 *
 * Copyright (C) 2026 wnduddld0513
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * The Play Integrity profile of the PixelOS spoofing, kept up to date by Specter. The
 * profiles come from PixelDeviceRepository, the same source as "Fetch" on the PixelOS page
 * (the latest Pixel Canary/beta builds, cached for the month), and are stored in the same
 * JSON form, so the PixelOS page shows and edits what Specter applies. The PixelOS page
 * only fills the profile when the user taps Fetch; Specter applies one on its first pass,
 * follows newer builds of the device and replaces a Canary profile before it expires. A
 * profile imported or edited on the PixelOS page (manually_imported) stays until the user
 * asks Specter for a new one.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License version 3 as published by the Free
 * Software Foundation. It is distributed WITHOUT ANY WARRANTY; see the GNU
 * General Public License (LICENSE) for details.
 */
package io.github.wnduddld0513.pif;

import android.app.ActivityManager;
import android.content.Context;
import android.provider.Settings;
import android.util.Log;

import com.android.internal.util.custom.PixelDeviceRepository;
import com.android.internal.util.custom.PixelDeviceRepository.PixelProfile;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class PifStore {
    /** Settings.Global: the codename chosen on the Specter page; unset or empty follows the profile. */
    static final String DEVICE_SETTING = "pif_device";

    /** A Canary profile is replaced this many days before its estimated expiry. */
    private static final long EXPIRY_MARGIN_DAYS = 7;

    // Google Play services (with DroidGuard) and the Play Store read the profile when they start
    private static final String[] TARGET_PACKAGES = {
        "com.google.android.gms", "com.android.vending",
    };
    private static final String TAG = "PifStore";

    private PifStore() {}

    /** Model, fingerprint and security patch of the profile in effect, or null without one. */
    static String[] summary(Context context) {
        final JSONObject config = Spoof.pifConfig(context);
        final String fingerprint = config.optString("FINGERPRINT", "");
        if (fingerprint.isEmpty()) return null;
        return new String[] {
            config.optString("MODEL", ""), fingerprint, config.optString("SECURITY_PATCH", ""),
        };
    }

    /** Days until the profile in effect expires, or null when it is not a Canary one. */
    static Long daysUntilExpiry(Context context) {
        final JSONObject config = Spoof.pifConfig(context);
        final String month = config.optString("_canary_month", "");
        if (month.isEmpty()) return null;
        return PixelDeviceRepository.getDaysUntilExpiry(month,
                config.optString("_canary_release_date", null));
    }

    static boolean isManual(Context context) {
        return Spoof.pifConfig(context).optBoolean(Spoof.MANUAL, false);
    }

    static String device(Context context) {
        final String value = Settings.Global.getString(context.getContentResolver(), DEVICE_SETTING);
        if (value == null) return "";
        // Earlier versions stored the product of the module's profiles (tokay_beta)
        return value.endsWith("_beta") ? value.substring(0, value.length() - 5) : value;
    }

    static void setDevice(Context context, String codename) {
        Settings.Global.putString(context.getContentResolver(), DEVICE_SETTING, codename);
    }

    /** {model, codename} of the profiles on offer, from the month's cache when it has them. */
    static List<String[]> deviceList(Context context) {
        final List<String[]> devices = new ArrayList<>();
        for (PixelProfile p : PixelDeviceRepository.getProfiles(context, false)) {
            devices.add(new String[] {p.model, p.codename});
        }
        return devices;
    }

    /**
     * Applies the profile of the chosen device (or the PixelOS recommendation), downloading
     * the latest builds first. Replaces a profile imported by hand too: this is the explicit
     * request. Blocking; call it from a worker thread.
     */
    static void fetch(Context context) throws IOException {
        final List<PixelProfile> profiles = PixelDeviceRepository.getProfiles(context, true);
        apply(context, pick(context, profiles, ""));
    }

    /**
     * The automatic update: applies a profile when none is stored, and otherwise follows a
     * newer build of the same device or replaces a Canary profile that is about to expire.
     * A profile imported by hand stays. Returns whether the profile changed. Blocking; call
     * it from a worker thread.
     */
    static boolean update(Context context) throws IOException {
        if (!Spoof.hasPifConfig(context)) {
            apply(context, pick(context, PixelDeviceRepository.getProfiles(context, false), ""));
            return true;
        }
        if (isManual(context)) return false;
        final JSONObject config = Spoof.pifConfig(context);
        final Long days = daysUntilExpiry(context);
        // The cache turns over once a month; an expiring profile needs the builds of now
        final boolean expiring = days != null && days <= EXPIRY_MARGIN_DAYS;
        final List<PixelProfile> profiles = PixelDeviceRepository.getProfiles(context, expiring);
        final PixelProfile profile = pick(context, profiles, config.optString("DEVICE", ""));
        if (profile.fingerprint.equals(config.optString("FINGERPRINT", ""))) return false;
        apply(context, profile);
        return true;
    }

    /** The chosen device, else the device of the current profile, else the PixelOS default. */
    private static PixelProfile pick(Context context, List<PixelProfile> profiles, String current)
            throws IOException {
        if (profiles == null || profiles.isEmpty()) throw new IOException("No Pixel profiles");
        for (String codename : new String[] {device(context), current,
                PixelDeviceRepository.getDefaultPifCodename(profiles)}) {
            if (codename == null || codename.isEmpty()) continue;
            for (PixelProfile p : profiles) {
                if (codename.equals(p.codename) && PixelDeviceRepository.isValidFingerprint(
                        p.fingerprint)) {
                    return p;
                }
            }
        }
        for (PixelProfile p : profiles) {
            if (PixelDeviceRepository.isValidFingerprint(p.fingerprint)) return p;
        }
        throw new IOException("No valid Pixel profile");
    }

    /**
     * Stores the profile like "Fetch" on the PixelOS page: the device fields of the profile,
     * keeping the options (spoof*, verboseLogs, DEBUG) of the current one.
     */
    private static void apply(Context context, PixelProfile profile) throws IOException {
        try {
            final JSONObject old = Spoof.pifConfig(context);
            final JSONObject out = new JSONObject();
            final JSONArray names = old.names();
            for (int i = 0; names != null && i < names.length(); i++) {
                final String key = names.getString(i);
                if (key.startsWith("spoof") || "verboseLogs".equals(key) || "DEBUG".equals(key)) {
                    out.put(key, old.get(key));
                }
            }
            final String brand = profile.brand == null ? "" : profile.brand;
            out.put("MANUFACTURER", brand.isEmpty() ? ""
                    : brand.substring(0, 1).toUpperCase(Locale.US) + brand.substring(1));
            out.put("BRAND", brand);
            out.put("MODEL", profile.model);
            out.put("PRODUCT", profile.product);
            out.put("DEVICE", profile.device);
            out.put("FINGERPRINT", profile.fingerprint);
            out.put("SECURITY_PATCH", profile.securityPatch);
            out.put("DEVICE_INITIAL_SDK_INT", PixelDeviceRepository.getInitialSdkInt(profile.device));
            if (profile.isCanary && profile.securityPatch != null
                    && profile.securityPatch.length() >= 7) {
                out.put("_canary_month", profile.securityPatch.substring(0, 7));
            }
            if (profile.isCanary && profile.releaseDate != null) {
                out.put("_canary_release_date", profile.releaseDate);
            }
            out.put(Spoof.MANUAL, false);
            Spoof.setPifConfig(context, out);
            Log.i(TAG, "Applied the profile of " + profile.codename);
        } catch (JSONException e) {
            throw new IOException(e);
        }
        stopTargets(context);
    }

    /**
     * Google Play services and the Play Store read the new state when they start. Left
     * alone until PixelOS turns its spoofing on (the first time Settings is opened after
     * setup): it restarts them itself then, and Setup Wizard must not lose them.
     */
    static void stopTargets(Context context) {
        if (Settings.Secure.getInt(context.getContentResolver(), "spoof_activated", 0) == 0) {
            return;
        }
        final ActivityManager am = context.getSystemService(ActivityManager.class);
        for (String pkg : TARGET_PACKAGES) {
            try {
                am.forceStopPackage(pkg);
            } catch (RuntimeException e) {
                Log.w(TAG, "Could not stop " + pkg, e);
            }
        }
    }
}
