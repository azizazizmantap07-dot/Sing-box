package com.liquid.box.compose.screen.settings

import com.liquid.box.compose.component.GlassToast
import com.liquid.box.compose.theme.GlassIconButton
import com.liquid.box.compose.theme.GlassPopup
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.DocumentsContract
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.WarningAmber
import com.liquid.box.compose.theme.GlassCard
import com.liquid.box.compose.theme.GlassGlowAccent
import com.liquid.box.compose.theme.GlassListCard
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import io.nekohasekai.libbox.Libbox
import com.liquid.box.R
import com.liquid.box.compose.topbar.LocalScaffoldPadding
import com.liquid.box.compose.topbar.OverrideTopBar
import com.liquid.box.database.Settings
import com.liquid.box.ktx.clipboardText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.liquid.box.compose.theme.GlassDropdownMenu

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CoreSettingsScreen(navController: NavController) {
    OverrideTopBar {
        TopAppBar(
            title = { Text(stringResource(R.string.core)) },
            navigationIcon = {
                GlassIconButton(onClick = { navController.navigateUp() }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.content_description_back),
                    )
                }
            },
        )
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var dataSize by remember { mutableStateOf("") }
    val version = remember { Libbox.version() }
    var showVersionMenu by remember { mutableStateOf(false) }
    var disableDeprecatedWarnings by remember { mutableStateOf(Settings.disableDeprecatedWarnings) }

    // Calculate data size on launch
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val filesDir = context.getExternalFilesDir(null) ?: context.filesDir
            val size =
                filesDir.walkTopDown()
                    .filter { it.isFile }
                    .map { it.length() }
                    .sum()
            val formattedSize = Libbox.formatBytes(size)
            dataSize = formattedSize
        }
    }

    val scaffoldPadding = LocalScaffoldPadding.current

    Column(
        modifier =
        Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color.Transparent)
            .verticalScroll(rememberScrollState())
            .padding(
                top = scaffoldPadding.calculateTopPadding() + 8.dp,
                bottom = scaffoldPadding.calculateBottomPadding() + 8.dp,
            ),
    ) {
        // Core Information Card
        GlassCard(
            modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            elevated = true,
            glowColor = GlassGlowAccent,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        ) {
            Column {
                // Version Info
                Box {
                    ListItem(
                        headlineContent = {
                            Text(
                                stringResource(R.string.core_version_title),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        },
                        supportingContent = {
                            Text(
                                version,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        },
                        leadingContent = {
                            Icon(
                                imageVector = Icons.Outlined.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        },
                        modifier = Modifier
                            .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                            .combinedClickable(
                                onClick = {},
                                onLongClick = { showVersionMenu = true },
                            ),
                        colors =
                        ListItemDefaults.colors(
                            containerColor = Color.Transparent,
                        ),
                    )
                    Box(modifier = Modifier.align(Alignment.BottomEnd)) {
                        GlassDropdownMenu(
                            expanded = showVersionMenu,
                            onDismissRequest = { showVersionMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.per_app_proxy_action_copy)) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Filled.ContentCopy,
                                        contentDescription = null,
                                    )
                                },
                                onClick = {
                                    clipboardText = version
                                    GlassToast.makeText(
                                        context,
                                        R.string.copied_to_clipboard,
                                        GlassToast.LENGTH_SHORT,
                                    ).show()
                                    showVersionMenu = false
                                },
                            )
                        }
                    }
                }

                // Data Size
                ListItem(
                    headlineContent = {
                        Text(
                            stringResource(R.string.core_data_size),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    },
                    supportingContent = {
                        Text(
                            dataSize.ifEmpty { stringResource(R.string.calculating) },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    },
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Outlined.Storage,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                    modifier =
                    Modifier.clip(
                        RoundedCornerShape(
                            bottomStart = 12.dp,
                            bottomEnd = 12.dp,
                        ),
                    ),
                    colors =
                    ListItemDefaults.colors(
                        containerColor = Color.Transparent,
                    ),
                )
            }
        }

        if (version.contains("-")) {
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.beta_settings),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
            )

            GlassCard(
                elevated = true,
                glowColor = GlassGlowAccent,
                modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            ) {
                ListItem(
                    headlineContent = {
                        Text(
                            stringResource(R.string.disable_deprecated_warnings),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    },
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Outlined.WarningAmber,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = disableDeprecatedWarnings,
                            onCheckedChange = { checked ->
                                disableDeprecatedWarnings = checked
                                scope.launch(Dispatchers.IO) {
                                    Settings.disableDeprecatedWarnings = checked
                                }
                            },
                        )
                    },
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)),
                    colors =
                    ListItemDefaults.colors(
                        containerColor = Color.Transparent,
                    ),
                )
            }
        }

        // Working Directory Section
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.working_directory),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
        )

        // Working Directory Card (GlassListCard stacks rows in a Column – a plain
        // GlassCard is a Box, which drew Browse and Destroy on top of each other)
        GlassListCard(
            modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            elevated = true,
            glowColor = GlassGlowAccent,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        ) {
            // Browse
            ListItem(
                headlineContent = {
                    Text(
                        stringResource(R.string.browse),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                },
                leadingContent = {
                    Icon(
                        imageVector = Icons.Outlined.FolderOpen,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                },
                modifier =
                Modifier
                    .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                    .clickable {
                        openInFileManager(context)
                    },
                colors =
                ListItemDefaults.colors(
                    containerColor = Color.Transparent,
                ),
            )

            // Destroy
            ListItem(
                headlineContent = {
                    Text(
                        stringResource(R.string.destroy),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                },
                leadingContent = {
                    Icon(
                        imageVector = Icons.Outlined.DeleteForever,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                },
                modifier =
                Modifier
                    .clip(RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp))
                    .clickable {
                        scope.launch(Dispatchers.IO) {
                            val filesDir = context.getExternalFilesDir(null) ?: context.filesDir
                            filesDir.deleteRecursively()
                            filesDir.mkdirs()

                            // Recalculate data size
                            val newSize =
                                filesDir.walkTopDown()
                                    .filter { it.isFile }
                                    .map { it.length() }
                                    .sum()
                            val formattedSize = Libbox.formatBytes(newSize)
                            dataSize = formattedSize
                        }
                    },
                colors =
                ListItemDefaults.colors(
                    containerColor = Color.Transparent,
                ),
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

private fun openInFileManager(context: Context) {
    val authority = "${context.packageName}.workingdir"
    val rootUri = DocumentsContract.buildRootUri(authority, "working_directory")

    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(rootUri, DocumentsContract.Document.MIME_TYPE_DIR)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }

    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        GlassToast.makeText(
            context,
            context.getString(R.string.no_file_manager),
            GlassToast.LENGTH_SHORT,
        ).show()
    }
}
