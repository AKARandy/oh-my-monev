package id.ohmymonev.android.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** Cek internet murah sebelum mesin membuka WebView (kasus malam tanpa sinyal). */
object NetworkStatus {
    fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
