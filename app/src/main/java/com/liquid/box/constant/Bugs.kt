package com.liquid.box.constant

import android.os.Build
import com.liquid.box.BuildConfig

/** Workarounds for platform/runtime bugs (e.g. golang/go#68760). */
object Bugs {
    val fixAndroidStack =
        BuildConfig.DEBUG ||
            (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                    Build.VERSION.SDK_INT <= Build.VERSION_CODES.N_MR1
                ) ||
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
}
