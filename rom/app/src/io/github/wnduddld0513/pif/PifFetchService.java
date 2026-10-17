/*
 * Play Integrity Fix (inject-s) - ROM integration
 *
 * Copyright (C) 2026 wnduddld0513
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Runs the Fetch of the module's WebUI (webui/src/lib/Update.ts) once the device is
 * online, for the first run and after a reset; retried with backoff on failure.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License version 3 as published by the Free
 * Software Foundation. It is distributed WITHOUT ANY WARRANTY; see the GNU
 * General Public License (LICENSE) for details.
 */
package io.github.wnduddld0513.pif;

import android.app.job.JobParameters;
import android.app.job.JobService;

/** Downloads the profile in the background when none is stored yet. */
public class PifFetchService extends JobService {
    @Override
    public boolean onStartJob(JobParameters params) {
        if (!PifStore.isEnabled(this) || PifStore.hasProfile(this)) {
            return false;
        }
        new Thread(() -> {
            boolean retry;
            try {
                PifStore.fetch(getApplicationContext());
                retry = false;
            } catch (Exception e) {
                retry = true;
            }
            jobFinished(params, retry);
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }
}
