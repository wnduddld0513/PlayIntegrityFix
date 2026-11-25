/*
 * Play Integrity Fix (inject-s) - ROM integration
 *
 * Copyright (C) 2026 wnduddld0513
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * The settings of the PixelOS spoofing (Settings > System > Developer options > Play
 * Integrity spoofing) that Specter reads and writes. PixelOS does the spoofing itself:
 * PlayIntegritySpoofService applies the Play Integrity profile to Google Play services and
 * the Play Store, and its TrickyStore port signs the key attestation of the target apps with
 * the keybox. The value formats are those of that page (SpoofStore): the profile is JSON,
 * the keybox is the Base64 of its XML and the target apps are one package per line with an
 * optional mode suffix (?, ! or -).
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License version 3 as published by the Free
 * Software Foundation. It is distributed WITHOUT ANY WARRANTY; see the GNU
 * General Public License (LICENSE) for details.
 */
package io.github.wnduddld0513.pif;

import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.security.trickystore.TrickyStoreService;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

final class Spoof {
    /** Settings.System: the Play Integrity Fix switch of the PixelOS page, unset or 1 is on. */
    static final String PIF_SWITCH = "spoof_pif_enabled";
    /** Settings.System: the TrickyStore switch of the PixelOS page, unset or 1 is on. */
    static final String TRICKY_SWITCH = "spoof_trickystore_enabled";
    /** Settings.Secure: who installed the keybox; the PixelOS page writes "user". */
    private static final String KEYBOX_SOURCE = "spoof_trickystore_keybox_source";
    static final String SOURCE_SPECTER = "specter";

    /** Set by the PixelOS page when a profile is imported or edited by hand. */
    static final String MANUAL = "manually_imported";

    private static final String SETTINGS_PACKAGE = "com.android.settings";
    private static final String SUB_SETTINGS = "com.android.settings.SubSettings";
    private static final String EXTRA_FRAGMENT = ":settings:show_fragment";
    static final String PIF_PAGE = "com.android.settings.development.spoof.PifSettings";
    static final String TRICKY_PAGE = "com.android.settings.development.spoof.TrickyStoreSettings";

    private static final String TAG = "Spoof";

    private Spoof() {}

    static boolean isPifOn(Context context) {
        return Settings.System.getInt(context.getContentResolver(), PIF_SWITCH, 1) != 0;
    }

    static boolean isTrickyStoreOn(Context context) {
        return Settings.System.getInt(context.getContentResolver(), TRICKY_SWITCH, 1) != 0;
    }

    /** The profile in effect, or an empty object when there is none or it cannot be read. */
    static JSONObject pifConfig(Context context) {
        final String raw = Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.SPOOF_PIF_CONFIG);
        if (TextUtils.isEmpty(raw) || !raw.trim().startsWith("{")) return new JSONObject();
        try {
            return new JSONObject(raw.trim());
        } catch (JSONException e) {
            Log.w(TAG, "Unreadable Play Integrity profile", e);
            return new JSONObject();
        }
    }

    /** Whether a profile with a fingerprint is stored, in either format of the PixelOS page. */
    static boolean hasPifConfig(Context context) {
        final String raw = Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.SPOOF_PIF_CONFIG);
        return raw != null && raw.contains("FINGERPRINT");
    }

    static void setPifConfig(Context context, JSONObject config) throws JSONException {
        Settings.Secure.putString(context.getContentResolver(), Settings.Secure.SPOOF_PIF_CONFIG,
                config.toString(2));
    }

    /** The keybox XML, or null when none is stored. */
    static String keybox(Context context) {
        final String stored = Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.SPOOF_TRICKYSTORE_KEYBOX);
        if (TextUtils.isEmpty(stored)) return null;
        final String trimmed = stored.trim();
        if (trimmed.startsWith("<")) return trimmed;
        try {
            final String xml = new String(Base64.decode(trimmed, Base64.DEFAULT),
                    StandardCharsets.UTF_8).trim();
            return xml.startsWith("<") ? xml : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** A digest of the stored keybox, to tell whether it changed since a check; "" without one. */
    static String keyboxId(Context context) {
        final String stored = Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.SPOOF_TRICKYSTORE_KEYBOX);
        if (TextUtils.isEmpty(stored)) return "";
        try {
            final byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(stored.trim().getBytes(StandardCharsets.UTF_8));
            return Base64.encodeToString(digest, Base64.NO_WRAP);
        } catch (Exception e) {
            return "";
        }
    }

    static void setKeybox(Context context, String xml) {
        final ContentResolver cr = context.getContentResolver();
        Settings.Secure.putString(cr, Settings.Secure.SPOOF_TRICKYSTORE_KEYBOX,
                Base64.encodeToString(xml.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
        Settings.Secure.putString(cr, KEYBOX_SOURCE, SOURCE_SPECTER);
    }

    /** Whether Specter installed the stored keybox (the PixelOS page writes "user"). */
    static boolean isKeyboxFromSpecter(Context context) {
        return SOURCE_SPECTER.equals(Settings.Secure.getString(context.getContentResolver(),
                KEYBOX_SOURCE));
    }

    /** The target apps by package with their mode suffix ("" for the default mode). */
    static Map<String, String> targets(Context context) {
        String stored = Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.SPOOF_TRICKYSTORE_TARGET);
        // Unset until SpoofManager seeds it: the same default list
        if (stored == null) stored = TrickyStoreService.DEFAULT_TARGET_LIST;
        final Map<String, String> out = new LinkedHashMap<>();
        for (String raw : stored.split("\n")) {
            final String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            final char last = line.charAt(line.length() - 1);
            if (last == '?' || last == '!' || last == '-') {
                final String pkg = line.substring(0, line.length() - 1).trim();
                if (!pkg.isEmpty()) out.put(pkg, String.valueOf(last));
            } else {
                out.put(line, "");
            }
        }
        return out;
    }

    static void setTargets(Context context, Map<String, String> targets) {
        final StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : targets.entrySet()) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(entry.getKey()).append(entry.getValue());
        }
        Settings.Secure.putString(context.getContentResolver(),
                Settings.Secure.SPOOF_TRICKYSTORE_TARGET, sb.toString());
    }

    /** The security patch of the key attestation; "" lets TrickyStore keep the real one. */
    static String patch(Context context) {
        final String value = Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.SPOOF_TRICKYSTORE_PATCH);
        return value == null ? "" : value.trim();
    }

    static void setPatch(Context context, String patch) {
        Settings.Secure.putString(context.getContentResolver(),
                Settings.Secure.SPOOF_TRICKYSTORE_PATCH, patch);
    }

    /** Opens a page of the PixelOS spoofing settings (this app runs as the system uid). */
    static Intent page(String fragment) {
        return new Intent(Intent.ACTION_MAIN)
                .setComponent(new ComponentName(SETTINGS_PACKAGE, SUB_SETTINGS))
                .putExtra(EXTRA_FRAGMENT, fragment)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }
}
