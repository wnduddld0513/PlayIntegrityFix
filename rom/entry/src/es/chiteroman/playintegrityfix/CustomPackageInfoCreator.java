/*
 * Play Integrity Fix (inject-s) - ROM integration
 *
 * Copyright (C) chiteroman, KOWX712 and the Play Integrity Fix contributors
 * Modifications (C) 2026 wnduddld0513
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Adapted from zygisk/src/main/java/es/chiteroman/playintegrityfix/CustomPackageInfoCreator.java
 * (KOWX712/PlayIntegrityFix, tag v4.9-inject-s). Unmodified apart from this
 * header; it is loaded by the ROM (see RomEntry) instead of by Zygisk.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License version 3 as published by the Free
 * Software Foundation. It is distributed WITHOUT ANY WARRANTY; see the GNU
 * General Public License (LICENSE) for details.
 */
package es.chiteroman.playintegrityfix;

import android.content.pm.PackageInfo;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;

public final class CustomPackageInfoCreator implements Parcelable.Creator<PackageInfo> {
    private final Parcelable.Creator<PackageInfo> originalCreator;
    private final Signature spoofedSignature;

    public CustomPackageInfoCreator(Parcelable.Creator<PackageInfo> originalCreator, Signature spoofedSignature) {
        this.originalCreator = originalCreator;
        this.spoofedSignature = spoofedSignature;
    }

    @Override
    @SuppressWarnings("deprecation")
    public PackageInfo createFromParcel(Parcel source) {
        PackageInfo packageInfo = originalCreator.createFromParcel(source);
        if (packageInfo.packageName.equals("android")) {
            if (packageInfo.signatures != null && packageInfo.signatures.length > 0) {
                packageInfo.signatures[0] = spoofedSignature;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                if (packageInfo.signingInfo != null) {
                    Signature[] signaturesArray = packageInfo.signingInfo.getApkContentsSigners();
                    if (signaturesArray != null && signaturesArray.length > 0) {
                        signaturesArray[0] = spoofedSignature;
                    }
                }
            }
        }
        return packageInfo;
    }

    @Override
    public PackageInfo[] newArray(int size) {
        return originalCreator.newArray(size);
    }
}