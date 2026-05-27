package com.openmate.feature.instance

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage

private const val TAG = "QrScanScreen"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrScanScreen(
    onBack: () -> Unit,
    onScanComplete: (name: String, address: String, port: Int, scanToken: String) -> Unit,
    onLoginComplete: () -> Unit = {},
    viewModel: QrScanViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scanState by viewModel.scanState.collectAsState()

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        if (!granted) {
            viewModel.setError("Camera permission denied")
        }
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    LaunchedEffect(scanState) {
        val result = scanState
        if (result is ScanResult) {
            onScanComplete(result.name, result.address, result.port, result.scanToken)
        } else if (result is ScanUiLoginConfirmed) {
            onLoginComplete()
        }
    }

    val isProcessing = scanState is ScanUiStateProcessing
    val isError = scanState is ScanUiStateError
    val isLoginConfirmed = scanState is ScanUiLoginConfirmed

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            isProcessing -> stringResource(R.string.scan_pairing)
                            isError -> stringResource(R.string.scan_pair_failed)
                            isLoginConfirmed -> stringResource(R.string.scan_login_success)
                            else -> stringResource(R.string.scan_qr_code)
                        }
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        when {
            isProcessing -> {
                PairingProgressScreen(
                    modifier = Modifier.padding(padding),
                )
            }
            isLoginConfirmed -> {
                LoginConfirmedScreen(
                    modifier = Modifier.padding(padding),
                )
            }
            isError -> {
                PairingErrorScreen(
                    message = (scanState as ScanUiStateError).message,
                    onRetry = {
                        viewModel.reset()
                    },
                    modifier = Modifier.padding(padding),
                )
            }
            else -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (hasCameraPermission) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.Center,
                        ) {
                            val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }

                            AndroidView(
                                factory = { ctx ->
                                    val previewView = PreviewView(ctx)
                                    val preview = Preview.Builder().build()
                                    val analyzer = ImageAnalysis.Builder()
                                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                        .build()

                                    analyzer.setAnalyzer(ContextCompat.getMainExecutor(ctx)) { imageProxy ->
                                        processImageProxy(imageProxy, viewModel)
                                    }

                                    val cameraProvider = cameraProviderFuture.get()
                                    preview.setSurfaceProvider(previewView.surfaceProvider)

                                    try {
                                        cameraProvider.unbindAll()
                                        cameraProvider.bindToLifecycle(
                                            lifecycleOwner,
                                            CameraSelector.DEFAULT_BACK_CAMERA,
                                            preview,
                                            analyzer,
                                        )
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Camera bind failed", e)
                                    }

                                    previewView
                                },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    } else {
                        Spacer(modifier = Modifier.height(48.dp))
                        Text(
                            stringResource(R.string.camera_permission_required),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PairingProgressScreen(
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(48.dp))
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.scan_pairing_message),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PairingErrorScreen(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onRetry) {
            Text(stringResource(R.string.retry_scan))
        }
    }
}

@Composable
private fun LoginConfirmedScreen(
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.scan_login_success_message),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

private fun processImageProxy(imageProxy: ImageProxy, viewModel: QrScanViewModel) {
    val mediaImage = imageProxy.image
    if (mediaImage != null) {
        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        val scanner = BarcodeScanning.getClient()

        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                for (barcode in barcodes) {
                    val rawValue = barcode.rawValue
                    if (!rawValue.isNullOrBlank() && (rawValue.startsWith("http") || rawValue.startsWith("op:", ignoreCase = true) || rawValue.startsWith("oplogin:", ignoreCase = true))) {
                        viewModel.handleBarcode(rawValue)
                        return@addOnSuccessListener
                    }
                    if (barcode.valueType == Barcode.TYPE_URL) {
                        val url = barcode.url?.url ?: continue
                        viewModel.handleBarcode(url)
                        return@addOnSuccessListener
                    }
                }
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    } else {
        imageProxy.close()
    }
}