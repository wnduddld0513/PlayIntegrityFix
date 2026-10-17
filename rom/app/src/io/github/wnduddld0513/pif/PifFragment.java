/*
 * Play Integrity Fix (inject-s) - ROM integration
 *
 * Copyright (C) 2026 wnduddld0513
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * The page maps the options of the module's WebUI (webui/src/data/spoofConfig.ts,
 * App.tsx; KOWX712/PlayIntegrityFix, tag v4.9-inject-s, Copyright KOWX712) to
 * preferences: spoofBuild, spoofProps, spoofProvider, spoofSignature,
 * spoofVendingBuild and spoofVendingSdk switches, the Fetch, View and Reset
 * actions of the WebUI, and the active profile of pif.prop.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License version 3 as published by the Free
 * Software Foundation. It is distributed WITHOUT ANY WARRANTY; see the GNU
 * General Public License (LICENSE) for details.
 */
package io.github.wnduddld0513.pif;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.widget.EditText;
import android.widget.Toast;

import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.SwitchPreferenceCompat;

import com.android.settingslib.widget.MainSwitchPreference;
import com.android.settingslib.widget.SettingsBasePreferenceFragment;

import es.chiteroman.playintegrityfix.PifConfig;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Main switch of Play Integrity Fix followed by the module's options, on while it is on. */
public class PifFragment extends SettingsBasePreferenceFragment {
    private static final String KEY_MAIN = "pif_enabled";
    private static final String[] CATEGORIES = {"pif_options", "pif_profile", "pif_config"};
    private static final String[] OPTION_KEYS = {
        PifConfig.SPOOF_BUILD, PifConfig.SPOOF_PROPS, PifConfig.SPOOF_VENDING_BUILD,
        PifConfig.SPOOF_PROVIDER, PifConfig.SPOOF_SIGNATURE, PifConfig.SPOOF_VENDING_SDK,
    };

    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private MainSwitchPreference mMainSwitch;
    private Preference mFetch;
    private Preference mReset;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.pif_settings, rootKey);
        final Context context = requireContext();

        mMainSwitch = findPreference(KEY_MAIN);
        mMainSwitch.setChecked(PifStore.isEnabled(context));
        mMainSwitch.addOnSwitchChangeListener((switchView, checked) -> {
            PifStore.setEnabled(requireContext(), checked);
            updateVisibility(checked);
        });

        for (String key : OPTION_KEYS) {
            final SwitchPreferenceCompat option = findPreference(key);
            option.setOnPreferenceChangeListener((preference, value) -> {
                PifStore.setOption(requireContext(), key, (Boolean) value);
                // Applied after the write, so the dependent rows read the new state
                mHandler.post(this::refresh);
                return true;
            });
        }

        mFetch = findPreference("pif_fetch");
        mFetch.setOnPreferenceClickListener(preference -> {
            fetch();
            return true;
        });
        findPreference("pif_edit").setOnPreferenceClickListener(preference -> {
            edit();
            return true;
        });
        mReset = findPreference("pif_reset");
        mReset.setOnPreferenceClickListener(preference -> {
            PifStore.reset(requireContext());
            refresh();
            return true;
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        getActivity().setTitle(R.string.app_name);
        refresh();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mExecutor.shutdown();
    }

    private void updateVisibility(boolean enabled) {
        for (String key : CATEGORIES) {
            findPreference(key).setVisible(enabled);
        }
    }

    /** Reads the switches and the profile from the pif.prop in effect. */
    private void refresh() {
        final Context context = requireContext();
        updateVisibility(mMainSwitch.isChecked());

        final PifConfig config = PifConfig.parse(PifStore.text(context));
        for (String key : OPTION_KEYS) {
            ((SwitchPreferenceCompat) findPreference(key)).setChecked(PifConfig.flag(config, key));
        }
        // PifConfig's spoofVendingSdk only applies while spoofVendingBuild is off (zygisk.cpp)
        findPreference(PifConfig.SPOOF_VENDING_SDK).setEnabled(!config.spoofVendingBuild);

        findPreference("profile_fingerprint").setSummary(PifStore.profileValue(context, config.props.get("FINGERPRINT")));
        findPreference("profile_model").setSummary(PifStore.profileValue(context, config.props.get("MODEL")));
        findPreference("profile_security_patch").setSummary(PifStore.profileValue(context, config.securityPatch));
        mReset.setEnabled(PifStore.hasCustom(context));
    }

    private void fetch() {
        final Context context = requireContext().getApplicationContext();
        mFetch.setEnabled(false);
        mExecutor.execute(() -> {
            boolean ok;
            try {
                PifStore.fetch(context);
                ok = true;
            } catch (Exception e) {
                ok = false;
            }
            final boolean success = ok;
            mHandler.post(() -> {
                if (!isAdded()) return;
                mFetch.setEnabled(true);
                if (success) {
                    refresh();
                } else {
                    Toast.makeText(context, R.string.pif_fetch_failed, Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    /** Shows the pif.prop in effect and lets the user change it. */
    private void edit() {
        final Context context = requireContext();
        final EditText editor = new EditText(context);
        editor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        editor.setTypeface(Typeface.MONOSPACE);
        editor.setHorizontallyScrolling(true);
        editor.setText(PifStore.text(context));
        new AlertDialog.Builder(context)
                .setTitle(R.string.pif_edit_title)
                .setView(editor)
                .setPositiveButton(R.string.save, (dialog, which) -> {
                    final String text = editor.getText().toString();
                    if (PifConfig.isValid(text)) {
                        PifStore.write(context, text);
                        refresh();
                    } else {
                        Toast.makeText(context, R.string.pif_invalid, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }
}
