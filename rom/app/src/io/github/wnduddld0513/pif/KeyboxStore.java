/*
 * Play Integrity Fix (inject-s) - ROM integration
 *
 * Copyright (C) 2026 wnduddld0513
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * The keybox of the PixelOS TrickyStore port as Specter manages it: installing one from
 * Specter's catalog or from an address, the revocation check against Google's published
 * list and the catalog, and the lookup of the latest security patch. The keybox itself is
 * stored where the PixelOS page keeps it (Spoof.setKeybox); the PixelOS page still imports
 * or removes one by hand.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License version 3 as published by the Free
 * Software Foundation. It is distributed WITHOUT ANY WARRANTY; see the GNU
 * General Public License (LICENSE) for details.
 */
package io.github.wnduddld0513.pif;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.security.trickystore.KeyBoxManager;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;

final class KeyboxStore {
    static final String GMS = "com.google.android.gms";
    static final String VENDING = "com.android.vending";

    private static final String PREFS = "keybox";
    // Source and version of the keybox that the catalog installed
    private static final String PREF_SOURCE = "source";
    private static final String PREF_VERSION = "version";
    // Result of the last revocation check and the keybox it was made for (Spoof.keyboxId)
    private static final String PREF_REVOKED = "revoked";
    private static final String PREF_CHECKED_ID = "checked_id";

    // Google's published list of revoked attestation keys
    private static final String REVOCATION_URL = "https://android.googleapis.com/attestation/status";
    // Security bulletin of the Pixel devices: the patch levels are dated YYYY-MM-01 or YYYY-MM-05
    private static final String PATCH_URL = "https://source.android.com/docs/security/bulletin/pixel";
    private static final Pattern PATCH =
            Pattern.compile("\\b(20\\d\\d-(?:0[1-9]|1[0-2])-0[15])\\b");
    private static final String TAG = "KeyboxStore";

    // Specter's keybox catalog server: the list of the keyboxes and the body of one of them
    private static final String CATALOG_URL = "https://rawbin.dpejoh.com/catalog";
    private static final String CATALOG_URL_FALLBACK = "https://rawbin.dpejoh.workers.dev/catalog";
    private static final String KEY_URL = "https://rawbin.dpejoh.com/key/%s/%s";
    private static final String FALLBACK_SOURCE = "Yuri";
    private static final String FALLBACK_VERSION = "8";
    // The server shuffles the base64 alphabet of the bodies
    private static final String SHUFFLED =
            "1dgWnocayqxU3r6vA5lCIPYfHmkV08b4tz+KMsp2NQ9LRXihODwSj7BEFJ/ZuGTe";
    private static final String STD =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    private static final Pattern KEY_BLOCK =
            Pattern.compile("<Key\\s+algorithm=\"(?:ecdsa|rsa)\"", Pattern.CASE_INSENSITIVE);

    private KeyboxStore() {}

    /** Whether no keybox is stored or the stored one was found revoked. */
    static boolean needsKeybox(Context context) {
        return Spoof.keybox(context) == null || isRevoked(context);
    }

    /** The result of the last revocation check, while it still is about the stored keybox. */
    static boolean isRevoked(Context context) {
        final SharedPreferences prefs = prefs(context);
        return prefs.getBoolean(PREF_REVOKED, false)
                && Spoof.keyboxId(context).equals(prefs.getString(PREF_CHECKED_ID, ""));
    }

    /**
     * Installs a keybox from the address set on the Specter page, or else from Specter's
     * catalog: starting at a random entry (or the newest of the chosen provider), walks the
     * candidates until one downloads, is valid and is not revoked. Throws if none could be
     * installed. Blocking; call it from a worker thread.
     */
    static void fetchOnline(Context context) throws Exception {
        final String url = SpecterSync.keyboxUrl(context);
        if (!url.isEmpty()) {
            installFromUrl(context, url);
            return;
        }
        final String provider = SpecterSync.prefs(context)
                .getString(SpecterSync.KEY_PROVIDER, SpecterSync.PROVIDER_AUTO);
        final List<String[]> pool = candidates(provider);
        final Set<String> revokedSerials = revokedSerials();
        final int start = SpecterSync.PROVIDER_AUTO.equals(provider)
                ? new Random().nextInt(pool.size()) : 0;
        Exception last = null;
        for (int i = 0; i < pool.size(); i++) {
            final String[] c = pool.get((start + i) % pool.size());
            try {
                install(context, decode(get(String.format(KEY_URL, c[0], c[1]))), revokedSerials);
                prefs(context).edit().putString(PREF_SOURCE, c[0]).putString(PREF_VERSION, c[1])
                        .apply();
                return;
            } catch (Exception e) {
                Log.w(TAG, "Keybox " + c[0] + "/" + c[1] + " not installed: " + e);
                last = e;
            }
        }
        throw new IOException("No keybox could be installed", last);
    }

