package com.aistudio.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import com.aistudio.R
import com.aistudio.checkHasStoragePermission
import com.aistudio.server.RootAdvancedFeatures
import com.aistudio.server.ServerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TOTAL_STEPS = 5

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupWizardScreen(
    viewModel: ServerViewModel,
    onFinished: () -> Unit
) {
    val context = LocalContext.current
    val powerManager = remember { context.getSystemService(Context.POWER_SERVICE) as PowerManager }
    val wizardPrefs = remember { context.getSharedPreferences("server_settings", Context.MODE_PRIVATE) }

    var currentStep by remember {
        mutableStateOf(wizardPrefs.getInt("wizard_saved_step", 0).coerceIn(0, TOTAL_STEPS - 1))
    }

    LaunchedEffect(currentStep) {
        wizardPrefs.edit().putInt("wizard_saved_step", currentStep).apply()
    }

    // Live permission states
    var hasStoragePerm by remember { mutableStateOf(checkHasStoragePermission(context)) }
    var hasNotificationPerm by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        )
    }
    var isIgnoringBattery by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                powerManager.isIgnoringBatteryOptimizations(context.packageName)
            } else {
                true
            }
        )
    }

    var startServerImmediately by remember { mutableStateOf(true) }

    // Check & auto-grant if root is active
    val isRootActive = RootAdvancedFeatures.isRootActive()
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            RootAdvancedFeatures.ensureRootPrivilegesAndAutoGrant(context.packageName) { granted ->
                if (granted) {
                    val prefs = context.getSharedPreferences("server_settings", Context.MODE_PRIVATE)
                    prefs.edit()
                        .putBoolean("is_notification_enabled", true)
                        .apply()
                    ServerManager.isNotificationEnabled = true
                    viewModel.notificationState.value = true
                    (context as? Activity)?.runOnUiThread {
                        hasStoragePerm = true
                        hasNotificationPerm = true
                        isIgnoringBattery = true
                    }
                }
            }
        }
    }

    // Refresh states when returning from system settings
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                hasStoragePerm = checkHasStoragePermission(context)
                hasNotificationPerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED
                } else {
                    true
                }
                isIgnoringBattery = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    powerManager.isIgnoringBatteryOptimizations(context.packageName)
                } else {
                    true
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val storagePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasStoragePerm = isGranted
    }

    val manageAllFilesLauncher = rememberManageAllFilesAutoReturnLauncher(
        onPermissionGranted = { hasStoragePerm = true },
        onSettingsUnavailable = {
            Toast.makeText(context, context.getString(R.string.toast_system_settings_error), Toast.LENGTH_SHORT).show()
        }
    )

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasNotificationPerm = isGranted
    }

    // Back handler for wizard navigation
    BackHandler {
        if (currentStep > 0) {
            currentStep--
        } else {
            (context as? Activity)?.finish()
        }
    }

    fun completeWizard(startServer: Boolean) {
        wizardPrefs.edit()
            .putBoolean("onboarding_completed", true)
            .remove("wizard_saved_step")
            .apply()

        viewModel.saveSettings(
            port = viewModel.portState.value,
            webdav = viewModel.webdavState.value,
            anonymous = viewModel.anonymousState.value,
            user = viewModel.usernameState.value,
            pass = viewModel.passwordState.value,
            notificationEnabled = hasNotificationPerm,
            timeoutMinutes = viewModel.timeoutState.value,
            rootSimulation = viewModel.rootSimulationState.value
        )

        if (startServer) {
            viewModel.startServer()
        }
        onFinished()
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .statusBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Wizard Step indicator badge
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.wizard_step_counter, currentStep + 1, TOTAL_STEPS),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Skip Wizard button
                        TextButton(
                            onClick = { completeWizard(startServer = false) },
                            modifier = Modifier.testTag("wizard_skip_button")
                        ) {
                            Text(
                                text = stringResource(R.string.wizard_btn_skip),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        // Language selector
                        val currentLocales = AppCompatDelegate.getApplicationLocales()
                        val isTraditionalChinese = !currentLocales.isEmpty && currentLocales[0]?.language == "zh"
                        TextButton(
                            onClick = {
                                val appLocale: LocaleListCompat = if (isTraditionalChinese) {
                                    LocaleListCompat.getEmptyLocaleList()
                                } else {
                                    LocaleListCompat.forLanguageTags("zh-HK")
                                }
                                AppCompatDelegate.setApplicationLocales(appLocale)
                            },
                            modifier = Modifier.testTag("onboarding_language_toggle")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Language,
                                contentDescription = "Language",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (isTraditionalChinese) "English" else "中文",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                // Progress Bar
                LinearProgressIndicator(
                    progress = { (currentStep + 1).toFloat() / TOTAL_STEPS.toFloat() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }
        },
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (currentStep > 0) {
                        OutlinedButton(
                            onClick = { currentStep-- },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.testTag("wizard_prev_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.wizard_btn_previous))
                        }
                    } else {
                        Spacer(modifier = Modifier.width(1.dp))
                    }

                    if (currentStep < TOTAL_STEPS - 1) {
                        Button(
                            onClick = { currentStep++ },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.testTag("wizard_next_button")
                        ) {
                            Text(
                                text = stringResource(R.string.wizard_btn_next),
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    } else {
                        Button(
                            onClick = { completeWizard(startServer = startServerImmediately) },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            ),
                            modifier = Modifier.testTag("wizard_finish_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.wizard_btn_finish),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            AnimatedContent(
                targetState = currentStep,
                transitionSpec = {
                    if (targetState > initialState) {
                        (slideInHorizontally { width -> width } + fadeIn()).togetherWith(
                            slideOutHorizontally { width -> -width } + fadeOut()
                        )
                    } else {
                        (slideInHorizontally { width -> -width } + fadeIn()).togetherWith(
                            slideOutHorizontally { width -> width } + fadeOut()
                        )
                    }
                },
                label = "WizardStepAnimation"
            ) { step ->
                when (step) {
                    0 -> StepWelcome(
                        isRootActive = isRootActive,
                        onNext = { currentStep = 1 }
                    )
                    1 -> StepStorage(
                        hasStoragePerm = hasStoragePerm,
                        onGrant = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                manageAllFilesLauncher()
                            } else {
                                storagePermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                            }
                        }
                    )
                    2 -> StepNotifications(
                        hasNotificationPerm = hasNotificationPerm,
                        onGrant = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                Toast.makeText(context, context.getString(R.string.toast_notifications_auto_enabled), Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                    3 -> StepBattery(
                        isIgnoringBattery = isIgnoringBattery,
                        onConfigure = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                try {
                                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                        data = Uri.parse("package:${context.packageName}")
                                    }
                                    context.startActivity(intent)
                                } catch (_: Exception) {
                                    try {
                                        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                        context.startActivity(intent)
                                    } catch (_: Exception) {
                                        Toast.makeText(context, context.getString(R.string.toast_battery_settings_error), Toast.LENGTH_SHORT).show()
                                    }
                                }
                            } else {
                                Toast.makeText(context, context.getString(R.string.toast_battery_not_supported), Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                    4 -> StepCompletion(
                        hasStoragePerm = hasStoragePerm,
                        hasNotificationPerm = hasNotificationPerm,
                        isIgnoringBattery = isIgnoringBattery,
                        startImmediately = startServerImmediately,
                        onStartImmediatelyChange = { startServerImmediately = it }
                    )
                }
            }
        }
    }
}

@Composable
private fun StepWelcome(
    isRootActive: Boolean,
    onNext: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.wizard_welcome_heading),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Start,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = stringResource(R.string.wizard_title_sub),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Start,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = stringResource(R.string.wizard_welcome_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Start,
            lineHeight = 20.sp,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(20.dp))

        if (isRootActive) {
            Surface(
                color = Color(0xFFE8F5E9),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = Color(0xFF2E7D32),
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.onboarding_root_detected_title),
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = Color(0xFF2E7D32)
                        )
                        Text(
                            text = stringResource(R.string.onboarding_root_detected_desc),
                            fontSize = 11.sp,
                            color = Color(0xFF1B5E20)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // 3 Key highlights
        FeatureHighlightCard(
            icon = Icons.Default.CloudOff,
            title = stringResource(R.string.wizard_welcome_feature1_title),
            desc = stringResource(R.string.wizard_welcome_feature1_desc)
        )
        Spacer(modifier = Modifier.height(10.dp))
        FeatureHighlightCard(
            icon = Icons.Default.Bolt,
            title = stringResource(R.string.wizard_welcome_feature2_title),
            desc = stringResource(R.string.wizard_welcome_feature2_desc)
        )
        Spacer(modifier = Modifier.height(10.dp))
        FeatureHighlightCard(
            icon = Icons.Default.Dns,
            title = stringResource(R.string.wizard_welcome_feature3_title),
            desc = stringResource(R.string.wizard_welcome_feature3_desc)
        )

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun FeatureHighlightCard(icon: ImageVector, title: String, desc: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column {
                Text(
                    text = title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = desc,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun StepStorage(
    hasStoragePerm: Boolean,
    onGrant: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        WizardStepHeader(
            icon = Icons.Default.Folder,
            title = stringResource(R.string.wizard_storage_title),
            description = stringResource(R.string.wizard_storage_body)
        )

        Spacer(modifier = Modifier.height(20.dp))

        // Status Card
        WizardStatusCard(
            isConfigured = hasStoragePerm,
            configuredText = stringResource(R.string.wizard_storage_status_granted),
            pendingText = stringResource(R.string.wizard_storage_status_needed),
            buttonText = stringResource(R.string.onboarding_step_storage_btn),
            onAction = onGrant
        )

        Spacer(modifier = Modifier.height(16.dp))

        Surface(
            color = if (hasStoragePerm) Color(0xFFE8F5E9) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (hasStoragePerm) Icons.Default.CheckCircle else Icons.Default.Info,
                    contentDescription = null,
                    tint = if (hasStoragePerm) Color(0xFF2E7D32) else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = stringResource(
                        if (hasStoragePerm) R.string.wizard_storage_tip_granted else R.string.wizard_storage_tip
                    ),
                    fontSize = 12.sp,
                    color = if (hasStoragePerm) Color(0xFF1B5E20) else MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 18.sp
                )
            }
        }
    }
}

@Composable
private fun StepNotifications(
    hasNotificationPerm: Boolean,
    onGrant: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        WizardStepHeader(
            icon = Icons.Default.Notifications,
            title = stringResource(R.string.wizard_notif_title),
            description = stringResource(R.string.wizard_notif_body)
        )

        Spacer(modifier = Modifier.height(20.dp))

        WizardStatusCard(
            isConfigured = hasNotificationPerm,
            configuredText = stringResource(R.string.wizard_notif_status_enabled),
            pendingText = stringResource(R.string.wizard_notif_status_needed),
            buttonText = stringResource(R.string.onboarding_step_notifications_btn),
            onAction = onGrant
        )
    }
}

@Composable
private fun StepBattery(
    isIgnoringBattery: Boolean,
    onConfigure: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        WizardStepHeader(
            icon = Icons.Default.Bolt,
            title = stringResource(R.string.wizard_battery_title),
            description = stringResource(R.string.wizard_battery_body)
        )

        Spacer(modifier = Modifier.height(20.dp))

        WizardStatusCard(
            isConfigured = isIgnoringBattery,
            configuredText = stringResource(R.string.wizard_battery_status_unrestricted),
            pendingText = stringResource(R.string.wizard_battery_status_optimized),
            buttonText = stringResource(R.string.onboarding_step_battery_btn),
            onAction = onConfigure
        )
    }
}

@Composable
private fun StepCompletion(
    hasStoragePerm: Boolean,
    hasNotificationPerm: Boolean,
    isIgnoringBattery: Boolean,
    startImmediately: Boolean,
    onStartImmediatelyChange: (Boolean) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(10.dp))

        Box(
            modifier = Modifier
                .size(72.dp)
                .background(Color(0xFFE8F5E9), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = Color(0xFF2E7D32),
                modifier = Modifier.size(40.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.wizard_complete_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = stringResource(R.string.wizard_complete_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(20.dp))

        // Summary Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SummaryRow(
                    label = "Storage Access",
                    status = if (hasStoragePerm) "Granted" else "Limited / Sandbox",
                    isGood = hasStoragePerm
                )
                SummaryRow(
                    label = "Foreground Service",
                    status = if (hasNotificationPerm) "Active" else "Disabled",
                    isGood = hasNotificationPerm
                )
                SummaryRow(
                    label = "Battery Optimization",
                    status = if (isIgnoringBattery) "Unrestricted" else "Optimized",
                    isGood = isIgnoringBattery
                )
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // Auto-start switch
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.wizard_start_now),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Switch(
                    checked = startImmediately,
                    onCheckedChange = onStartImmediatelyChange
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
    }
}

@Composable
private fun SummaryRow(label: String, status: String, isGood: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = status,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = if (isGood) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error
        )
    }
}

@Composable
private fun WizardStepHeader(
    icon: ImageVector,
    title: String,
    description: String
) {
    Column {
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 20.sp
        )
    }
}

@Composable
private fun WizardStatusCard(
    isConfigured: Boolean,
    configuredText: String,
    pendingText: String,
    buttonText: String,
    onAction: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isConfigured) Color(0xFFE8F5E9) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        border = BorderStroke(
            1.dp,
            if (isConfigured) Color(0xFF81C784) else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (isConfigured) Icons.Default.CheckCircle else Icons.Default.Warning,
                        contentDescription = null,
                        tint = if (isConfigured) Color(0xFF2E7D32) else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = if (isConfigured) configuredText else pendingText,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = if (isConfigured) Color(0xFF1B5E20) else MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            if (!isConfigured) {
                Button(
                    onClick = onAction,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(buttonText, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
