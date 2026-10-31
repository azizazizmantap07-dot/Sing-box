package com.liquid.box.compose.component

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Cable
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur
import com.liquid.box.R
import com.liquid.box.compose.theme.GlassGlowAccent
import com.liquid.box.compose.theme.GlassGlowPrimary
import com.liquid.box.compose.theme.GlassShapes
import com.liquid.box.compose.theme.GlassSurface
import com.liquid.box.compose.theme.JellySpring
import com.liquid.box.compose.theme.JellySpringCard
import com.liquid.box.compose.theme.LiquidBackground
import com.liquid.box.compose.theme.LocalHazeState
import com.liquid.box.compose.theme.OnGlassPrimary
import com.liquid.box.compose.theme.OnGlassSecondary
import com.liquid.box.compose.theme.jellyClickable
import com.liquid.box.compose.theme.jellyTouch
import com.liquid.box.compose.theme.liquidGlass
import com.liquid.box.compose.theme.liquidGlassStrong
import com.liquid.box.constant.Status
import kotlinx.coroutines.delay

/** Backdrop blur of the floating status pill. Small area, so it can afford more than the sheets. */
private val StatusBarBlurRadius = 20.dp

/**
 * Floating glass pill with the service state, connections / groups shortcuts and stop button.
 *
 * What keeps the cards / text that scroll behind it from reading through is a real Haze backdrop
 * blur (same source as the nav bar and the bottom sheets), not a dark, near-opaque fill: the fill is
 * the normal dashboard glass and the page behind is simply blurred to an unreadable wash.
 *
 * Without a usable blur (no [LocalHazeState], or Android < 12 where Haze cannot really blur) the
 * old dense dark overlay fill is kept, otherwise the content behind would show through.
 *
 * The bar must NOT be a descendant of the `hazeSource` node it samples, see MainActivity.
 */
