package com.aistudio

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.aistudio.db.LogEntry
import com.aistudio.db.SharedFolder
import com.aistudio.ui.ServerViewModel
import com.aistudio.ui.theme.MyApplicationTheme
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

import androidx.compose.foundation.isSystemInDarkTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private val viewModel: ServerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val themeMode by viewModel.themeState.collectAsState()
            val darkTheme = when (themeMode) {
                "light" -> false
                "dark" -> true
                else -> isSystemInDarkTheme()
            }
            MyApplicationTheme(darkTheme = darkTheme) {
                MainAppScreen(viewModel)
            }
        }
    }
}

fun checkHasStoragePermission(context: Context): Boolean {
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.os.Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    } catch (_: Throwable) {
        false
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppScreen(viewModel: ServerViewModel) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    
    val sharedPrefs = remember { context.getSharedPreferences("server_settings", Context.MODE_PRIVATE) }
    var showOnboarding by remember {
        mutableStateOf(!sharedPrefs.getBoolean("onboarding_completed", false))
    }

    if (showOnboarding) {
        com.aistudio.ui.SetupWizardScreen(viewModel = viewModel, onFinished = { showOnboarding = false })
        return
    }
    
    // Server state flows
    val isRunning by viewModel.isRunning.collectAsState()
    val serverUrl by viewModel.serverUrl.collectAsState()
    val folders by viewModel.allFolders.collectAsState()
    val logs by viewModel.allLogs.collectAsState()

    // Tab state selection
    var selectedTab by remember { mutableStateOf(0) }

    // Dialog state for adding folders
    var showAddFolderDialog by remember { mutableStateOf(false) }

    // Double-Back to Exit Logic
    var backPressedTime by remember { mutableStateOf(0L) }
    BackHandler {
        val now = System.currentTimeMillis()
        if (now - backPressedTime < 2000) {
            (context as? Activity)?.finish()
        } else {
            backPressedTime = now
            Toast.makeText(context, context.getString(R.string.toast_back_exit), Toast.LENGTH_SHORT).show()
        }
    }

    // Android 13+ Notification Permissions check
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Toast.makeText(context, context.getString(R.string.toast_perm_notification), Toast.LENGTH_LONG).show()
        }
    }

    var hasStoragePerm by remember {
        mutableStateOf(checkHasStoragePermission(context))
    }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.aistudio.server.RootAdvancedFeatures.ensureRootPrivilegesAndAutoGrant(context.packageName) { granted ->
                if (granted) {
                    val prefs = context.getSharedPreferences("server_settings", Context.MODE_PRIVATE)
                    prefs.edit().putBoolean("is_notification_enabled", true).apply()
                    com.aistudio.server.ServerManager.isNotificationEnabled = true
                    viewModel.notificationState.value = true
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                        hasStoragePerm = checkHasStoragePermission(context)
                    }
                }
            }
        }
    }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                hasStoragePerm = checkHasStoragePermission(context)
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
        if (!isGranted) {
            Toast.makeText(context, context.getString(R.string.toast_perm_storage), Toast.LENGTH_SHORT).show()
        }
    }

    val manageAllFilesMainLauncher = com.aistudio.ui.rememberManageAllFilesAutoReturnLauncher(
        onPermissionGranted = { hasStoragePerm = true },
        onSettingsUnavailable = {
            Toast.makeText(context, context.getString(R.string.toast_open_settings_error), Toast.LENGTH_SHORT).show()
        }
    )

    val requestStoragePermission = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            manageAllFilesMainLauncher()
        } else {
            storagePermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    LaunchedEffect(Unit) {
        if (com.aistudio.server.ServerManager.isNotificationEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(onTap = { focusManager.clearFocus() })
            },
        bottomBar = {
            // Standard Navigation Bar safeguarding bottom 3-button overlay with automatic insets
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)
            ) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Dns, contentDescription = stringResource(R.string.nav_server)) },
                    label = { Text(stringResource(R.string.nav_server)) },
                    modifier = Modifier.testTag("tab_server")
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.Folder, contentDescription = stringResource(R.string.nav_drives)) },
                    label = { Text(stringResource(R.string.nav_drives)) },
                    modifier = Modifier.testTag("tab_drives")
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Default.History, contentDescription = stringResource(R.string.nav_logs)) },
                    label = { Text(stringResource(R.string.nav_logs)) },
                    modifier = Modifier.testTag("tab_logs")
                )
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    icon = { Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.nav_settings)) },
                    label = { Text(stringResource(R.string.nav_settings)) },
                    modifier = Modifier.testTag("tab_settings")
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            when (selectedTab) {
                0 -> DashboardScreen(viewModel, isRunning, serverUrl, folders, hasStoragePerm, requestStoragePermission)
                1 -> SharesScreen(
                    folders = folders,
                    onAddClick = { showAddFolderDialog = true },
                    onDeleteClick = { id -> viewModel.deleteFolderShare(id) },
                    onToggleEnabled = { id, enabled -> viewModel.toggleFolderEnabled(id, enabled) }
                )
                2 -> LogsScreen(logs, onClear = { viewModel.clearLogHistory() })
                3 -> SettingsScreen(viewModel, onRerunWizard = {
                    sharedPrefs.edit().remove("wizard_saved_step").apply()
                    showOnboarding = true
                })
            }

            if (showAddFolderDialog) {
                AddFolderDialog(
                    onDismiss = { showAddFolderDialog = false },
                    onConfirm = { name, path, write ->
                        viewModel.addFolderShare(name, path, write)
                        showAddFolderDialog = false
                    }
                )
            }
        }
    }
}

