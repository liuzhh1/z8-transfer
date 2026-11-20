package com.liuzh.z8transfer

import android.graphics.Bitmap
import com.liuzh.z8transfer.core.RemoteStatus

data class RemoteUiState(
    val open: Boolean = false,
    val closing: Boolean = false,
    val pending: Boolean = false,
    val status: RemoteStatus = RemoteStatus(),
    val frame: Bitmap? = null,
    val frameNumber: Long = 0,
    val receivedAt: Long = 0,
    val fps: Double = 0.0,
) {
    val busy: Boolean get() = closing || pending || status.busy
}
