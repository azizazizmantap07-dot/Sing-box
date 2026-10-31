package com.liquid.box.compose.component

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.liquid.box.compose.theme.GlassPopupTheme
import com.liquid.box.compose.theme.GlassShapes
import com.liquid.box.compose.theme.OnGlassPrimary
import com.liquid.box.compose.theme.liquidGlass
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Drop-in replacement for `android.widget.Toast`, drawn as the same popup glass card as the
 * dialogs, menus and snackbars (popup fill, double specular rim, top shine, [GlassPopupTheme]).
 *
 * The system toast is drawn by the OS outside of the app, so it can not be styled; this one is
 * drawn by [GlassToastHost] in the app's own window. Call sites only swap the class:
 *
 *     GlassToast.makeText(context, R.string.copied_to_clipboard, GlassToast.LENGTH_SHORT).show()
 *
 * - Safe to call from any thread.
 * - A new toast replaces the one on screen (no queue that keeps showing old messages).
 * - When no [GlassToastHost] is on screen (broadcast receiver, service, app in the background)
 *   nothing of the app can draw the glass card, so it falls back to the system toast and the
 *   message is never lost.
 */
object GlassToast {
    const val LENGTH_SHORT = Toast.LENGTH_SHORT
    const val LENGTH_LONG = Toast.LENGTH_LONG

    private const val SHORT_MILLIS = 2_000L
    private const val LONG_MILLIS = 3_500L

    /** A message on screen. [id] is unique per call, so showing the same text twice is a new entry. */
    internal data class Entry(val id: Long, val message: String)

    /** Returned by [makeText]; call [show] to display it (same shape as `Toast`). */
    class Handle internal constructor(
        private val context: Context,
        private val message: CharSequence?,
        private val duration: Int,
    ) {
        fun show() {
            GlassToast.post(context, message, duration)
        }
    }

    fun makeText(context: Context, text: CharSequence?, duration: Int): Handle = Handle(context, text, duration)

    fun makeText(context: Context, @StringRes resId: Int, duration: Int): Handle = Handle(context, context.getText(resId), duration)

    // Everything below is touched on the main thread only.
    private val mainHandler = Handler(Looper.getMainLooper())
    private val _current = MutableStateFlow<Entry?>(null)
    internal val current: StateFlow<Entry?> = _current.asStateFlow()
    private val clearCurrent = Runnable { _current.value = null }
    private var hostCount = 0
    private var lastId = 0L

    private fun post(context: Context, message: CharSequence?, duration: Int) {
        val text = message?.toString().orEmpty()
        if (text.isEmpty()) return
        val appContext = context.applicationContext ?: context
        if (Looper.myLooper() == Looper.getMainLooper()) {
            dispatch(appContext, text, duration)
        } else {
            mainHandler.post { dispatch(appContext, text, duration) }
        }
    }

    private fun dispatch(context: Context, text: String, duration: Int) {
        if (hostCount <= 0) {
            Toast.makeText(context, text, duration).show()
            return
        }
        _current.value = Entry(++lastId, text)
        mainHandler.removeCallbacks(clearCurrent)
        mainHandler.postDelayed(clearCurrent, if (duration == LENGTH_LONG) LONG_MILLIS else SHORT_MILLIS)
    }

    internal fun attachHost() {
        hostCount++
    }

    internal fun detachHost() {
        hostCount = (hostCount - 1).coerceAtLeast(0)
        if (hostCount == 0) {
            // Nothing can show it any more; do not let it pop up later when the app comes back.
            mainHandler.removeCallbacks(clearCurrent)
            _current.value = null
        }
    }
}

/**
 * Draws [GlassToast] messages. Put it once at the root of an activity's Compose content, inside the
 * app theme (`Theme { App(); GlassToastHost() }`).
 *
 * The card lives in its own window, not in the layout: a toast copied from inside a dialog or a
 * bottom sheet (those are windows too) is then still drawn above them. The window is created only
 * while a toast is visible, never takes focus or touches, and sits above the floating navigation
 * bar (and above the keyboard when it is open).
 */
