/*
 * Play Integrity Fix (inject-s) - ROM integration
 *
 * Copyright (C) 2026 wnduddld0513
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * The Specter page: the management layer over the PixelOS spoofing (Play Integrity profile
 * and TrickyStore). The online revocation check, the keybox catalog, the lookup of the
 * latest security patch and the restart of Google Play services run in this app; the
 * PixelOS pages edit the profile, the keybox and the target apps by hand. The settings of
 * the page are stored in the "specter" preferences, see SpecterSync.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License version 3 as published by the Free
 * Software Foundation. It is distributed WITHOUT ANY WARRANTY; see the GNU
 * General Public License (LICENSE) for details.
 */
package io.github.wnduddld0513.pif;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Log;
import android.widget.EditText;
import android.widget.Toast;

import androidx.preference.ListPreference;
import androidx.preference.MultiSelectListPreference;
import androidx.preference.Preference;
import androidx.preference.SwitchPreferenceCompat;

import com.android.settingslib.widget.MainSwitchPreference;
import com.android.settingslib.widget.SettingsBasePreferenceFragment;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SpecterFragment extends SettingsBasePreferenceFragment {
    private static final String TAG = "SpecterFragment";
    private static final String[] CATEGORIES = {
        "specter_sync", "specter_steps", "specter_pif", "specter_keybox",
        "specter_patch", "specter_apply", "specter_adb", "specter_rom",
    };

    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    // Model names of the devices by codename
    private final Map<String, String> mModels = new HashMap<>();

    private SwitchPreferenceCompat mAutoUpdate;
    private Preference mUpdateNow;
    private Preference mProfile;
    private Preference mFetchProfile;
    private Preference mKeyboxInfo;
    private Preference mFetchKeybox;
    private Preference mKeyboxUrl;
    private Preference mCheck;
    private MultiSelectListPreference mPreferred;
    private MultiSelectListPreference mBlocked;
    private ListPreference mProvider;
    private ListPreference mPatchSource;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        getPreferenceManager().setSharedPreferencesName(SpecterSync.PREFS);
        setPreferencesFromResource(R.xml.specter_settings, rootKey);

        final MainSwitchPreference mainSwitch = findPreference("specter_enabled");
        mainSwitch.setChecked(SpecterSync.isEnabled(requireContext()));
        mainSwitch.addOnSwitchChangeListener((switchView, checked) -> {
            SpecterSync.setEnabled(requireContext(), checked);
            updateVisibility(checked);
        });
        updateVisibility(mainSwitch.isChecked());

        mAutoUpdate = findPreference("specter_auto");
        mAutoUpdate.setOnPreferenceChangeListener((p, value) -> {
            SpecterSync.setAutoUpdate(requireContext(), (Boolean) value);
            return true;
        });
        findPreference(SpecterSync.KEY_INTERVAL).setOnPreferenceChangeListener((p, value) -> {
            // Rescheduled once the value is stored
            mHandler.post(() -> SpecterSync.schedule(requireContext()));
            return true;
        });
        mUpdateNow = findPreference("specter_update_now");
        mUpdateNow.setOnPreferenceClickListener(p -> {
            run(mUpdateNow, SpecterSync::propagate);
            return true;
        });

        mProfile = findPreference("pif_profile");
        mFetchProfile = findPreference("pif_fetch");
        mFetchProfile.setOnPreferenceClickListener(p -> {
            run(mFetchProfile, PifStore::fetch);
            return true;
        });
        mPreferred = findPreference(SpecterSync.KEY_PREFERRED);
        mBlocked = findPreference(SpecterSync.KEY_BLOCKED);
        for (MultiSelectListPreference list : new MultiSelectListPreference[] {mPreferred, mBlocked}) {
            setDevices(list, null);
            list.setOnPreferenceChangeListener((p, value) -> {
                @SuppressWarnings("unchecked")
                final Set<String> selected = (Set<String>) value;
                list.setSummary(deviceSummary(selected));
                mHandler.post(() -> run(null, SpecterSync::propagate));
                return true;
            });
        }
        loadDevices();
        openOnClick("pif_open", Spoof.PIF_PAGE);

        mKeyboxInfo = findPreference("keybox_info");
        mFetchKeybox = findPreference("keybox_fetch");
        mFetchKeybox.setOnPreferenceClickListener(p -> {
            run(mFetchKeybox, SpecterSync::propagateReplacingKeybox);
            return true;
        });
        mProvider = findPreference(SpecterSync.KEY_PROVIDER);
        setProviders(null);
        loadProviders();
        mKeyboxUrl = findPreference("keybox_url");
        mKeyboxUrl.setOnPreferenceClickListener(p -> {
            askKeyboxUrl();
            return true;
        });
        mCheck = findPreference("keybox_check");
        mCheck.setOnPreferenceClickListener(p -> {
            checkRevocation();
            return true;
        });
        openOnClick("keybox_open", Spoof.TRICKY_PAGE);

        mPatchSource = findPreference(SpecterSync.KEY_PATCH_SOURCE);
        mPatchSource.setOnPreferenceChangeListener((p, value) -> {
            if (SpecterSync.SOURCE_MANUAL.equals(value)) {
                // The value that is set manually stays; the dialog edits it
                mHandler.post(() -> PatchDialog.show(requireContext(), null, this::refresh));
            } else {
                mHandler.post(() -> run(null, SpecterSync::propagate));
            }
            return true;
        });
        for (String key : new String[] {SpecterSync.KEY_PATCH_DEVICE, SpecterSync.KEY_PATCH_BULLETIN,
                SpecterSync.KEY_PATCH_SYNTHETIC}) {
            findPreference(key).setOnPreferenceChangeListener((p, value) -> {
                mHandler.post(() -> run(null, SpecterSync::propagate));
                return true;
            });
        }
        findPreference("patch_fetch").setOnPreferenceClickListener(p -> {
            fetchPatch();
            return true;
        });

        findPreference("apply_now").setOnPreferenceClickListener(p -> {
            final Context context = appContext();
            mExecutor.execute(() -> KeyboxStore.applyNow(context));
            Toast.makeText(context, R.string.apply_done, Toast.LENGTH_SHORT).show();
            return true;
        });

        for (String key : new String[] {SpecterSync.KEY_ADB_DEV, SpecterSync.KEY_ADB_USB}) {
            findPreference(key).setOnPreferenceChangeListener((p, value) -> {
                // Applied once the toggle is stored
                final Context context = appContext();
                mHandler.post(() -> mExecutor.execute(() -> SpecterSync.applyAdb(context)));
                return true;
            });
        }

        findPreference("rom_fingerprint").setSummary(Build.FINGERPRINT);
        findPreference("rom_model").setSummary(Build.MODEL);
        findPreference("rom_build_type").setSummary(Build.TYPE + " / " + Build.TAGS);
        findPreference("rom_security_patch").setSummary(Build.VERSION.SECURITY_PATCH);
    }

    @Override
    public void onResume() {
        super.onResume();
        getActivity().setTitle(R.string.specter_title);
        refresh();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mExecutor.shutdown();
    }

    private Context appContext() {
        return requireContext().getApplicationContext();
    }

    /** The categories are shown while Specter is on. */
    private void updateVisibility(boolean enabled) {
        for (String key : CATEGORIES) {
            findPreference(key).setVisible(enabled);
        }
    }

    private void openOnClick(String key, String fragment) {
        findPreference(key).setOnPreferenceClickListener(p -> {
            try {
                startActivity(Spoof.page(fragment));
            } catch (RuntimeException e) {
                Log.w(TAG, "Cannot open " + fragment, e);
            }
            return true;
        });
    }

    private interface Task {
        void run(Context context) throws Exception;
    }

    /** Runs a step off the UI thread and reports whether it worked; the row waits meanwhile. */
    private void run(Preference row, Task task) {
        final Context context = appContext();
        if (row != null) row.setEnabled(false);
        mExecutor.execute(() -> {
            int message;
            try {
                task.run(context);
                message = R.string.specter_update_done;
            } catch (Exception e) {
                Log.w(TAG, "Update failed", e);
                message = R.string.specter_update_failed;
            }
            final int result = message;
            mHandler.post(() -> {
                if (!isAdded()) return;
                if (row != null) row.setEnabled(true);
                Toast.makeText(context, result, Toast.LENGTH_LONG).show();
                refresh();
            });
        });
    }

    /** The selected devices by model name, or "Automatic" when none is selected. */
    private CharSequence deviceSummary(Set<String> selected) {
        if (selected.isEmpty()) return getString(R.string.pif_devices_auto);
        final List<String> names = new ArrayList<>();
        for (String codename : selected) {
            names.add(mModels.getOrDefault(codename, codename));
        }
        java.util.Collections.sort(names);
        return String.join(", ", names);
    }

    /** Offers the devices when they could be loaded; until then only the selected ones. */
    private void setDevices(MultiSelectListPreference list, List<String[]> devices) {
        final List<CharSequence> names = new ArrayList<>();
        final List<CharSequence> values = new ArrayList<>();
        if (devices != null) {
            for (String[] d : devices) {
                if (values.contains(d[1])) continue;
                names.add(d[0]);
                values.add(d[1]);
            }
        }
        for (String codename : list.getValues()) {
            if (!values.contains(codename)) {
                names.add(codename);
                values.add(codename);
            }
        }
        list.setEntries(names.toArray(new CharSequence[0]));
        list.setEntryValues(values.toArray(new CharSequence[0]));
        list.setSummary(deviceSummary(list.getValues()));
    }

    private void loadDevices() {
        final Context context = appContext();
        mExecutor.execute(() -> {
            List<String[]> devices = null;
            try {
                devices = PifStore.deviceList(context);
            } catch (Exception e) {
                Log.w(TAG, "Device list not available", e);
            }
            final List<String[]> result = devices;
            mHandler.post(() -> {
                if (!isAdded() || result == null) return;
                for (String[] d : result) {
                    mModels.put(d[1], d[0]);
                }
                setDevices(mPreferred, result);
                setDevices(mBlocked, result);
            });
        });
    }

    /** Lists Automatic first, then the sources of the catalog when they could be loaded. */
    private void setProviders(List<String> sources) {
        final List<CharSequence> names = new ArrayList<>();
        final List<CharSequence> values = new ArrayList<>();
        names.add(getString(R.string.keybox_provider_auto));
        values.add(SpecterSync.PROVIDER_AUTO);
        final List<String> all = new ArrayList<>();
        if (sources != null) all.addAll(sources);
        if (!all.contains(mProvider.getValue())
                && !SpecterSync.PROVIDER_AUTO.equals(mProvider.getValue())) {
            all.add(mProvider.getValue());
        }
        for (String source : all) {
            names.add(source);
            values.add(source);
        }
        mProvider.setEntries(names.toArray(new CharSequence[0]));
        mProvider.setEntryValues(values.toArray(new CharSequence[0]));
        mProvider.setValue(mProvider.getValue());
    }

    private void loadProviders() {
        mExecutor.execute(() -> {
            List<String> sources = null;
            try {
                sources = KeyboxStore.providers();
            } catch (Exception e) {
                Log.w(TAG, "Keybox catalog not available", e);
            }
            final List<String> result = sources;
            mHandler.post(() -> {
                if (isAdded() && result != null) setProviders(result);
            });
        });
    }

    private void refresh() {
        final Context context = requireContext();
        mAutoUpdate.setChecked(SpecterSync.isAutoUpdate(context));

        final String[] profile = PifStore.summary(context);
        if (profile == null) {
            mProfile.setSummary(R.string.pif_profile_none);
        } else {
            String text = getString(R.string.pif_profile_summary, profile[0], profile[2], profile[1]);
            final Long days = PifStore.daysUntilExpiry(context);
            if (days != null) {
                text += "\n" + (days < 0 ? getString(R.string.pif_profile_expired)
                        : getString(R.string.pif_profile_expires, days));
            }
            if (PifStore.isManual(context)) {
                text += "\n" + getString(R.string.pif_profile_manual);
            }
            mProfile.setSummary(text);
        }
        final boolean pifOn = Spoof.isPifOn(context);
        mFetchProfile.setEnabled(pifOn);
        if (!pifOn) mProfile.setSummary(R.string.pif_off);

        // The provider is used only while no address is set
        final String url = SpecterSync.keyboxUrl(context);
        mKeyboxUrl.setSummary(url.isEmpty() ? getString(R.string.keybox_url_none) : url);
        mProvider.setEnabled(url.isEmpty());

        // A patch set in the dialog is the manual one; clearing it follows the profile
        if (SpecterSync.SOURCE_MANUAL.equals(mPatchSource.getValue())
                && PatchDialog.current(context).isEmpty()) {
            mPatchSource.setValue(SpecterSync.SOURCE_PROFILE);
        }

        final boolean trickyOn = Spoof.isTrickyStoreOn(context);
        mFetchKeybox.setEnabled(trickyOn);
        mKeyboxUrl.setEnabled(trickyOn);
        if (!trickyOn) {
            mCheck.setEnabled(false);
            mKeyboxInfo.setSummary(R.string.tricky_off);
            return;
        }
        refreshKeyboxInfo();
    }

    /** Reads the keybox state, and the catalog entry of the keybox, off the UI thread. */
    private void refreshKeyboxInfo() {
        final Context context = appContext();
        mExecutor.execute(() -> {
            final KeyboxStore.Info result = KeyboxStore.info(context);
            mHandler.post(() -> {
                if (!isAdded()) return;
                mCheck.setEnabled(result.present);
                if (!result.present) {
                    mKeyboxInfo.setSummary(R.string.keybox_status_none);
                    return;
                }
                final List<String> states = new ArrayList<>();
                if (result.googleRevoked) states.add(getString(R.string.keybox_info_revoked_google));
                if (result.catalogRevoked) states.add(getString(R.string.keybox_info_revoked_catalog));
                if (result.softbanned) states.add(getString(R.string.keybox_info_softbanned));
                if (states.isEmpty()) states.add(getString(R.string.keybox_info_present));
                String text = String.join(", ", states);
                if (!result.source.isEmpty()) {
                    text += "\n" + getString(R.string.keybox_info_source, result.source,
                            result.version);
                } else if (!result.fromSpecter) {
                    text += "\n" + getString(R.string.keybox_info_user);
                }
                mKeyboxInfo.setSummary(text);
            });
        });
    }

    private void checkRevocation() {
        final Context context = appContext();
        mCheck.setEnabled(false);
        mExecutor.execute(() -> {
            int message;
            try {
                final boolean revoked = KeyboxStore.checkRevocation(context);
                if (revoked) {
                    SpecterSync.propagate(context);
                }
                message = revoked ? R.string.keybox_check_revoked : R.string.keybox_check_ok;
            } catch (Exception e) {
                message = R.string.keybox_check_unknown;
            }
            final int result = message;
            mHandler.post(() -> {
                if (!isAdded()) return;
                Toast.makeText(context, result, Toast.LENGTH_LONG).show();
                refresh();
            });
        });
    }

    /**
     * Asks for the address keyboxes are taken from; a new one is installed from it now and
     * whenever the keybox needs replacing. Clearing it goes back to the catalog.
     */
    private void askKeyboxUrl() {
        final EditText input = new EditText(requireContext());
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint("https://");
        input.setText(SpecterSync.keyboxUrl(requireContext()));
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.keybox_url_title)
                .setMessage(R.string.keybox_url_message)
                .setView(input)
                .setPositiveButton(R.string.save, (dialog, which) -> {
                    final String url = input.getText().toString().trim();
                    if (url.isEmpty()) {
                        SpecterSync.setKeyboxUrl(requireContext(), "");
                        refresh();
                    } else if (url.startsWith("https://")) {
                        installFromUrl(url);
                    } else {
                        Toast.makeText(requireContext(), R.string.keybox_url_invalid,
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setNeutralButton(R.string.keybox_url_clear, (dialog, which) -> {
                    SpecterSync.setKeyboxUrl(requireContext(), "");
                    refresh();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** Installs the keybox of the address and keeps the address when that worked. */
    private void installFromUrl(String url) {
        final Context context = appContext();
        mExecutor.execute(() -> {
            String error = null;
            try {
                KeyboxStore.installFromUrl(context, url);
                SpecterSync.setKeyboxUrl(context, url);
            } catch (Exception e) {
                Log.w(TAG, "Installing the keybox from the address failed: " + e);
                error = String.valueOf(e.getMessage());
            }
            final String reason = error;
            mHandler.post(() -> {
                if (!isAdded()) return;
                Toast.makeText(context, reason == null ? context.getString(R.string.keybox_imported)
                        : context.getString(R.string.keybox_import_failed, reason),
                        Toast.LENGTH_LONG).show();
                refresh();
            });
        });
    }

    /** Looks up the latest security patch and offers it in the manual field. */
    private void fetchPatch() {
        final Context context = appContext();
        mExecutor.execute(() -> {
            String latest;
            try {
                latest = KeyboxStore.latestSecurityPatch();
            } catch (Exception e) {
                latest = null;
            }
            final String result = latest;
            mHandler.post(() -> {
                if (!isAdded()) return;
                if (result == null) {
                    Toast.makeText(context, R.string.patch_fetch_failed, Toast.LENGTH_SHORT).show();
                } else {
                    PatchDialog.show(requireContext(), result, () -> {
                        // A date saved in the dialog is the manual patch
                        if (!PatchDialog.current(requireContext()).isEmpty()) {
                            mPatchSource.setValue(SpecterSync.SOURCE_MANUAL);
                        }
                        refresh();
                    });
                }
            });
        });
    }
}
