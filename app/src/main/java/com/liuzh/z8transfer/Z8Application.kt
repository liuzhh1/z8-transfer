package com.liuzh.z8transfer

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat

class Z8Application : Application() {
    lateinit var controller: AppController
        private set

    override fun onCreate() {
        super.onCreate()
        controller = AppController(this)
        ContextCompat.registerReceiver(this, object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    ?.let(controller::onDetached)
            }
        }, IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED), ContextCompat.RECEIVER_NOT_EXPORTED)
    }
}
