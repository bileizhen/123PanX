package io.github.bileizhen.pan123x.core.network

import android.content.Context
import android.net.ConnectivityManager
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.URI

/** The platform selector handles PAC; ConnectivityManager covers ROM-specific Wi-Fi settings. */
class AndroidSystemProxy(private val context: Context) {
    fun select(uri: URI): Proxy? {
        val selected = ProxySelector.getDefault()?.select(uri)?.firstOrNull()
        if (selected != null && selected.type() != Proxy.Type.DIRECT) return selected
        val info = context.getSystemService(ConnectivityManager::class.java)?.defaultProxy ?: return null
        if (ProxyBypass.matches(info.exclusionList.orEmpty().joinToString(","), uri.host.orEmpty())) return null
        val host = info.host
        return if (!host.isNullOrBlank() && info.port in 1..65535)
            Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(host, info.port)) else null
    }
}
