package com.liquid.box.compose.screen.settings

import com.liquid.box.compose.component.GlassToast
import com.liquid.box.compose.theme.GlassTextButton
import com.liquid.box.compose.theme.GlassActionButton
import com.liquid.box.compose.theme.GlassIconButton
import com.liquid.box.compose.theme.GlassOutlinedButton
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.AirplanemodeActive
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Tune
import com.liquid.box.compose.theme.GlassCard
import com.liquid.box.compose.theme.GlassGlowAccent
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.liquid.box.R
import com.liquid.box.bg.RootClient
import com.liquid.box.bg.RootState
import com.liquid.box.bg.ServiceConnection
import com.liquid.box.bg.ServiceWatchdog
import com.liquid.box.compose.base.UiEvent
import com.liquid.box.compose.base.rememberApplyServiceChangeNotifier
import com.liquid.box.compose.topbar.LocalScaffoldPadding
import com.liquid.box.compose.topbar.OverrideTopBar
import com.liquid.box.constant.Status
import com.liquid.box.database.Settings
import com.liquid.box.ktx.launchCustomTab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServiceSettingsScreen(
    navController: NavController,
    serviceConnection: ServiceConnection? = null,
    serviceStatus: Status = Status.Stopped,
) {
    OverrideTopBar {
        TopAppBar(
            title = { Text(stringResource(R.string.service)) },
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
    val focusManager = LocalFocusManager.current
    var isBatteryOptimizationIgnored by remember { mutableStateOf(false) }
    var allowBypass by remember { mutableStateOf(Settings.allowBypass) }
    val watchdogEnabled by ServiceWatchdog.watchdogEnabledFlow.collectAsState()
    var autoRestartEnabled by remember { mutableStateOf(ServiceWatchdog.autoRestartEnabled) }
    var airplaneRecoveryEnabled by remember {
        mutableStateOf(ServiceWatchdog.watchdogEnabled && Settings.airplaneRecoveryEnabled)
    }

    // What the watcher currently uses, and the text being edited until "Apply" is pressed.
    var savedThreshold by remember { mutableStateOf(Settings.airplaneRecoveryFailureThreshold.coerceIn(THRESHOLD_RANGE)) }
    var savedCooldown by remember { mutableStateOf(Settings.airplaneRecoveryCooldownSec.coerceIn(COOLDOWN_RANGE)) }
    var thresholdInput by remember { mutableStateOf(savedThreshold.toString()) }
    var cooldownInput by remember { mutableStateOf(savedCooldown.toString()) }
    // Refreshed automatically every time the app is opened (MainActivity.onStart), so it is
    // already up to date here; no manual recheck needed.
    val rootState by RootClient.rootState.collectAsState()

    val thresholdValue = thresholdInput.toIntOrNull()?.takeIf { it in THRESHOLD_RANGE }
    val cooldownValue = cooldownInput.toIntOrNull()?.takeIf { it in COOLDOWN_RANGE }
    // The trigger values belong to the watchdog: locked while it is off.
    val canApply = watchdogEnabled &&
        thresholdValue != null &&
        cooldownValue != null &&
        (thresholdValue != savedThreshold || cooldownValue != savedCooldown)

    val notifyApplyChange = rememberApplyServiceChangeNotifier(serviceStatus)
    val requestBatteryOptimizationLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) { _ ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val pm = context.getSystemService(PowerManager::class.java)
                isBatteryOptimizationIgnored =
                    pm?.isIgnoringBatteryOptimizations(context.packageName) == true
            }
        }

    // Saves both values. The running watcher picks them up by itself through the settings
    // listener in AirplaneModeRecovery, so no service restart is needed.
    val applyAirplaneConfig: () -> Unit = {
        if (watchdogEnabled && thresholdValue != null && cooldownValue != null) {
            focusManager.clearFocus()
            savedThreshold = thresholdValue
            savedCooldown = cooldownValue
            thresholdInput = thresholdValue.toString()
            cooldownInput = cooldownValue.toString()
            scope.launch(Dispatchers.IO) {
                Settings.airplaneRecoveryFailureThreshold = thresholdValue
                Settings.airplaneRecoveryCooldownSec = cooldownValue
            }
            GlassToast.makeText(context, "Airplane recovery settings applied", GlassToast.LENGTH_SHORT).show()
        }
    }

    // Actively asks for root (this is what shows the su prompt of the root manager).
    val checkRoot: (Boolean) -> Unit = { warnWhenDenied ->
        if (rootState != RootState.Checking) {
            scope.launch {
                // RootClient publishes Checking / Granted / Denied itself.
                val granted = try {
                    RootClient.checkRootAvailable()
                } catch (e: Exception) {
                    false
                }
                if (!granted && warnWhenDenied) {
                    GlassToast.makeText(
                        context,
                        context.getString(R.string.root_access_required),
                        GlassToast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    LaunchedEffect(watchdogEnabled) {
        if (!watchdogEnabled) focusManager.clearFocus()
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = context.getSystemService(PowerManager::class.java)
            isBatteryOptimizationIgnored =
                pm?.isIgnoringBatteryOptimizations(context.packageName) == true
        } else {
            isBatteryOptimizationIgnored = true
        }
    }

    val scaffoldPadding = LocalScaffoldPadding.current

    Column(
        modifier =
        Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color.Transparent)
            // Shrinks the scrolling area when the keyboard is open (the activity is edge-to-edge,
            // so it does not resize the window by itself). Must come before verticalScroll.
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(
                top = scaffoldPadding.calculateTopPadding() + 8.dp,
                bottom = scaffoldPadding.calculateBottomPadding() + 8.dp,
            ),
    ) {
        if (!isBatteryOptimizationIgnored && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            GlassCard(
                modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                elevated = true,
                glowColor = GlassGlowAccent,
            ) {
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.BatteryChargingFull,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.padding(end = 12.dp),
                        )
                        Text(
                            stringResource(R.string.background_permission),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }

                    Text(
                        stringResource(R.string.background_permission_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        GlassOutlinedButton(
                            onClick = {
                                context.launchCustomTab("https://dontkillmyapp.com/")
                            },
                            modifier = Modifier.padding(end = 8.dp),
                        ) {
                            Text(stringResource(R.string.read_more))
                        }

                        GlassActionButton(
                            onClick = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                    val intent =
                                        Intent(
                                            android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                            Uri.parse("package:${context.packageName}"),
                                        )
                                    requestBatteryOptimizationLauncher.launch(intent)
                                }
                            },
                        ) {
                            Text(stringResource(R.string.request_background_permission))
                        }
                    }
                }
            }
        }

        // VPN Section
        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "VPN",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
        )

        GlassCard(
            modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            elevated = true,
            glowColor = GlassGlowAccent,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        ) {
            ListItem(
                headlineContent = {
                    Text(
                        stringResource(R.string.allow_bypass),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                },
                supportingContent = {
                    Text(
                        stringResource(R.string.allow_bypass_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                },
                trailingContent = {
                    Switch(
                        checked = allowBypass,
                        onCheckedChange = { checked ->
                            allowBypass = checked
                            scope.launch(Dispatchers.IO) {
                                Settings.allowBypass = checked
                                withContext(Dispatchers.Main) {
                                    notifyApplyChange(UiEvent.ApplyServiceChange.Mode.Reload)
                                }
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

        // Watchdog master switch. Root access status sits right above it because the watchdog
        // needs root and can only be switched on once root has been granted.
        Spacer(modifier = Modifier.height(16.dp))
        SectionLabel("Watchdog")
        SettingsCard {
            RootAccessItem(
                state = rootState,
                onCheck = { checkRoot(false) },
            )

            ListItem(
                headlineContent = {
                    Text(
                        "Enable watchdog",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                },
                supportingContent = {
                    Column(modifier = Modifier.padding(top = 4.dp)) {
                        Text(
                            "Keeps the service alive and the connection healthy: auto restart and airplane " +
                                "recovery, the Watchdog card on the dashboard and the connection health in " +
                                "the notification. Turning it off also turns auto restart and airplane " +
                                "recovery off.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "Requires root access. It can only be turned on once root has been granted to " +
                                "this app.",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                        if (!watchdogEnabled && rootState != RootState.Granted) {
                            Text(
                                "Grant root first (see Root access above), then turn this on.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                },
                leadingContent = {
                    Icon(
                        imageVector = Icons.Outlined.Shield,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                },
                trailingContent = {
                    Switch(
                        checked = watchdogEnabled,
                        // It can always be switched off; switching it on needs root.
                        enabled = watchdogEnabled || rootState == RootState.Granted,
                        onCheckedChange = { checked ->
                            if (checked && rootState != RootState.Granted) {
                                GlassToast.makeText(
                                    context,
                                    context.getString(R.string.root_access_required),
                                    GlassToast.LENGTH_LONG,
                                ).show()
                            } else {
                                if (!checked) {
                                    // Both features follow the watchdog off. They stay off when it
                                    // is turned back on, and are switched on again below.
                                    autoRestartEnabled = false
                                    airplaneRecoveryEnabled = false
                                }
                                scope.launch(Dispatchers.IO) { ServiceWatchdog.setWatchdogEnabled(checked) }
                            }
                        },
                    )
                },
                modifier = Modifier.clip(RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)),
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }

        // Auto restart (service watchdog). Needs the watchdog to be on.
        Spacer(modifier = Modifier.height(16.dp))
        SectionLabel("Auto restart")
        SettingsCard {
            ListItem(
                headlineContent = {
                    Text(
                        "Restart service when it dies",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                },
                supportingContent = {
                    Column(modifier = Modifier.padding(top = 4.dp)) {
                        Text(
                            "Brings the VPN back if Android kills the service, retries when the core fails to " +
                                "start, and keeps a background alarm watching it. Airplane recovery comes back " +
                                "with the service. Stops trying after repeated crashes.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (!watchdogEnabled) {
                            DependsOnWatchdogHint()
                        }
                    }
                },
                leadingContent = {
                    Icon(
                        imageVector = Icons.Outlined.RestartAlt,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                },
                trailingContent = {
                    Switch(
                        checked = autoRestartEnabled && watchdogEnabled,
                        enabled = watchdogEnabled,
                        onCheckedChange = { checked ->
                            autoRestartEnabled = checked
                            scope.launch(Dispatchers.IO) {
                                ServiceWatchdog.autoRestartEnabled = checked
                            }
                        },
                    )
                },
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .alpha(if (watchdogEnabled) 1f else DISABLED_ALPHA),
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }

        // Airplane mode recovery (root). Needs the watchdog to be on.
        Spacer(modifier = Modifier.height(16.dp))
        SectionLabel("Airplane recovery")
        SettingsCard {
            ListItem(
                headlineContent = {
                    Text(
                        "Enable airplane recovery",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                },
                supportingContent = {
                    Column(modifier = Modifier.padding(top = 4.dp)) {
                        Text(
                            "Toggles airplane mode via root when the physical network drops, downlink stalls " +
                                "while connections are open (or they keep dying with no downlink), or the selected " +
                                "urltest outbound keeps failing. A new url test of that outbound runs first. " +
                                "Reaching a public host is not treated as proof, so a stuck tunnel does not skip recovery. " +
                                "Works in the background. Root required.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (!watchdogEnabled) {
                            DependsOnWatchdogHint()
                        }
                    }
                },
                leadingContent = {
                    Icon(
                        imageVector = Icons.Outlined.AirplanemodeActive,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                },
                trailingContent = {
                    Switch(
                        checked = airplaneRecoveryEnabled && watchdogEnabled,
                        enabled = watchdogEnabled,
                        onCheckedChange = { checked ->
                            airplaneRecoveryEnabled = checked
                            scope.launch(Dispatchers.IO) {
                                Settings.airplaneRecoveryEnabled = checked
                            }
                            // Turning it on is the moment root is needed: ask for it right away
                            // so the user sees the prompt and the indicator above.
                            if (checked && rootState != RootState.Granted) {
                                checkRoot(true)
                            }
                        },
                    )
                },
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .alpha(if (watchdogEnabled) 1f else DISABLED_ALPHA),
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        SettingsCard {
            ListItem(
                headlineContent = {
                    Text(
                        "Trigger settings",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                },
                supportingContent = {
                    Column(modifier = Modifier.padding(top = 4.dp)) {
                        Text(
                            "Press Apply to save the values and use them right away, no service restart needed.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (!watchdogEnabled) {
                            DependsOnWatchdogHint()
                        }
                    }
                },
                leadingContent = {
                    Icon(
                        imageVector = Icons.Outlined.Tune,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                },
                modifier = Modifier
                    .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                    .alpha(if (watchdogEnabled) 1f else DISABLED_ALPHA),
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                NumberSettingField(
                    value = thresholdInput,
                    onValueChange = { thresholdInput = it },
                    label = "Failure threshold (1–20)",
                    hint = "How many seconds a failed URL test or stalled traffic must last before recovery. " +
                        "Disconnect is faster (about 2.5s). A live check still runs first, so a short blip " +
                        "does not toggle anything. Default 8.",
                    error = if (thresholdValue == null) "Enter a number from 1 to 20" else null,
                    enabled = watchdogEnabled,
                    maxLength = 3,
                    imeAction = ImeAction.Next,
                    onImeAction = { focusManager.moveFocus(FocusDirection.Down) },
                )
                NumberSettingField(
                    value = cooldownInput,
                    onValueChange = { cooldownInput = it },
                    label = "Cooldown seconds (1–900)",
                    hint = "Time between toggles (at least 8 s in practice). The value is used as " +
                        "entered for every toggle, it never grows. Default 8.",
                    error = if (cooldownValue == null) "Enter a number from 1 to 900" else null,
                    enabled = watchdogEnabled,
                    maxLength = 4,
                    imeAction = ImeAction.Done,
                    onImeAction = {
                        if (canApply) applyAirplaneConfig() else focusManager.clearFocus()
                    },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    GlassActionButton(
                        onClick = applyAirplaneConfig,
                        enabled = canApply,
                    ) {
                        Text("Apply")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

private val THRESHOLD_RANGE = 1..20
private val COOLDOWN_RANGE = 1..900

private val RootGrantedColor = Color(0xFF4CAF50)

/** Look of a switch that cannot be used because the watchdog is off. */
private const val DISABLED_ALPHA = 0.5f

@Composable
private fun DependsOnWatchdogHint() {
    Text(
        "Turn on Watchdog above to use this.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(top = 6.dp),
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
    )
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    GlassCard(
        modifier =
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        elevated = true,
        glowColor = GlassGlowAccent,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
    ) {
        Column(content = content)
    }
}

/** Status row showing whether this app has been granted root, with a button to (re)check it. */
@Composable
private fun RootAccessItem(
    state: RootState,
    onCheck: () -> Unit,
) {
    val statusColor = when (state) {
        RootState.Granted -> RootGrantedColor
        RootState.Denied -> MaterialTheme.colorScheme.error
        RootState.Unknown, RootState.Checking -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val statusText = when (state) {
        RootState.Unknown -> "Not checked yet"
        RootState.Checking -> "Checking…"
        RootState.Granted -> "Granted"
        RootState.Denied -> "Not granted"
    }
    val hint = when (state) {
        RootState.Unknown -> "Checked automatically every time you open the app."
        RootState.Denied ->
            "The watchdog can't be turned on without it. Grant root to this app in " +
                "your root manager; it is re-checked every time you open the app (or tap Check)."
        RootState.Checking, RootState.Granted -> null
    }

    ListItem(
        headlineContent = {
            Text(
                "Root access",
                style = MaterialTheme.typography.bodyLarge,
            )
        },
        supportingContent = {
            Column(modifier = Modifier.padding(top = 4.dp)) {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = statusColor,
                )
                if (hint != null) {
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        },
        leadingContent = {
            Icon(
                imageVector = when (state) {
                    RootState.Granted -> Icons.Filled.CheckCircle
                    RootState.Denied -> Icons.Filled.Error
                    RootState.Unknown, RootState.Checking -> Icons.Outlined.AdminPanelSettings
                },
                contentDescription = null,
                tint = statusColor,
            )
        },
        trailingContent = {
            when (state) {
                RootState.Checking -> CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                )
                // Granted needs no button any more: it is re-checked on every app open.
                RootState.Granted -> Unit
                RootState.Unknown, RootState.Denied -> GlassTextButton(onClick = onCheck) {
                    Text("Check")
                }
            }
        },
        modifier = Modifier.clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

/**
 * Digits-only field. When it gains focus it asks its parent scroll container to bring it into
 * view once the keyboard animation has finished, so the keyboard never covers what is being typed.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NumberSettingField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    hint: String,
    error: String?,
    enabled: Boolean,
    maxLength: Int,
    imeAction: ImeAction,
    onImeAction: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val bringIntoViewRequester = remember { BringIntoViewRequester() }

    OutlinedTextField(
        value = value,
        onValueChange = { text ->
            if (text.length <= maxLength && text.all { it.isDigit() }) {
                onValueChange(text)
            }
        },
        label = { Text(label) },
        enabled = enabled,
        isError = error != null,
        supportingText = { Text(error ?: hint) },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
            imeAction = imeAction,
        ),
        keyboardActions = KeyboardActions(
            onNext = { onImeAction() },
            onDone = { onImeAction() },
        ),
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(bringIntoViewRequester)
            .onFocusEvent { focusState ->
                if (focusState.isFocused) {
                    scope.launch {
                        delay(KEYBOARD_ANIMATION_MS)
                        bringIntoViewRequester.bringIntoView()
                    }
                }
            },
    )
}

private const val KEYBOARD_ANIMATION_MS = 350L
