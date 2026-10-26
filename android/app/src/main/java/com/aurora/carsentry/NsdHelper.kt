package com.aurora.carsentry

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

/**
 * 通过 mDNS 广播 `_carsentry._tcp`，手机端在同一 WiFi 下自动发现。
 *
 * 广播失败不致命：手机端还有手输 IP 的退路，只是体验差一点。
 */
class NsdHelper(
    private val context: Context,
    private val port: Int,
    private val serviceName: String,
) {

    companion object {
        private const val TAG = "NsdHelper"
        private const val SERVICE_TYPE = "_carsentry._tcp"
    }

    private var manager: NsdManager? = null
    private var listener: NsdManager.RegistrationListener? = null

    fun register() {
        if (listener != null) return

        val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        manager = nsdManager

        val info = NsdServiceInfo().apply {
            this.serviceName = serviceName
            serviceType = SERVICE_TYPE
            setPort(port)
            setAttribute("api", "/api/v1")
            setAttribute("proto", "1")
        }

        val registration = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "registered: ${info.serviceName}")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "registration failed: $errorCode")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                Log.i(TAG, "unregistered")
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "unregistration failed: $errorCode")
            }
        }

        listener = registration
        try {
            nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration)
        } catch (e: Exception) {
            Log.w(TAG, "register threw: ${e.message}")
            listener = null
        }
    }

    fun unregister() {
        val reg = listener ?: return
        try {
            manager?.unregisterService(reg)
        } catch (_: Exception) {
        }
        listener = null
    }
}