@Composable
fun ServiceStatusBar(
    visible: Boolean,
    serviceStatus: Status,
    startTime: Long?,
    groupsCount: Int,
    hasGroups: Boolean,
    onGroupsClick: () -> Unit,
    connectionsCount: Int,
    onConnectionsClick: () -> Unit,
    onStopClick: () -> Unit,
    modifier: Modifier = Modifier,
    enableBackdropBlur: Boolean = true,
    blurRadius: Dp = StatusBarBlurRadius,
) {
    // Null means "no real blur available": fall back to the dense overlay fill.
    val blurSource = if (enableBackdropBlur && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        LocalHazeState.current
    } else {
        null
    }

    // Blur only; the dark part is just a light tint for text contrast (the nav bar uses 0.40).
    val blurStyle = remember(blurRadius) {
        HazeBlurStyle {
            this.blurRadius(blurRadius)
            noiseFactor(0.06f)
            backgroundColor(LiquidBackground)
            colorEffects(
                listOf(
                    HazeColorEffect.tint(Color(0xFF05080F).copy(alpha = 0.20f)),
                ),
            )
        }
    }

    val barShape = GlassShapes.statusBar
    val barModifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 12.dp, vertical = 8.dp)
        .then(
            if (blurSource != null) {
                Modifier
                    // Jelly press goes OUTSIDE the blur, so the blur layer squishes together with
                    // the glass body (GlassSurface's own jelly sits inside it, hence jelly = false).
                    .jellyTouch(
                        pressedScale = 0.98f,
                        pressedStretchX = 1.005f,
                        pressedSquashY = 0.99f,
                        springSpec = JellySpringCard,
                        skipWhenChildConsumes = true,
                    )
                    // Clip *outside* hazeBlur so the blurred backdrop stays inside the rounded
                    // pill (hazeBlur defaults expandLayerBounds = true), same as the nav bar.
                    .clip(barShape)
                    .hazeBlur(
                        input = HazeInput.Sources(blurSource),
                        style = blurStyle,
                    )
            } else {
                Modifier
            },
        )

    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
        modifier = modifier,
    ) {
        GlassSurface(
            modifier = barModifier,
            shape = barShape,
            elevated = true,
            strong = true,
            // Blur available -> normal dashboard glass fill over the blur.
            // No blur -> dense ~85% overlay fill so nothing behind reads through.
            overlay = blurSource == null,
            darkGlass = true,
            jelly = blurSource == null,
            glowColor = GlassGlowAccent,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 14.dp,
                vertical = 10.dp,
            ),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusItem(
                    text = when (serviceStatus) {
                        Status.Starting -> stringResource(R.string.status_starting)
                        Status.Started -> stringResource(R.string.status_started)
                        Status.Stopping -> stringResource(R.string.status_stopping)
                        else -> ""
                    },
                    modifier = Modifier.weight(1f),
                )

                if (serviceStatus == Status.Started) {
                    GlassChip(
                        onClick = onConnectionsClick,
                        text = connectionsCount.toString(),
                        selected = false,
                        icon = {
                            Icon(
                                imageVector = Icons.Outlined.Cable,
                                contentDescription = stringResource(R.string.title_connections),
                                modifier = Modifier.size(18.dp),
                                tint = OnGlassPrimary,
                            )
                        },
                    )
                }

                if (hasGroups) {
                    GlassChip(
                        onClick = onGroupsClick,
                        text = groupsCount.toString(),
                        selected = false,
                        icon = {
                            Icon(
                                imageVector = Icons.Default.Folder,
                                contentDescription = stringResource(R.string.title_groups),
                                modifier = Modifier.size(18.dp),
                                tint = OnGlassPrimary,
                            )
                        },
                    )
                }

                // Stop = stronger selected-style glass
                GlassChip(
                    onClick = onStopClick,
                    text = null,
                    selected = true,
                    leading = {
                        if (startTime != null) {
                            UptimeText(startTime = startTime)
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                    },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.Stop,
                            contentDescription = stringResource(R.string.stop),
                            modifier = Modifier.size(18.dp),
                            tint = OnGlassPrimary,
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun GlassChip(
    onClick: () -> Unit,
    text: String?,
    selected: Boolean,
    modifier: Modifier = Modifier,
    leading: @Composable (() -> Unit)? = null,
    icon: @Composable (() -> Unit)? = null,
) {
    val glassModifier = if (selected) {
        Modifier.liquidGlassStrong(
            shape = GlassShapes.chip,
            glowColor = GlassGlowPrimary,
            darkGlass = false,
        )
    } else {
        Modifier.liquidGlass(
            shape = GlassShapes.chip,
            elevated = true,
            strong = false,
            glowColor = GlassGlowPrimary,
            darkGlass = false,
        )
    }

    Row(
        // jellyClickable BEFORE the glass so the chip's glass body squishes, not only its content.
        modifier = modifier
            .jellyClickable(
                pressedScale = 0.84f,
                pressedStretchX = 1.12f,
                pressedSquashY = 0.88f,
                springSpec = JellySpring,
                onClick = onClick,
            )
            .then(glassModifier)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        leading?.invoke()
        if (text != null) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = OnGlassPrimary,
            )
            if (icon != null) Spacer(modifier = Modifier.width(4.dp))
        }
        icon?.invoke()
    }
}

@Composable
private fun StatusItem(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = OnGlassPrimary,
        modifier = modifier,
    )
}

@Composable
fun UptimeText(startTime: Long, modifier: Modifier = Modifier) {
    var currentTime by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(startTime) {
        while (true) {
            delay(1000)
            currentTime = System.currentTimeMillis()
        }
    }

    val elapsedSeconds = ((currentTime - startTime) / 1000).coerceAtLeast(0)
    val hours = elapsedSeconds / 3600
    val minutes = (elapsedSeconds % 3600) / 60
    val seconds = elapsedSeconds % 60

    val formattedTime =
        if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%d:%02d", minutes, seconds)
        }

    Text(
        text = formattedTime,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Medium,
        color = OnGlassSecondary,
        modifier = modifier,
    )
}
