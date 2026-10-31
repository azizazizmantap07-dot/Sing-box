@file:Suppress("DEPRECATION")

package com.liquid.box.compose.base

import com.liquid.box.compose.component.GlassToast
import com.liquid.box.compose.theme.GlassTextButton
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.liquid.box.R
import com.liquid.box.compose.theme.GlassAlertDialog

@Composable
fun SelectableMessageDialog(title: String, message: String, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    GlassAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Box(
                modifier = Modifier
                    .heightIn(max = 320.dp)
                    .verticalScroll(scrollState),
            ) {
                SelectionContainer {
                    Text(message)
                }
            }
        },
        dismissButton = {
            GlassTextButton(
                onClick = {
                    clipboard.setText(AnnotatedString(message))
                    GlassToast.makeText(context, context.getString(R.string.copied_to_clipboard), GlassToast.LENGTH_SHORT).show()
                },
            ) {
                Text(stringResource(R.string.per_app_proxy_action_copy))
            }
        },
        confirmButton = {
            GlassTextButton(onClick = onDismiss) {
                Text(stringResource(R.string.ok))
            }
        },
    )
}