    /**
     * Downloads a keybox from an address, in the encoding of the catalog (the shuffled
     * base64) or as plain XML, validates it, checks Google's revocation list and installs it.
     * Throws with the reason if that fails. Blocking; call it from a worker thread.
     */
    static void installFromUrl(Context context, String url) throws Exception {
        if (!url.startsWith("https://")) {
            throw new IllegalArgumentException("Not an https:// address");
        }
        final String body = get(url).trim();
        install(context, body.startsWith("<") ? body : decode(body), revokedSerials());
        prefs(context).edit().remove(PREF_SOURCE).remove(PREF_VERSION).apply();
    }

    /** Validates the keybox like the PixelOS page, rejects a revoked one and stores it. */
    private static void install(Context context, String xml, Set<String> revokedSerials)
            throws Exception {
        if (!KEY_BLOCK.matcher(xml).find()) {
            throw new IllegalArgumentException("No key block");
        }
        final List<String> serials = serials(xml);
        if (serials.isEmpty() && !xml.contains("DeviceID")) {
            throw new IllegalArgumentException("No serial");
        }
        final KeyBoxManager manager = new KeyBoxManager();
        if (!manager.parseKeybox(xml) || !manager.hasKeyboxes()) {
            throw new IllegalArgumentException("Not a valid keybox");
        }
        if (!Collections.disjoint(serials, revokedSerials)) {
            throw new IOException("Revoked");
        }
        Spoof.setKeybox(context, xml);
        prefs(context).edit().putBoolean(PREF_REVOKED, false)
                .putString(PREF_CHECKED_ID, Spoof.keyboxId(context)).apply();
        PifStore.stopTargets(context);
    }

    /** The entries of Specter's catalog, from its mirror when the main server fails. */
    private static List<JSONObject> catalog() throws Exception {
        String body;
        try {
            body = get(CATALOG_URL);
        } catch (IOException e) {
            body = get(CATALOG_URL_FALLBACK);
        }
        final JSONArray entries = new JSONObject(body).getJSONArray("entries");
        final List<JSONObject> list = new ArrayList<>();
        for (int i = 0; i < entries.length(); i++) {
            list.add(entries.getJSONObject(i));
        }
        return list;
    }

    /** The sources of the catalog, for the provider list; throws while offline. */
    static List<String> providers() throws Exception {
        final List<String> sources = new ArrayList<>();
        for (JSONObject entry : catalog()) {
            final String source = entry.optString("source");
            if (!source.isEmpty() && !sources.contains(source)) sources.add(source);
        }
        return sources;
    }

