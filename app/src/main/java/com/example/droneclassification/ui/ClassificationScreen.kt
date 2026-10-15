package com.example.droneclassification.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.droneclassification.ClassificationUiState
import com.example.droneclassification.DroneClassificationViewModel
import kotlin.math.sin
import kotlin.random.Random

private val PrimaryCyan = Color(0xFF00E5FF)
private val AlertRed = Color(0xFFFF1744)
private val DeepBg = Color(0xFF0A0E14)
private val SurfacePanel = Color(0xFF151D29)

@Composable
fun ClassificationScreen(
    viewModel: DroneClassificationViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val isRecording by viewModel.isRecording.collectAsState()
    val threshold by viewModel.threshold.collectAsState()
    val waveform by viewModel.currentAudioWaveform.collectAsState()
    val isModelReady by viewModel.isModelReady.collectAsState()
    val context = LocalContext.current

    var showThresholdDialog by remember { mutableStateOf(false) }

    // Notification permission launcher (Android 13+) — needed for foreground service notification
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Whether granted or not, proceed to start classification.
        // The foreground service will still work, just without a visible notification if denied.
        viewModel.startLiveClassification()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            // Audio permission granted — now check notification permission
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val notifPerm = Manifest.permission.POST_NOTIFICATIONS
                val hasNotif = ContextCompat.checkSelfPermission(context, notifPerm) ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED
                if (!hasNotif) {
                    notificationPermissionLauncher.launch(notifPerm)
                    return@rememberLauncherForActivityResult
                }
            }
            viewModel.startLiveClassification()
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { viewModel.classifyFile(it) }
    }


    Scaffold(
        containerColor = DeepBg,
        floatingActionButton = {
            ThresholdFloatingButton(
                onClick = { showThresholdDialog = true }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            HudHeader()

            Spacer(modifier = Modifier.height(20.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(360.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(SurfacePanel)
                    .border(1.dp, if (isModelReady) PrimaryCyan.copy(alpha = 0.3f) else Color.Gray.copy(alpha = 0.2f), RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center
            ) {
                ScanningGrid()
                SignalActivityWave(waveform, isRecording)
                AdvancedRadar(
                    isDrone = uiState is ClassificationUiState.Success && (uiState as ClassificationUiState.Success).isDrone,
                    isScanning = isRecording,
                    confidence = if (uiState is ClassificationUiState.Success) (uiState as ClassificationUiState.Success).confidence else 0f
                )

                if (!isModelReady) {
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = PrimaryCyan)
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            DetectionReadout(uiState)

            Spacer(modifier = Modifier.height(20.dp))

            TacticalControls(
                isRecording = isRecording,
                isModelReady = isModelReady,
                onToggleMic = {
                    if (isRecording) viewModel.stopLiveClassification()
                    else {
                        val audioPerm = Manifest.permission.RECORD_AUDIO
                        val hasAudio = ContextCompat.checkSelfPermission(context, audioPerm) == android.content.pm.PackageManager.PERMISSION_GRANTED

                        if (!hasAudio) {
                            permissionLauncher.launch(audioPerm)
                        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                                    android.content.pm.PackageManager.PERMISSION_GRANTED
                        ) {
                            // Request notification permission — classification starts in the callback
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            viewModel.startLiveClassification()
                        }
                    }
                },
                onFilePick = {
                    filePickerLauncher.launch("audio/*")
                }
            )

            if (showThresholdDialog) {
                ThresholdDialog(
                    currentThreshold = threshold,
                    onThresholdChange = { viewModel.setThreshold(it) },
                    onDismiss = { showThresholdDialog = false }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                "Developed by M YOUSAF RANA",
                color = PrimaryCyan.copy(alpha = 0.5f),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                letterSpacing = 2.sp
            )
        }
    }
}

@Composable
fun HudHeader() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                "SIGNAL INTEL V6.0",
                style = MaterialTheme.typography.labelSmall,
                color = PrimaryCyan,
                letterSpacing = 3.sp
            )
            Text(
                "ACOUSTIC SCANNER",
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                fontWeight = FontWeight.Black
            )
        }
        Icon(
            Icons.Default.Security,
            contentDescription = null,
            tint = PrimaryCyan,
            modifier = Modifier.size(28.dp)
        )
    }
}

@Composable
fun ScanningGrid() {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val gridStep = 40.dp.toPx()
        val strokeWidth = 0.5.dp.toPx()
        val color = PrimaryCyan.copy(alpha = 0.05f)
        var x = 0f
        while (x < size.width) {
            drawLine(color, Offset(x, 0f), Offset(x, size.height), strokeWidth)
            x += gridStep
        }
        var y = 0f
        while (y < size.height) {
            drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth)
            y += gridStep
        }
    }
}

