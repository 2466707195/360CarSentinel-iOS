package com.aurora.carsentry

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.util.Log

/**
 * USB 摄像头插拔通知。
 *
 * 车机上 UVC 摄像头经常是后接的（甚至在整机启动之后才插上），
 * 没有这个广播就只能等用户手动重启服务。插上即重连，体验差别很大。
 */
class UsbAttachReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val attached = action == UsbManager.ACTION_USB_DEVICE_ATTACHED
        Log.i("UsbAttachReceiver", "usb ${if (attached) "attached" else "detached"}")

        if (attached) {
            SentryService.instance?.onUsbChanged()
        }
        ServiceBus.notifyChanged()
    }
}