    /**
     * {source, version} of the catalog entries to try. Automatic: the ones that are not
     * revoked, the soft-banned ones only when no other is left. A provider: its entries that
     * are not revoked, newest version first and the soft-banned ones last; automatic when it has none.
     */
    private static List<String[]> candidates(String provider) {
        final boolean auto = SpecterSync.PROVIDER_AUTO.equals(provider);
        List<String[]> pool = new ArrayList<>();
        try {
            final List<JSONObject> entries = catalog();
            if (!auto) {
                entries.removeIf(e -> !provider.equals(e.optString("source")));
                entries.sort((a, b) -> {
                    final int banned = Boolean.compare(a.optBoolean("softbanned"),
                            b.optBoolean("softbanned"));
                    return banned != 0 ? banned
                            : compareVersions(b.optString("version"), a.optString("version"));
                });
            }
            final List<String[]> good = new ArrayList<>();
            final List<String[]> softbanned = new ArrayList<>();
            for (JSONObject entry : entries) {
                if (entry.optBoolean("revoked")) continue;
                final String[] c = {entry.getString("source"), entry.getString("version")};
                (entry.optBoolean("softbanned") ? softbanned : good).add(c);
            }
            if (auto) {
                pool = good.isEmpty() ? softbanned : good;
            } else {
                pool.addAll(good);
                pool.addAll(softbanned);
                if (pool.isEmpty()) {
                    Log.w(TAG, "The provider has no keybox: automatic");
                    return candidates(SpecterSync.PROVIDER_AUTO);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Keybox catalog not available: " + e);
        }
        if (pool.isEmpty()) {
            pool.add(new String[] {FALLBACK_SOURCE, FALLBACK_VERSION});
        }
        return pool;
    }

    /** Orders dotted version numbers numerically, anything else as text. */
    private static int compareVersions(String a, String b) {
        final String[] pa = a.split("\\.");
        final String[] pb = b.split("\\.");
        for (int i = 0; i < Math.max(pa.length, pb.length); i++) {
            final String x = i < pa.length ? pa[i] : "0";
            final String y = i < pb.length ? pb[i] : "0";
            final int c;
            if (x.matches("\\d{1,9}") && y.matches("\\d{1,9}")) {
                c = Integer.compare(Integer.parseInt(x), Integer.parseInt(y));
            } else {
                c = x.compareTo(y);
            }
            if (c != 0) return c;
        }
        return 0;
    }

    /** What the Specter page shows about the stored keybox. */
    static final class Info {
        boolean present;
        boolean fromSpecter;
        boolean googleRevoked;
        boolean catalogRevoked;
        boolean softbanned;
        String source = "";
        String version = "";
    }

    /**
     * The state of the stored keybox: the last revocation check, and the catalog entry with
     * the same serial (revoked, soft-banned, source, version). While the catalog is not
     * reachable the source and version remembered at the install stay.
     */
    static Info info(Context context) {
        final Info info = new Info();
        final String xml = Spoof.keybox(context);
        info.present = xml != null;
        if (!info.present) return info;
        info.fromSpecter = Spoof.isKeyboxFromSpecter(context);
        info.googleRevoked = isRevoked(context);
        if (info.fromSpecter) {
            info.source = prefs(context).getString(PREF_SOURCE, "");
            info.version = prefs(context).getString(PREF_VERSION, "");
        }
        final Set<String> serials = new HashSet<>();
        try {
            for (String hex : serials(xml)) {
                serials.add(hex.replaceFirst("^0+(?!$)", ""));
                serials.add(new java.math.BigInteger(hex, 16).toString());
            }
            for (JSONObject entry : catalog()) {
                if (serials.contains(entry.optString("serial").toLowerCase()
                        .replaceFirst("^0+(?!$)", ""))) {
                    info.source = entry.optString("source", info.source);
                    info.version = entry.optString("version", info.version);
                    info.catalogRevoked = entry.optBoolean("revoked");
                    info.softbanned = entry.optBoolean("softbanned");
                    break;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Keybox catalog not available: " + e);
        }
        return info;
    }

    /**
     * Checks the keybox's certificates against Google's revocation list and remembers the
     * result for this keybox. Returns whether it is revoked; throws if there is no keybox,
     * its serials are unknown or the list cannot be fetched.
     */
    static boolean checkRevocation(Context context) throws Exception {
        final String xml = Spoof.keybox(context);
        if (xml == null) throw new IOException("No keybox");
        final List<String> serials = serials(xml);
        if (serials.isEmpty()) throw new IOException("No certificate serials");
        final boolean revoked = !Collections.disjoint(serials, revokedSerials());
        prefs(context).edit().putBoolean(PREF_REVOKED, revoked)
                .putString(PREF_CHECKED_ID, Spoof.keyboxId(context)).apply();
        return revoked;
    }

    /** The latest Pixel security patch level (YYYY-MM-DD); throws while offline. */
    static String latestSecurityPatch() throws IOException {
        final Matcher m = PATCH.matcher(get(PATCH_URL));
        String latest = null;
        while (m.find()) {
            if (latest == null || m.group(1).compareTo(latest) > 0) latest = m.group(1);
        }
        if (latest == null) throw new IOException("No security patch found");
        return latest;
    }

    /**
     * Force-stops Google Play services (with DroidGuard) and the Play Store, and clears the
     * Play Store's storage, as the options of the update steps say.
     */
    static void applyNow(Context context) {
        final SharedPreferences prefs = SpecterSync.prefs(context);
        if (prefs.getBoolean(SpecterSync.KEY_GMS_STOP, true)) {
            PifStore.stopTargets(context);
        }
        if (prefs.getBoolean(SpecterSync.KEY_GMS_CLEAR, false)) {
            context.getSystemService(ActivityManager.class).clearApplicationUserData(VENDING, null);
        }
    }

    /** Maps the shuffled alphabet back to the standard one and decodes the base64 to the keybox XML. */
    private static String decode(String body) {
        final StringBuilder sb = new StringBuilder(body.length());
        for (char ch : body.replaceAll("\\s+", "").toCharArray()) {
            final int i = SHUFFLED.indexOf(ch);
            sb.append(i < 0 ? ch : STD.charAt(i));
        }
        return new String(Base64.decode(sb.toString(), Base64.DEFAULT), StandardCharsets.UTF_8);
    }

    /** Lower-case serials of Google's revocation list; throws while offline. */
    private static Set<String> revokedSerials() throws Exception {
        final JSONObject entries = new JSONObject(get(REVOCATION_URL)).getJSONObject("entries");
        final Set<String> revoked = new HashSet<>();
        for (Iterator<String> it = entries.keys(); it.hasNext(); ) {
            revoked.add(it.next().toLowerCase());
        }
        return revoked;
    }

    /** Lower-case hex serials of every certificate of the keybox, as the revocation list lists them. */
    private static List<String> serials(String xml) throws Exception {
        final Document doc;
        try {
            doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                    .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalArgumentException(e.getMessage());
        }
        final NodeList nodes = doc.getElementsByTagName("Certificate");
        final CertificateFactory factory = CertificateFactory.getInstance("X.509");
        final List<String> serials = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            final String pem = nodes.item(i).getTextContent().trim().replaceAll("(?m)^\\s+", "");
            try {
                final X509Certificate cert = (X509Certificate) factory.generateCertificate(
                        new ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8)));
                serials.add(cert.getSerialNumber().toString(16));
            } catch (Exception e) {
                throw new IllegalArgumentException(e.getMessage());
            }
        }
        return serials;
    }

    /** The name of an app, or its package name when it is not installed. */
    static String label(Context context, String pkg) {
        final PackageManager pm = context.getPackageManager();
        try {
            return pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String get(String url) throws IOException {
        final HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(10000);
        connection.setRequestProperty("Cache-Control", "no-cache");
        connection.setRequestProperty("User-Agent", "Specter");
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
}
