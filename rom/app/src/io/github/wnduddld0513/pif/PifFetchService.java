/*
 * Play Integrity Fix (inject-s) - ROM integration
 *
 * Copyright (C) 2026 wnduddld0513
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Runs Specter's scheduled pass (in place of the module's autopif_ota.sh and Specter's
 * scheduler) once the device is online; the first pass is retried with backoff until it
 * succeeds.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License version 3 as published by the Free
 * Software Foundation. It is distributed WITHOUT ANY WARRANTY; see the GNU
 * General Public License (LICENSE) for details.
 */
package io.github.wnduddld0513.pif;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.Context;
import android.util.Log;

public class PifFetchService extends JobService {
    @Override
    public boolean onStartJob(JobParameters params) {
        if (!SpecterSync.isEnabled(this) || !SpecterSync.isAutoUpdate(this)) {
            return false;
        }
        final Context context = getApplicationContext();
        new Thread(() -> {
            boolean retry = false;
            try {
                SpecterSync.scheduledPass(context);
            } catch (Exception e) {
                Log.w("SpecterSync", "Scheduled pass failed", e);
                // Only the first pass retries; the periodic one runs again anyway
                retry = !SpecterSync.isFirstUpdateDone(context);
            }
            jobFinished(params, retry);
        }, "specter-pass").start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }
}
