package com.liquid.box.bg

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.preference.PreferenceDataStore
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.OutboundGroup
import io.nekohasekai.libbox.StatusMessage
import com.liquid.box.Application
import com.liquid.box.constant.ServiceMode
import com.liquid.box.constant.SettingsKey
import com.liquid.box.database.Settings
import com.liquid.box.database.preference.OnPreferenceDataStoreChangeListener
import com.liquid.box.utils.CommandClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import android.provider.Settings as AndroidSettings

/**
 * Service-level watchdog. Toggles airplane mode via root when the physical link is actually
 * dead or the tunnel is stuck ("bengong"), not when a stale URL-test number still looks fine.
 *
 * Three signals, any one of them is enough to start a check:
 *  - Disconnect: the non-VPN network (wifi / cellular / ethernet) is gone. Confirmed in about
 *    [DISCONNECT_DEBOUNCE_MS] so a wifi↔cell handoff or a weak-signal cell reselection does not
 *    toggle, then airplane mode runs. This does not wait for the next URL test.
 *  - Stall: open connections and almost no downlink for several seconds. Upload
 *    retries and a 1 KB blip do not clear it. Connections that keep dying
 *    without downlink count too, so a bengong that never holds a socket open
 *    is still noticed. A fresh URL-test result that is still healthy makes
 *    this wait the full configured window; a stale or failed result halves it, but never
 *    below [FAST_STALL_MS].
 *  - Selected URL test: only the outbound the urltest group is actually using has to fail
 *    (a spare node with a good delay no longer hides a dead selection, and a dead spare no
 *    longer looks like a total outage). It is ignored while real data is arriving.
 *
 * Real downlink is the strongest proof of life. A weak signal that is slow but still moving
 * data (a download crawling along) must not be treated as bengong, so:
 *  - a recorded URL-test failure is not counted while downlink is flowing;
 *  - the live check below is also passed by downlink that arrived during the check;
 *  - when the radio answers, the tunnel test missed but some data is still trickling in, the
 *    check keeps watching for [GRACE_WATCH_MS] before it gives up. Complete silence gets no
 *    grace, so a real bengong is still recovered promptly.
 *
 * Before the toggle, a live check runs. When a urltest group exists, every selected
 * outbound has to earn a new passing delay whose timestamp comes from this check.
 * A TCP connect to a public host does not count: that socket follows routing rules
 * and often hits direct / a CDN that still answers while the selected outbound is
 * stuck ("bengong"). TCP is only used when there is no urltest selection to ask.
 * A stale "200 ms" that arrives after the test, or a delay with no timestamp, is
 * not a pass. No URL-test group is required for disconnect and radio-level stall.
 *
 * Lifecycle: [BoxService] calls [start] once the sing-box service is up and [stop] when it goes
 * down. The watcher owns its own local [CommandClient]s, so it keeps working while the UI is
 * in the background. It is NOT driven by any UI.
 *
 * The failure threshold and the cooldown are used exactly as configured, every time. There is
 * no escalation.
 */
object AirplaneModeRecovery {
    private const val TAG = "AirplaneRecovery"

    private const val URLTEST_GROUP_TYPE = "urltest"
    private const val FAILURE_DELAY_MS = 10_000

    /** No URL-test / stall failures are counted this long after the watcher starts. */
    private const val STARTUP_GRACE_MS = 8_000L

    /** Minimum quiet period after a toggle so the modem can re-register. */
    private const val POST_RECOVERY_GRACE_MS = 6_000L

    /**
     * How long a URL-test delay may be trusted. Older than this, a "healthy" number is stale:
     * stall detection switches to the fast window instead of waiting out a calm period.
     */
    private const val FRESH_URLTEST_MS = 12_000L

    /** Groups stream tick. Detection of a recorded URL-test failure waits at most one tick. */
    private const val WATCH_INTERVAL_MS = 1_500L

    /** Traffic counters. Stall detection is sampled from this. */
    private const val STATUS_INTERVAL_MS = 800L

    /** Poll backing the network callback, so a missed `onLost` still counts. */
    private const val LINK_POLL_MS = 800L

    /**
     * A disappearing wifi/cell network must stay gone this long. Long enough for a normal
     * handoff or a weak-signal cell reselection to publish the new network, short enough
     * that a real drop does not sit there.
     */
    private const val DISCONNECT_DEBOUNCE_MS = 6_000L

    /** Extra pause inside the disconnect path so a late handoff still aborts the toggle. */
    private const val DISCONNECT_RECHECK_MS = 800L

    /**
     * Downlink seen this recently while no underlying network is reported means the ROM is
     * hiding the real network, not that the link is gone.
     */
    private const val LINK_LOST_TRAFFIC_MS = 3_000L

    /** Downlink bytes that must arrive before open connections count as alive. */
    private const val STALL_PROGRESS_BYTES = 1_024L

    /**
     * Downlink slower than this over the stall window is still a stall.
     * One 1 KB blip (retry, probe response, background poll) must not clear it.
     * Lowered slightly so a slow-but-alive crawl is less likely to look dead.
     */
    private const val STALL_MIN_RATE_BPS = 256L

    /**
     * Connections that vanish without downlink. Several of them, across more than one sample.
     * Apps open and drop idle sockets all the time, and a weak signal makes them time out, so
     * five was still reachable on some ROMs; raise the bar.
     */
    private const val FAST_FAIL_WINDOW_MS = 8_000L
    private const val FAST_FAIL_MIN = 8

    /** The stall must already have lasted this long before a dying connection is counted. */
    private const val FAST_FAIL_MIN_SILENCE_MS = 2_000L

    /** A new urlTest stamp may sit this far behind the probe start and still count. */
    private const val PROBE_TIME_SKEW_MS = 2_000L

    /**
     * Shortest stall window when the last URL test is already stale or failing. The window is
     * halved in that case, but never below this: a weak link routinely goes quiet for a few
     * seconds without being dead. Raised to cut rare false triggers on idle open sockets.
     */
    private const val FAST_STALL_MS = 7_000L

    /** Smallest stall window, whatever the failure threshold says. */
    private const val STALL_MIN_WINDOW_MS = 5_000L

    /** After a live check says the path is fine, don't re-probe a quiet tunnel right away. */
    private const val STALL_RECHECK_MS = 12_000L

    /** Open connections that then vanish after this much silence were stuck, not finished. */
    private const val STALL_DROP_MIN_MS = 5_000L

    /**
     * Pure stall (no dying sockets) is only considered when real traffic was seen this recently.
     * Idle open connections with no data for a long time are normal and must not toggle airplane.
     */
    private const val STALL_ACTIVITY_WINDOW_MS = 45_000L

    /**
     * Proof of life from real traffic. [PROGRESS_BYTES] of downlink within [PROGRESS_WINDOW_MS]
     * (about 1 KB/s) counts as the link carrying data; that stays true for [RECENT_PROGRESS_MS].
     */
    private const val PROGRESS_BYTES = 4L * 1024
    private const val PROGRESS_WINDOW_MS = 4_000L
    private const val RECENT_PROGRESS_MS = 6_000L

