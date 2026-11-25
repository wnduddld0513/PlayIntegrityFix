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

import android.app.AlertDialog;
import android.content.Context;
import android.text.InputType;
import android.widget.EditText;
import android.widget.Toast;

import java.util.regex.Pattern;

/** Edits the TrickyStore security patch: empty (the real one) or a manual YYYY-MM-DD. */
final class PatchDialog {
    private static final Pattern FORMAT = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    private PatchDialog() {}

    /** The stored security patch when it is a plain date, "" otherwise. */
    static String current(Context context) {
        final String patch = Spoof.patch(context);
        return FORMAT.matcher(patch).matches() ? patch : "";
    }

    /** @param prefill the text of the field, or null for the stored security patch */
    static void show(Context context, String prefill, Runnable onDone) {
        final EditText input = new EditText(context);
        input.setInputType(InputType.TYPE_CLASS_DATETIME | InputType.TYPE_DATETIME_VARIATION_DATE);
        input.setHint("YYYY-MM-DD");
        input.setText(prefill != null ? prefill : current(context));
        new AlertDialog.Builder(context)
                .setTitle(R.string.patch_title)
                .setView(input)
                .setPositiveButton(R.string.save, (dialog, which) -> {
                    final String patch = input.getText().toString().trim();
                    if (patch.isEmpty() || FORMAT.matcher(patch).matches()) {
                        Spoof.setPatch(context, patch);
                        onDone.run();
                    } else {
                        Toast.makeText(context, R.string.patch_invalid, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }
}