@Composable
fun GlassToastHost() {
    // The host can only draw while its activity is visible; tell GlassToast so it can fall back
    // to the system toast otherwise.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val registration = HostRegistration()
        lifecycle.addObserver(registration)
        onDispose {
            lifecycle.removeObserver(registration)
            registration.detach()
        }
    }

    val requested by GlassToast.current.collectAsState()
    // What is drawn. It is kept after the toast was cleared so the exit animation still has a text.
    var shown by remember { mutableStateOf<GlassToast.Entry?>(null) }
    val visibility = remember { MutableTransitionState(false) }

    LaunchedEffect(requested) {
        val next = requested
        if (next != null) {
            shown = next
            visibility.targetState = true
        } else {
            visibility.targetState = false
        }
    }

    val entry = shown
    if (entry != null && (visibility.currentState || visibility.targetState)) {
        GlassToastWindow(entry = entry, visibility = visibility, imeInsets = WindowInsets.ime)
    }
}

/** Counts this host in [GlassToast] while its lifecycle is STARTED, and exactly once per start. */
private class HostRegistration : LifecycleEventObserver {
    private var attached = false

    override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
        when (event) {
            Lifecycle.Event.ON_START -> attach()
            Lifecycle.Event.ON_STOP -> detach()
            else -> Unit
        }
    }

    private fun attach() {
        if (attached) return
        attached = true
        GlassToast.attachHost()
    }

    fun detach() {
        if (!attached) return
        attached = false
        GlassToast.detachHost()
    }
}

@Composable
private fun GlassToastWindow(
    entry: GlassToast.Entry,
    visibility: MutableTransitionState<Boolean>,
    imeInsets: WindowInsets,
) {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        // A toast is information only: no focus (the keyboard and the screen below stay as they
        // are), no touches (everything below stays tappable) and no dimming.
        val view = LocalView.current
        val dialogWindow = remember(view) {
            ((view as? DialogWindowProvider) ?: (view.parent as? DialogWindowProvider))?.window
        }
        SideEffect {
            dialogWindow?.let { window ->
                window.addFlags(
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                )
                window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                window.setDimAmount(0f)
            }
        }

        // This window is not focusable, so it never receives keyboard insets itself; they are read
        // from the activity window instead and the card is lifted above the keyboard.
        val density = LocalDensity.current
        val imeBottom = imeInsets.getBottom(density)
        val keyboardOpen = imeBottom > 0
        // Above the keyboard when it is open, otherwise above the floating glass navigation bar
        // (about 82 dp tall) plus the system navigation bar inset added below.
        val bottomGap = if (keyboardOpen) with(density) { imeBottom.toDp() + 16.dp } else 96.dp
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(if (keyboardOpen) Modifier else Modifier.navigationBarsPadding())
                .padding(start = 16.dp, end = 16.dp, bottom = bottomGap),
            contentAlignment = Alignment.BottomCenter,
        ) {
            AnimatedVisibility(
                visibleState = visibility,
                enter = fadeIn(tween(160)) +
                    scaleIn(initialScale = 0.88f, animationSpec = spring(dampingRatio = 0.6f, stiffness = 380f)) +
                    slideInVertically(tween(260, easing = FastOutSlowInEasing)) { it / 2 },
                exit = fadeOut(tween(160)) +
                    scaleOut(targetScale = 0.94f, animationSpec = tween(180)) +
                    slideOutVertically(tween(180, easing = FastOutSlowInEasing)) { it / 3 },
                label = "glassToast",
            ) {
                GlassToastCard(entry)
            }
        }
    }
}

/**
 * The card itself: the exact recipe of the glass snackbar and dialogs (`liquidGlass` with
 * `popup = true`), so a toast is indistinguishable in style from every other popup.
 */
@Composable
private fun GlassToastCard(entry: GlassToast.Entry) {
    GlassPopupTheme {
        Box(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .liquidGlass(
                    shape = GlassShapes.card,
                    elevated = true,
                    popup = true,
                )
                .padding(horizontal = 22.dp, vertical = 14.dp),
        ) {
            AnimatedContent(
                targetState = entry,
                transitionSpec = { fadeIn(tween(140)) togetherWith fadeOut(tween(90)) },
                label = "glassToastText",
            ) { current ->
                Text(
                    text = current.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnGlassPrimary,
                    textAlign = TextAlign.Center,
                    // Read out like a system toast.
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
    }
}
