/*
 * Play Integrity Fix (inject-s) - ROM integration
 *
 * Copyright (C) 2026 wnduddld0513
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License version 3 as published by the Free
 * Software Foundation. It is distributed WITHOUT ANY WARRANTY; see the GNU
 * General Public License (LICENSE) for details.
 */
package io.github.wnduddld0513.pif;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.security.keybox.IKeyboxManager;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** Adds a newly installed app to the target apps while "add new apps automatically" is on. */
public class PifPackageReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false) || intent.getData() == null) return;
        final String pkg = intent.getData().getSchemeSpecificPart();
        final IKeyboxManager svc = KeyboxStore.service();
        if (svc == null) return;
        try {
            if (!svc.isAutoTarget()) return;
            // The apps that the user excluded on the Specter page are never added
            if (SpecterSync.prefs(context).getStringSet(SpecterSync.KEY_EXCLUDED, new HashSet<>())
                    .contains(pkg)) {
                return;
            }
            final List<String> packages = new ArrayList<>(svc.getTargetApps());
            if (!packages.contains(pkg)) {
                packages.add(pkg);
                svc.setTargetApps(packages);
            }
        } catch (Exception e) {
            // The service is not reachable: the app stays out of the list
        }
    }
}
