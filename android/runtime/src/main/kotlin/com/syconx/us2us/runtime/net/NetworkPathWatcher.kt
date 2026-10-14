package com.syconx.us2us.runtime.net

import android.Manifest
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.annotation.RequiresPermission

/**
 * Watches the device's default network (no traffic of its own): whether there is one the app may
 * use. Callbacks arrive on the system's connectivity thread; [ConnectionSupervisor.networkChanged]
 * may be called from there. The app's manifest declares `ACCESS_NETWORK_STATE`.
 */
class NetworkPathWatcher(context: Context) {
    private val connectivity = requireNotNull(context.getSystemService(ConnectivityManager::class.java)) { "no connectivity service" }
    private var callback: ConnectivityManager.NetworkCallback? = null

    /** Reports the current state at once, then every change, until [stop]. */
    @Synchronized
    @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
    fun start(onChange: (Boolean) -> Unit) {
        stop()
        val callback = object : ConnectivityManager.NetworkCallback() {
            // The default network's state; usable = it has internet access and this app is not
            // blocked from it (data saver, background restrictions).
            private var internet = false
            private var blocked = false

            private fun report() = onChange(internet && !blocked)

            override fun onAvailable(network: Network) {
                internet = true
                blocked = false
                report()
            }

            override fun onLost(network: Network) {
                internet = false
                report()
            }

            override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
                this.blocked = blocked
                report()
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                internet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                report()
            }
        }
        this.callback = callback
        // The callback reports a default network at once if there is one; without one it stays
        // silent, so the current state goes first.
        onChange(isAvailable())
        connectivity.registerDefaultNetworkCallback(callback)
    }

    @Synchronized
    fun stop() {
        callback?.let { connectivity.unregisterNetworkCallback(it) }
        callback = null
    }

    /** Whether there is a default network with internet access right now. */
    @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
    fun isAvailable(): Boolean {
        val network = connectivity.activeNetwork ?: return false
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