    /** Downlink that must arrive during a live check for the path to count as alive. */
    private const val PROBE_ALIVE_BYTES = 6L * 1024

    /**
     * When the radio answers, the tunnel test did not pass, but some data is still trickling in
     * ([GRACE_MIN_BYTES] during the check), the link is slow rather than dead. The check keeps
     * watching this long for a late pass or for the data to pick up before it gives up and
     * toggles. A link with no data at all gets no grace: that is bengong, waiting only delays
     * the recovery.
     */
    private const val GRACE_WATCH_MS = 4_500L
    private const val GRACE_MIN_BYTES = 1L * 1024

    private const val AIRPLANE_OFF_DELAY_MS = 1_500L
    private const val STATE_CHANGE_TIMEOUT_MS = 2_000L
    private const val PROBE_TIMEOUT_MS = 8_000L
    private const val PROBE_SETTLE_MS = 800L

    // A weak signal answers a TCP connect in seconds, not milliseconds. These run in parallel
    // with the (slower) URL test, so a longer timeout costs no extra waiting.
    private const val RADIO_PROBE_TIMEOUT_MS = 4_000
    private const val TUNNEL_TCP_TIMEOUT_MS = 4_000

    /** A snapshot must be this much newer than the end of the tests to count as their result. */
    private const val SNAPSHOT_MARGIN_MS = 200L
    private const val SNAPSHOT_POLL_MS = 150L

    private const val RECONNECT_MIN_DELAY_MS = 2_500L
    private const val RECONNECT_MAX_DELAY_MS = 25_000L

    private val RADIO_PROBE_HOSTS = listOf("1.1.1.1", "8.8.8.8", "9.9.9.9")

    /** Hostnames used for tunnel probe so DNS resolution is exercised, not only raw TCP. */
    private val TUNNEL_PROBE_HOSTS = listOf("www.cloudflare.com", "dns.google", "one.one.one.one")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Guards serviceActive + session.
    private val access = Any()
    private var serviceActive = false
    private var session: Session? = null

    private val toggling = AtomicBoolean(false)

    // The recovery run that is in flight (probe / toggle), so it can be cancelled when the
    // service goes down. Only one runs at a time ([toggling]).
    @Volatile private var recoveryJob: Job? = null

    // Toggles in a row that were not followed by a single passing check. Only used for logging.
    private val recoveryAttempts = AtomicInteger(0)

    // When the current uninterrupted run of "selected url test failed" began; 0 = not failing.
    @Volatile private var failingSince = 0L

    // Downlink stall window. 0 = downlink is moving or there is nothing to watch.
    // Upload is ignored on purpose: a bengong tunnel keeps sending retries.
    @Volatile private var stallSince = 0L
    @Volatile private var stallAnchorDown = 0L
    @Volatile private var prevDown = 0L
    @Volatile private var prevConns = 0L
    @Volatile private var trafficPrimed = false

    // Next status sample re-anchors counters. Set when a probe finishes so the
    // probe's own responses are not treated as user traffic, and so they cannot
    // wipe [stallRearmAt].
    @Volatile private var reanchorTraffic = false

    // Connections that appeared and died without downlink, inside [FAST_FAIL_WINDOW_MS].
    @Volatile private var fastFailWindowStart = 0L
    @Volatile private var fastFailCount = 0

    // Real-traffic meter. Unlike the stall counters above it keeps running while a recovery
    // attempt is probing, so a download that picks up again during the check is seen.
    // meterDown is the last downlinkTotal seen (-1 = not primed yet).
    @Volatile private var meterDown = -1L
    @Volatile private var meterBytes = 0L
    @Volatile private var meterWindowStart = 0L

    // elapsedRealtime of the last time the meter saw real downlink; 0 = never.
    @Volatile private var lastProgressAt = 0L

    // Don't start another stall probe before this elapsedRealtime; 0 = not held.
    @Volatile private var stallRearmAt = 0L

    // URL-test history is a bad picture until this elapsedRealtime (a live check just passed).
    @Volatile private var suppressUrlTestUntil = 0L

    // True when every selected URL test is healthy AND recent. Stall uses it to pick a window.
    @Volatile private var lastUrlTestFresh = true

    // When the underlying network was first noticed missing; 0 = it is up.
    @Volatile private var linkLostSince = 0L

    // Some ROMs only expose the VPN. Don't call that a disconnect until this session has
    // actually seen a wifi/cell network and then lost it.
    @Volatile private var everSawUnderlying = false

    /**
     * Live set of underlying (non-VPN wifi/cell/ethernet/bt) networks, maintained by
     * [linkCallback]. Replaces the deprecated [ConnectivityManager.allNetworks] snapshot.
     */
    private val underlyingNetworks = ConcurrentHashMap.newKeySet<Network>()

    // When the last snapshot was processed (elapsedRealtime). The URL-test probe uses it to know
    // that the stream has caught up with the tests it just ran.
    @Volatile private var lastSnapshotAt = 0L

    // SystemClock.elapsedRealtime() deadlines; 0 = not set.
    @Volatile private var startupGraceUntil = 0L

    @Volatile private var cooldownUntil = 0L

    // When the current cooldown began (elapsedRealtime); 0 = none. Lets a changed cooldown setting
    // be applied to a cooldown that is already running.
    @Volatile private var cooldownStartedAt = 0L

    // Snapshots taken after this elapsedRealtime count as the result of the URL test we just
    // ran. Long.MAX_VALUE = not inside that wait, so a stale "healthy" delay cannot pass a probe.
    @Volatile private var probeAcceptAfter = Long.MAX_VALUE

    // Wall-clock millis when the live urlTest was issued. A passing delay only counts when
    // its urlTestTime is at least this new. 0 = not inside a probe.
    @Volatile private var probeWallStartMs = 0L

    // Selected outbounds that must produce that new pass. Empty = no urltest group.
    @Volatile private var probeNeeds: List<ProbeNeed> = emptyList()

    @Volatile private var lastTargets: List<Target> = emptyList()

    // Last time a fresh check showed the path working (elapsedRealtime).
    @Volatile private var lastHealthyAt = 0L

    @Volatile private var urlTestGroupTags: List<String> = emptyList()

    /** Tags passed to urlTest: the selected outbound when we know it, otherwise the group. */
    @Volatile private var selectedProbeTags: List<String> = emptyList()

    private var linkCallback: ConnectivityManager.NetworkCallback? = null
    private var linkJob: Job? = null