// 1. Dashboard Screen (Tab 1)
@Composable
fun DashboardScreen(
    viewModel: ServerViewModel,
    isRunning: Boolean,
    serverUrl: String,
    folders: List<SharedFolder>,
    hasStoragePermission: Boolean,
    onRequestStoragePermission: () -> Unit
) {
    val context = LocalContext.current
    val hostIp = remember(isRunning) { com.aistudio.server.ServerManager.getLocalIpAddress() }

    // Pulsing circle animation for active server state
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    val serverBorderColor = if (isRunning) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
    } else {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
    }

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize()
    ) {
        val width = maxWidth
        val isWideScreen = width > 600.dp

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // App Header (compact without logo)
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    Text(
                        text = stringResource(R.string.app_name),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = stringResource(R.string.dashboard_core_engine),
                        fontSize = 8.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                    )
                }
            }
            if (!hasStoragePermission) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth().testTag("storage_permission_warning_card"),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = "Warning",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = stringResource(R.string.dashboard_perm_required),
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    fontSize = 16.sp
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.dashboard_perm_desc),
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.85f)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
                                onClick = onRequestStoragePermission,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error
                                ),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(stringResource(R.string.dashboard_btn_grant), color = MaterialTheme.colorScheme.onError)
                            }
                        }
                    }
                }
            }

            if (isWideScreen) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Column(
                            modifier = Modifier.weight(1.2f),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            ServerStatusCard(isRunning, serverUrl, hostIp, serverBorderColor, pulseScale)
                            ServerToggleButton(
                                isRunning = isRunning,
                                onClick = {
                                    if (isRunning) {
                                        viewModel.stopServer()
                                        Toast.makeText(context, context.getString(R.string.toast_server_stopped), Toast.LENGTH_SHORT).show()
                                    } else {
                                        viewModel.startServer()
                                        Toast.makeText(context, context.getString(R.string.toast_server_starting), Toast.LENGTH_SHORT).show()
                                    }
                                }
                            )
                        }

                        Column(
                            modifier = Modifier.weight(1.0f),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            if (isRunning) {
                                QrCodeCard(serverUrl, context)
                            }
                            AccessDetailsCard(hostIp, folders.size)
                            WindowsDriveToolsCard(serverUrl, hostIp, com.aistudio.server.ServerManager.port)
                        }
                    }
                }

                item {
                    FoldersListCard(folders, context)
                }
            } else {
                item {
                    ServerStatusCard(isRunning, serverUrl, hostIp, serverBorderColor, pulseScale)
                }

                if (isRunning) {
                    item {
                        QrCodeCard(serverUrl, context)
                    }
                }

                item {
                    ServerToggleButton(
                        isRunning = isRunning,
                        onClick = {
                            if (isRunning) {
                                viewModel.stopServer()
                                Toast.makeText(context, context.getString(R.string.toast_server_stopped), Toast.LENGTH_SHORT).show()
                            } else {
                                viewModel.startServer()
                                Toast.makeText(context, context.getString(R.string.toast_server_starting), Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                }

                item {
                    AccessDetailsCard(hostIp, folders.size)
                }

                item {
                    WindowsDriveToolsCard(serverUrl, hostIp, com.aistudio.server.ServerManager.port)
                }

                item {
                    FoldersListCard(folders, context)
                }
            }

            // Help info block
            item {
                HelpInfoCard(serverUrl)
            }
        }
    }
}

@Composable
fun ServerToggleButton(
    isRunning: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(58.dp)
            .testTag("action_toggle_server"),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isRunning) Color(0xFFB3261E) else MaterialTheme.colorScheme.primary
        ),
        shape = RoundedCornerShape(16.dp),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = if (isRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (isRunning) stringResource(R.string.settings_btn_shut_down) else stringResource(R.string.settings_btn_deploy),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun ServerStatusCard(
    isRunning: Boolean,
    serverUrl: String,
    hostIp: String,
    borderColor: Color,
    pulseScale: Float
) {
    val context = LocalContext.current
    val isWebdav = com.aistudio.server.ServerManager.isWebdavEnabled
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.5.dp,
                color = borderColor,
                shape = RoundedCornerShape(topStart = 24.dp, bottomEnd = 24.dp)
            ),
        shape = RoundedCornerShape(topStart = 24.dp, bottomEnd = 24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Box(
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(modifier = Modifier.matchParentSize()) {
                Spacer(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(1.dp)
                        .padding(start = 50.dp)
                        .background(borderColor.copy(alpha = 0.08f))
                )
                Spacer(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(1.dp)
                        .padding(start = 180.dp)
                        .background(borderColor.copy(alpha = 0.08f))
                )
                Spacer(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .padding(top = 90.dp)
                        .background(borderColor.copy(alpha = 0.08f))
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1F)) {
                        Text(
                            text = stringResource(R.string.dashboard_engine_status),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 2.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = if (isRunning) stringResource(R.string.dashboard_status_active) else stringResource(R.string.dashboard_status_stopped),
                                fontSize = 24.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = if (isRunning) Color(0xFF10B981) else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(16.dp)) {
                                if (isRunning) {
                                    Box(
                                        modifier = Modifier
                                            .size(14.dp * pulseScale)
                                            .background(Color(0xFF10B981).copy(alpha = 0.25f), shape = CircleShape)
                                    )
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .background(Color(0xFF10B981), shape = CircleShape)
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), shape = CircleShape)
                                    )
                                }
                            }
                        }
                    }

                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(52.dp)
                            .border(1.5.dp, borderColor.copy(alpha = 0.4f), shape = CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f), shape = CircleShape)
                    ) {
                        Icon(
                            imageVector = if (isRunning) Icons.Default.Dns else Icons.Default.CloudOff,
                            contentDescription = if (isRunning) stringResource(R.string.dashboard_status_active) else stringResource(R.string.dashboard_status_stopped),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = stringResource(R.string.dashboard_conn_address),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.background.copy(alpha = 0.5f), shape = RoundedCornerShape(8.dp))
                        .clickable {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Server URL", serverUrl))
                            Toast.makeText(context, context.getString(R.string.toast_copied), Toast.LENGTH_SHORT).show()
                        }
                        .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f), shape = RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isRunning) serverUrl else stringResource(R.string.dashboard_no_listening),
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium,
                        color = if (isRunning) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.weight(1F)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "Copy",
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                        modifier = Modifier.size(16.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .background(
                                color = if (isWebdav) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(4.dp)
                            )
                            .border(1.dp, if (isWebdav) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.15f), shape = RoundedCornerShape(4.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = if (isWebdav) stringResource(R.string.dashboard_webdav_compat) else stringResource(R.string.dashboard_webdav_standard),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isWebdav) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Box(
                        modifier = Modifier
                            .background(
                                color = if (isRunning) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(4.dp)
                            )
                            .border(1.dp, if (isRunning) MaterialTheme.colorScheme.secondary.copy(alpha = 0.3f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.15f), shape = RoundedCornerShape(4.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = if (isRunning) stringResource(R.string.dashboard_lan_ip_ready) else stringResource(R.string.dashboard_local_interface),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isRunning) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun QrCodeCard(
    serverUrl: String,
    context: Context
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                shape = RoundedCornerShape(topEnd = 24.dp, bottomStart = 24.dp)
            ),
        shape = RoundedCornerShape(topEnd = 24.dp, bottomStart = 24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(20.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.dashboard_qr_title),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(14.dp))

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(176.dp)
                    .border(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                    .padding(6.dp)
                    .border(2.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                    .padding(8.dp)
                    .testTag("qr_code_card")
            ) {
                QrCodeView(
                    content = serverUrl,
                    sizeDp = 144.dp,
                    qrColor = Color(0xFF1E1E1E),
                    backgroundColor = Color.White
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.dashboard_qr_desc),
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun FoldersListCard(
    folders: List<SharedFolder>,
    context: Context
) {
    val borderColor = MaterialTheme.colorScheme.outline
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                shape = RoundedCornerShape(16.dp)
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.dashboard_folders_title),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.5.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = stringResource(R.string.dashboard_folders_subtitle),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
                
                Box(
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(12.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = stringResource(R.string.dashboard_folders_active_badge, folders.size),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            if (folders.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            BorderStroke(1.dp, borderColor.copy(alpha = 0.3f)),
                            shape = RoundedCornerShape(12.dp)
                        )
                        .background(MaterialTheme.colorScheme.background.copy(alpha = 0.2f), shape = RoundedCornerShape(12.dp))
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.FolderOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.dashboard_no_folders),
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.dashboard_add_mount_desc),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    folders.forEach { folder ->
                        val isSdCard = folder.name.contains("SD", ignoreCase = true) ||
                                (folder.absolutePath.startsWith("/storage/") && !folder.absolutePath.startsWith("/storage/emulated"))
                        val dashIcon = when {
                            isSdCard -> Icons.Default.SdCard
                            folder.isDefault -> Icons.Default.Storage
                            else -> Icons.Default.FolderOpen
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.background.copy(alpha = 0.4f), shape = RoundedCornerShape(8.dp))
                                .border(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f), shape = RoundedCornerShape(8.dp))
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = dashIcon,
                                contentDescription = "Folder",
                                tint = if (folder.isEnabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.8f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                                modifier = Modifier.size(20.dp)
                             )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = folder.name,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = if (folder.isEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
                                    )
                                    Box(
                                        modifier = Modifier
                                            .background(
                                                color = if (folder.isEnabled) Color(0xFFE8F5E9) else Color(0xFFF3F4F6),
                                                shape = RoundedCornerShape(4.dp)
                                            )
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = if (folder.isEnabled) "Shared" else "Disabled",
                                            fontSize = 8.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = if (folder.isEnabled) Color(0xFF2E7D32) else Color(0xFF6B7280)
                                        )
                                    }
                                    Box(
                                        modifier = Modifier
                                            .background(
                                                color = if (folder.isWritable) Color(0xFFE8F5E9) else Color(0xFFECEFF1),
                                                shape = RoundedCornerShape(4.dp)
                                            )
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = if (folder.isWritable) stringResource(R.string.dashboard_read_write) else stringResource(R.string.dashboard_read_only),
                                            fontSize = 8.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = if (folder.isWritable) Color(0xFF2E7D32) else Color(0xFF455A64)
                                        )
                                    }
                                }
                                Text(
                                    text = folder.absolutePath,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AccessDetailsCard(
    hostIp: String,
    sharesCount: Int
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                shape = RoundedCornerShape(24.dp)
            ),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                text = stringResource(R.string.dashboard_access_details),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(14.dp))

            DetailItemRow(label = stringResource(R.string.dashboard_host_ip), value = hostIp, icon = Icons.Default.SettingsEthernet)
            Divider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(vertical = 10.dp))
            DetailItemRow(label = stringResource(R.string.dashboard_primary_port), value = com.aistudio.server.ServerManager.port.toString(), icon = Icons.Default.Numbers)
            Divider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(vertical = 10.dp))
            DetailItemRow(label = stringResource(R.string.dashboard_active_shares_path), value = stringResource(R.string.dashboard_mounts_count, sharesCount), icon = Icons.Default.FolderZip)
            Divider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(vertical = 10.dp))
            DetailItemRow(
                label = stringResource(R.string.dashboard_webdav_compatibility),
                value = if (com.aistudio.server.ServerManager.isWebdavEnabled) stringResource(R.string.dashboard_level_active) else stringResource(R.string.dashboard_disabled),
                icon = Icons.Default.CheckCircle,
                tintValue = if (com.aistudio.server.ServerManager.isWebdavEnabled) Color(0xFF10B981) else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun WindowsDriveToolsCard(
    serverUrl: String,
    hostIp: String,
    port: Int
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var showGuideDialog by remember { mutableStateOf(false) }

    val unlockCmd = "reg add \"HKLM\\SYSTEM\\CurrentControlSet\\Services\\WebClient\\Parameters\" /v FileSizeLimitInBytes /t REG_DWORD /d 0xffffffff /f & reg add \"HKLM\\SYSTEM\\CurrentControlSet\\Services\\WebClient\\Parameters\" /v BasicAuthLevel /t REG_DWORD /d 2 /f & net stop WebClient & net start WebClient & net use S: $serverUrl /persistent:yes"

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                shape = RoundedCornerShape(24.dp)
            ),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Computer,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "WINDOWS 1-CLICK TOOLS",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                TextButton(
                    onClick = { showGuideDialog = true },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text("Setup Guide", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "Mount as a Windows Drive letter (S:) without accounts or cloud sign-in.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 16.sp
            )
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("WebDAV Unlock Command", unlockCmd))
                        Toast.makeText(context, "Copied 4GB Unlock & Mount Command!", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Copy 4GB Cmd", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }

                Button(
                    onClick = {
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "Windows 1-Click Mount Script")
                            putExtra(Intent.EXTRA_TEXT, "Download Windows 1-Click Scripts directly from tablet:\n• Native WebDAV (4GB Unlock & Map S:): $serverUrl/tools/unlock-webdav.bat\n• Virtual Drive (WinFsp + Rclone): $serverUrl/tools/mount-rclone.bat\n• Universal Disconnect: $serverUrl/tools/disconnect-drive.bat\n\nDisclaimer: This app is an HTTP & WebDAV server. Client helper scripts are provided AS-IS at user's own risk.\n\nDirect Admin CMD:\n$unlockCmd")
                        }
                        context.startActivity(Intent.createChooser(shareIntent, "Share Windows Setup Scripts"))
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Share Scripts", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "⚠️ Disclaimer: HTTP/WebDAV server utility. Helper scripts & registry tweaks are optional and executed at user's own risk.",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                lineHeight = 13.sp
            )
        }
    }

    if (showGuideDialog) {
        AlertDialog(
            onDismissRequest = { showGuideDialog = false },
            title = {
                Text("Windows Network Drive Guide", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Native WebDAV
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("⚡ Native Windows WebDAV (Built-in)", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("Built into Windows Explorer. Open $serverUrl on PC and run '4GB Unlock & Map S:'. Removes the 50MB lock & 10s proxy freeze.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    // Virtual Drive
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("🚀 Virtual Drive (WinFsp + Rclone)", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.secondary)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("Uses WinFsp driver + Rclone. 100% offline, zero sign-up. Caches thumbnails locally and parallel streams large files. Auto-picks free drive letter.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    // Disconnect
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("🔌 Universal Disconnect & Cleaner", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("Unmount any drive letter (S:, Z:, or ALL) and purge stale Explorer ghost connections.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    // Disclaimer
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("⚠️ Disclaimer & Terms of Use", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("This software is strictly an HTTP & WebDAV file server. Helper scripts and system tweaks are provided AS-IS. You download, execute scripts, and modify system settings at your own risk. The author assumes no responsibility for system changes or data loss.", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showGuideDialog = false }) {
                    Text("Close")
                }
            }
        )
    }
}

@Composable
fun HelpInfoCard(serverUrl: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = "Tips",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = stringResource(R.string.dashboard_help_title),
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.dashboard_help_desc, serverUrl),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp
                )
            }
        }
    }
}

