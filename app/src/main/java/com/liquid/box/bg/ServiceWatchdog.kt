package com.liquid.box.bg

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.liquid.box.Application
import com.liquid.box.R
import com.liquid.box.compose.MainActivity
import com.liquid.box.database.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Supervisor that lives OUTSIDE [BoxService]. Three layers keep the VPN/proxy alive:
 *
 *  1. `START_STICKY` in [BoxService.onStartCommand]: Android recreates a killed service and
 *     [BoxService] resumes it when the user had it running (`Settings.startedByUser`).
 *  2. Start retries with backoff inside [BoxService] when the core fails to come up.
 *  3. This object: an AlarmManager heartbeat that is owned by the system, not by the service.
 *     It keeps firing after the service (or the whole process) is gone, and if the user still
 *     wants the VPN on it starts the service again. Because [AirplaneModeRecovery] is started
 *     by the service, bringing the service back also brings the airplane watchdog back.
 *
 * Scope: the heartbeat exists only while the service is switched on (`Settings.startedByUser`,
 * mirrored in-process by [BoxService.serviceWanted]). Every stop path cancels it, a beat that
 * finds the service shutting down does not re-arm it, and with the service off nothing here
 * restarts anything or wakes the device.
 *
 * A crash-loop guard ([consumeRestartBudget]) stops everything after too many automatic
 * restarts in a short window, clears `startedByUser` and posts a notification.
 *
 * The on/off switch and the restart history live in SharedPreferences on purpose: they must be
 * readable on the main thread right when the system recreates the service, before the Room
 * backed [Settings] store is loaded (Room refuses main-thread queries).
 */
object ServiceWatchdog {
    private const val TAG = "ServiceWatchdog"

    const val ACTION_CHECK = "com.liquid.box.SERVICE_WATCHDOG"

    /** The receiver must finish well inside the broadcast timeout. */
    const val CHECK_TIMEOUT_MS = 8_000L

    private const val PREFS_NAME = "service_watchdog"
    private const val KEY_ENABLED = "auto_restart_enabled"
    private const val KEY_WATCHDOG_ENABLED = "watchdog_enabled"
    private const val KEY_RESTART_TIMES = "restart_times"

    /**
     * Heartbeat while the service is alive. It is only a safety net for a dead process:
     * `START_STICKY` is the fast path, so there is no need to wake the device every minute.
     */
    private const val CHECK_INTERVAL_MS = 120_000L

    /**
     * Heartbeat while the service is gone and the user wants it back (restart just attempted,
     * or Android refused it). Short on purpose: this is when reaction time matters, and it is
     * rare, so it costs nothing in the normal case. Same cadence as the old fixed heartbeat.
     */
    const val RESTART_RECHECK_MS = 60_000L

    /** More than [MAX_RESTARTS_IN_WINDOW] automatic restarts within this window = crash loop. */
    private const val RESTART_WINDOW_MS = 10 * 60_000L
    private const val MAX_RESTARTS_IN_WINDOW = 6

    private const val NOTIFICATION_ID = 2
    private const val NOTIFICATION_CHANNEL = "service_watchdog"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val prefs: SharedPreferences
        get() = Application.application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _watchdogEnabled = MutableStateFlow(prefs.getBoolean(KEY_WATCHDOG_ENABLED, false))

    /** Observable form of [watchdogEnabled], for the UI. */
    val watchdogEnabledFlow: StateFlow<Boolean> = _watchdogEnabled

    /**
     * Master switch of the whole watchdog (default off).
     * While it is off, auto restart and airplane recovery are off too, the dashboard card is
     * hidden and the notification no longer shows the connection health. Safe to read from any
     * thread; stored in SharedPreferences for the same reason as [autoRestartEnabled].
     * Change it only through [setWatchdogEnabled].
     */
    val watchdogEnabled: Boolean
        get() = _watchdogEnabled.value

