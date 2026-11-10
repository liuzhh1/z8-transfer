package com.liuzh.z8transfer.usb

import android.hardware.usb.UsbDevice

/** Excludes camera serial numbers and filenames; safe for a user-requested error report. */
internal fun describeUsbConnection(device: UsbDevice): String = buildString {
    appendLine("USB VID/PID：%04x/%04x".format(device.vendorId, device.productId))
    appendLine("手机：${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} / Android ${android.os.Build.VERSION.RELEASE}")
    for (index in 0 until device.interfaceCount) {
        val intf = device.getInterface(index)
        appendLine("接口 ${intf.id}，备用 ${intf.alternateSetting}：class=${intf.interfaceClass} subclass=${intf.interfaceSubclass} protocol=${intf.interfaceProtocol}")
        for (endpointIndex in 0 until intf.endpointCount) {
            val ep = intf.getEndpoint(endpointIndex)
            appendLine("  端点 0x${ep.address.toString(16)} type=${ep.type} maxPacket=${ep.maxPacketSize}")
        }
    }
}.trimEnd()
