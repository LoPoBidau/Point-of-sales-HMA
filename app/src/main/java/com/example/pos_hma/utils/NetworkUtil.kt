package com.example.pos_hma.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest

object NetworkUtil {
    fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val isValidated = if (android.os.Build.VERSION.SDK_INT >= 23) caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) else true
        return hasInternet && isValidated
    }

    fun registerNetworkCallback(
        context: Context,
        onAvailable: () -> Unit,
        onLost: () -> Unit
    ): ConnectivityManager.NetworkCallback {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { onAvailable() }
            override fun onLost(network: Network) { onLost() }
            override fun onUnavailable() { onLost() }
        }
        cm.registerNetworkCallback(req, cb)
        return cb
    }

    fun unregisterNetworkCallback(context: Context, cb: ConnectivityManager.NetworkCallback?) {
        if (cb == null) return
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        try { cm.unregisterNetworkCallback(cb) } catch (_: Throwable) {}
    }
}

