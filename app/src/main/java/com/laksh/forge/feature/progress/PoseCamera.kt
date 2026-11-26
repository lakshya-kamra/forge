package com.laksh.forge.feature.progress

import android.Manifest
import com.laksh.forge.ui.components.SecureWindow
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cameraswitch
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.laksh.forge.domain.plan.Pose
import com.laksh.forge.ui.components.EmptyState
import com.laksh.forge.ui.components.Pill
import com.laksh.forge.util.ImageUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Full-screen in-app camera for progress photos with a pose-guide overlay. The system camera
 * app cannot draw a guide, and matching framing month to month is what makes photos
 * comparable (guardrails.final.json photo_capture_standardisation).
 *
 * Captures go to a temporary cache file; [onCaptured] receives its Uri, and the caller must
 * delete it (ImageUtils.discardCapture) once it has read it into memory or copied it to the
 * person's gallery - Forge itself keeps no progress photos.
 */
@Composable
fun PoseCaptureScreen(
    poses: List<Pose>,
    onCaptured: (Pose, Uri) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    SecureWindow()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val currentOnCaptured by rememberUpdatedState(onCaptured)
    val currentOnClose by rememberUpdatedState(onClose)

    BackHandler(onBack = onClose)

    var hasPermission by remember {
        mutableStateOf(context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasPermission = it
    }
    LaunchedEffect(Unit) { if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA) }

    if (!hasPermission) {
        Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).windowInsetsPadding(WindowInsets.safeDrawing)) {
            IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopStart).padding(8.dp)) {
                Icon(Icons.Outlined.Close, "Close camera")
            }
            EmptyState(
                icon = Icons.Outlined.PhotoCamera,
                title = "Camera access needed",
                body = "Forge uses the camera only while this screen is open, and doesn't keep the photos itself.",
                modifier = Modifier.align(Alignment.Center),
                action = { Pill("Allow camera", filled = true, onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) }
            )
        }
        return
    }

    var poseIndex by rememberSaveable { mutableIntStateOf(0) }
    // Back camera by default: the protocol puts the phone on a stand with the self-timer.
    var useFront by rememberSaveable { mutableStateOf(false) }
    var timerSeconds by rememberSaveable { mutableIntStateOf(10) }
    var countdown by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val pose = poses.getOrNull(poseIndex)
    LaunchedEffect(pose) { if (pose == null) currentOnClose() }
    if (pose == null) return

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .build()
    }

    // Held so the camera is released the moment this screen leaves composition, rather than
    // staying bound to the host lifecycle in the background.
    var boundProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    DisposableEffect(Unit) { onDispose { runCatching { boundProvider?.unbindAll() } } }

    LaunchedEffect(useFront) {
        runCatching {
            val provider = ProcessCameraProvider.awaitInstance(context)
            boundProvider = provider
            val preview = Preview.Builder().build().apply { surfaceProvider = previewView.surfaceProvider }
            provider.unbindAll()
            provider.bindToLifecycle(
                lifecycleOwner,
                if (useFront) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                imageCapture
            )
        }.onFailure { e -> error = e.message ?: "The camera couldn't start." }
    }

    fun takeNow() {
        val file = ImageUtils.newCaptureFile(context)
        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        imageCapture.takePicture(
            options,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    busy = false
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    currentOnCaptured(pose, Uri.fromFile(file))
                    poseIndex += 1
                }

                override fun onError(exception: ImageCaptureException) {
                    busy = false
                    haptics.performHapticFeedback(HapticFeedbackType.Reject)
                    error = exception.message ?: "The photo couldn't be taken."
                }
            }
        )
    }

    fun shoot() {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            for (s in timerSeconds downTo 1) {
                countdown = s
                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                delay(1000)
            }
            countdown = 0
            takeNow()
        }
    }

    Box(modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        PoseGuideOverlay(pose, Modifier.fillMaxSize())

        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalIconButton(onClick = onClose, shapes = IconButtonDefaults.shapes()) {
                    Icon(Icons.Outlined.Close, "Close camera")
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "${pose.label} · ${poseIndex + 1} of ${poses.size}",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.weight(1f))
                FilledTonalIconButton(onClick = { useFront = !useFront }, shapes = IconButtonDefaults.shapes()) {
                    Icon(Icons.Outlined.Cameraswitch, if (useFront) "Use back camera" else "Use front camera")
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                pose.instruction,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.large)
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(12.dp)
            )
            Spacer(Modifier.weight(1f))
            error?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.error.copy(alpha = 0.8f))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
                Spacer(Modifier.height(10.dp))
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Self-timer cycles off -> 3 s -> 10 s: the protocol asks for a stand and a timer.
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    FilledTonalIconButton(
                        onClick = { timerSeconds = when (timerSeconds) { 0 -> 3; 3 -> 10; else -> 0 } },
                        shapes = IconButtonDefaults.shapes()
                    ) { Icon(Icons.Outlined.Timer, "Self-timer") }
                    Text(if (timerSeconds == 0) "Off" else "${timerSeconds}s", color = Color.White, style = MaterialTheme.typography.labelMedium)
                }
                Box(
                    Modifier
                        .size(78.dp)
                        .clip(CircleShape)
                        .border(4.dp, Color.White, CircleShape)
                        .padding(6.dp)
                        .clip(CircleShape)
                        .background(if (busy) Color.White.copy(alpha = 0.4f) else Color.White)
                        .clickable(enabled = !busy) { shoot() },
                    contentAlignment = Alignment.Center
                ) {
                    if (busy && countdown == 0) LoadingIndicator(Modifier.size(36.dp), color = Color.Black)
                }
                Spacer(Modifier.size(48.dp))
            }
        }

        // Big countdown in the middle so it is readable from 1.5 m away.
        AnimatedContent(
            targetState = countdown,
            transitionSpec = { (scaleIn(initialScale = 1.4f) + fadeIn()) togetherWith (scaleOut(targetScale = 0.6f) + fadeOut()) },
            modifier = Modifier.align(Alignment.Center),
            label = "countdown"
        ) { n ->
            if (n > 0) {
                Text(
                    "$n",
                    color = Color.White,
                    fontSize = 120.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/**
 * A ghost silhouette plus head and feet guide lines. Matching the outline every month keeps
 * distance, height and framing constant, which is what makes two photos comparable.
 */
@Composable
private fun PoseGuideOverlay(pose: Pose, modifier: Modifier) {
    Canvas(modifier) {
        val guide = Color.White.copy(alpha = 0.75f)
        val dash = PathEffect.dashPathEffect(floatArrayOf(18f, 14f))
        val stroke = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
        val w = size.width
        val h = size.height

        // Head near the top, feet near the bottom (photo protocol framing).
        val top = h * 0.12f
        val bottom = h * 0.92f
        drawLine(guide, Offset(w * 0.12f, top), Offset(w * 0.88f, top), strokeWidth = 2.dp.toPx(), pathEffect = dash)
        drawLine(guide, Offset(w * 0.12f, bottom), Offset(w * 0.88f, bottom), strokeWidth = 2.dp.toPx(), pathEffect = dash)

        val bodyH = bottom - top
        val cx = w / 2f
        val headR = bodyH * 0.065f
        val side = pose == Pose.SIDE
        drawCircle(guide, radius = headR, center = Offset(cx, top + headR), style = stroke)

        val neckY = top + headR * 2f + bodyH * 0.01f
        val shoulderHalf = if (side) bodyH * 0.06f else bodyH * 0.12f
        val hipHalf = if (side) bodyH * 0.065f else bodyH * 0.09f
        val hipY = top + bodyH * 0.52f
        val torso = Path().apply {
            moveTo(cx - shoulderHalf, neckY + bodyH * 0.02f)
            lineTo(cx + shoulderHalf, neckY + bodyH * 0.02f)
            lineTo(cx + hipHalf, hipY)
            lineTo(cx - hipHalf, hipY)
            close()
        }
        drawPath(torso, guide, style = stroke)

        // Arms: slightly away from the body (the 'A' pose) for front/back, hanging for side.
        val armSpread = if (side) bodyH * 0.01f else bodyH * 0.07f
        val handY = top + bodyH * 0.50f
        drawLine(guide, Offset(cx - shoulderHalf, neckY + bodyH * 0.03f), Offset(cx - shoulderHalf - armSpread, handY), strokeWidth = stroke.width, cap = StrokeCap.Round)
        drawLine(guide, Offset(cx + shoulderHalf, neckY + bodyH * 0.03f), Offset(cx + shoulderHalf + armSpread, handY), strokeWidth = stroke.width, cap = StrokeCap.Round)

        // Legs, feet slightly apart.
        val footSpread = if (side) bodyH * 0.02f else bodyH * 0.06f
        drawLine(guide, Offset(cx - hipHalf * 0.5f, hipY), Offset(cx - footSpread, bottom), strokeWidth = stroke.width, cap = StrokeCap.Round)
        drawLine(guide, Offset(cx + hipHalf * 0.5f, hipY), Offset(cx + footSpread, bottom), strokeWidth = stroke.width, cap = StrokeCap.Round)

        // A soft frame so the eye reads the guide as an overlay, not part of the scene.
        drawRoundRect(
            Color.White.copy(alpha = 0.18f),
            topLeft = Offset(w * 0.06f, top - h * 0.04f),
            size = Size(w * 0.88f, bodyH + h * 0.08f),
            cornerRadius = CornerRadius(28.dp.toPx()),
            style = Stroke(width = 1.5.dp.toPx())
        )
    }
}
