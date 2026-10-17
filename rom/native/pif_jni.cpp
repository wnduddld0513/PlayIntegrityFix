/*
 * Play Integrity Fix (inject-s) - ROM integration
 *
 * Copyright (C) 2026 wnduddld0513
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * JNI glue that lets the ROM run the module's property virtualizer
 * (zygisk/src/main/cpp/property_virtualizer.cpp and pif_config.cpp,
 * KOWX712/PlayIntegrityFix, tag v4.9-inject-s, Copyright chiteroman and KOWX712,
 * both unmodified) inside an app process, where Zygisk used to call install()
 * from preAppSpecialize.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License version 3 as published by the Free
 * Software Foundation. It is distributed WITHOUT ANY WARRANTY; see the GNU
 * General Public License (LICENSE) for details.
 */

#include <jni.h>

#include <string>

#include "pif_config.hpp"
#include "property_virtualizer.hpp"

extern "C" JNIEXPORT jboolean JNICALL
Java_es_chiteroman_playintegrityfix_RomEntry_nativeSpoofProps(JNIEnv *env, jclass,
                                                              jstring pifProp) {
    const char *chars = env->GetStringUTFChars(pifProp, nullptr);
    if (chars == nullptr) {
        return JNI_FALSE;
    }
    const std::string content(chars);
    env->ReleaseStringUTFChars(pifProp, chars);

    // Static: the virtualizer keeps pointers into the config for the process lifetime.
    static pif::Config config;
    config = pif::parseConfig(content);
    return pif::property_virtualizer::install(config) ? JNI_TRUE : JNI_FALSE;
}
