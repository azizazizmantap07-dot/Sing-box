@file:OptIn(DelicateCoroutinesApi::class)

package com.liquid.box.bg

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.MutableLiveData
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.StatusMessage
import com.liquid.box.Application
import com.liquid.box.R
import com.liquid.box.compose.MainActivity
import com.liquid.box.constant.Action
import com.liquid.box.constant.Status
import com.liquid.box.database.Settings
import com.liquid.box.utils.CommandClient
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * The ongoing foreground-service notification: profile, state and (optionally) real-time speed.
 *
 * On API 24+ it is drawn as liquid glass (see res/layout/notification_traffic_*.xml and the
 * notif_* drawables / colors): upload and download chips when collapsed, and a status + uptime
 * card, a Stop pill and one frosted tile per direction when expanded. Both layouts are
 * RemoteViews inside
 * [NotificationCompat.DecoratedCustomViewStyle], so the system keeps drawing the small icon, app
 * name and expand arrow around them.
 *
 * Since Android 12 an app can no longer draw over that system chrome, so the glass covers the whole
 * notification by painting its surface instead: the notification is colorized (setColor +
 * setColorized, honoured for foreground-service notifications) with the glass base colour, and the
 * layouts lay the glow and the chips on top of it. The colors have a day (light glass) and a night
 * (dark glass) variant that follow the system dark mode; because setColor takes a resolved ARGB
 * value, the notification is rebuilt when the configuration (dark mode) changes.
 *
 * Collapsed by default, on any ROM: SystemUI decides by itself whether to open a notification
 * expanded (stock Android expands the first one in the shade, except one in the minimized
 * section; some ROMs expand every notification, whatever the channel importance says). So the
 * app does not leave that choice to the system: the notification is posted with the compact pill
 * only and gets its big layout only while [detailsShown] is on, switched by the chevron in the
 * pill (and back off by the one in the detail card). With no big layout there is nothing for
 * SystemUI to auto-expand. The channel and priority stay IMPORTANCE_MIN / PRIORITY_MIN, which
 * additionally keeps stock Android from expanding it; the cost is that a minimized notification
 * has no status bar icon of its own (the system VPN key stays).
 *
 * Older devices (the API 23 legacy flavor) keep the plain text notification with a Stop action.
 */
