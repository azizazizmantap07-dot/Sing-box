package com.liquid.box.bg

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.liquid.box.aidl.IService
import com.liquid.box.aidl.IServiceCallback
import com.liquid.box.constant.Action
import com.liquid.box.constant.Alert
import com.liquid.box.constant.Status
import com.liquid.box.database.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ServiceConnection(private val context: Context, callback: Callback, private val register: Boolean = true) : ServiceConnection {
    companion object {
        private const val TAG = "ServiceConnection"
    }

    private val callback = ServiceCallback(callback)
    private var service: IService? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var connectJob: Job? = null

    val status get() = service?.status?.let { Status.values()[it] } ?: Status.Stopped

    fun connect() {
        connectJob?.cancel()
        connectJob = scope.launch {
            Settings.dataStore.initialize()
            val intent = Intent(context, Settings.serviceClass()).setAction(Action.SERVICE)
            context.bindService(intent, this@ServiceConnection, AppCompatActivity.BIND_AUTO_CREATE)
            Log.d(TAG, "request connect")
        }
    }

    fun disconnect() {
        connectJob?.cancel()
        connectJob = null
        try {
            context.unbindService(this)
        } catch (_: IllegalArgumentException) {
        }
        Log.d(TAG, "request disconnect")
    }

    fun reconnect() {
        disconnect()
        connect()
    }

    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
        val service = IService.Stub.asInterface(binder)
        this.service = service
        try {
            if (register) service.registerCallback(callback)
            callback.onServiceStatusChanged(service.status)
        } catch (e: RemoteException) {
            Log.e(TAG, "initialize service connection", e)
        }
        Log.d(TAG, "service connected")
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        try {
            service?.unregisterCallback(callback)
        } catch (e: RemoteException) {
            Log.e(TAG, "cleanup service connection", e)
        }
        Log.d(TAG, "service disconnected")
    }

    override fun onBindingDied(name: ComponentName?) {
        reconnect()
        Log.d(TAG, "service dead")
    }

    interface Callback {
        fun onServiceStatusChanged(status: Status)

        fun onServiceAlert(type: Alert, message: String?) {
        }
    }

    class ServiceCallback(private val callback: Callback) : IServiceCallback.Stub() {
        override fun onServiceStatusChanged(status: Int) {
            callback.onServiceStatusChanged(Status.values()[status])
        }

        override fun onServiceAlert(type: Int, message: String?) {
            callback.onServiceAlert(Alert.values()[type], message)
        }
    }
}
