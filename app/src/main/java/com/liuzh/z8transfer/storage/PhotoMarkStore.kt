package com.liuzh.z8transfer.storage

import android.content.Context
import androidx.core.content.edit

/** Store source fingerprints, never session-local PTP object handles. */
class PhotoMarkStore(context: Context) {
    private val preferences = context.getSharedPreferences("photo-marks", Context.MODE_PRIVATE)
    fun read(): Set<String> = preferences.getStringSet("marked", emptySet()).orEmpty().toSet()
    fun write(marks: Set<String>) {
        preferences.edit { putStringSet("marked", marks.toSet()) }
    }
}
