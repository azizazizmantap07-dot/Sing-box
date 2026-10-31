package com.liquid.box.ktx

import android.content.ClipData
import com.liquid.box.Application

var clipboardText: String?
    get() = Application.clipboard.primaryClip?.getItemAt(0)?.text?.toString()
    set(plainText) {
        if (plainText != null) {
            Application.clipboard.setPrimaryClip(ClipData.newPlainText(null, plainText))
        }
    }