@Composable
fun DetailItemRow(
    label: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tintValue: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1F)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = label,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = value,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = tintValue,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
        )
    }
}

// 2. Shares Config Screen (Tab 2)
@Composable
fun SharesScreen(
    folders: List<SharedFolder>,
    onAddClick: () -> Unit,
    onDeleteClick: (Long) -> Unit,
    onToggleEnabled: (Long, Boolean) -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        if (folders.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.FolderOff,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(72.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.shares_empty_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(R.string.shares_empty_desc),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 18.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Text(
                        text = stringResource(R.string.shares_list_title),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }

                items(folders) { folder ->
                    val isSdCard = folder.name.contains("SD", ignoreCase = true) ||
                            (folder.absolutePath.startsWith("/storage/") && !folder.absolutePath.startsWith("/storage/emulated"))
                    val folderIcon = when {
                        isSdCard -> Icons.Default.SdCard
                        folder.isDefault -> Icons.Default.Storage
                        else -> Icons.Default.FolderOpen
                    }

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (folder.isEnabled) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .padding(16.dp)
                                .fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = folderIcon,
                                contentDescription = null,
                                tint = if (folder.isEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                modifier = Modifier.size(36.dp)
                            )
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1F)) {
                                Text(
                                    text = folder.name,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp,
                                    color = if (folder.isEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                )
                                Text(
                                    text = folder.absolutePath,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Status Badge (Shared vs Disabled)
                                    val statusColor = if (folder.isEnabled) Color(0xFF10B981) else Color(0xFF6B7280)
                                    val statusBg = if (folder.isEnabled) Color(0xFFECFDF5) else Color(0xFFF3F4F6)
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(statusBg)
                                            .padding(horizontal = 8.dp, vertical = 3.dp)
                                    ) {
                                        Text(
                                            text = if (folder.isEnabled) "Shared" else "Disabled",
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = statusColor
                                        )
                                    }

                                    // RW Badge
                                    val badgeColor = if (folder.isWritable) Color(0xFF10B981) else Color(0xFFEF4444)
                                    val badgeBg = if (folder.isWritable) Color(0xFFECFDF5) else Color(0xFFFEF2F2)
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(badgeBg)
                                            .padding(horizontal = 8.dp, vertical = 3.dp)
                                    ) {
                                        Text(
                                            text = if (folder.isWritable) stringResource(R.string.shares_badge_rw) else stringResource(R.string.shares_badge_ro),
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = badgeColor
                                        )
                                    }
                                    if (folder.isDefault) {
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(MaterialTheme.colorScheme.primaryContainer)
                                                .padding(horizontal = 8.dp, vertical = 3.dp)
                                        ) {
                                            Text(
                                                text = stringResource(R.string.shares_badge_default),
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer
                                            )
                                        }
                                    }
                                }
                            }

                            // Checkbox to toggle sharing on/off
                            Checkbox(
                                checked = folder.isEnabled,
                                onCheckedChange = { checked ->
                                    onToggleEnabled(folder.id, checked)
                                },
                                modifier = Modifier.testTag("checkbox_share_${folder.id}")
                            )

                            // Delete button (greyed out & disabled for default Internal Storage)
                            IconButton(
                                onClick = { if (!folder.isDefault) onDeleteClick(folder.id) },
                                enabled = !folder.isDefault,
                                modifier = Modifier.testTag("delete_share_button_${folder.id}")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = stringResource(R.string.shares_btn_delete_desc),
                                    tint = if (!folder.isDefault) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Floating button to introduce customizable directories
        FloatingActionButton(
            onClick = onAddClick,
            containerColor = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(24.dp)
                .testTag("fab_add_share"),
            shape = RoundedCornerShape(16.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = stringResource(R.string.shares_btn_add_desc),
                tint = Color.White
            )
        }
    }
}

