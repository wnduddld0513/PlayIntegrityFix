/*
 * Play Integrity Fix (inject-s) - ROM integration
 *
 * Copyright (C) chiteroman, KOWX712 and the Play Integrity Fix contributors
 * Modifications (C) 2026 wnduddld0513
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Java port of zygisk/src/main/cpp/pif_config.cpp (parseConfig, expandFingerprint)
 * of KOWX712/PlayIntegrityFix, tag v4.9-inject-s, and of the pif.prop read/write
 * helpers of webui/src/lib/PROP.ts and PifConfig.ts. Same pif.prop format.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License version 3 as published by the Free
 * Software Foundation. It is distributed WITHOUT ANY WARRANTY; see the GNU
 * General Public License (LICENSE) for details.
 */
package es.chiteroman.playintegrityfix;

import android.content.Context;
import android.provider.Settings;
import android.util.Log;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** A parsed pif.prop: the option flags and the build fields to spoof. */
public final class PifConfig {
    public static final String SPOOF_BUILD = "spoofBuild";
    public static final String SPOOF_PROPS = "spoofProps";
    public static final String SPOOF_PROVIDER = "spoofProvider";
    public static final String SPOOF_SIGNATURE = "spoofSignature";
    public static final String SPOOF_VENDING_BUILD = "spoofVendingBuild";
    public static final String SPOOF_VENDING_SDK = "spoofVendingSdk";

    /** Settings.Global: the user's pif.prop (empty or unset: the system default). */
    public static final String CONFIG_SETTING = "lenovo_pif_module_config";
    /** The pif.prop shipped with the system (module/pif.prop of the release). */
    public static final String DEFAULT_PROP = "/system_ext/etc/PlayIntegrityFix/pif.prop";

    // Field names of the segments of a fingerprint, in order.
    private static final String[] FINGERPRINT_KEYS = {
        "BRAND", "PRODUCT", "DEVICE", "RELEASE", "ID", "INCREMENTAL", "TYPE", "TAGS",
    };

    // Defaults of the module (pif_config.hpp).
    public boolean spoofBuild = true;
    public boolean spoofProps = true;
    public boolean spoofProvider = false;
    public boolean spoofSignature = false;
    public boolean spoofVendingBuild = false;
    public boolean spoofVendingSdk = false;
    public boolean debug = false;
    public String deviceInitialSdkInt = "32";
    public String securityPatch = "";
    public String buildId = "";
    /** Build / Build.VERSION fields to spoof, by field name. */
    public Map<String, String> props = new HashMap<>();

    /** Key=value pairs of the text, '#' starts a comment. */
    public static Map<String, String> parseRaw(String content) {
        final Map<String, String> raw = new HashMap<>();
        for (String line : content.split("\n", -1)) {
            final int comment = line.indexOf('#');
            if (comment >= 0) line = line.substring(0, comment);
            line = line.trim();
            final int eq = line.indexOf('=');
            if (eq > 0 && !raw.containsKey(line.substring(0, eq).trim())) {
                raw.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
            }
        }
        return raw;
    }

    private static boolean parseBool(String value) {
        return value.equals("1") || value.equals("true");
    }

    private static List<String> splitFingerprint(String fingerprint) {
        final List<String> parts = new ArrayList<>();
        final StringBuilder current = new StringBuilder();
        for (char ch : fingerprint.toCharArray()) {
            if (ch == '/' || ch == ':') {
                parts.add(current.toString());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        parts.add(current.toString());
        return parts;
    }

    private static void expandFingerprint(Map<String, String> props, String fingerprint) {
        final List<String> parts = splitFingerprint(fingerprint);
        for (int i = 0; i < FINGERPRINT_KEYS.length; i++) {
            props.put(FINGERPRINT_KEYS[i], i < parts.size() ? parts.get(i) : "");
        }
    }

    public static PifConfig parse(String content) {
        final PifConfig config = new PifConfig();
        final Map<String, String> raw = parseRaw(content);

        String value;
        if ((value = raw.remove(SPOOF_VENDING_SDK)) != null) config.spoofVendingSdk = parseBool(value);
        if ((value = raw.remove(SPOOF_VENDING_BUILD)) != null) config.spoofVendingBuild = parseBool(value);
        if ((value = raw.remove("DEVICE_INITIAL_SDK_INT")) != null) config.deviceInitialSdkInt = value;
        if ((value = raw.remove(SPOOF_BUILD)) != null) config.spoofBuild = parseBool(value);
        if ((value = raw.remove(SPOOF_PROVIDER)) != null) config.spoofProvider = parseBool(value);
        if ((value = raw.remove(SPOOF_PROPS)) != null) config.spoofProps = parseBool(value);
        if ((value = raw.remove(SPOOF_SIGNATURE)) != null) config.spoofSignature = parseBool(value);
        if ((value = raw.remove("DEBUG")) != null) config.debug = parseBool(value);
        if ((value = raw.get("SECURITY_PATCH")) != null) config.securityPatch = value;

        config.props = raw;
        final String fingerprint = raw.get("FINGERPRINT");
        if (fingerprint != null) expandFingerprint(config.props, fingerprint);
        final String id = config.props.get("ID");
        if (id != null) config.buildId = id;
        return config;
    }

    /** The text with the key set to the value, appended if it is missing. */
    public static String withValue(String content, String key, String value) {
        final String line = key + "=" + value;
        final StringBuilder out = new StringBuilder();
        boolean found = false;
        for (String l : content.split("\n", -1)) {
            final int eq = l.indexOf('=');
            if (!found && eq > 0 && !l.trim().startsWith("#")
                    && l.substring(0, eq).trim().equals(key)) {
                l = line;
                found = true;
            }
            out.append(l).append('\n');
        }
        String result = out.toString().replaceAll("\n+$", "\n");
        if (!found) result += line + "\n";
        return result;
    }

    /** The value of an option flag. */
    public static boolean flag(PifConfig config, String key) {
        switch (key) {
            case SPOOF_BUILD: return config.spoofBuild;
            case SPOOF_PROPS: return config.spoofProps;
            case SPOOF_PROVIDER: return config.spoofProvider;
            case SPOOF_SIGNATURE: return config.spoofSignature;
            case SPOOF_VENDING_BUILD: return config.spoofVendingBuild;
            case SPOOF_VENDING_SDK: return config.spoofVendingSdk;
            default: return false;
        }
    }

    /** Whether a text can be used as pif.prop (it has a fingerprint). */
    public static boolean isValid(String content) {
        final String fingerprint = parseRaw(content).get("FINGERPRINT");
        return fingerprint != null && !fingerprint.isEmpty();
    }

    /** The pif.prop in effect: the user's, otherwise the system default. */
    public static String readConfigText(Context context) {
        try {
            final String custom = Settings.Global.getString(context.getContentResolver(),
                    CONFIG_SETTING);
            if (custom != null && !custom.isBlank()) {
                return custom;
            }
        } catch (Throwable t) {
            Log.e("PIF", "Couldn't read the custom pif.prop", t);
        }
        return readDefaultText();
    }

    /** The pif.prop shipped with the system. */
    public static String readDefaultText() {
        try {
            return new String(Files.readAllBytes(new File(DEFAULT_PROP).toPath()),
                    StandardCharsets.UTF_8);
        } catch (Throwable t) {
            Log.e("PIF", "Couldn't read " + DEFAULT_PROP, t);
            return "";
        }
    }
}