@Composable
fun SignalActivityWave(waveform: FloatArray, active: Boolean) {
    if (!active || waveform.isEmpty()) return
    val infiniteTransition = rememberInfiniteTransition(label = "wave")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * Math.PI.toFloat(),
        animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing)),
        label = "phase"
    )
    Canvas(modifier = Modifier.fillMaxSize().padding(horizontal = 40.dp)) {
        val path = Path()
        val midY = size.height / 2
        val width = size.width
        val points = 50
        val step = width / points
        path.moveTo(0f, midY)
        for (i in 0..points) {
            val x = i * step
            val waveIdx = (i * (waveform.size / points)).coerceAtMost(waveform.size - 1)
            val amplitude = waveform[waveIdx] * 100f
            val dy = amplitude * sin(x * 0.05f + phase)
            path.lineTo(x, midY + dy)
        }
        drawPath(path = path, color = PrimaryCyan.copy(alpha = 0.3f), style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
    }
}

@Composable
fun AdvancedRadar(isDrone: Boolean, isScanning: Boolean, confidence: Float) {
    val infiniteTransition = rememberInfiniteTransition(label = "Radar")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(4000, easing = LinearEasing)),
        label = "rotation"
    )
    val blipOffset = remember(isDrone) {
        if (isDrone) Offset(x = Random.nextFloat() * 0.4f + 0.3f, y = Random.nextFloat() * 0.4f + 0.3f)
        else Offset(0.5f, 0.5f)
    }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2, size.height / 2)
            val radius = size.minDimension / 2.2f
            for (i in 1..4) {
                drawCircle(color = PrimaryCyan, radius = radius * (i / 4f), center = center, style = Stroke(if (i == 4) 2.dp.toPx() else 1.dp.toPx()), alpha = if (i == 4) 0.3f else 0.1f)
            }
            drawLine(PrimaryCyan.copy(alpha = 0.2f), Offset(center.x - radius, center.y), Offset(center.x + radius, center.y))
            drawLine(PrimaryCyan.copy(alpha = 0.2f), Offset(center.x, center.y - radius), Offset(center.x, center.y + radius))
            if (isScanning) {
                drawArc(brush = Brush.sweepGradient(0f to Color.Transparent, 0.5f to PrimaryCyan.copy(alpha = 0.05f), 1f to PrimaryCyan.copy(alpha = 0.4f)), startAngle = rotation, sweepAngle = 60f, useCenter = true, topLeft = Offset(center.x - radius, center.y - radius), size = Size(radius * 2, radius * 2))
            }
            if (isDrone) {
                val blipX = size.width * blipOffset.x
                val blipY = size.height * blipOffset.y
                drawCircle(color = AlertRed, radius = 12.dp.toPx() * (1f + (confidence * 0.5f)), center = Offset(blipX, blipY), alpha = 0.3f)
                drawCircle(color = AlertRed, radius = 4.dp.toPx(), center = Offset(blipX, blipY))
            }
        }
        if (isDrone) TargetLockOverlay()
    }
}

@Composable
fun TargetLockOverlay() {
    val infiniteTransition = rememberInfiniteTransition(label = "lock")
    val scale by infiniteTransition.animateFloat(initialValue = 1f, targetValue = 1.2f, animationSpec = infiniteRepeatable(tween(500), RepeatMode.Reverse), label = "scale")
    Box(modifier = Modifier.size(120.dp).scale(scale).border(2.dp, AlertRed.copy(alpha = 0.6f), RoundedCornerShape(4.dp))) {
        Text("LOCK ON", color = AlertRed, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.TopStart).padding(4.dp))
    }
}

