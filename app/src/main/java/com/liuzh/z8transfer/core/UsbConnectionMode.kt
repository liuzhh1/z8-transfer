package com.liuzh.z8transfer.core

enum class UsbConnectionMode(val label: String) {
    SYSTEM_MTP("系统 MTP（推荐）"),
    DIRECT_PTP("标准 PTP（实验）"),
}

/** Use one driver for each explicit connection attempt. Never probe with the
 * other driver first or automatically retry it after a failed transaction. */
internal suspend fun <T> openSelectedUsb(
    mode: UsbConnectionMode = UsbConnectionMode.SYSTEM_MTP,
    system: suspend () -> T,
    standard: suspend () -> T,
): T = when (mode) {
    UsbConnectionMode.SYSTEM_MTP -> system()
    UsbConnectionMode.DIRECT_PTP -> standard()
}
