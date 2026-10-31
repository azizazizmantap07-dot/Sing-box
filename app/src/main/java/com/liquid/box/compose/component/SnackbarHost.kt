package com.liquid.box.compose.component

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.liquid.box.R
import com.liquid.box.compose.theme.GlassIconButton
import com.liquid.box.compose.theme.GlassPopupTheme
import com.liquid.box.compose.theme.GlassShapes
import com.liquid.box.compose.theme.GlassTextButton
import com.liquid.box.compose.theme.OnGlassPrimary
import com.liquid.box.compose.theme.OnGlassSecondary
import com.liquid.box.compose.theme.liquidGlass

@Composable
fun SnackbarHost(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    androidx.compose.material3.SnackbarHost(
        hostState = hostState,
        modifier = modifier,
    ) { snackbarData ->
        GlassSnackbar(snackbarData)
    }
}

/**
 * Snackbar drawn as the same popup glass card as the dialogs and menus (popup fill, double
 * specular rim, top shine). The action is a glass pill ([GlassTextButton]) and the dismiss
 * button a glass circle ([GlassIconButton]), like the buttons inside [GlassAlertDialog].
 *
 * It replaces Material's `Snackbar` on purpose: that one always draws its own flat surface with
 * a shadow, which cannot be made transparent.
 */
@Composable
private fun GlassSnackbar(data: SnackbarData) {
    val visuals = data.visuals
    val actionLabel = visuals.actionLabel
    val hasButtons = actionLabel != null || visuals.withDismissAction

    GlassPopupTheme {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth()
                .widthIn(max = 600.dp)
                .liquidGlass(
                    shape = GlassShapes.card,
                    elevated = true,
                    popup = true,
                )
                .padding(
                    start = 20.dp,
                    end = if (hasButtons) 8.dp else 20.dp,
                    top = 4.dp,
                    bottom = 4.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = visuals.message,
                style = MaterialTheme.typography.bodyMedium,
                color = OnGlassPrimary,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 10.dp),
            )
            if (actionLabel != null) {
                GlassTextButton(onClick = { data.performAction() }) {
                    Text(actionLabel)
                }
            }
            if (visuals.withDismissAction) {
                GlassIconButton(onClick = { data.dismiss() }) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.dismiss),
                        tint = OnGlassSecondary,
                    )
                }
            }
        }
    }
}