@Composable
fun DetectionReadout(state: ClassificationUiState) {
    Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(SurfacePanel).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val statusColor = when (state) {
                is ClassificationUiState.Success -> if (state.isDrone) AlertRed else Color.Green
                is ClassificationUiState.Processing -> Color.Yellow
                else -> Color.Green
            }
            Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(statusColor))
            Spacer(Modifier.width(10.dp))
            Text("MODE: ACOUSTIC", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
        }
        val (label, subLabel) = when (state) {
            is ClassificationUiState.Initial -> "STANDBY" to "WAITING FOR AUDIO"
            is ClassificationUiState.Processing -> "ANALYZING" to "DECODING STREAM"
            is ClassificationUiState.Success -> {
                if (state.isDrone) "TARGET IDENTIFIED" to "SIGNATURE MATCHED"
                else "CLEAR" to "NO THREATS"
            }
            is ClassificationUiState.Error -> "ERROR" to state.message
        }
        Text(label, color = if (state is ClassificationUiState.Success && state.isDrone) AlertRed else Color.White, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black, modifier = Modifier.padding(top = 4.dp))
        Text(subLabel, color = PrimaryCyan.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
        if (state is ClassificationUiState.Success) {
            Spacer(Modifier.height(16.dp))
            LinearProgressIndicator(progress = { state.confidence }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape), color = if (state.isDrone) AlertRed else PrimaryCyan, trackColor = Color.DarkGray)
            Text("PROBABILITY: ${(state.confidence * 100).toInt()}%", color = PrimaryCyan, style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.End).padding(top = 6.dp), fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun TacticalControls(
    isRecording: Boolean,
    isModelReady: Boolean,
    onToggleMic: () -> Unit,
    onFilePick: () -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(
            onClick = onToggleMic,
            enabled = isModelReady,
            modifier = Modifier.weight(1f).height(56.dp),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = if (isRecording) AlertRed else PrimaryCyan)
        ) {
            Icon(if (isRecording) Icons.Default.Cancel else Icons.Default.Radio, null, tint = Color.Black)
            Spacer(Modifier.width(12.dp))
            Text(if (isRecording) "ABORT" else "START SCAN", color = Color.Black, fontWeight = FontWeight.Black)
        }
        IconButton(onClick = onFilePick, modifier = Modifier.size(56.dp).background(SurfacePanel, RoundedCornerShape(8.dp)).border(1.dp, PrimaryCyan.copy(alpha = 0.2f), RoundedCornerShape(8.dp))) {
            Icon(Icons.Default.CloudUpload, null, tint = PrimaryCyan)
        }
    }
}

@Composable
fun ThresholdFloatingButton(onClick: () -> Unit) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val glow by infiniteTransition.animateFloat(initialValue = 0f, targetValue = 8f, animationSpec = infiniteRepeatable(tween(1500), RepeatMode.Reverse), label = "glow")
    Box(modifier = Modifier.padding(bottom = 16.dp, end = 16.dp).size(64.dp).shadow(glow.dp, CircleShape, spotColor = PrimaryCyan).clip(CircleShape).background(PrimaryCyan).clickable { onClick() }, contentAlignment = Alignment.Center) {
        Icon(Icons.Default.Tune, contentDescription = "Adjust Threshold", tint = Color.Black, modifier = Modifier.size(32.dp))
    }
}

@Composable
fun ThresholdDialog(
    currentThreshold: Float,
    onThresholdChange: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfacePanel,
        title = {
            Text("DETECTION THRESHOLD", color = PrimaryCyan, fontWeight = FontWeight.Black, fontSize = 18.sp)
        },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "Current Sensitivity: ${(currentThreshold * 100).toInt()}%",
                    color = Color.White,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                Slider(
                    value = currentThreshold,
                    onValueChange = onThresholdChange,
                    colors = SliderDefaults.colors(
                        thumbColor = PrimaryCyan,
                        activeTrackColor = PrimaryCyan,
                        inactiveTrackColor = Color.DarkGray
                    )
                )
                Text(
                    "Higher values reduce false positives but may miss distant targets.",
                    color = Color.Gray,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("DONE", color = PrimaryCyan, fontWeight = FontWeight.Bold)
            }
        }
    )
}