class ServiceNotification(private val status: MutableLiveData<Status>, private val service: Service) :
    BroadcastReceiver(),
    CommandClient.Handler {

    /** One direction of the live speed, split so the number can be drawn big and the unit small. */
    private data class Speed(
        val value: String,
        val unit: String,
        val text: String,
    )

    private data class Traffic(
        val up: Speed,
        val down: Speed,
        val upTotal: String,
        val downTotal: String,
        val upRate: Long,
        val downRate: Long,
    )

    /**
     * What the health line is saying. Transient recoveries stay up for [NOTICE_HOLD_MS],
     * then the line falls back to the live reading from traffic.
     */
    enum class LinkNotice(
        @StringRes val label: Int,
        @StringRes val labelShort: Int,
        val tone: Tone,
    ) {
        CONNECTING(R.string.notif_health_connecting, R.string.notif_health_connecting_short, Tone.WAIT),
        HEALTHY(R.string.notif_health_healthy, R.string.notif_health_healthy_short, Tone.GOOD),
        STALLED(R.string.notif_health_stalled, R.string.notif_health_stalled_short, Tone.BAD),
        AIRPLANE_RECOVERY(R.string.notif_health_airplane, R.string.notif_health_airplane_short, Tone.WARN),
        AUTO_RESTART(R.string.notif_health_restart, R.string.notif_health_restart_short, Tone.WARN),
        RESTORED(R.string.notif_health_restored, R.string.notif_health_restored_short, Tone.GOOD),
    }

    enum class Tone { GOOD, WAIT, WARN, BAD }

    companion object {
        private const val notificationId = 1
        private const val notificationChannel = "service"

        /**
         * MIN keeps SystemUI from auto-expanding this notification. An existing channel is only
         * ever lowered to it (and only while the person has not edited the channel themselves).
         * Also used by the settings screen, which creates the same channel.
         */
        const val channelImportance = NotificationManager.IMPORTANCE_MIN
        val flags =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

        private const val STALL_SAMPLES = 6
        private const val NOTICE_HOLD_MS = 8_000L

        private const val MINUTE_MS = 60_000L
        private const val HOUR_MS = 60 * MINUTE_MS

        /** The uptime tick fires this long after the boundary, so the new minute is already in. */
        private const val TICK_SLACK_MS = 30L

        /** From this many hours on the uptime is counted in days: three digits would be wider than MM:SS. */
        private const val UPTIME_DAYS_FROM_HOURS = 100L

        @Volatile
        private var current: ServiceNotification? = null

        /** Post a recovery sentence. Safe from any thread; ignored if the notification is down. */
        fun announce(notice: LinkNotice) {
            // The connection health line only exists while the watchdog is on.
            if (!ServiceWatchdog.watchdogEnabled) return
            current?.announce(notice)
        }

        /** Posts the notification again, e.g. after the watchdog was switched on or off. */
        fun refresh() {
            current?.refreshColors()
        }

        fun checkPermission(): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                return true
            }
            return Application.notification.areNotificationsEnabled()
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    private val commandClient =
        CommandClient(GlobalScope, CommandClient.ConnectionType.Status, this, localOnly = true)
    private var receiverRegistered = false

    /** Live speed (command client) is on. The receiver also runs without it, for dark mode. */
    @Volatile
    private var liveSpeed = false

    // Everything below is read and written under `lock`: show() runs on the main thread,
    // updateStatus() on the command client thread and close() on whichever thread stops the service.
    private val lock = Any()
    private var active = false
    private var profileName = ""

    @StringRes
    private var statusTextId = R.string.status_started

    /** [SystemClock.elapsedRealtime] at which the core started, 0 while there is no uptime to show. */
    private var uptimeBase = 0L
    private var traffic: Traffic? = null

    /** Live reading from traffic. A held [notice] overrides it until [noticeUntil]. */
    private var reading = LinkNotice.CONNECTING
    private var notice: LinkNotice? = null
    private var noticeUntil = 0L
    private var quietSamples = 0

    /** The detail card is shown (big layout posted). Starts off, and is reset by [close]. */
    private var detailsShown = false

    /**
     * Redraws the uptime in the detail card once it is counted in hours (H:MM, no seconds). The
     * system only ticks a Chronometer, and that always shows seconds, so from one hour on the
     * uptime is plain text and has to be redrawn here: once at the one-hour mark and then every
     * minute. Re-armed by every [buildNotification], paused while the screen is off.
     */
    private val uptimeHandler = Handler(Looper.getMainLooper())
    private val uptimeTick = Runnable {
        synchronized(lock) {
            if (!active) return@Runnable
            Application.notificationManager.notify(notificationId, buildNotification())
        }
    }

    @Volatile
    private var screenOn = Application.powerManager.isInteractive

    private val useGlassLayout = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N

    init {
        current = this
    }

    private val stopIntent by lazy {
        PendingIntent.getBroadcast(
            service,
            0,
            Intent(Action.SERVICE_CLOSE).setPackage(service.packageName),
            flags,
        )
    }

    private val showDetailsIntent by lazy { detailsIntent(Action.NOTIFICATION_SHOW_DETAILS, 1) }
    private val hideDetailsIntent by lazy { detailsIntent(Action.NOTIFICATION_HIDE_DETAILS, 2) }

    private fun detailsIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            service,
            requestCode,
            Intent(action).setPackage(service.packageName),
            flags,
        )

    private val notificationBuilder by lazy {
        NotificationCompat.Builder(service, notificationChannel).setShowWhen(false).setOngoing(true)
            .setContentTitle("sing-box").setOnlyAlertOnce(true)
            .setSmallIcon(R.drawable.ic_menu)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(
                PendingIntent.getActivity(
                    service,
                    0,
                    Intent(
                        service,
                        MainActivity::class.java,
                    ).setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                    flags,
                ),
            )
            .setPriority(NotificationCompat.PRIORITY_MIN).apply {
                if (useGlassLayout) {
                    // The Stop button is part of the expanded glass layout, so no system action here.
                    // The surface colour is set in buildNotification() so it follows dark mode.
                    setStyle(NotificationCompat.DecoratedCustomViewStyle())
                } else {
                    addAction(
                        NotificationCompat.Action.Builder(
                            0,
                            service.getText(R.string.stop),
                            stopIntent,
                        ).build(),
                    )
                }
            }
    }

    fun show(lastProfileName: String, @StringRes contentTextId: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Application.notification.createNotificationChannel(
                NotificationChannel(
                    notificationChannel,
                    "Service Notifications",
                    channelImportance,
                ),
            )
        }
        synchronized(lock) {
            active = true
            profileName = lastProfileName
            statusTextId = contentTextId
            quietSamples = 0
            if (contentTextId == R.string.status_started) {
                if (uptimeBase == 0L) uptimeBase = SystemClock.elapsedRealtime()
                if (notice == null) reading = LinkNotice.HEALTHY
            } else {
                // Starting / reconnecting: no uptime, and the last speeds are stale.
                uptimeBase = 0L
                traffic = null
                if (notice == null) reading = LinkNotice.CONNECTING
            }
            service.startForeground(notificationId, buildNotification())
        }
    }

    suspend fun start() {
        liveSpeed = Settings.dynamicNotification && checkPermission()
        if (liveSpeed) {
            commandClient.connect()
        }
        // Registered even without live speed: the surface colour has to follow dark mode.
        withContext(Dispatchers.Main) {
            registerReceiver()
        }
    }

    private fun registerReceiver() {
        if (receiverRegistered) return
        ContextCompat.registerReceiver(
            service,
            this,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_CONFIGURATION_CHANGED)
                addAction(Action.NOTIFICATION_SHOW_DETAILS)
                addAction(Action.NOTIFICATION_HIDE_DETAILS)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiverRegistered = true
    }

    override fun updateStatus(status: StatusMessage) {
        val next = Traffic(
            up = speedOf(status.uplink),
            down = speedOf(status.downlink),
            upTotal = Libbox.formatBytes(status.uplinkTotal),
            downTotal = Libbox.formatBytes(status.downlinkTotal),
            upRate = status.uplink,
            downRate = status.downlink,
        )
        synchronized(lock) {
            if (!active) return
            val alive = status.uplink > 0L || status.downlink > 0L
            if (alive) {
                quietSamples = 0
                if (statusTextId == R.string.status_started) reading = LinkNotice.HEALTHY
            } else if (statusTextId == R.string.status_started) {
                quietSamples += 1
                if (quietSamples >= STALL_SAMPLES) reading = LinkNotice.STALLED
            }
            val held = notice
            if (held != null && SystemClock.elapsedRealtime() >= noticeUntil) {
                notice = null
            }
            if (next == traffic && held == notice) return
            traffic = next
            Application.notificationManager.notify(notificationId, buildNotification())
        }
    }

    /** Recovery sentence. Holds for [NOTICE_HOLD_MS], then the live reading returns. */
    private fun announce(next: LinkNotice) {
        synchronized(lock) {
            if (!active) return
            notice = next
            noticeUntil = SystemClock.elapsedRealtime() + NOTICE_HOLD_MS
            if (next == LinkNotice.RESTORED) {
                quietSamples = 0
                reading = LinkNotice.HEALTHY
            }
            Application.notificationManager.notify(notificationId, buildNotification())
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_SCREEN_ON -> {
                screenOn = true
                if (liveSpeed) commandClient.connect()
                // The uptime tick sleeps while the screen is off: catch the text up and re-arm it.
                refreshColors()
            }

            Intent.ACTION_SCREEN_OFF -> {
                screenOn = false
                uptimeHandler.removeCallbacks(uptimeTick)
                if (liveSpeed) commandClient.disconnect()
            }

            Intent.ACTION_CONFIGURATION_CHANGED -> refreshColors()

            Action.NOTIFICATION_SHOW_DETAILS -> setDetailsShown(true)

            Action.NOTIFICATION_HIDE_DETAILS -> setDetailsShown(false)
        }
    }

    /** Chevron tapped: post the notification again with or without its big (detail) layout. */
    private fun setDetailsShown(shown: Boolean) {
        synchronized(lock) {
            if (!active || detailsShown == shown) return
            detailsShown = shown
            Application.notificationManager.notify(notificationId, buildNotification())
        }
    }

    /** Dark mode flipped: setColor holds a resolved ARGB, so post again with the new surface. */
    private fun refreshColors() {
        synchronized(lock) {
            if (!active) return
            Application.notificationManager.notify(notificationId, buildNotification())
        }
    }

    fun close() {
        synchronized(lock) {
            active = false
            traffic = null
            uptimeBase = 0L
            detailsShown = false
            uptimeHandler.removeCallbacks(uptimeTick)
        }
        commandClient.disconnect()
        ServiceCompat.stopForeground(service, ServiceCompat.STOP_FOREGROUND_REMOVE)
        if (receiverRegistered) {
            service.unregisterReceiver(this)
            receiverRegistered = false
        }
    }

    // ── Building ────────────────────────────────────────────────────────────────────────────────

    /** Must be called with [lock] held. */
    private fun buildNotification(): Notification {
        val title = profileName.takeIf { it.isNotBlank() } ?: "sing-box"
        val current = traffic
        val text = if (current != null) {
            current.up.text + " ↑\t" + current.down.text + " ↓"
        } else {
            service.getString(statusTextId)
        }
        val builder = notificationBuilder.setContentTitle(title).setContentText(text)
        if (useGlassLayout) {
            // The title area is replaced by the glass layouts. The profile name is deliberately
            // NOT passed to setSubText(): the system would draw it in the header as
            // "sing-box • profile", with a plain text bullet we cannot style. setSubText(null)
            // also clears any value left on the reused builder.
            // Paint the whole notification (icon column, header, chevron) with the glass base
            // colour. The system picks readable header text for it; the RemoteViews colours come
            // from the same day / night resources, so both always agree.
            builder
                .setColor(ContextCompat.getColor(service, R.color.notif_surface))
                .setColorized(true)
                .setSubText(null)
                .setCustomContentView(buildCollapsedView(current))
                // Null clears the big layout left on this reused builder: a notification without
                // one is not auto-expanded into anything bigger than the pill.
                .setCustomBigContentView(if (detailsShown) buildExpandedView(current) else null)
        }
        scheduleUptimeTick()
        return builder.build()
    }

    /**
     * Must be called with [lock] held. Arms the next redraw of the hours-only uptime (see
     * [uptimeTick]): at the one-hour mark while the Chronometer is still counting, then at every
     * minute boundary. Nothing is armed when the text is not visible: no detail card, no uptime,
     * or the screen is off ([onReceive] re-arms it on screen on).
     */
    private fun scheduleUptimeTick() {
        uptimeHandler.removeCallbacks(uptimeTick)
        if (!active || !detailsShown || !screenOn || uptimeBase == 0L) return
        val elapsed = SystemClock.elapsedRealtime() - uptimeBase
        val wait = if (elapsed < HOUR_MS) HOUR_MS - elapsed else MINUTE_MS - elapsed % MINUTE_MS
        uptimeHandler.postDelayed(uptimeTick, wait + TICK_SLACK_MS)
    }

    /**
     * Uptime without seconds, for one hour and more: "1:05", "12:34". Never wider than the
     * Chronometer's MM:SS (the digits are tabular), so the status card keeps its shape. From
     * [UPTIME_DAYS_FROM_HOURS] hours on it is counted in days ("4d") instead, because "100:05"
     * would be one digit too wide.
     */
    private fun hoursText(elapsedMs: Long): String {
        val minutes = elapsedMs / MINUTE_MS
        val hours = minutes / 60
        val locale = Locale.getDefault()
        return if (hours < UPTIME_DAYS_FROM_HOURS) {
            String.format(locale, "%d:%02d", hours, minutes % 60)
        } else {
            String.format(locale, "%dd", hours / 24)
        }
    }

    private fun buildCollapsedView(current: Traffic?): RemoteViews {
        val views = RemoteViews(service.packageName, R.layout.notification_traffic_collapsed)
        bindStatusDot(views)
        views.setOnClickPendingIntent(
            R.id.notification_details_toggle,
            if (detailsShown) hideDetailsIntent else showDetailsIntent,
        )
        views.setImageViewResource(
            R.id.notification_details_chevron,
            if (detailsShown) R.drawable.ic_notif_chevron_up else R.drawable.ic_notif_chevron_down,
        )
        views.setContentDescription(
            R.id.notification_details_toggle,
            service.getText(if (detailsShown) R.string.notif_details_hide else R.string.notif_details_show),
        )
        if (healthVisible()) {
            val shown = displayedNotice()
            views.setTextViewText(R.id.notification_status_text, service.getString(shown.labelShort))
            views.setTextColor(R.id.notification_status_text, ContextCompat.getColor(service, toneColor(shown.tone)))
            views.setViewVisibility(R.id.notification_status_text, View.VISIBLE)
        } else if (current == null) {
            // Watchdog off: no health word. Until the traffic chips arrive, the pill still says
            // what the service is doing (Starting / Started) so it is not just a dot.
            views.setTextViewText(R.id.notification_status_text, service.getString(statusTextId))
            views.setTextColor(R.id.notification_status_text, ContextCompat.getColor(service, R.color.notif_text_secondary))
            views.setViewVisibility(R.id.notification_status_text, View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.notification_status_text, View.GONE)
        }
        views.setViewVisibility(R.id.notification_traffic_group, if (current == null) View.GONE else View.VISIBLE)
        if (current != null) {
            views.setTextViewText(R.id.notification_up_value, current.up.value)
            views.setTextViewText(R.id.notification_up_unit, current.up.unit)
            views.setTextViewText(R.id.notification_down_value, current.down.value)
            views.setTextViewText(R.id.notification_down_unit, current.down.unit)
        }
        return views
    }

    private fun buildExpandedView(current: Traffic?): RemoteViews {
        val views = RemoteViews(service.packageName, R.layout.notification_traffic_expanded)
        bindStatusDot(views)
        views.setTextViewText(R.id.notification_status_text, service.getString(statusTextId))
        if (healthVisible()) {
            val shown = displayedNotice()
            views.setTextViewText(R.id.notification_health_text, service.getString(shown.label))
            views.setTextColor(R.id.notification_health_text, ContextCompat.getColor(service, toneColor(shown.tone)))
            views.setInt(R.id.notification_health_dot, "setBackgroundResource", healthDot(shown))
            // Watchdog on: the health card carries the chevron that folds the notification back.
            views.setViewVisibility(R.id.notification_health, View.VISIBLE)
            views.setViewVisibility(R.id.notification_details_hide_inline, View.GONE)
        } else {
            // Watchdog off: no health row at all. Its only remaining job would be the chevron, and
            // a row holding just that pushed the tiles away from the status row. So the chevron
            // moves into the status row, right beside the status card, and the tiles follow the
            // status row directly.
            views.setViewVisibility(R.id.notification_health, View.GONE)
            views.setViewVisibility(R.id.notification_details_hide_inline, View.VISIBLE)
        }
        views.setOnClickPendingIntent(R.id.notification_stop, stopIntent)
        views.setOnClickPendingIntent(R.id.notification_details_hide, hideDetailsIntent)
        views.setOnClickPendingIntent(R.id.notification_details_hide_inline, hideDetailsIntent)

        // Under an hour the system ticks the Chronometer itself (MM:SS). From one hour on the seconds
        // are dropped (H:MM), which a Chronometer cannot do: a plain TextView takes over and
        // scheduleUptimeTick() redraws it every minute.
        val showUptime = uptimeBase != 0L
        val uptimeMs = if (showUptime) SystemClock.elapsedRealtime() - uptimeBase else 0L
        val hoursOnly = showUptime && uptimeMs >= HOUR_MS
        views.setViewVisibility(R.id.notification_uptime_separator, if (showUptime) View.VISIBLE else View.GONE)
        views.setViewVisibility(
            R.id.notification_uptime,
            if (showUptime && !hoursOnly) View.VISIBLE else View.GONE,
        )
        views.setViewVisibility(R.id.notification_uptime_hours, if (hoursOnly) View.VISIBLE else View.GONE)
        if (showUptime && !hoursOnly) {
            views.setChronometer(R.id.notification_uptime, uptimeBase, null, true)
        }
        if (hoursOnly) {
            views.setTextViewText(R.id.notification_uptime_hours, hoursText(uptimeMs))
        }

        views.setViewVisibility(R.id.notification_tiles, if (current == null) View.GONE else View.VISIBLE)
        if (current != null) {
            views.setTextViewText(R.id.notification_up_value, current.up.value)
            views.setTextViewText(R.id.notification_up_unit, current.up.unit)
            views.setTextViewText(R.id.notification_up_total, current.upTotal)
            views.setTextViewText(R.id.notification_down_value, current.down.value)
            views.setTextViewText(R.id.notification_down_unit, current.down.unit)
            views.setTextViewText(R.id.notification_down_total, current.downTotal)
        }
        return views
    }

    /** The connection health (words, dot colour, "Link" card) is shown only while the watchdog is on. */
    private fun healthVisible(): Boolean = ServiceWatchdog.watchdogEnabled

    /** Must be called with [lock] held. A held recovery sentence wins over the live reading. */
    private fun displayedNotice(): LinkNotice {
        val held = notice
        if (held != null && SystemClock.elapsedRealtime() < noticeUntil) return held
        return reading
    }

    private fun toneColor(tone: Tone): Int = when (tone) {
        Tone.GOOD -> R.color.notif_health_good
        Tone.WAIT -> R.color.notif_health_wait
        Tone.WARN -> R.color.notif_health_warn
        Tone.BAD -> R.color.notif_health_bad
    }

    /** Green while the link is healthy, amber while waiting or recovering, red when stalled. */
    private fun bindStatusDot(views: RemoteViews) {
        val dot = if (healthVisible()) {
            when (displayedNotice().tone) {
                Tone.GOOD -> R.drawable.notif_dot_running
                Tone.BAD -> R.drawable.notif_dot_stalled
                Tone.WAIT, Tone.WARN -> R.drawable.notif_dot_starting
            }
        } else {
            // No health reading while the watchdog is off: the dot only follows the service state.
            if (statusTextId == R.string.status_started) R.drawable.notif_dot_running else R.drawable.notif_dot_starting
        }
        views.setInt(R.id.notification_dot, "setBackgroundResource", dot)
    }

    private fun healthDot(notice: LinkNotice): Int = when (notice.tone) {
        Tone.GOOD -> R.drawable.notif_dot_running
        Tone.BAD -> R.drawable.notif_dot_stalled
        Tone.WAIT, Tone.WARN -> R.drawable.notif_dot_starting
    }

    private fun speedOf(bytesPerSecond: Long): Speed {
        val formatted = Libbox.formatBytes(bytesPerSecond)
        val split = formatted.lastIndexOf(' ')
        return if (split > 0) {
            Speed(formatted.substring(0, split), formatted.substring(split + 1) + "/s", "$formatted/s")
        } else {
            Speed(formatted, "/s", "$formatted/s")
        }
    }
}
