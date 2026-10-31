package com.liquid.box.bg

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.liquid.box.R
import com.liquid.box.compose.component.GlassToast
import com.liquid.box.compose.screen.profileoverride.PerAppProxyScanner
import com.liquid.box.database.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppChangeReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "AppChangeReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "onReceive: ${intent.action}")
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (!Settings.perAppProxyEnabled) {
                    Log.d(TAG, "per app proxy disabled")
                    return@launch
                }
                if (!Settings.perAppProxyManagedMode) {
                    Log.d(TAG, "managed mode disabled")
                    return@launch
                }
                rescanAllApps()
                Settings.dataStore.flush()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to rescan apps", e)
                withContext(Dispatchers.Main) {
                    GlassToast.makeText(context, R.string.error_title, GlassToast.LENGTH_SHORT).show()
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun rescanAllApps() {
        Log.d(TAG, "rescanning all apps")
        val chinaApps = PerAppProxyScanner.scanAllChinaApps()
        Settings.perAppProxyManagedList = chinaApps
        Log.d(TAG, "rescan complete, found ${chinaApps.size} china apps")
    }
}
