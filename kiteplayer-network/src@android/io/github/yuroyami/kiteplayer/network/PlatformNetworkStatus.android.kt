package io.github.yuroyami.kiteplayer.network

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import io.github.yuroyami.kiteplayer.NetworkStatus
import kotlin.concurrent.Volatile

internal actual fun platformNetworkStatus(): NetworkStatus? {
    val context = KiteNetworkContextProvider.application ?: return null
    val manager = context.getSystemService(ConnectivityManager::class.java) ?: return null
    return ConnectivityNetworkStatus(manager)
}

/**
 * Android's network status (#461): the default network, from a `ConnectivityManager` callback.
 * It needs `ACCESS_NETWORK_STATE`, which this module's manifest adds; without it the watch throws
 * and the player's timer alone tries.
 */
internal class ConnectivityNetworkStatus(private val manager: ConnectivityManager) : NetworkStatus {
    override fun watch(onChange: (online: Boolean) -> Unit): AutoCloseable {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = onChange(true)

            override fun onLost(network: Network) = onChange(false)
        }
        onChange(manager.activeNetwork != null)
        manager.registerDefaultNetworkCallback(callback)
        return AutoCloseable { runCatching { manager.unregisterNetworkCallback(callback) } }
    }
}

/**
 * Keeps the application context from the moment the app starts (#461), as `kiteplayer-io` does
 * (#457), so the network status needs no context passed by hand. It serves no data. An app that
 * does not want it removes it in its manifest with `tools:node="remove"`, and the player's timer
 * alone then tries.
 */
internal class KiteNetworkContextProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        application = context?.applicationContext
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    internal companion object {
        @Volatile
        var application: Context? = null
    }
}