    private val settingsListener = object : OnPreferenceDataStoreChangeListener {
        override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
            when (key) {
                SettingsKey.AIRPLANE_RECOVERY_ENABLED -> sync()
                SettingsKey.AIRPLANE_RECOVERY_FAILURE_THRESHOLD,
                SettingsKey.AIRPLANE_RECOVERY_COOLDOWN_SEC,
                -> applyConfig()
            }
        }
    }

    /** Called by [BoxService] once the sing-box service is running. Idempotent. */
    fun start() {
        synchronized(access) {
            if (serviceActive) return
            serviceActive = true
        }
        Settings.dataStore.registerChangeListener(settingsListener)
        sync()
    }

    /** Called by [BoxService] when the service stops. Idempotent. */
    fun stop() {
        val wasActive = synchronized(access) {
            val previous = serviceActive
            serviceActive = false
            previous
        }
        if (wasActive) {
            Settings.dataStore.unregisterChangeListener(settingsListener)
        }
        sync()
    }

    /** The user's toggle, which only counts while the whole watchdog is on. */
    private fun recoveryEnabled(): Boolean = ServiceWatchdog.watchdogEnabled && Settings.airplaneRecoveryEnabled

    /** Opens or closes the watchers depending on service state and the user's toggle. */
    private fun sync() {
        var toOpen: Session? = null
        var toClose: Session? = null
        synchronized(access) {
            val wanted = serviceActive && recoveryEnabled()
            if (wanted && session == null) {
                toOpen = Session().also { session = it }
            } else if (!wanted && session != null) {
                toClose = session
                session = null
            }
        }
        toClose?.let {
            it.close()
            resetState(withStartupGrace = false)
            stopLinkMonitor()
            // Service stopped (or feature switched off): an attempt that is still probing must
            // not go on to toggle airplane mode. If it is cancelled in the middle of a toggle,
            // executeAirplaneToggle() still switches airplane mode back off (NonCancellable).
            recoveryJob?.cancel()
            recoveryJob = null
        }
        toOpen?.let {
            resetState(withStartupGrace = true)
            startLinkMonitor()
            it.connect()
        }
    }

    /**
     * Makes a changed failure threshold / cooldown take effect right away, without a service
     * restart. The threshold is read on every update, so only a cooldown that is already
     * running needs to be recomputed from its start with the new length. Triggered by the
     * settings listener, so the "Apply" button in the Service screen only has to save the values.
     */
    fun applyConfig() {
        val startedAt = cooldownStartedAt
        if (startedAt != 0L) {
            cooldownUntil = startedAt + cooldownMs()
        }
        Log.i(TAG, "config applied: threshold=${failureThreshold()} cooldown=${cooldownMs() / 1000}s")
    }

    private fun resetState(withStartupGrace: Boolean) {
        failingSince = 0L
        stallSince = 0L
        stallAnchorDown = 0L
        prevDown = 0L
        prevConns = 0L
        trafficPrimed = false
        reanchorTraffic = false
        fastFailWindowStart = 0L
        fastFailCount = 0
        meterDown = -1L
        meterBytes = 0L
        meterWindowStart = 0L
        lastProgressAt = 0L
        stallRearmAt = 0L
        suppressUrlTestUntil = 0L
        lastUrlTestFresh = true
        linkLostSince = 0L
        everSawUnderlying = false
        underlyingNetworks.clear()
        recoveryAttempts.set(0)
        urlTestGroupTags = emptyList()
        selectedProbeTags = emptyList()
        lastTargets = emptyList()
        probeNeeds = emptyList()
        probeWallStartMs = 0L
        cooldownUntil = 0L
        cooldownStartedAt = 0L
        startupGraceUntil = if (withStartupGrace) SystemClock.elapsedRealtime() + STARTUP_GRACE_MS else 0L
    }

    private fun isWatching(): Boolean = synchronized(access) { serviceActive && session != null } &&
        recoveryEnabled() && BoxService.serviceWanted

    /** Doze: the core is paused (see BoxService), so traffic and URL-test numbers carry no news. */
    private fun isDeviceIdle(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
        try {
            Application.powerManager.isDeviceIdleMode
        } catch (e: Exception) {
            false
        }

    private data class ProbeNeed(
        val groupTag: String,
        val probeTag: String,
    )

    private data class Target(
        val groupTag: String,
        val probeTag: String,
        val historyHealthy: Boolean,
        val historyFresh: Boolean,
        val urlTestTime: Long,
    )

    /** True only when this sample is a healthy delay stamped by the live probe, not an older one. */
    private fun Target.provedSince(wallStartMs: Long): Boolean {
        if (!historyHealthy || urlTestTime <= 0L || wallStartMs <= 0L) return false
        val timeMs = if (urlTestTime > 10_000_000_000L) urlTestTime else urlTestTime * 1000L
        return timeMs + PROBE_TIME_SKEW_MS >= wallStartMs
    }

    /** Every selected outbound must earn a new pass. A stale green delay is not proof. */
    private fun armProbeNeeds() {
        probeNeeds = lastTargets.map { ProbeNeed(it.groupTag, it.probeTag) }
    }

    private fun liveProbePassed(targets: List<Target>): Boolean {
        val need = probeNeeds
        val wall = probeWallStartMs
        if (need.isEmpty() || wall <= 0L) return false
        return need.all { n ->
            targets.any {
                it.groupTag == n.groupTag && it.probeTag == n.probeTag && it.provedSince(wall)
            }
        }
    }

    private fun parseTargets(groups: List<OutboundGroup>): List<Target> {
        val nowMs = System.currentTimeMillis()
        val out = ArrayList<Target>(2)
        for (group in groups) {
            if (group.type != URLTEST_GROUP_TYPE) continue
            val items = group.items ?: continue
            val selected = group.selected.orEmpty()
            var count = 0
            var anyHealthy = false
            var selectedDelay = -1
            var selectedTime = 0L
            while (items.hasNext()) {
                val item = items.next()
                count++
                val delay = item.urlTestDelay
                if (delay > 0 && delay < FAILURE_DELAY_MS) anyHealthy = true
                if (selected.isNotEmpty() && item.tag == selected) {
                    selectedDelay = delay
                    selectedTime = item.urlTestTime
                }
            }
            if (count == 0) continue
            // Judge the outbound the group is using. Other nodes don't vote: a healthy spare
            // must not hide a dead selection, and a dead spare must not force a toggle.
            val historyHealthy = if (selectedDelay >= 0) {
                selectedDelay > 0 && selectedDelay < FAILURE_DELAY_MS
            } else {
                anyHealthy
            }
            val timed = if (selectedDelay >= 0) selectedTime > 0L else false
            val probeTag = if (selectedDelay >= 0 && selected.isNotEmpty()) selected else group.tag
            out += Target(
                groupTag = group.tag,
                probeTag = probeTag,
                historyHealthy = historyHealthy,
                historyFresh = if (timed) historyFresh(selectedTime, nowMs) else false,
                urlTestTime = if (selectedDelay >= 0) selectedTime else 0L,
            )
        }
        return out
    }

    /** libbox has shipped this as unix seconds and as unix millis; accept both. */
    private fun historyFresh(urlTestTime: Long, nowMs: Long): Boolean {
        if (urlTestTime <= 0L) return false
        val ageMs = if (urlTestTime > 10_000_000_000L) {
            nowMs - urlTestTime
        } else {
            nowMs - urlTestTime * 1000L
        }
        return ageMs in 0..FRESH_URLTEST_MS
    }

    private fun onGroupsUpdated(groups: List<OutboundGroup>) {
        val now = SystemClock.elapsedRealtime()
        lastSnapshotAt = now
        val targets = parseTargets(groups)
        if (targets.isNotEmpty()) {
            lastTargets = targets
            selectedProbeTags = targets.map { it.probeTag }
            urlTestGroupTags = targets.map { it.groupTag }
            // No timestamp is not "fresh". A frozen healthy delay must not slow stall detection.
            lastUrlTestFresh = targets.all { it.historyHealthy && it.historyFresh }
        }

        if (!recoveryEnabled() || !BoxService.serviceWanted || isDeviceIdle()) {
            failingSince = 0L
            if (isDeviceIdle()) {
                stallSince = 0L
                resetFastFail()
            }
            return
        }
        if (targets.isEmpty()) {
            failingSince = 0L
            return
        }

        val allHealthy = targets.all { it.historyHealthy }
        val anyFreshHealthy = targets.any { it.historyHealthy && it.historyFresh }
        // A spare urltest that is down must not force a toggle while another group just passed.
        // A spare that is merely stale must not hide a selected outbound that is actually failing:
        // only a fresh pass counts as "something still works".
        if (allHealthy || anyFreshHealthy) {
            failingSince = 0L
            val fromLiveProbe = toggling.get() &&
                probeAcceptAfter != Long.MAX_VALUE &&
                now >= probeAcceptAfter &&
                liveProbePassed(targets)
            // While a probe is in flight, only a delay stamped by the test we just ran counts.
            // The periodic groups tick otherwise re-sends the previous delay and would hide a
            // hung tunnel that still has an old successful number.
            if (fromLiveProbe || (!toggling.get() && anyFreshHealthy)) {
                lastHealthyAt = now
            }
            if (!toggling.get() && anyFreshHealthy) {
                startupGraceUntil = 0L
                suppressUrlTestUntil = 0L
            }
            if ((anyFreshHealthy || fromLiveProbe) && recoveryAttempts.getAndSet(0) > 0) {
                Log.i(TAG, "url test passing again, attempt counter reset")
            }
            return
        }

        // A recovery attempt is running (probe / toggle): it owns the state, don't count on top.
        if (toggling.get()) return
        if (now < startupGraceUntil || now < cooldownUntil || now < suppressUrlTestUntil) {
            failingSince = 0L
            return
        }
        // A recorded failure is old news while real data is arriving: a download crawling over
        // a weak signal can starve the URL test without the path being dead.
        if (downlinkFlowing(now)) {
            failingSince = 0L
            return
        }

        // Debounce on elapsed time, not on the number of snapshots.
        if (failingSince == 0L) {
            failingSince = now
            Log.i(TAG, "selected url test failing, recovery in ${debounceMs() / 1000}s if it persists")
        }
        if (now - failingSince < debounceMs()) return

        val failingFor = now - failingSince
        failingSince = 0L
        if (isAirplaneModeOn()) return
        trigger("urltest_fail_${failingFor / 1000}s")
    }

    private fun onStatus(status: StatusMessage) {
        // Opportunistic: the status tick also expires the disconnect timer if the callback
        // was missed. evaluateLink() is cheap when the network is up.
        evaluateLink()
        if (!recoveryEnabled() || !BoxService.serviceWanted || isDeviceIdle()) {
            stallSince = 0L
            resetFastFail()
            return
        }
        if (!status.trafficAvailable) return

        val now = SystemClock.elapsedRealtime()
        val down = status.downlinkTotal
        // Always feed the meter, also while a recovery attempt is running.
        trackDownlink(now, down)

        // Probe traffic must not clear a stall or the hold that follows a passed check.
        if (toggling.get()) return

        val conns = status.connectionsIn.toLong()

        if (reanchorTraffic || !trafficPrimed || down < prevDown) {
            stallSince = 0L
            stallAnchorDown = down
            prevDown = down
            prevConns = conns
            trafficPrimed = true
            reanchorTraffic = false
            resetFastFail()
            return
        }

        val prevConn = prevConns
        prevDown = down
        prevConns = conns
        val downlinkGained = (down - stallAnchorDown).coerceAtLeast(0L)

        // A socket that dies after a moment of silence was stuck, not a download that finished.
        // Finished downloads clear stallSince when downlink moves, so they never get here.
        if (stallSince != 0L &&
            now - stallSince >= FAST_FAIL_MIN_SILENCE_MS &&
            conns < prevConn &&
            downlinkGained < STALL_PROGRESS_BYTES &&
            now >= startupGraceUntil
        ) {
            noteFastFail(now, (prevConn - conns).toInt())
        }

        if (conns <= 0L) {
            val stalledFor = if (stallSince == 0L) 0L else now - stallSince
            stallSince = 0L
            stallAnchorDown = down
            if (now < startupGraceUntil || now < cooldownUntil || now < stallRearmAt || isAirplaneModeOn()) {
                return
            }
            if (stalledFor >= STALL_DROP_MIN_MS && downlinkFlowing(now, STALL_ACTIVITY_WINDOW_MS)) {
                Log.w(TAG, "open connections died after ${stalledFor}ms with no downlink")
                trigger("stall_drop_${stalledFor / 1000}s")
                return
            }
            if (fastFailReady(now)) {
                val n = fastFailCount
                resetFastFail()
                Log.w(TAG, "connections closed $n times with no downlink")
                trigger("stall_fast_fail_$n")
            }
            return
        }

        if (stallSince != 0L && downlinkIsAlive(now, downlinkGained)) {
            stallSince = 0L
            stallAnchorDown = down
            resetFastFail()
            return
        }

        if (stallSince == 0L) {
            stallSince = now
            stallAnchorDown = down
            return
        }
        if (now < startupGraceUntil || now < cooldownUntil || now < stallRearmAt) return

        if (fastFailReady(now)) {
            val n = fastFailCount
            resetFastFail()
            stallSince = 0L
            stallAnchorDown = down
            Log.w(TAG, "connections failed fast with no downlink ($n) while $conns still open")
            trigger("stall_fast_fail_$n")
            return
        }

        val window = effectiveStallWindowMs()
        if (now - stallSince < window) return

        // Pure stall (no fast-fail evidence) only when there was real traffic recently.
        // Long-idle open sockets are normal and must not force airplane recovery.
        if (!downlinkFlowing(now, STALL_ACTIVITY_WINDOW_MS)) {
            stallSince = 0L
            stallAnchorDown = down
            return
        }

        stallSince = 0L
        stallAnchorDown = down
        if (isAirplaneModeOn()) return
        Log.w(TAG, "downlink stalled ${window}ms with $conns open connections")
        trigger("stall_${window / 1000}s")
    }

    /** Enough downlink, fast enough, to call the open connections alive. */
    private fun downlinkIsAlive(now: Long, gained: Long): Boolean {
        if (gained < STALL_PROGRESS_BYTES) return false
        val elapsed = (now - stallSince).coerceAtLeast(1L)
        return gained * 1000L >= STALL_MIN_RATE_BPS * elapsed
    }

    private fun noteFastFail(now: Long, dropped: Int) {
        if (dropped <= 0) return
        if (fastFailWindowStart == 0L || now - fastFailWindowStart > FAST_FAIL_WINDOW_MS) {
            fastFailWindowStart = now
            fastFailCount = 0
        }
        fastFailCount += dropped.coerceAtMost(8)
    }

    private fun resetFastFail() {
        fastFailWindowStart = 0L
        fastFailCount = 0
    }

    /** True once several fast failures have been seen across more than one sample. */
    private fun fastFailReady(now: Long): Boolean {
        if (fastFailCount < FAST_FAIL_MIN || fastFailWindowStart == 0L) return false
        val age = now - fastFailWindowStart
        return age in 1_500L..FAST_FAIL_WINDOW_MS
    }

    /**
     * Feeds the real-traffic meter. [PROGRESS_BYTES] gained inside one [PROGRESS_WINDOW_MS]
     * window is real downlink; a trickle of retries or a single 1 KB blip never adds up to it.
     */
    private fun trackDownlink(now: Long, down: Long) {
        val prev = meterDown
        meterDown = down
        // First sample, or the core's counters restarted: nothing to compare against.
        if (prev < 0L || down < prev) {
            meterBytes = 0L
            meterWindowStart = now
            return
        }
        if (meterWindowStart == 0L) meterWindowStart = now
        meterBytes += down - prev
        if (meterBytes >= PROGRESS_BYTES) {
            lastProgressAt = now
            meterBytes = 0L
            meterWindowStart = now
        } else if (now - meterWindowStart >= PROGRESS_WINDOW_MS) {
            meterBytes = 0L
            meterWindowStart = now
        }
    }

    /** True when real downlink was seen within the last [withinMs]. */
    private fun downlinkFlowing(now: Long, withinMs: Long = RECENT_PROGRESS_MS): Boolean {
        val at = lastProgressAt
        return at != 0L && now - at in 0..withinMs
    }

    /** Downlink bytes since [base] (a [meterDown] reading); 0 when the baseline is unusable. */
    private fun downlinkSince(base: Long): Long {
        val current = meterDown
        return if (base < 0L || current < base) 0L else current - base
    }

    /**
     * Real data arrived since [base] and is still arriving. Both conditions: a burst that
     * flushed a buffer and then went quiet again is not proof the path works.
     */
    private fun downlinkProved(base: Long): Boolean =
        downlinkSince(base) >= PROBE_ALIVE_BYTES && downlinkFlowing(SystemClock.elapsedRealtime())

    /** Any of: a new URL-test pass, a delay stamped by this check, or data moving. */
    private fun pathProved(probeStartedAt: Long, downBase: Long, tunnelOk: Boolean): Boolean =
        tunnelOk || lastHealthyAt >= probeStartedAt || downlinkProved(downBase)

    private fun failureThreshold(): Int = Settings.airplaneRecoveryFailureThreshold.coerceIn(1, 20)

    /** How long every selected URL test must keep failing: `threshold - 1` seconds. */
    private fun debounceMs(): Long = (failureThreshold() - 1) * 1000L

    /** How long open connections must sit with no downlink before a stall probe. */
    private fun stallWindowMs(): Long = maxOf(debounceMs(), STALL_MIN_WINDOW_MS)

    /**
     * The window actually used. After a fresh healthy URL test it is the configured one. When
     * the last test is stale or failing it is halved, but never below [FAST_STALL_MS] (and never
     * above the configured window), so a quiet weak link is not probed after a second.
     */
    private fun effectiveStallWindowMs(): Long {
        val window = stallWindowMs()
        if (lastUrlTestFresh) return window
        return minOf(window, maxOf(FAST_STALL_MS, window / 2))
    }

    private fun trigger(reason: String) {
        if (!toggling.compareAndSet(false, true)) return
        recoveryJob = scope.launch {
            try {
                runRecovery(reason)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "airplane recovery failed", e)
            } finally {
                probeAcceptAfter = Long.MAX_VALUE
                probeWallStartMs = 0L
                probeNeeds = emptyList()
                // The next status sample rebases counters so probe bytes are ignored
                // and cannot clear stallRearmAt.
                reanchorTraffic = true
                toggling.set(false)
            }
        }
    }

    private suspend fun runRecovery(reason: String) {
        // Active only while the service is on: the service may have stopped between the trigger
        // and this coroutine actually running.
        if (!isWatching()) return
        if (isAirplaneModeOn()) return

        if (reason.startsWith("link_lost")) {
            delay(DISCONNECT_RECHECK_MS)
            if (!isWatching() || isAirplaneModeOn()) return
            if (systemHasUnderlying()) {
                Log.i(TAG, "link back before toggle, skip ($reason)")
                linkLostSince = 0L
                return
            }
            // The network has been gone for seconds, yet data is still arriving: the ROM is
            // just not reporting the real network. Nothing to recover.
            if (downlinkFlowing(SystemClock.elapsedRealtime(), LINK_LOST_TRAFFIC_MS)) {
                Log.i(TAG, "no underlying network reported but downlink is moving, skip ($reason)")
                return
            }
            if (!hasRoot()) {
                Log.w(TAG, "root not available, skip airplane toggle")
                enterCooldown()
                return
            }
            if (!isWatching() || systemHasUnderlying()) return
            val attempt = recoveryAttempts.incrementAndGet()
            Log.w(TAG, "executing airplane toggle: $reason (attempt $attempt, no underlying network)")
            ServiceNotification.announce(ServiceNotification.LinkNotice.AIRPLANE_RECOVERY)
            StartTrace.mark("AIRPLANE TOGGLE begin: $reason")
            try {
                executeAirplaneToggle()
            } finally {
                enterCooldown()
            }
            return
        }

        // Stall / URL test: history may be stale. Confirm with a live check first.
        // With a urltest group, only a new pass of every selected outbound counts.
        armProbeNeeds()
        val probeStartedAt = SystemClock.elapsedRealtime()
        // Downlink counter at the start of the check, to tell whether data moved during it.
        val downBase = meterDown
        val (radioOk, tunnelOk) = probeLinkAndTunnel()
        if (!isWatching()) return
        if (isAirplaneModeOn()) return

        val hadTunnelSignal = probeNeeds.isNotEmpty() ||
            selectedProbeTags.isNotEmpty() ||
            urlTestGroupTags.isNotEmpty() ||
            Settings.serviceMode == ServiceMode.VPN
        // Radio alone is not enough: "connected, no useful path" is the common bengong case.
        // tunnelOk is a new urltest pass, or (only when there is no urltest) a TCP probe.
        // Real downlink during the check is proof too: on a weak signal the URL test can time
        // out while a download is still crawling along.
        var pathOk = pathProved(probeStartedAt, downBase, tunnelOk)
        if (!pathOk && radioOk && hadTunnelSignal && downlinkSince(downBase) >= GRACE_MIN_BYTES) {
            // The physical network answers and some data is still trickling in, so this looks
            // slow rather than dead. Keep watching a little longer for a late URL-test pass or
            // for the data to pick up. With no data at all there is nothing to wait for.
            Log.i(TAG, "tunnel check missed but link is trickling, watching ${GRACE_WATCH_MS}ms ($reason)")
            val watchUntil = SystemClock.elapsedRealtime() + GRACE_WATCH_MS
            while (SystemClock.elapsedRealtime() < watchUntil) {
                delay(200)
                if (!isWatching() || isAirplaneModeOn()) return
                if (pathProved(probeStartedAt, downBase, false)) {
                    pathOk = true
                    break
                }
            }
        }
        if (pathOk || (!hadTunnelSignal && radioOk)) {
            Log.i(
                TAG,
                "live check passed, skip airplane ($reason) radio=$radioOk tunnel=$tunnelOk " +
                    "signal=$hadTunnelSignal down=${downlinkSince(downBase)}B",
            )
            failingSince = 0L
            stallSince = 0L
            stallRearmAt = SystemClock.elapsedRealtime() + STALL_RECHECK_MS
            suppressUrlTestUntil = stallRearmAt
            return
        }

        if (!hasRoot()) {
            Log.w(TAG, "root not available, skip airplane toggle")
            enterCooldown()
            return
        }
        if (!isWatching()) return
        // The URL test can finish just after the wait gave up, or data can start moving while the
        // root check runs. Don't toggle over a pass.
        if (pathProved(probeStartedAt, downBase, false)) {
            Log.i(TAG, "connection recovered before toggle, skip ($reason)")
            ServiceNotification.announce(ServiceNotification.LinkNotice.RESTORED)
            failingSince = 0L
            stallRearmAt = SystemClock.elapsedRealtime() + STALL_RECHECK_MS
            suppressUrlTestUntil = stallRearmAt
            return
        }

        val attempt = recoveryAttempts.incrementAndGet()
        Log.w(
            TAG,
            "executing airplane toggle: $reason (attempt $attempt, radio=$radioOk tunnel=$tunnelOk " +
                "down=${downlinkSince(downBase)}B)",
        )
        ServiceNotification.announce(ServiceNotification.LinkNotice.AIRPLANE_RECOVERY)
        StartTrace.mark("AIRPLANE TOGGLE begin: $reason")
        try {
            executeAirplaneToggle()
        } finally {
            enterCooldown()
        }
    }

    /**
     * Cooldown length: the live setting (1–900 s, never below the post-recovery grace so the
     * modem can re-register). It is the same for every attempt, no matter how many recoveries in
     * a row did not help.
     */
    private fun cooldownMs(): Long =
        maxOf(Settings.airplaneRecoveryCooldownSec.coerceIn(1, 900) * 1000L, POST_RECOVERY_GRACE_MS)

    private fun enterCooldown() {
        failingSince = 0L
        stallSince = 0L
        val now = SystemClock.elapsedRealtime()
        cooldownStartedAt = now
        cooldownUntil = now + cooldownMs()
        stallRearmAt = cooldownUntil
        suppressUrlTestUntil = cooldownUntil
    }

    /** Radio probe and tunnel probe in parallel; either one finishing early is not cancelled. */
    private suspend fun probeLinkAndTunnel(): Pair<Boolean, Boolean> = coroutineScope {
        val radio = async { probeRadio() }
        val tunnel = async { probeTunnel() }
        Pair(radio.await(), tunnel.await())
    }

    /**
     * TCP connect on the physical network, bypassing the VPN. Succeeds only when the radio
     * (or wifi) can actually carry a packet — "connected, no internet" fails here.
     * Uses IP addresses so the result is independent of DNS.
     */
    private suspend fun probeRadio(): Boolean {
        val network = pickUnderlyingNetwork() ?: return false
        return tcpProbe(network, RADIO_PROBE_TIMEOUT_MS, RADIO_PROBE_HOSTS)
    }

    /**
     * True when a check started just now shows the selected outbound can move data.
     * URL-test history is not consulted. A TCP connect is used only when there is no
     * urltest selection: otherwise it follows routing rules and a direct/CDN host can
     * answer while the outbound the user is actually on is stuck.
     */
    private suspend fun probeTunnel(): Boolean = coroutineScope {
        val wallStart = System.currentTimeMillis()
        probeWallStartMs = wallStart
        val need = probeNeeds
        val tags = need.map { it.probeTag }.distinct()
        val url = async {
            if (need.isEmpty()) {
                false
            } else {
                probeUrlTests(tags)
                val passed = liveProbePassed(lastTargets)
                if (!passed) {
                    val detail = lastTargets.joinToString { t ->
                        "${t.groupTag}/${t.probeTag} healthy=${t.historyHealthy} time=${t.urlTestTime}"
                    }
                    Log.i(TAG, "selected url test did not prove a new pass ($detail)")
                }
                passed
            }
        }
        val tcp = async {
            if (need.isEmpty() && Settings.serviceMode == ServiceMode.VPN) {
                tcpProbe(null, TUNNEL_TCP_TIMEOUT_MS, TUNNEL_PROBE_HOSTS)
            } else {
                false
            }
        }
        var urlOk = false
        var tcpOk = false
        while (isActive) {
            if (tcp.isCompleted) tcpOk = tcp.await()
            if (url.isCompleted) urlOk = url.await()
            if (tcpOk || urlOk) break
            if (tcp.isCompleted && url.isCompleted) break
            delay(40)
        }
        // TCP must not mark the tunnel healthy when a urltest selection exists.
        if (tcpOk && need.isEmpty()) lastHealthyAt = SystemClock.elapsedRealtime()
        tcpOk || urlOk
    }

    /**
     * Asks sing-box to re-test the selected outbounds, then waits until the groups stream has
     * delivered a snapshot taken after them.
     */
    private suspend fun probeUrlTests(tags: List<String>) {
        probeAcceptAfter = Long.MAX_VALUE
        if (tags.isNotEmpty()) {
            // The native call blocks until the test finishes and cannot be interrupted, so it
            // runs detached: the timeout only stops us from waiting for it.
            withTimeoutOrNull(PROBE_TIMEOUT_MS) {
                tags.map { tag ->
                    scope.async {
                        try {
                            Libbox.newStandaloneCommandClient().urlTest(tag)
                        } catch (e: Exception) {
                            Log.w(TAG, "urlTest($tag) failed: ${e.message}")
                        }
                    }
                }.awaitAll()
            }
        }
        val testsDoneAt = SystemClock.elapsedRealtime()
        probeAcceptAfter = testsDoneAt + SNAPSHOT_MARGIN_MS
        val deadline = testsDoneAt + WATCH_INTERVAL_MS + PROBE_SETTLE_MS
        // Keep waiting until a delay stamped by this probe shows up. Returning on the
        // first snapshot after the test was wrong: that snapshot is often the previous
        // healthy number, still inside the freshness window.
        while (SystemClock.elapsedRealtime() < deadline) {
            if (liveProbePassed(lastTargets)) return
            delay(SNAPSHOT_POLL_MS)
        }
    }

    private suspend fun tcpProbe(
        bind: Network?,
        timeoutMs: Int,
        hosts: List<String> = RADIO_PROBE_HOSTS,
    ): Boolean = coroutineScope {
        val jobs = hosts.map { host ->
            async(Dispatchers.IO) { connectOnce(bind, host, 443, timeoutMs) }
        }
        val deadline = SystemClock.elapsedRealtime() + timeoutMs + 400L
        var ok = false
        while (isActive && SystemClock.elapsedRealtime() < deadline) {
            for (job in jobs) {
                if (!job.isCompleted) continue
                val success = try {
                    job.await()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    false
                }
                if (success) {
                    ok = true
                    break
                }
            }
            if (ok || jobs.all { it.isCompleted }) break
            delay(40)
        }
        jobs.forEach { it.cancel() }
        ok
    }

    private fun connectOnce(bind: Network?, host: String, port: Int, timeoutMs: Int): Boolean {
        var socket: Socket? = null
        return try {
            socket = if (bind != null) bind.socketFactory.createSocket() else Socket()
            socket.soTimeout = timeoutMs
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            true
        } catch (e: Exception) {
            false
        } finally {
            try {
                socket?.close()
            } catch (e: Exception) {
                // already closed
            }
        }
    }

    // region underlying link (disconnect)

    private fun startLinkMonitor() {
        if (linkCallback != null) return
        underlyingNetworks.clear()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val caps = try {
                    Application.connectivity.getNetworkCapabilities(network)
                } catch (e: Exception) {
                    null
                }
                // The VPN itself also matches a plain INTERNET request. Only a real
                // wifi/cell/ethernet network means the drop is over.
                if (caps != null && isUnderlying(caps)) {
                    underlyingNetworks.add(network)
                    everSawUnderlying = true
                    linkLostSince = 0L
                }
            }

            override fun onLost(network: Network) {
                underlyingNetworks.remove(network)
                evaluateLink()
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                if (isUnderlying(networkCapabilities)) {
                    underlyingNetworks.add(network)
                    everSawUnderlying = true
                    linkLostSince = 0L
                } else {
                    underlyingNetworks.remove(network)
                    evaluateLink()
                }
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try {
            Application.connectivity.registerNetworkCallback(request, callback)
            linkCallback = callback
        } catch (e: Exception) {
            Log.w(TAG, "link monitor failed, polling only (deprecated allNetworks fallback)", e)
        }
        linkJob?.cancel()
        linkJob = scope.launch {
            while (isActive) {
                evaluateLink()
                delay(LINK_POLL_MS)
            }
        }
    }

    private fun stopLinkMonitor() {
        linkJob?.cancel()
        linkJob = null
        underlyingNetworks.clear()
        val callback = linkCallback ?: return
        linkCallback = null
        try {
            Application.connectivity.unregisterNetworkCallback(callback)
        } catch (e: Exception) {
            Log.w(TAG, "link monitor unregister failed", e)
        }
    }

    private fun evaluateLink() {
        if (!isWatching() || toggling.get() || isAirplaneModeOn()) return
        // Doze pauses the core, but a vanished wifi/cell network is still a real drop.
        if (systemHasUnderlying()) {
            everSawUnderlying = true
            if (linkLostSince != 0L) Log.i(TAG, "underlying network is back")
            linkLostSince = 0L
            return
        }
        // Never observed a real network: this is not a drop (or the ROM is only exposing the VPN).
        if (!everSawUnderlying) return
        val now = SystemClock.elapsedRealtime()
        if (now < cooldownUntil) return
        if (linkLostSince == 0L) {
            linkLostSince = now
            Log.w(TAG, "no underlying network, airplane recovery in ${DISCONNECT_DEBOUNCE_MS / 1000}s if it stays down")
            return
        }
        if (now - linkLostSince < DISCONNECT_DEBOUNCE_MS) return
        // Require a fresh debounce before a second shot. Cooldown covers the gap after a toggle.
        linkLostSince = now
        trigger("link_lost")
    }

    /**
     * True when at least one non-VPN wifi/cell/ethernet/bt network is known to be up.
     *
     * Preferred path: the live set maintained by [linkCallback] (no deprecated APIs).
     * Fallback (only when registration failed): one-shot [ConnectivityManager.allNetworks]
     * snapshot so the poll loop still works on broken ROMs.
     */
    private fun systemHasUnderlying(): Boolean {
        if (linkCallback != null) return underlyingNetworks.isNotEmpty()
        return allNetworksHasUnderlying()
    }

    private fun isUnderlying(caps: NetworkCapabilities): Boolean {
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return false
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)
    }

    /**
     * Best underlying network for a radio-side TCP probe.
     * Same preference order as before (validated > wifi).
     */
    private fun pickUnderlyingNetwork(): Network? {
        if (linkCallback != null) {
            return underlyingNetworks.maxByOrNull { network -> scoreUnderlying(network) }
        }
        return allNetworksPickUnderlying()
    }

    private fun scoreUnderlying(network: Network): Int {
        val caps = try {
            Application.connectivity.getNetworkCapabilities(network)
        } catch (e: Exception) {
            null
        }
        var score = 0
        if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true) score += 2
        if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) score += 1
        return score
    }

    // Deprecated fallback only used when NetworkCallback registration failed.
    @Suppress("DEPRECATION")
    private fun allNetworksHasUnderlying(): Boolean = try {
        val cm = Application.connectivity
        cm.allNetworks.any { network ->
            val caps = cm.getNetworkCapabilities(network) ?: return@any false
            isUnderlying(caps)
        }
    } catch (e: Exception) {
        false
    }

    @Suppress("DEPRECATION")
    private fun allNetworksPickUnderlying(): Network? = try {
        val cm = Application.connectivity
        cm.allNetworks
            .filter { network ->
                val caps = cm.getNetworkCapabilities(network) ?: return@filter false
                isUnderlying(caps)
            }
            .maxByOrNull { scoreUnderlying(it) }
    } catch (e: Exception) {
        null
    }

    // endregion

    // region airplane mode control (root)

    private fun hasRoot(): Boolean = try {
        RootClient.shell().isRoot
    } catch (e: Exception) {
        Log.w(TAG, "root shell unavailable", e)
        false
    }

    private fun runRoot(vararg commands: String): Boolean = try {
        val result = RootClient.shell().newJob().add(*commands).exec()
        if (!result.isSuccess) {
            Log.w(TAG, "root command failed (code=${result.code}): ${commands.joinToString(" ; ")}")
        }
        result.isSuccess
    } catch (e: Exception) {
        Log.w(TAG, "root command error: ${commands.joinToString(" ; ")}", e)
        false
    }

    private fun isAirplaneModeOn(): Boolean = try {
        AndroidSettings.Global.getInt(
            Application.application.contentResolver,
            AndroidSettings.Global.AIRPLANE_MODE_ON,
            0,
        ) != 0
    } catch (e: Exception) {
        false
    }

    private suspend fun awaitAirplaneState(expected: Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + STATE_CHANGE_TIMEOUT_MS
        while (true) {
            if (isAirplaneModeOn() == expected) return true
            if (SystemClock.elapsedRealtime() >= deadline) return false
            delay(100)
        }
    }

    /**
     * Switches airplane mode and verifies the global setting actually changed.
     * Android 11+ goes through ConnectivityService (`cmd connectivity airplane-mode`), which is
     * the same path the system UI uses; older releases, or ROMs where that command is missing,
     * fall back to writing the setting and broadcasting the change.
     */
    private suspend fun setAirplaneMode(enabled: Boolean): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runRoot("cmd connectivity airplane-mode ${if (enabled) "enable" else "disable"}")
            if (awaitAirplaneState(enabled)) return true
        }
        runRoot(
            "settings put global airplane_mode_on ${if (enabled) 1 else 0}",
            "am broadcast -a android.intent.action.AIRPLANE_MODE --ez state $enabled",
        )
        return awaitAirplaneState(enabled)
    }

    private suspend fun executeAirplaneToggle() {
        if (isAirplaneModeOn()) {
            Log.i(TAG, "airplane mode already on, skip")
            return
        }
        try {
            if (!setAirplaneMode(true)) {
                Log.e(TAG, "could not turn airplane mode on")
            }
            delay(AIRPLANE_OFF_DELAY_MS)
        } finally {
            // Whatever happened above (error, cancellation), never leave the phone offline.
            withContext(NonCancellable) { leaveAirplaneMode() }
        }
        // Clear stale DNS so browsers stop showing "DNS gagal / diblokir" after the radio
        // comes back. Best-effort; failures are ignored.
        flushDnsCaches()
        Log.i(TAG, "airplane toggle finished")
        StartTrace.mark("AIRPLANE TOGGLE finished")
    }

    private suspend fun leaveAirplaneMode() {
        repeat(3) {
            if (!isAirplaneModeOn()) return
            if (setAirplaneMode(false)) return
            delay(500)
        }
        if (isAirplaneModeOn()) {
            Log.e(TAG, "FAILED to turn airplane mode off, manual action needed")
        }
    }

    /**
     * Best-effort DNS cache flush after airplane recovery. Covers common Android netd paths
     * so resolvers do not keep serving NXDOMAIN / timeout results from the stuck period.
     * Commands are run individually so a missing binary on one ROM does not abort the rest.
     */
    private fun flushDnsCaches() {
        val cmds = listOf(
            "cmd netd resolver flush_cache",
            "ndc resolver flushdefaultif",
            "ndc resolver flushnet wlan0",
            "ndc resolver flushnet rmnet0",
            "ndc resolver flushnet rmnet_data0",
            "ndc resolver flushnet ccmni0",
        )
        var any = false
        for (cmd in cmds) {
            if (runRoot(cmd)) any = true
        }
        if (any) {
            Log.i(TAG, "dns cache flush requested")
        } else {
            Log.w(TAG, "dns cache flush: no command succeeded")
        }
    }

    // endregion

    /** Groups stream + status stream, each with its own reconnect while the session is open. */
    private class Session {
        @Volatile private var closed = false

        private val groupsReconnect = Reconnect()
        private val statusReconnect = Reconnect()

        private val groupsClient = CommandClient(
            scope,
            CommandClient.ConnectionType.Groups,
            GroupsHandler(),
            localOnly = true,
            statusIntervalMs = WATCH_INTERVAL_MS,
        )
        private val statusClient = CommandClient(
            scope,
            CommandClient.ConnectionType.Status,
            StatusHandler(),
            localOnly = true,
            statusIntervalMs = STATUS_INTERVAL_MS,
        )

        fun connect() {
            if (closed) return
            groupsClient.connect()
            statusClient.connect()
        }

        fun close() {
            closed = true
            groupsReconnect.cancel()
            statusReconnect.cancel()
            groupsClient.disconnect()
            statusClient.disconnect()
        }

        private inner class GroupsHandler : CommandClient.Handler {
            override fun onConnected() {
                groupsReconnect.delayMs = RECONNECT_MIN_DELAY_MS
                Log.i(TAG, "watching url-test groups (tick ${WATCH_INTERVAL_MS / 1000}s)")
            }

            override fun onDisconnected() {
                schedule(groupsClient, groupsReconnect)
            }

            override fun onConnectionError(message: String) {
                Log.w(TAG, "groups stream error: $message")
                schedule(groupsClient, groupsReconnect)
            }

            override fun updateGroups(newGroups: MutableList<OutboundGroup>) {
                if (closed) return
                try {
                    onGroupsUpdated(newGroups)
                } catch (e: Exception) {
                    Log.w(TAG, "group update failed", e)
                }
            }
        }

        private inner class StatusHandler : CommandClient.Handler {
            override fun onConnected() {
                statusReconnect.delayMs = RECONNECT_MIN_DELAY_MS
                Log.i(TAG, "watching traffic (tick ${STATUS_INTERVAL_MS / 1000}s)")
            }

            override fun onDisconnected() {
                schedule(statusClient, statusReconnect)
            }

            override fun onConnectionError(message: String) {
                Log.w(TAG, "status stream error: $message")
                schedule(statusClient, statusReconnect)
            }

            override fun updateStatus(status: StatusMessage) {
                if (closed) return
                try {
                    onStatus(status)
                } catch (e: Exception) {
                    Log.w(TAG, "status update failed", e)
                }
            }
        }

        private fun schedule(client: CommandClient, reconnect: Reconnect) {
            if (closed) return
            synchronized(reconnect) {
                if (closed || reconnect.job?.isActive == true) return
                val delayMs = reconnect.delayMs
                reconnect.delayMs = minOf(delayMs * 2, RECONNECT_MAX_DELAY_MS)
                reconnect.job = scope.launch {
                    delay(delayMs)
                    if (!closed) client.connect()
                }
            }
        }
    }

    private class Reconnect {
        var delayMs = RECONNECT_MIN_DELAY_MS
        var job: Job? = null

        fun cancel() {
            synchronized(this) {
                job?.cancel()
                job = null
            }
        }
    }
}
