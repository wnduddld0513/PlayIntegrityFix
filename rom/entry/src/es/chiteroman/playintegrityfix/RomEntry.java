/*
 * Play Integrity Fix (inject-s) - ROM integration
 *
 * Copyright (C) 2026 wnduddld0513
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Replaces the Zygisk module class (zygisk/src/main/cpp/zygisk.cpp,
 * KOWX712/PlayIntegrityFix, tag v4.9-inject-s, Copyright chiteroman and KOWX712):
 * the routing of preAppSpecialize/postAppSpecialize, the config load of the
 * companion and doSpoofVending() are ported to Java; the ROM calls init() from its
 * app start hook (PropImitationHooks) instead of Zygisk.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License version 3 as published by the Free
 * Software Foundation. It is distributed WITHOUT ANY WARRANTY; see the GNU
 * General Public License (LICENSE) for details.
 */
package es.chiteroman.playintegrityfix;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import com.android.internal.util.custom.KeyProviderManager;

import java.lang.reflect.Field;

/**
 * Entry point of the module inside the ROM. {@link #init} runs at the start of every
 * Google Play services / Play Store process, like the Zygisk module did for
 * com.google.android.gms.unstable (DroidGuard) and com.android.vending.
 */
public final class RomEntry {
    private static final String TAG = EntryPoint.TAG;

    private static final String VENDING_PACKAGE = "com.android.vending";
    private static final String DROIDGUARD_PACKAGE = "com.google.android.gms.unstable";
    private static final int VENDING_SDK = 32;

    private RomEntry() {}

    /**
     * Spoofs the process. Returns false when it did nothing (not a target process, or no
     * profile fetched yet), so the caller can keep its own handling.
     */
    public static boolean init(Context context, String processName) {
        final boolean droidGuard = DROIDGUARD_PACKAGE.equals(processName);
        final boolean vending = VENDING_PACKAGE.equals(processName);
        if (!droidGuard && !vending) {
            return false;
        }
        try {
            final String text = PifConfig.readConfigText(context);
            if (!PifConfig.isValid(text)) {
                Log.w(TAG, "No profile fetched yet, not spoofing");
                return false;
            }
            final PifConfig config = PifConfig.parse(text);
            if (droidGuard) {
                if (config.spoofProps) {
                    spoofProps(text);
                }
                // The ROM's keybox, when set, already makes key attestation pass.
                final boolean provider = config.spoofProvider
                        && !KeyProviderManager.isKeyboxAvailable();
                EntryPoint.init(config.props, provider, config.spoofSignature,
                        config.spoofBuild);
            } else if (config.spoofVendingBuild) {
                EntryPoint.spoofBuildFields(config.props);
            } else if (config.spoofVendingSdk) {
                spoofVendingSdk();
            }
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "init", t);
            return false;
        }
    }

    /** doSpoofVending(): the SDK_INT seen by the Play Store is at most 32. */
    private static void spoofVendingSdk() throws Exception {
        final int old = Build.VERSION.SDK_INT;
        final int target = Math.min(old, VENDING_SDK);
        if (old == target) {
            return;
        }
        final Field sdkInt = Build.VERSION.class.getField("SDK_INT");
        EntryPoint.setField(sdkInt, target);
        Log.i(TAG, "[SDK_INT]: " + old + " -> " + target);
    }

    private static void spoofProps(String text) {
        try {
            System.loadLibrary("pif_propvirt");
            if (!nativeSpoofProps(text)) {
                Log.e(TAG, "[PROP-VIRT] unavailable; property spoofing disabled for this process");
            }
        } catch (Throwable t) {
            Log.e(TAG, "[PROP-VIRT] unavailable; property spoofing disabled for this process", t);
        }
    }

    /** Overrides the system properties of this process (property_virtualizer.cpp). */
    private static native boolean nativeSpoofProps(String pifProp);
}
