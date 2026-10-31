package com.liquid.box.compose.screen.tools

import com.liquid.box.compose.theme.GlassTextButton
import com.liquid.box.compose.theme.GlassActionButton
import com.liquid.box.compose.theme.GlassIconButton
import com.liquid.box.compose.theme.GlassPopupBorder
import com.liquid.box.compose.theme.GlassPopup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ButtonDefaults
import com.liquid.box.compose.theme.GlassCard
import com.liquid.box.compose.theme.GlassGlowAccent
import com.liquid.box.compose.theme.OnGlassPrimary
import com.liquid.box.compose.theme.OnGlassSecondary
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import io.nekohasekai.libbox.Libbox
import com.liquid.box.R
import com.liquid.box.compose.topbar.LocalScaffoldPadding
import com.liquid.box.compose.topbar.OverrideTopBar
import com.liquid.box.constant.Status
import com.liquid.box.utils.RemoteControlManager
import com.liquid.box.compose.theme.GlassAlertDialog
import com.liquid.box.compose.theme.glassTextFieldColors
import com.liquid.box.compose.theme.GlassShapes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun STUNTestScreen(
    navController: NavController,
    serviceStatus: Status = Status.Stopped,
    viewModel: STUNTestViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val remoteServer by RemoteControlManager.remoteServer.collectAsState()
    val remoteConnected by RemoteControlManager.isConnected.collectAsState()
    val serviceAvailable = remoteServer != null || serviceStatus == Status.Started
    val vpnRunning =
        if (remoteServer != null) remoteConnected else serviceStatus == Status.Started

    var showServerDialog by remember { mutableStateOf(false) }

    OverrideTopBar {
        TopAppBar(
            title = { Text(stringResource(R.string.stun_test)) },
            navigationIcon = {
                GlassIconButton(onClick = { navController.navigateUp() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                }
            },
        )
    }

    LaunchedEffect(vpnRunning) {
        if (!vpnRunning) {
            viewModel.onVpnDisconnected()
        }
    }

    val selectedOutboundResult = navController.currentBackStackEntry
        ?.savedStateHandle
        ?.getStateFlow("selected_outbound", state.selectedOutbound)
        ?.collectAsState()
    LaunchedEffect(selectedOutboundResult?.value) {
        selectedOutboundResult?.value?.let { viewModel.selectOutbound(it) }
    }

    DisposableEffect(Unit) {
        onDispose {
            if (state.isRunning) {
                viewModel.cancelTest()
            }
        }
    }

    if (showServerDialog) {
        ServerEditDialog(
            currentServer = state.server,
            onServerChanged = { viewModel.updateServer(it) },
            onDismiss = { showServerDialog = false },
        )
    }

    val scaffoldPadding = LocalScaffoldPadding.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color.Transparent)
            .verticalScroll(rememberScrollState())
            .padding(scaffoldPadding)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        GlassCard(
            elevated = true,
            glowColor = GlassGlowAccent,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.tool_configuration),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )

                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = GlassShapes.card,
                    elevated = true,
                    glowColor = GlassGlowAccent,
                    onClick = { showServerDialog = true },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.stun_server),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                state.server,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (vpnRunning) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )
                    OutboundPickerRow(
                        selectedOutbound = state.selectedOutbound,
                        onClick = {
                            navController.navigate(
                                "tools/outbound_picker/${android.net.Uri.encode(state.selectedOutbound)}",
                            )
                        },
                    )
                }
            }
        }

        if (state.isRunning) {
            GlassActionButton(
                onClick = { viewModel.cancelTest() },
                modifier = Modifier.fillMaxWidth(),
                tint = MaterialTheme.colorScheme.error,
            ) {
                Text(stringResource(R.string.stun_cancel))
            }
        } else {
            GlassActionButton(
                onClick = { viewModel.startTest(serviceAvailable) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.stun_start))
            }
        }

        if (state.phase >= 0) {
            GlassCard(
                elevated = true,
                glowColor = GlassGlowAccent,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = stringResource(R.string.tool_results),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )

                    ResultItem(
                        label = stringResource(R.string.stun_external_address),
                        value = state.externalAddr.ifEmpty { null },
                        isActive = state.phase == Libbox.STUNPhaseBinding,
                        isRunning = state.isRunning,
                    )
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )
                    ResultItem(
                        label = stringResource(R.string.stun_latency),
                        value = if (state.latencyMs > 0) "${state.latencyMs} ms" else null,
                        isActive = state.phase == Libbox.STUNPhaseBinding,
                        isRunning = state.isRunning,
                    )
                    if (state.phase == Libbox.STUNPhaseDone && !state.natTypeSupported) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        )
                        ResultItem(
                            label = stringResource(R.string.stun_nat_type_detection),
                            value = stringResource(R.string.stun_nat_not_supported),
                            isActive = false,
                            isRunning = false,
                        )
                    } else {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        )
                        ResultItem(
                            label = stringResource(R.string.stun_nat_mapping),
                            value = if (state.natMapping > 0) Libbox.formatNATMapping(state.natMapping) else null,
                            isActive = state.phase == Libbox.STUNPhaseNATMapping,
                            isRunning = state.isRunning,
                            valueColor = if (state.natMapping > 0) natMappingColor(state.natMapping) else null,
                        )
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        )
                        ResultItem(
                            label = stringResource(R.string.stun_nat_filtering),
                            value = if (state.natFiltering > 0) Libbox.formatNATFiltering(state.natFiltering) else null,
                            isActive = state.phase == Libbox.STUNPhaseNATFiltering,
                            isRunning = state.isRunning,
                            valueColor = if (state.natFiltering > 0) natFilteringColor(state.natFiltering) else null,
                        )
                    }
                }
            }
        }
    }
}

private fun natMappingColor(value: Int): Color = when (value) {
    Libbox.NATMappingEndpointIndependent -> Color.Green
    Libbox.NATMappingAddressDependent -> Color.Yellow
    Libbox.NATMappingAddressAndPortDependent -> Color.Red
    else -> Color.Unspecified
}

private fun natFilteringColor(value: Int): Color = when (value) {
    Libbox.NATFilteringEndpointIndependent -> Color.Green
    Libbox.NATFilteringAddressDependent -> Color.Yellow
    Libbox.NATFilteringAddressAndPortDependent -> Color.Red
    else -> Color.Unspecified
}

@Composable
private fun ServerEditDialog(
    currentServer: String,
    onServerChanged: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(currentServer) }
    GlassAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.stun_server)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                shape = GlassShapes.chip,
                colors = glassTextFieldColors(),
            )
        },
        confirmButton = {
            GlassTextButton(onClick = {
                onServerChanged(text)
                onDismiss()
            }) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            GlassTextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
        titleContentColor = androidx.compose.ui.graphics.Color.White,
        textContentColor = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.9f),
    )
}