// 3. Connection Request logs history (Tab 3)
@Composable
fun LogsScreen(logs: List<LogEntry>, onClear: () -> Unit) {
    if (logs.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.ListAlt,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(72.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.logs_empty_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = stringResource(R.string.logs_empty_desc),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
    } else {
        val dateFormat = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()) }
        
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.logs_title),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.logs_count_badge, logs.size),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                        IconButton(
                            onClick = onClear,
                            modifier = Modifier.testTag("clear_logs_button").size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteSweep,
                                contentDescription = stringResource(R.string.logs_btn_clear_desc),
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            items(logs) { log ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Row(
                        modifier = Modifier
                            .padding(12.dp)
                            .fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Method tag visual style color matching
                        val (methodBg, methodColor) = getMethodColorTokens(log.method)
                        Box(
                            modifier = Modifier
                                .width(68.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(methodBg)
                                .padding(vertical = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = log.method,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = methodColor
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1F)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                  Text(
                                    text = log.clientIp,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = dateFormat.format(Date(log.timestamp)),
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = log.uri,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1
                            )
                            if (log.details.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = stringResource(R.string.logs_details_prefix, log.details),
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.primary,
                                    lineHeight = 14.sp
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        // Status code pill representation
                        val statusColor = if (log.responseCode in 200..299) Color(0xFF10B981) else Color(0xFFEF4444)
                        val statusBg = if (log.responseCode in 200..299) Color(0xFFECFDF5) else Color(0xFFFEF2F2)
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(statusBg)
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = log.responseCode.toString(),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Black,
                                color = statusColor
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun getMethodColorTokens(method: String): Pair<Color, Color> {
    return when (method.uppercase()) {
        "GET" -> Pair(Color(0xFFECFDF5), Color(0xFF059669))
        "PUT" -> Pair(Color(0xFFEFF6FF), Color(0xFF2563EB))
        "DELETE" -> Pair(Color(0xFFFEF2F2), Color(0xFFDC2626))
        "PROPFIND" -> Pair(Color(0xFFFAF5FF), Color(0xFF7E22CE))
        "MKCOL" -> Pair(Color(0xFFFFF7ED), Color(0xFFC2410C))
        "OPTIONS" -> Pair(Color(0xFFF1F5F9), Color(0xFF475569))
        else -> Pair(Color(0xFFFCF8E3), Color(0xFFB58900))
    }
}

// 4. Server Configurations Modifier Screen (Tab 4)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: ServerViewModel, onRerunWizard: () -> Unit = {}) {
    val context = LocalContext.current
    
    val settingsPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Toast.makeText(context, context.getString(R.string.toast_notif_perm_required), Toast.LENGTH_LONG).show()
        }
    }
    
    val initialPort by viewModel.portState.collectAsState()
    val initialWebdav by viewModel.webdavState.collectAsState()
    val initialAnonymous by viewModel.anonymousState.collectAsState()
    val initialUsername by viewModel.usernameState.collectAsState()
    val initialPassword by viewModel.passwordState.collectAsState()
    val initialNotification by viewModel.notificationState.collectAsState()
    val initialTimeout by viewModel.timeoutState.collectAsState()
    val initialRootSimulation by viewModel.rootSimulationState.collectAsState()

    var portInput by remember { mutableStateOf(initialPort) }
    var webdavToggle by remember { mutableStateOf(initialWebdav) }
    var anonymousToggle by remember { mutableStateOf(initialAnonymous) }
    var usernameInput by remember { mutableStateOf(initialUsername) }
    var passwordInput by remember { mutableStateOf(initialPassword) }
    var notificationToggle by remember { mutableStateOf(initialNotification) }
    var timeoutInput by remember { mutableStateOf(initialTimeout) }
    var rootSimulationToggle by remember { mutableStateOf(initialRootSimulation) }
    val themeMode by viewModel.themeState.collectAsState()

    val triggerImmediateSave = { currentPort: String, currentWebdav: Boolean, currentAnonymous: Boolean, currentUser: String, currentPass: String, currentNotification: Boolean, currentTimeout: String, currentRootSimulation: Boolean ->
        val portNum = currentPort.toIntOrNull()
        val timeoutNum = currentTimeout.toIntOrNull()
        
        val validPort = if (portNum != null && portNum in 1024..65535) currentPort else initialPort
        val validTimeout = if (timeoutNum != null && timeoutNum >= 0) currentTimeout else initialTimeout
        
        viewModel.saveSettings(
            port = validPort,
            webdav = currentWebdav,
            anonymous = currentAnonymous,
            user = currentUser,
            pass = currentPass,
            notificationEnabled = currentNotification,
            timeoutMinutes = validTimeout,
            rootSimulation = currentRootSimulation
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                text = stringResource(R.string.settings_title),
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 2.dp)
            )
        }

        // Theme Style / Visual Appearance Selection Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.settings_theme_title),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val options = listOf(
                            Triple("system", stringResource(R.string.settings_theme_system), Icons.Default.Settings),
                            Triple("light", stringResource(R.string.settings_theme_light), Icons.Default.LightMode),
                            Triple("dark", stringResource(R.string.settings_theme_dark), Icons.Default.DarkMode)
                        )

                        options.forEach { (mode, label, icon) ->
                            val isSelected = themeMode == mode
                            val containerColor = if (isSelected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                            }
                            val contentColor = if (isSelected) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                            val borderColor = if (isSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                Color.Transparent
                            }

                            Card(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(72.dp)
                                    .clickable {
                                        viewModel.setThemePreference(mode)
                                    }
                                    .testTag("theme_btn_$mode"),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = containerColor),
                                border = if (isSelected) BorderStroke(2.dp, borderColor) else null
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxSize(),
                                    verticalArrangement = Arrangement.Center,
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Icon(
                                        imageVector = icon,
                                        contentDescription = null,
                                        tint = contentColor,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = contentColor,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Basics Params Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.settings_network_title),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(14.dp))

                    OutlinedTextField(
                        value = portInput,
                        onValueChange = { 
                            portInput = it
                            triggerImmediateSave(it, webdavToggle, anonymousToggle, usernameInput, passwordInput, notificationToggle, timeoutInput, rootSimulationToggle)
                        },
                        label = { Text(stringResource(R.string.settings_port_label)) },
                        placeholder = { Text(stringResource(R.string.settings_port_placeholder)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("settings_port_input"),
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Adjust, contentDescription = null) }
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1F)) {
                            Text(text = stringResource(R.string.settings_webdav_label), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text(text = stringResource(R.string.settings_webdav_desc), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = webdavToggle,
                            onCheckedChange = { 
                                webdavToggle = it
                                triggerImmediateSave(portInput, it, anonymousToggle, usernameInput, passwordInput, notificationToggle, timeoutInput, rootSimulationToggle)
                            },
                            modifier = Modifier.testTag("settings_webdav_switch")
                        )
                    }

                    Divider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(vertical = 12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1F)) {
                            Text(text = stringResource(R.string.settings_notif_label), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text(
                                text = if (notificationToggle) {
                                    stringResource(R.string.settings_notif_active_desc)
                                } else {
                                    stringResource(R.string.settings_notif_disabled_desc)
                                }, 
                                fontSize = 12.sp, 
                                color = if (notificationToggle) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                                fontWeight = if (notificationToggle) FontWeight.Normal else FontWeight.Medium
                            )
                        }
                        Switch(
                            checked = notificationToggle,
                            onCheckedChange = { isChecked ->
                                notificationToggle = isChecked
                                if (isChecked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                                        settingsPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                                    }
                                }
                                triggerImmediateSave(portInput, webdavToggle, anonymousToggle, usernameInput, passwordInput, isChecked, timeoutInput, rootSimulationToggle)
                            },
                            modifier = Modifier.testTag("settings_notification_switch")
                        )
                    }

                    Divider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(vertical = 12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val pm = remember { context.getSystemService(Context.POWER_SERVICE) as PowerManager }
                        var isOptIgnored by remember {
                            mutableStateOf(
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                    pm.isIgnoringBatteryOptimizations(context.packageName)
                                } else {
                                    true
                                }
                            )
                        }

                        // Also poll on resume to show updated state
                        val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
                        DisposableEffect(lifecycleOwner) {
                            val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                                if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                                    isOptIgnored = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                        pm.isIgnoringBatteryOptimizations(context.packageName)
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

                        Column(modifier = Modifier.weight(1F)) {
                            Text(text = stringResource(R.string.settings_battery_label), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text(
                                text = if (isOptIgnored) {
                                    stringResource(R.string.settings_battery_unrestricted_desc)
                                } else {
                                    stringResource(R.string.settings_battery_optimized_desc)
                                },
                                fontSize = 12.sp,
                                color = if (isOptIgnored) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                                fontWeight = if (isOptIgnored) FontWeight.Normal else FontWeight.Medium
                            )
                        }
                        Button(
                            onClick = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                    try {
                                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                            data = Uri.parse("package:${context.packageName}")
                                        }
                                        context.startActivity(intent)
                                    } catch (e: Exception) {
                                        try {
                                            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                            context.startActivity(intent)
                                        } catch (ex: Exception) {
                                            Toast.makeText(context, context.getString(R.string.toast_battery_settings_error), Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                } else {
                                    Toast.makeText(context, context.getString(R.string.toast_battery_not_supported), Toast.LENGTH_SHORT).show()
                                }
                            },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.padding(start = 8.dp)
                        ) {
                            Text(text = if (isOptIgnored) stringResource(R.string.settings_btn_battery_unrestricted) else stringResource(R.string.settings_btn_battery_configure), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Divider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(vertical = 12.dp))

                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(text = stringResource(R.string.settings_timeout_title), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text(text = stringResource(R.string.settings_timeout_desc), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedTextField(
                            value = timeoutInput,
                            onValueChange = { 
                                timeoutInput = it
                                triggerImmediateSave(portInput, webdavToggle, anonymousToggle, usernameInput, passwordInput, notificationToggle, it, rootSimulationToggle)
                            },
                            label = { Text(stringResource(R.string.settings_timeout_label)) },
                            placeholder = { Text(stringResource(R.string.settings_timeout_placeholder)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_timeout_input"),
                            singleLine = true,
                            leadingIcon = { Icon(Icons.Default.Timer, contentDescription = null) }
                        )
                    }
                }
            }
        }

        // Security settings card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.settings_auth_title),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1F)) {
                            Text(text = stringResource(R.string.settings_anonymous_label), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text(text = stringResource(R.string.settings_anonymous_desc), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = anonymousToggle,
                            onCheckedChange = { 
                                anonymousToggle = it
                                triggerImmediateSave(portInput, webdavToggle, it, usernameInput, passwordInput, notificationToggle, timeoutInput, rootSimulationToggle)
                            },
                            modifier = Modifier.testTag("settings_anonymous_switch")
                        )
                    }

                    if (!anonymousToggle) {
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        OutlinedTextField(
                            value = usernameInput,
                            onValueChange = { 
                                usernameInput = it
                                triggerImmediateSave(portInput, webdavToggle, anonymousToggle, it, passwordInput, notificationToggle, timeoutInput, rootSimulationToggle)
                            },
                            label = { Text(stringResource(R.string.settings_username_label)) },
                            leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_username_input")
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        OutlinedTextField(
                            value = passwordInput,
                            onValueChange = { 
                                passwordInput = it
                                triggerImmediateSave(portInput, webdavToggle, anonymousToggle, usernameInput, it, notificationToggle, timeoutInput, rootSimulationToggle)
                            },
                            label = { Text(stringResource(R.string.settings_password_label)) },
                            leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_password_input")
                        )
                    }
                }
            }
        }

        // Root Subsystem Settings Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.settings_system_privileges_title),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1F)) {
                            Text(text = stringResource(R.string.settings_root_features_title), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text(text = stringResource(R.string.settings_root_features_description), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = rootSimulationToggle,
                            onCheckedChange = { 
                                rootSimulationToggle = it
                                if (it) {
                                    com.aistudio.server.RootAdvancedFeatures.forceRootCheckAsync()
                                }
                                triggerImmediateSave(portInput, webdavToggle, anonymousToggle, usernameInput, passwordInput, notificationToggle, timeoutInput, it)
                            },
                            modifier = Modifier.testTag("settings_root_simulation_switch")
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    
                    val isRealRoot = com.aistudio.server.RootAdvancedFeatures.mIsDeviceRooted
                    val isActive = com.aistudio.server.RootAdvancedFeatures.isRootActive()
                    
                    Surface(
                        color = if (isRealRoot) {
                            androidx.compose.ui.graphics.Color(0xFFE8F5E9)
                        } else if (isActive) {
                            androidx.compose.ui.graphics.Color(0xFFE3F2FD)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (isRealRoot) Icons.Default.CheckCircle else if (isActive) Icons.Default.Info else Icons.Default.Lock,
                                contentDescription = null,
                                tint = if (isRealRoot) {
                                    androidx.compose.ui.graphics.Color(0xFF2E7D32)
                                } else if (isActive) {
                                    androidx.compose.ui.graphics.Color(0xFF1565C0)
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = if (isRealRoot) {
                                        "REAL ROOT ACCESS GRANTED"
                                    } else if (isActive) {
                                        "SIMULATED ROOT ACTIVE (MOCK MODE)"
                                    } else {
                                        "ROOT EXPLORER INACTIVE"
                                    },
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = if (isRealRoot) {
                                        androidx.compose.ui.graphics.Color(0xFF2E7D32)
                                    } else if (isActive) {
                                        androidx.compose.ui.graphics.Color(0xFF1565C0)
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                                Text(
                                    text = if (isRealRoot) {
                                        "This app is operating with full superuser privileges, acting as a real root file explorer."
                                    } else if (isActive) {
                                        "Operating on standard environment. Mock files (/system, /data/data) are generated for exploring."
                                    } else {
                                        "Enable Root Advanced Features to simulate root system files on unrooted devices."
                                    },
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Divider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(vertical = 12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val currentLocales = AppCompatDelegate.getApplicationLocales()
                        var isTraditionalChinese by remember { mutableStateOf(!currentLocales.isEmpty && currentLocales[0]?.language == "zh") }

                        Column(modifier = Modifier.weight(1F)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Image(
                                    painter = painterResource(id = R.drawable.ic_hk_flag),
                                    contentDescription = stringResource(R.string.cd_hk_flag),
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(text = stringResource(R.string.settings_language_label), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            }
                        }
                        Switch(
                            checked = isTraditionalChinese,
                            onCheckedChange = { 
                                isTraditionalChinese = it
                                val appLocale: LocaleListCompat = if (it) {
                                    LocaleListCompat.forLanguageTags("zh-HK")
                                } else {
                                    LocaleListCompat.getEmptyLocaleList()
                                }
                                AppCompatDelegate.setApplicationLocales(appLocale)
                            }
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(vertical = 12.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onRerunWizard() },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1F)) {
                            Text(
                                text = stringResource(R.string.settings_rerun_wizard),
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = stringResource(R.string.settings_rerun_wizard_desc),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { onRerunWizard() }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }
}

// 5. Shared Drive creation prompt mapping dialog
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddFolderDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String, Boolean) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var path by remember { mutableStateOf("") }
    var writable by remember { mutableStateOf(true) }
    var showFolderPicker by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.add_folder_dialog_title),
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(R.string.add_folder_dialog_desc),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.add_folder_dialog_name_label)) },
                    placeholder = { Text(stringResource(R.string.add_folder_dialog_name_placeholder)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("dialog_share_name")
                )

                OutlinedTextField(
                    value = path,
                    onValueChange = { path = it },
                    label = { Text(stringResource(R.string.add_folder_dialog_path_label)) },
                    placeholder = { Text(stringResource(R.string.add_folder_dialog_path_placeholder)) },
                    singleLine = true,
                    trailingIcon = {
                        IconButton(
                            onClick = { showFolderPicker = true },
                            modifier = Modifier.testTag("dialog_browse_folder_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.FolderOpen,
                                contentDescription = stringResource(R.string.add_folder_dialog_browse_desc)
                            )
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("dialog_share_path")
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1F)) {
                        Text(text = stringResource(R.string.add_folder_dialog_write_label), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text(text = stringResource(R.string.add_folder_dialog_write_desc), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = writable,
                        onCheckedChange = { writable = it },
                        modifier = Modifier.testTag("dialog_writable_switch")
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.trim().isEmpty() || path.trim().isEmpty()) {
                        return@Button
                    }
                    val targetFile = File(path)
                    if (!targetFile.exists() || !targetFile.isDirectory) {
                        // Attempt to make directory automatically
                        val success = targetFile.mkdirs()
                        if (!success) {
                            // Non blocking warning
                        }
                    }
                    onConfirm(name.trim(), path.trim(), writable)
                },
                modifier = Modifier.testTag("dialog_confirm_button")
            ) {
                Text(stringResource(R.string.add_folder_dialog_btn_confirm))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("dialog_cancel_button")
            ) {
                Text(stringResource(R.string.btn_cancel))
            }
        }
    )

    if (showFolderPicker) {
        FolderPickerDialog(
            onDismiss = { showFolderPicker = false },
            onSelect = { selectedPath ->
                path = selectedPath
                if (name.trim().isEmpty()) {
                    name = selectedPath.substringAfterLast("/")
                }
                showFolderPicker = false
            }
        )
    }
}

@Composable
fun QuickPathChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .border(
                width = if (isSelected) 0.dp else 1.dp,
                color = if (isSelected) androidx.compose.ui.graphics.Color.Transparent else MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(16.dp)
            )
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderPickerDialog(
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val removableStorage = remember { com.aistudio.server.ServerManager.detectRemovableStorage(context) }
    var currentDir by remember { mutableStateOf(File("/storage/emulated/0")) }
    val directories = remember(currentDir) {
        try {
            com.aistudio.server.RootAdvancedFeatures.fileListFiles(currentDir)
                .filter { com.aistudio.server.RootAdvancedFeatures.fileIsDirectory(it) && !it.name.startsWith(".") }
                .sortedBy { it.name.lowercase() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(
                    text = stringResource(R.string.picker_title),
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                // Beautiful current path surface
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = currentDir.absolutePath,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                // Row of storage selectors
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    QuickPathChip(
                        label = "Shared",
                        isSelected = currentDir.absolutePath.startsWith("/storage/emulated/0"),
                        onClick = { currentDir = File("/storage/emulated/0") }
                    )
                    for ((label, rPath) in removableStorage) {
                        QuickPathChip(
                            label = label,
                            isSelected = currentDir.absolutePath.startsWith(rPath),
                            onClick = { currentDir = File(rPath) }
                        )
                    }
                    QuickPathChip(
                        label = "App /data",
                        isSelected = currentDir.absolutePath.startsWith(context.dataDir.absolutePath),
                        onClick = { currentDir = context.dataDir }
                    )
                    if (com.aistudio.server.RootAdvancedFeatures.isRootActive()) {
                        QuickPathChip(
                            label = "Root /",
                            isSelected = currentDir.absolutePath == "/" || (!currentDir.absolutePath.startsWith("/storage/emulated/0") && !removableStorage.any { currentDir.absolutePath.startsWith(it.second) } && !currentDir.absolutePath.startsWith(context.dataDir.absolutePath)),
                            onClick = { currentDir = File("/") }
                        )
                    }
                }
            }
        },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
            ) {
                if (directories.isEmpty() && (currentDir.absolutePath == "/storage/emulated/0" || currentDir.absolutePath == context.dataDir.absolutePath || currentDir.absolutePath == "/")) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(16.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.Folder,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.picker_empty),
                            fontSize = 12.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // "Up a level" row
                        val parentFile = currentDir.parentFile
                        val isAtBase = if (com.aistudio.server.RootAdvancedFeatures.isRootActive()) {
                            currentDir.absolutePath == "/"
                        } else {
                            currentDir.absolutePath == "/storage/emulated/0" || 
                            currentDir.absolutePath == context.dataDir.absolutePath ||
                            removableStorage.any { currentDir.absolutePath == it.second } ||
                            currentDir.absolutePath == "/"
                        }
                        if (parentFile != null && !isAtBase) {
                            item {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .minimumInteractiveComponentSize()
                                        .clickable {
                                            currentDir = parentFile
                                        }
                                        .padding(vertical = 8.dp, horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ArrowBack,
                                        contentDescription = stringResource(R.string.picker_btn_up_desc),
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = stringResource(R.string.picker_up_level),
                                        fontWeight = FontWeight.Medium,
                                        fontSize = 14.sp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }

                        if (directories.isEmpty()) {
                            item {
                                Box(
                                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = stringResource(R.string.picker_empty_folder),
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }
                            }
                        } else {
                            items(directories) { dir ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .minimumInteractiveComponentSize()
                                        .clickable {
                                            currentDir = dir
                                        }
                                        .padding(vertical = 8.dp, horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Folder,
                                        contentDescription = stringResource(R.string.picker_cd_directory),
                                        tint = MaterialTheme.colorScheme.secondary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = dir.name,
                                        fontSize = 14.sp,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSelect(currentDir.absolutePath)
                },
                modifier = Modifier.testTag("dialog_folder_picker_select_button")
            ) {
                Text(stringResource(R.string.picker_btn_select))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("dialog_folder_picker_cancel_button")
            ) {
                Text(stringResource(R.string.btn_cancel))
            }
        }
    )
}

@Composable
fun QrCodeView(
    content: String,
    modifier: Modifier = Modifier,
    sizeDp: androidx.compose.ui.unit.Dp = 160.dp,
    qrColor: Color = Color(0xFF1E1E1E),
    backgroundColor: Color = Color.White
) {
    if (content.isBlank()) {
        Box(
            modifier = modifier
                .size(sizeDp)
                .background(Color(0xFFF5F5F7), shape = RoundedCornerShape(12.dp))
                .padding(12.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.CloudOff,
                    contentDescription = null,
                    tint = Color.Gray,
                    modifier = Modifier.size(36.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.qr_offline),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.Gray,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.qr_offline_sub),
                    fontSize = 10.sp,
                    color = Color.Gray,
                    textAlign = TextAlign.Center
                )
            }
        }
    } else {
        val sizePx = 250 // Matrix size
        val bitMatrix = remember(content) {
            try {
                val writer = QRCodeWriter()
                val hints = mapOf(
                    EncodeHintType.MARGIN to 1
                )
                writer.encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }

        if (bitMatrix != null) {
            val width = bitMatrix.width
            val height = bitMatrix.height
            Box(
                modifier = modifier
                    .size(sizeDp)
                    .background(backgroundColor, shape = RoundedCornerShape(12.dp))
                    .padding(8.dp),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val moduleSizeX = size.width / width
                    val moduleSizeY = size.height / height
                    for (y in 0 until height) {
                        for (x in 0 until width) {
                            if (bitMatrix.get(x, y)) {
                                drawRect(
                                    color = qrColor,
                                    topLeft = androidx.compose.ui.geometry.Offset(x * moduleSizeX, y * moduleSizeY),
                                    size = androidx.compose.ui.geometry.Size(moduleSizeX + 0.3f, moduleSizeY + 0.3f)
                                )
                            }
                        }
                    }
                }
            }
        } else {
            Box(
                modifier = modifier
                    .size(sizeDp)
                    .background(Color(0xFFFFF2F2), shape = RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(12.dp)) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.qr_error),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
fun OnboardingSetupScreen(
    viewModel: ServerViewModel,
    onFinished: () -> Unit
) {
    val context = LocalContext.current
    val powerManager = remember { context.getSystemService(Context.POWER_SERVICE) as PowerManager }

    var hasStoragePerm by remember {
        mutableStateOf(checkHasStoragePermission(context))
    }

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

    LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.aistudio.server.RootAdvancedFeatures.ensureRootPrivilegesAndAutoGrant(context.packageName) { granted ->
                if (granted) {
                    val prefs = context.getSharedPreferences("server_settings", Context.MODE_PRIVATE)
                    prefs.edit()
                        .putBoolean("is_notification_enabled", true)
                        .putBoolean("onboarding_completed", true)
                        .apply()
                    com.aistudio.server.ServerManager.isNotificationEnabled = true
                    viewModel.notificationState.value = true
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                        hasStoragePerm = true
                        hasNotificationPerm = true
                        isIgnoringBattery = true
                        Toast.makeText(
                            context,
                            context.getString(R.string.toast_root_auto_configured),
                            Toast.LENGTH_LONG
                        ).show()
                        onFinished()
                    }
                }
            }
        }
    }

    // Refresh states when returning from settings
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

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasNotificationPerm = isGranted
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
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
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .testTag("onboarding_language_toggle"),
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Language,
                        contentDescription = "Switch Language / 切換語言",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isTraditionalChinese) "English" else "中文",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 24.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Button(
                    onClick = {
                        val prefs = context.getSharedPreferences("server_settings", Context.MODE_PRIVATE)
                        prefs.edit().putBoolean("onboarding_completed", true).apply()
                        
                        // Sync notification preference with the permission state from onboarding
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
                        
                        onFinished()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .testTag("onboarding_continue_button"),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(
                        text = stringResource(R.string.onboarding_btn_continue),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.onboarding_footnote),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.wizard_welcome_heading),
                    style = MaterialTheme.typography.headlineMedium,
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
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.onboarding_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            if (com.aistudio.server.RootAdvancedFeatures.isRootActive()) {
                item {
                    Surface(
                        color = Color(0xFFE8F5E9),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = Color(0xFF2E7D32),
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = stringResource(R.string.onboarding_root_detected_title),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
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
                }
            }

                // Card 1: Storage Permission
                item {
                    val onboardingManageAllFiles = com.aistudio.ui.rememberManageAllFilesAutoReturnLauncher(
                        onPermissionGranted = { hasStoragePerm = true },
                        onSettingsUnavailable = {
                            Toast.makeText(context, context.getString(R.string.toast_system_settings_error), Toast.LENGTH_SHORT).show()
                        }
                    )
                    OnboardingStepCard(
                        title = stringResource(R.string.onboarding_step_storage_title),
                        description = stringResource(R.string.onboarding_step_storage_desc),
                        isConfigured = hasStoragePerm,
                        buttonText = stringResource(R.string.onboarding_step_storage_btn),
                        icon = Icons.Default.Folder,
                        onAction = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                onboardingManageAllFiles()
                            } else {
                                storagePermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                            }
                        }
                    )
                }

                // Card 2: Notification Permission
                item {
                    OnboardingStepCard(
                        title = stringResource(R.string.onboarding_step_notifications_title),
                        description = stringResource(R.string.onboarding_step_notifications_desc),
                        isConfigured = hasNotificationPerm,
                        buttonText = stringResource(R.string.onboarding_step_notifications_btn),
                        icon = Icons.Default.Notifications,
                        onAction = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                Toast.makeText(context, context.getString(R.string.toast_notifications_auto_enabled), Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                }

                // Card 3: Battery Optimization Setting
                item {
                    OnboardingStepCard(
                        title = stringResource(R.string.onboarding_step_battery_title),
                        description = stringResource(R.string.onboarding_step_battery_desc),
                        isConfigured = isIgnoringBattery,
                        buttonText = stringResource(R.string.onboarding_step_battery_btn),
                        icon = Icons.Default.Bolt,
                        onAction = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                try {
                                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                        data = Uri.parse("package:${context.packageName}")
                                    }
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    try {
                                        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                        context.startActivity(intent)
                                    } catch (ex: Exception) {
                                        Toast.makeText(context, context.getString(R.string.toast_battery_settings_error), Toast.LENGTH_SHORT).show()
                                    }
                                }
                            } else {
                                Toast.makeText(context, context.getString(R.string.toast_battery_not_supported), Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                }

                item {
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
    }
}

@Composable
fun OnboardingStepCard(
    title: String,
    description: String,
    isConfigured: Boolean,
    buttonText: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onAction: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                BorderStroke(
                    width = 1.dp,
                    color = if (isConfigured) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant
                ),
                shape = RoundedCornerShape(16.dp)
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (isConfigured) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(
                            color = if (isConfigured) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f) else MaterialTheme.colorScheme.surfaceVariant,
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (isConfigured) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isConfigured) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = stringResource(R.string.onboarding_step_cd_configured),
                                tint = Color(0xFF10B981),
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = stringResource(R.string.onboarding_step_enabled),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF10B981)
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = stringResource(R.string.onboarding_step_cd_pending),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = stringResource(R.string.onboarding_step_pending),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }

            Text(
                text = description,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (!isConfigured) {
                Button(
                    onClick = onAction,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary
                    ),
                    modifier = Modifier.fillMaxWidth().height(40.dp)
                ) {
                    Text(text = buttonText, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
