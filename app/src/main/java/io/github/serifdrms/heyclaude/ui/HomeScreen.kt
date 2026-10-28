package io.github.serifdrms.heyclaude.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.serifdrms.heyclaude.R
import io.github.serifdrms.heyclaude.launch.ClaudeLauncher
import io.github.serifdrms.heyclaude.launch.LaunchMethod
import io.github.serifdrms.heyclaude.service.ErrorReason
import io.github.serifdrms.heyclaude.service.ListenerService
import io.github.serifdrms.heyclaude.service.ListenerState
import io.github.serifdrms.heyclaude.service.ListenerStatus
import io.github.serifdrms.heyclaude.settings.AppSettings
import io.github.serifdrms.heyclaude.shizuku.ShizukuBridge
import io.github.serifdrms.heyclaude.shizuku.ShizukuStatus
import io.github.serifdrms.heyclaude.wakeword.ModelCatalog
import kotlin.math.roundToInt

private data class SetupState(
    val microphone: Boolean,
    val notifications: Boolean,
    val overlay: Boolean,
    val battery: Boolean,
    val claudeInstalled: Boolean,
)

private fun readSetup(context: Context): SetupState {
    fun granted(permission: String) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    val power = context.getSystemService(PowerManager::class.java)
    return SetupState(
        microphone = granted(Manifest.permission.RECORD_AUDIO),
        notifications = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            granted(Manifest.permission.POST_NOTIFICATIONS),
        overlay = Settings.canDrawOverlays(context),
        battery = power.isIgnoringBatteryOptimizations(context.packageName),
        claudeInstalled = ClaudeLauncher.isInstalled(context),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen() {
    val context = LocalContext.current
    val settings = remember { AppSettings(context) }
    val status by ListenerState.status.collectAsStateWithLifecycle()
    val level by ListenerState.level.collectAsStateWithLifecycle()
    val shizuku by ShizukuBridge.status.collectAsStateWithLifecycle()

    var setup by remember { mutableStateOf(readSetup(context)) }
    LifecycleResumeEffect(Unit) {
        setup = readSetup(context)
        ShizukuBridge.refresh()
        if (ListenerState.status.value == ListenerStatus.PausedLocked) ListenerService.recheck(context)
        onPauseOrDispose { }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { setup = readSetup(context) }

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatusCard(
                status = status,
                level = level,
                threshold = settings.threshold,
                canStart = setup.microphone,
                onStart = { ListenerService.start(context) },
                onStop = { ListenerService.stop(context) },
            )

            SectionCard(title = stringResource(R.string.setup_title)) {
                SetupRow(
                    title = stringResource(R.string.setup_mic),
                    description = stringResource(R.string.setup_mic_desc),
                    done = setup.microphone,
                    action = stringResource(R.string.grant),
                ) { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    SetupRow(
                        title = stringResource(R.string.setup_notifications),
                        description = stringResource(R.string.setup_notifications_desc),
                        done = setup.notifications,
                        action = stringResource(R.string.grant),
                    ) { permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
                }

                SetupRow(
                    title = stringResource(R.string.setup_overlay),
                    description = stringResource(R.string.setup_overlay_desc),
                    done = setup.overlay,
                    action = stringResource(R.string.open_settings),
                ) {
                    context.startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")),
                    )
                }

                SetupRow(
                    title = stringResource(R.string.setup_battery),
                    description = stringResource(R.string.setup_battery_desc),
                    done = setup.battery,
                    action = stringResource(R.string.open_settings),
                ) {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:${context.packageName}"),
                        ),
                    )
                }

                SetupRow(
                    title = stringResource(R.string.setup_claude),
                    description = stringResource(R.string.setup_claude_desc),
                    done = setup.claudeInstalled,
                    action = stringResource(R.string.open_store),
                ) {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${ClaudeLauncher.PACKAGE}"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }

                ShizukuRow(shizuku)
            }

            SectionCard(title = stringResource(R.string.wake_words_title)) {
                ModelCatalog.wakeWords.forEach { spec ->
                    Text("• ${spec.phrase}", style = MaterialTheme.typography.bodyLarge)
                }
            }

            LaunchMethodCard(settings = settings, shizuku = shizuku)

            TuningCard(settings = settings)

            Text(
                text = stringResource(R.string.footer_privacy),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.footer_unofficial),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun StatusCard(
    status: ListenerStatus,
    level: Float,
    threshold: Float,
    canStart: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val running = status != ListenerStatus.Stopped && status !is ListenerStatus.Error
    val text = when (status) {
        ListenerStatus.Stopped -> stringResource(R.string.status_stopped)
        ListenerStatus.Listening -> stringResource(R.string.status_listening)
        ListenerStatus.PausedLocked -> stringResource(R.string.status_paused_locked)
        ListenerStatus.PausedCooldown -> stringResource(R.string.status_paused_cooldown)
        ListenerStatus.PausedMicBusy -> stringResource(R.string.status_paused_mic_busy)
        is ListenerStatus.Error -> when (status.reason) {
            ErrorReason.MODELS -> stringResource(R.string.status_error_models)
            ErrorReason.MICROPHONE -> stringResource(R.string.status_error_mic)
            ErrorReason.PERMISSION -> stringResource(R.string.status_error_permission)
        } + (status.detail?.let { "\n$it" } ?: "")
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.status_title), style = MaterialTheme.typography.titleMedium)
            Text(text, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)

            if (status == ListenerStatus.Listening) {
                Text(
                    stringResource(R.string.level_label, (level * 100).roundToInt(), (threshold * 100).roundToInt()),
                    style = MaterialTheme.typography.bodySmall,
                )
                LinearProgressIndicator(progress = { level }, modifier = Modifier.fillMaxWidth())
            }

            if (running) {
                OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.action_stop))
                }
            } else {
                Button(onClick = onStart, enabled = canStart, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.action_start))
                }
                if (!canStart) {
                    Text(stringResource(R.string.start_needs_mic), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun SetupRow(title: String, description: String, done: Boolean, action: String, onAction: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        if (done) {
            Text("✓", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
        } else {
            FilledTonalButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun ShizukuRow(status: ShizukuStatus) {
    val context = LocalContext.current
    val description = when (status) {
        ShizukuStatus.NOT_INSTALLED -> stringResource(R.string.shizuku_not_installed)
        ShizukuStatus.NOT_RUNNING -> stringResource(R.string.shizuku_not_running)
        ShizukuStatus.UNSUPPORTED -> stringResource(R.string.shizuku_unsupported)
        ShizukuStatus.NO_PERMISSION -> stringResource(R.string.shizuku_no_permission)
        ShizukuStatus.READY -> stringResource(R.string.shizuku_ready)
    }
    val action = when (status) {
        ShizukuStatus.NOT_INSTALLED, ShizukuStatus.UNSUPPORTED -> stringResource(R.string.open_store)
        ShizukuStatus.NOT_RUNNING -> stringResource(R.string.shizuku_open)
        else -> stringResource(R.string.grant)
    }
    SetupRow(
        title = stringResource(R.string.setup_shizuku),
        description = description,
        done = status == ShizukuStatus.READY,
        action = action,
    ) { onShizukuAction(context, status) }
}

private fun onShizukuAction(context: Context, status: ShizukuStatus) {
    when (status) {
        ShizukuStatus.NOT_INSTALLED, ShizukuStatus.UNSUPPORTED -> {
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${ShizukuBridge.MANAGER_PACKAGE}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
        ShizukuStatus.NOT_RUNNING -> {
            ShizukuBridge.openManager(context)
        }
        ShizukuStatus.NO_PERMISSION -> {
            if (!ShizukuBridge.requestPermission()) {
                Toast.makeText(context, R.string.shizuku_grant_in_app, Toast.LENGTH_LONG).show()
                ShizukuBridge.openManager(context)
            }
        }
        ShizukuStatus.READY -> Unit
    }
}

@Composable
private fun LaunchMethodCard(settings: AppSettings, shizuku: ShizukuStatus) {
    val context = LocalContext.current
    var method by remember { mutableStateOf(settings.launchMethod) }

    fun choose(newMethod: LaunchMethod) {
        method = newMethod
        settings.launchMethod = newMethod
    }

    fun test(delayMs: Long) {
        val app = context.applicationContext
        val chosen = method
        if (delayMs > 0) Toast.makeText(app, R.string.launch_test_delayed_toast, Toast.LENGTH_LONG).show()
        Thread {
            Thread.sleep(delayMs)
            ClaudeLauncher.launch(app, chosen)
        }.start()
    }

    SectionCard(title = stringResource(R.string.launch_title)) {
        ChoiceRow(
            title = stringResource(R.string.launch_voice),
            note = if (shizuku == ShizukuStatus.READY) null else stringResource(R.string.launch_voice_needs_shizuku),
            selected = method == LaunchMethod.ASSIST_KEY,
        ) { choose(LaunchMethod.ASSIST_KEY) }
        ChoiceRow(
            title = stringResource(R.string.launch_text),
            note = null,
            selected = method == LaunchMethod.ASSIST_INTENT,
        ) { choose(LaunchMethod.ASSIST_INTENT) }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { test(0) }) { Text(stringResource(R.string.launch_test_now)) }
            OutlinedButton(onClick = { test(5_000) }) { Text(stringResource(R.string.launch_test_delayed)) }
        }
    }
}

@Composable
private fun ChoiceRow(title: String, note: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (note != null) {
                Text(
                    note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TuningCard(settings: AppSettings) {
    var threshold by remember { mutableFloatStateOf(settings.threshold) }
    var cooldown by remember { mutableIntStateOf(settings.cooldownSeconds) }

    SectionCard(title = stringResource(R.string.tuning_title)) {
        Text(stringResource(R.string.threshold_label, (threshold * 100).roundToInt()))
        Slider(
            value = threshold,
            onValueChange = { threshold = it },
            onValueChangeFinished = { settings.threshold = threshold },
            valueRange = AppSettings.MIN_THRESHOLD..AppSettings.MAX_THRESHOLD,
        )
        Text(
            stringResource(R.string.threshold_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text(stringResource(R.string.cooldown_label, cooldown))
        Slider(
            value = cooldown.toFloat(),
            onValueChange = { cooldown = it.roundToInt() },
            onValueChangeFinished = { settings.cooldownSeconds = cooldown },
            valueRange = AppSettings.MIN_COOLDOWN.toFloat()..AppSettings.MAX_COOLDOWN.toFloat(),
        )
        Text(
            stringResource(R.string.cooldown_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
