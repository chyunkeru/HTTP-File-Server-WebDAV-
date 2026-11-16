package com.aistudio.ui

import android.app.Activity
import android.app.AppOpsManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.aistudio.MainActivity
import com.aistudio.checkHasStoragePermission
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

const val REQUEST_CODE_MANAGE_STORAGE = 4001

tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Remembers a launcher that opens the Android "All files access" system settings page
 * on Android 11+ (API 30+) and automatically closes the settings screen to return to
 * the app as soon as "Allow access to manage all files" is enabled.
 */
@Composable
fun rememberManageAllFilesAutoReturnLauncher(
    onPermissionGranted: () -> Unit,
    onSettingsUnavailable: () -> Unit
): () -> Unit {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var launchToken by remember { mutableIntStateOf(0) }
    var hasPausedSinceLaunch by remember { mutableStateOf(false) }

    val currentOnGranted by rememberUpdatedState(onPermissionGranted)
    val currentOnUnavailable by rememberUpdatedState(onSettingsUnavailable)

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    if (launchToken > 0) {
                        hasPausedSinceLaunch = true
                    }
                }
                Lifecycle.Event.ON_RESUME -> {
                    if (launchToken > 0 && hasPausedSinceLaunch) {
                        launchToken = 0
                        hasPausedSinceLaunch = false
                        if (checkHasStoragePermission(context)) {
                            currentOnGranted()
                        }
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(launchToken) {
        if (launchToken == 0 || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return@LaunchedEffect

        val activity = context.findActivity()
        val appOps = try {
            context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
        } catch (_: Throwable) {
            null
        }

        val grantedSignal = CompletableDeferred<Unit>()
        var opListener: AppOpsManager.OnOpChangedListener? = null

        try {
            val listener = AppOpsManager.OnOpChangedListener { op, packageName ->
                if (packageName == context.packageName &&
                    op.contains("manage_external_storage", ignoreCase = true) &&
                    checkHasStoragePermission(context)
                ) {
                    grantedSignal.complete(Unit)
                }
            }
            opListener = listener
            @Suppress("DEPRECATION")
            appOps?.startWatchingMode(
                "android:manage_external_storage",
                context.packageName,
                listener
            )
        } catch (_: Throwable) {
            // Polling loop below handles detection reliably if watching this op is restricted.
        }

        try {
            val deadline = System.currentTimeMillis() + 120_000L
            while (launchToken != 0 && System.currentTimeMillis() < deadline) {
                if (checkHasStoragePermission(context) || grantedSignal.isCompleted) {
                    withContext(Dispatchers.Main) {
                        currentOnGranted()
                        try {
                            activity?.finishActivity(REQUEST_CODE_MANAGE_STORAGE)
                        } catch (_: Throwable) {
                        }
                    }

                    // If finishActivity closed the child Settings activity cleanly, ON_RESUME
                    // cancels this LaunchedEffect within ~100ms. Otherwise bring MainActivity forward.
                    delay(250)
                    if (activity != null &&
                        launchToken != 0 &&
                        !lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                    ) {
                        withContext(Dispatchers.Main) {
                            try {
                                val bringBackIntent = Intent(context, MainActivity::class.java).apply {
                                    addFlags(
                                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                                    )
                                }
                                activity.startActivity(bringBackIntent)
                            } catch (_: Throwable) {
                            }
                        }
                    }
                    launchToken = 0
                    hasPausedSinceLaunch = false
                    break
                }
                withTimeoutOrNull(180L) {
                    grantedSignal.await()
                }
            }
        } finally {
            try {
                opListener?.let { appOps?.stopWatchingMode(it) }
            } catch (_: Throwable) {
            }
        }
    }

    return remember(context) {
        {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val activity = context.findActivity()
                val appIntent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
                val allIntent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                try {
                    hasPausedSinceLaunch = false
                    launchToken += 1
                    if (activity != null) {
                        @Suppress("DEPRECATION")
                        activity.startActivityForResult(appIntent, REQUEST_CODE_MANAGE_STORAGE)
                    } else {
                        context.startActivity(appIntent)
                    }
                } catch (_: Exception) {
                    try {
                        hasPausedSinceLaunch = false
                        launchToken += 1
                        if (activity != null) {
                            @Suppress("DEPRECATION")
                            activity.startActivityForResult(allIntent, REQUEST_CODE_MANAGE_STORAGE)
                        } else {
                            context.startActivity(allIntent)
                        }
                    } catch (_: Exception) {
                        launchToken = 0
                        hasPausedSinceLaunch = false
                        currentOnUnavailable()
                    }
                }
            }
        }
    }
}
