package com.liquid.box.compose.splash

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.liquid.box.R
import kotlin.math.roundToInt

/**
 * Tempo of the launch animation. 1.0 is the authored timing (about 3.4 s from first frame to the
 * app being fully revealed); 1.1 trims it to roughly 3 s without changing how the slime moves.
 */
const val LAUNCH_SPLASH_SPEED = 1.1f

/** Frames to wait after the app UI exists before the finale may start, so its first-frame jank stays hidden. */
private const val READY_SETTLE_FRAMES = 3

/** A press shorter than this that did not travel counts as a tap. */
private const val TAP_MAX_MS = 350L

/**
 * Shared between [LaunchSplash] and the content it reveals, so the app can ease from slightly
 * zoomed-in to its normal size while the portal opens.
 */
@Stable
class LaunchSplashState {
    /** 0 while the splash fully covers the app, 1 once the app is completely revealed. */
    var reveal by mutableFloatStateOf(0f)
        internal set

    /** Scale to apply to the app content right now (1.05 behind the splash, 1 when revealed). */
    fun appScale(): Float {
        val p = reveal.coerceIn(0f, 1f)
        val e = 1f - (1f - p) * (1f - p) * (1f - p)
        return 1.05f - 0.05f * e
    }
}

/**
 * Launch animation: the app's glass cube drops in, hops around like a living slime cube (it has a
 * face, blinks, looks at your finger, can be poked, charged and called over by tapping), then leaps
 * at the camera and opens a glowing portal onto the app.
 *
 * It is drawn over the real UI. [appReady] tells it when that UI exists; the finale waits for it
 * (the slime keeps hopping in place while the app is still loading). [onFinished] is called once
 * the app is fully revealed and the overlay can be removed. System Back skips ahead.
 */
@Composable
fun LaunchSplash(
    appReady: Boolean,
    state: LaunchSplashState,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    speed: Float = LAUNCH_SPLASH_SPEED,
) {
    val sim = remember(speed) { SlimeSim(speed) }
    val painter = remember { SlimePainter() }
    var frame by remember { mutableLongStateOf(0L) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val view = LocalView.current
    val ready by rememberUpdatedState(appReady)
    val finish by rememberUpdatedState(onFinished)
    val appName = stringResource(R.string.app_name)

    DarkSystemBarIcons()
    BackHandler { sim.skip() }

    LaunchedEffect(sim) {
        var last = 0L
        var readyFrames = 0
        while (true) {
            val now = withFrameNanos { it }
            val vp = viewport
            if (vp.width <= 0 || vp.height <= 0) {
                last = now
                continue
            }
            sim.setViewport(vp.width.toFloat(), vp.height.toFloat())
            val dt = if (last == 0L) 1f / 60f else (now - last) / 1_000_000_000f
            last = now
            readyFrames = if (ready) readyFrames + 1 else 0
            sim.ready = readyFrames >= READY_SETTLE_FRAMES
            sim.step(dt)
            when (sim.takeHaptic()) {
                1 -> view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                2 -> view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                3 -> view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            }
            state.reveal = sim.revealProgress
            frame++
            if (sim.finished) {
                finish()
                break
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { viewport = it }
            // Offscreen so the portal can punch a real hole through the scene and show the app.
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .pointerInput(sim) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val slop = viewConfiguration.touchSlop * 2f
                    sim.touchDown(down.position.x, down.position.y)
                    var lastPos = down.position
                    var lastTime = down.uptimeMillis
                    var travelled = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        lastPos = change.position
                        lastTime = change.uptimeMillis
                        if (!travelled && (lastPos - down.position).getDistance() > slop) travelled = true
                        sim.touchMove(lastPos.x, lastPos.y)
                        change.consume()
                        if (!change.pressed) break
                    }
                    sim.touchUp(lastPos.x, lastPos.y, !travelled && lastTime - down.uptimeMillis < TAP_MAX_MS)
                }
            }
            .clearAndSetSemantics { contentDescription = appName },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            // Reading the tick is what re-draws the scene every frame (no recomposition needed).
            @Suppress("UNUSED_VARIABLE")
            val tick = frame
            if (sim.width <= 0f || sim.height <= 0f) {
                // First draw, before the viewport is known: just the base colour, no flash.
                drawRect(SplashPalette.Base)
            } else {
                painter.paint(this, sim)
            }
        }
        Wordmark(name = appName, sim = sim, viewport = viewport, tick = { frame })
    }
}

/** The app name, rising in letter by letter below the reflection once the cube has landed. */
@Composable
private fun Wordmark(name: String, sim: SlimeSim, viewport: IntSize, tick: () -> Long) {
    if (viewport.width <= 0 || viewport.height <= 0) return
    val topPx = SlimeSim.wordmarkTop(viewport.width.toFloat(), viewport.height.toFloat())
    val style = remember {
        TextStyle(
            color = Color(0xFFF1EAFF),
            fontSize = 28.sp,
            fontWeight = FontWeight.Light,
            letterSpacing = 6.sp,
            shadow = Shadow(color = Color(0x997C4DFF), offset = Offset.Zero, blurRadius = 26f),
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .offset { IntOffset(0, topPx.roundToInt()) },
        horizontalArrangement = Arrangement.Center,
    ) {
        val count = name.length
        name.forEachIndexed { index, ch ->
            Text(
                text = ch.toString(),
                style = style,
                modifier = Modifier.graphicsLayer {
                    tick()
                    val start = index.toFloat() / count * LETTER_STAGGER
                    val t = ((sim.wordmark - start) / (1f - LETTER_STAGGER)).coerceIn(0f, 1f)
                    val p = t * t * (3f - 2f * t)
                    alpha = p * sim.wordmarkFade
                    translationY = (1f - p) * 14.dp.toPx()
                    val k = 0.88f + 0.12f * p
                    scaleX = k
                    scaleY = k
                },
            )
        }
    }
}

private const val LETTER_STAGGER = 0.55f

/**
 * The splash is always dark, so the status / navigation bar icons must be light for its whole
 * lifetime, also when the app itself is in light mode. Restored when the splash goes away.
 */
@Composable
private fun DarkSystemBarIcons() {
    val view = LocalView.current
    if (view.isInEditMode) return
    val restoreLight = !isSystemInDarkTheme()
    SideEffect { applyBarIcons(view, light = false) }
    DisposableEffect(view) {
        onDispose { applyBarIcons(view, light = restoreLight) }
    }
}

private fun applyBarIcons(view: View, light: Boolean) {
    val window = view.context.findActivity()?.window ?: return
    WindowCompat.getInsetsController(window, view).apply {
        isAppearanceLightStatusBars = light
        isAppearanceLightNavigationBars = light
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
