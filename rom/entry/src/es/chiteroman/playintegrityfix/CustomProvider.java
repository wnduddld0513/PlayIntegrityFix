/*
 * Play Integrity Fix (inject-s) - ROM integration
 *
 * Copyright (C) chiteroman, KOWX712 and the Play Integrity Fix contributors
 * Modifications (C) 2026 wnduddld0513
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Adapted from zygisk/src/main/java/es/chiteroman/playintegrityfix/CustomProvider.java
 * (KOWX712/PlayIntegrityFix, tag v4.9-inject-s). Unmodified apart from this
 * header; it is loaded by the ROM (see RomEntry) instead of by Zygisk.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License version 3 as published by the Free
 * Software Foundation. It is distributed WITHOUT ANY WARRANTY; see the GNU
 * General Public License (LICENSE) for details.
 */
package es.chiteroman.playintegrityfix;

import java.security.Provider;

public final class CustomProvider extends Provider {

    public CustomProvider(Provider provider) {
        super(provider.getName(), provider.getVersion(), provider.getInfo());
        putAll(provider);
        put("KeyStore.AndroidKeyStore", CustomKeyStoreSpi.class.getName());
    }

    @Override
    public synchronized Service getService(String type, String algorithm) {
        EntryPoint.spoofFields();
        return super.getService(type, algorithm);
    }
}
