/*
 * Play Integrity Fix (inject-s) - ROM integration
 *
 * Copyright (C) 2026 wnduddld0513
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Schedules the automatic update of the profile after boot.
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

/** Schedules the automatic update at boot when the switch and the automatic update are on. */
public class PifBootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        PifStore.scheduleUpdates(context);
    }
}