    /**
     * Turns the whole watchdog on or off. Switching it off also switches auto restart and airplane
     * recovery off (their stored values are cleared, so they stay off when the watchdog comes
     * back and have to be turned on again on purpose). Needs root to be switched ON; that check
     * belongs to the caller, because only the UI knows how to ask for it. Call off the main thread.
     */
    suspend fun setWatchdogEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_WATCHDOG_ENABLED, enabled).apply()
        _watchdogEnabled.value = enabled
        if (!enabled) {
            prefs.edit().putBoolean(KEY_ENABLED, false).apply()
            cancel()
            // The running airplane watcher closes itself through its settings listener.
            Settings.airplaneRecoveryEnabled = false
        }
        // Health line of the notification appears / disappears right away.
        ServiceNotification.refresh()
    }

    /** Auto restart switch (default off). Always false while the watchdog is off. Safe to read from any thread. */
    var autoRestartEnabled: Boolean
        get() = watchdogEnabled && prefs.getBoolean(KEY_ENABLED, false)
        set(value) {
            // Cannot be turned on while the whole watchdog is off.
            if (value && !watchdogEnabled) return
            prefs.edit().putBoolean(KEY_ENABLED, value).apply()
            if (!value) {
                cancel()
            } else if (BoxService.serviceAlive && BoxService.serviceWanted) {
                // Only while the service is switched on: turning the setting on with the
                // service off must not start a heartbeat.
                arm()
            }
        }

    // region alarm

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, ServiceWatchdogReceiver::class.java).setAction(ACTION_CHECK),
        PendingIntent.FLAG_UPDATE_CURRENT or ServiceNotification.flags,
    )

    /** Schedules the next heartbeat. Idempotent: it replaces the pending one. */
    fun arm(delayMs: Long = CHECK_INTERVAL_MS) {
        if (!autoRestartEnabled) return
        try {
            val context = Application.application
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val triggerAt = SystemClock.elapsedRealtime() + delayMs
            val pending = pendingIntent(context)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pending)
            } else {
                alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pending)
            }
        } catch (e: Exception) {
            Log.w(TAG, "arm failed", e)
        }
    }

    /** Stops the heartbeat. Called when the user stops the VPN or when we give up. */
    fun cancel() {
        try {
            val context = Application.application
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            alarmManager.cancel(pendingIntent(context))
        } catch (e: Exception) {
            Log.w(TAG, "cancel failed", e)
        }
    }

    // endregion

    /** One heartbeat. Runs from [ServiceWatchdogReceiver] on an IO thread. */
    suspend fun check() {
        if (!autoRestartEnabled) {
            cancel()
            return
        }
        // Fast path, the case 99% of the beats end in: the service is up. Re-arm at the relaxed
        // cadence and leave without touching the database.
        if (BoxService.serviceAlive) {
            if (BoxService.serviceWanted) {
                arm()
            } else {
                // The service is still shutting down after a stop (BoxService.stopService() already
                // cancelled the alarm; this beat was in flight). Do NOT re-arm: the watchdog is
                // active only while the service is switched on.
                cancel()
            }
            return
        }

        Settings.dataStore.initialize()
        if (!Settings.startedByUser) {
            // The user (or a final failure) turned the VPN off: nothing to supervise.
            cancel()
            return
        }
        // The service is gone but wanted. Re-arm first, at the short cadence, so that nothing
        // below can end the supervision by accident and a refused restart is retried soon.
        arm(RESTART_RECHECK_MS)

        if (!consumeRestartBudget()) {
            giveUp("sing-box kept stopping, automatic restart is paused. Open the app to start it again.")
            return
        }
        Log.w(TAG, "service is gone but the user wants it running, restarting")
        ServiceNotification.announce(ServiceNotification.LinkNotice.AUTO_RESTART)
        StartTrace.mark("watchdog restarting service")
        if (!startWithFramework()) {
            // Root can take a few seconds to answer; don't keep the broadcast open for it.
            scope.launch {
                if (!startWithRoot()) Log.e(TAG, "could not restart the service")
            }
        }
    }

    private suspend fun startWithFramework(): Boolean = withContext(Dispatchers.Main) {
        try {
            val context = Application.application
            val intent = Intent(context, Settings.serviceClass())
                .putExtra(BoxService.EXTRA_AUTOMATIC, true)
            ContextCompat.startForegroundService(context, intent)
            true
        } catch (e: Exception) {
            // Android 12+ refuses foreground starts from the background for non-exempt apps.
            Log.w(TAG, "startForegroundService refused: ${e.message}")
            false
        }
    }

    /**
     * Fallback for when Android refuses the foreground start: `am` run by root is not subject to
     * the app's background restrictions. Best effort, only works with root.
     */
    private fun startWithRoot(): Boolean = try {
        val shell = RootClient.shell()
        if (!shell.isRoot) {
            false
        } else {
            val component = "${Application.application.packageName}/${Settings.serviceClass().name}"
            val result = shell.newJob()
                .add("am start-foreground-service -n $component --ez ${BoxService.EXTRA_AUTOMATIC} true")
                .exec()
            if (!result.isSuccess) Log.w(TAG, "root start failed (code=${result.code})")
            result.isSuccess
        }
    } catch (e: Exception) {
        Log.w(TAG, "root start error", e)
        false
    }

    // region crash-loop guard

    /**
     * Records one automatic restart and tells whether it is still allowed. Used by every
     * automatic path (system restart, alarm, boot) so a crash loop cannot restart forever.
     */
    @Synchronized
    fun consumeRestartBudget(): Boolean {
        val now = System.currentTimeMillis()
        val recent = prefs.getString(KEY_RESTART_TIMES, "").orEmpty()
            .split(',')
            .mapNotNull { it.toLongOrNull() }
            .filter { it in (now - RESTART_WINDOW_MS)..now }
        if (recent.size >= MAX_RESTARTS_IN_WINDOW) {
            prefs.edit().putString(KEY_RESTART_TIMES, recent.joinToString(",")).apply()
            return false
        }
        prefs.edit().putString(KEY_RESTART_TIMES, (recent + now).joinToString(",")).apply()
        return true
    }

    /** Ends supervision: no alarm, `startedByUser = false`, and a notification explaining why. */
    suspend fun giveUp(reason: String) {
        Log.w(TAG, "giving up: $reason")
        cancel()
        Settings.startedByUser = false
        Settings.dataStore.flush()
        notifyStopped(reason)
    }

    // endregion

    /** Tells the user the service is down and will not come back on its own. */
    fun notifyStopped(reason: String) {
        try {
            val context = Application.application
            val manager = Application.notification
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        NOTIFICATION_CHANNEL,
                        "Service watchdog",
                        NotificationManager.IMPORTANCE_HIGH,
                    ),
                )
            }
            val open = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                ServiceNotification.flags,
            )
            manager.notify(
                NOTIFICATION_ID,
                NotificationCompat.Builder(context, NOTIFICATION_CHANNEL)
                    .setSmallIcon(R.drawable.ic_menu)
                    .setContentTitle("sing-box stopped")
                    .setContentText(reason)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(reason))
                    .setCategory(NotificationCompat.CATEGORY_ERROR)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .setContentIntent(open)
                    .build(),
            )
        } catch (e: Exception) {
            Log.w(TAG, "notify failed", e)
        }
    }
}
