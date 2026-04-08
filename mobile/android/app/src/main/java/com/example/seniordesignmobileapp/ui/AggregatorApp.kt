package com.example.seniordesignmobileapp.ui

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.seniordesignmobileapp.ble.REQUIRED_BLE_PERMISSIONS
import com.example.seniordesignmobileapp.ble.hasRequiredBlePermissions
import com.example.seniordesignmobileapp.viewmodel.AggregatorViewModel

@Composable
fun AggregatorApp(
    applicationContext: Context,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val viewModel: AggregatorViewModel = viewModel(
        factory = AggregatorViewModel.factory(applicationContext),
    )
    val uiState by viewModel.uiState.collectAsState()
    var permissionsGranted by remember {
        mutableStateOf(hasRequiredBlePermissions(context))
    }
    var requestedPermissions by rememberSaveable { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        permissionsGranted = hasRequiredBlePermissions(context)
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissionsGranted = hasRequiredBlePermissions(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(permissionsGranted) {
        viewModel.onBlePermissionsChanged(permissionsGranted)
    }

    LaunchedEffect(permissionsGranted, requestedPermissions) {
        if (!permissionsGranted && !requestedPermissions) {
            requestedPermissions = true
            permissionLauncher.launch(REQUIRED_BLE_PERMISSIONS)
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) { innerPadding ->
        AggregatorScreen(
            uiState = uiState,
            permissionsGranted = permissionsGranted,
            onGrantPermissions = {
                requestedPermissions = true
                permissionLauncher.launch(REQUIRED_BLE_PERMISSIONS)
            },
            onReconnect = viewModel::reconnect,
            onCalibrateSitting = viewModel::calibrateSittingPosture,
            onStartRecording = viewModel::startRecording,
            onStopRecording = viewModel::stopRecording,
            onShareSession = { session -> shareSavedSession(context, session) },
            modifier = Modifier.padding(innerPadding),
        )
    }
}
