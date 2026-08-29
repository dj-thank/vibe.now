package app.setlog.capture.ui

import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Collections
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.media3.common.util.UnstableApi
import app.setlog.capture.R
import app.setlog.capture.SetLogViewModel
import app.setlog.capture.model.CaptureState
import app.setlog.capture.model.SetLogUiState
import app.setlog.capture.model.formatDuration

@UnstableApi
@Composable
fun CameraScreen(
    state: SetLogUiState,
    viewModel: SetLogViewModel,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    DisposableEffect(previewView, lifecycleOwner, state.cameraPermissionGranted) {
        if (state.cameraPermissionGranted) {
            viewModel.attachPreview(previewView, lifecycleOwner)
        }
        onDispose {
            viewModel.detachPreview(previewView)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize(),
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to Color.Black.copy(alpha = 0.48f),
                        0.22f to Color.Transparent,
                        0.62f to Color.Transparent,
                        1.0f to Color.Black.copy(alpha = 0.84f),
                    ),
                ),
        )

        RecordingCorners(visible = state.isRecording)

        CameraTopBar(
            state = state,
            onOpenGallery = viewModel::openGalleryFromUi,
            onSwitchCamera = viewModel::switchCamera,
        )

        CameraBottomPanel(state = state)

        AnimatedVisibility(
            visible = state.errorMessage != null,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(
                    top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 82.dp,
                    start = 18.dp,
                    end = 18.dp,
                ),
        ) {
            ErrorBanner(
                message = state.errorMessage.orEmpty(),
                onDismiss = viewModel::clearError,
            )
        }
    }

    if (state.showFirstGuide) {
        FirstGuideDialog(onDismiss = viewModel::dismissFirstGuide)
    }
}

@Composable
private fun CameraTopBar(
    state: SetLogUiState,
    onOpenGallery: () -> Unit,
    onSwitchCamera: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 12.dp,
                start = 16.dp,
                end = 16.dp,
            ),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusPill(state)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.52f),
            ) {
                IconButton(
                    onClick = onSwitchCamera,
                    enabled = !state.isRecording && state.captureState != CaptureState.EXPORTING,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Cameraswitch,
                        contentDescription = stringResource(R.string.switch_camera),
                        tint = Color.White,
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(18.dp),
                color = Color.Black.copy(alpha = 0.60f),
            ) {
                Button(
                    onClick = onOpenGallery,
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Transparent,
                        contentColor = Color.White,
                    ),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Collections,
                        contentDescription = null,
                        modifier = Modifier.size(19.dp),
                    )
                    Text(
                        text = stringResource(R.string.gallery),
                        modifier = Modifier.padding(start = 7.dp),
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusPill(state: SetLogUiState) {
    val statusText = when (state.captureState) {
        CaptureState.PREPARING -> stringResource(R.string.ready)
        CaptureState.READY -> if ((state.activeSession?.segments?.size ?: 0) > 0) {
            stringResource(R.string.paused)
        } else {
            stringResource(R.string.ready)
        }
        CaptureState.RECORDING -> stringResource(R.string.recording)
        CaptureState.FINALIZING_SEGMENT -> stringResource(R.string.saving_segment)
        CaptureState.EXPORTING -> stringResource(R.string.exporting)
        CaptureState.ERROR -> stringResource(R.string.failed)
    }
    val accent = when (state.captureState) {
        CaptureState.RECORDING -> MaterialTheme.colorScheme.primary
        CaptureState.ERROR -> MaterialTheme.colorScheme.error
        else -> Color.White
    }

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = Color.Black.copy(alpha = 0.60f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.captureState == CaptureState.RECORDING) {
                Surface(
                    modifier = Modifier.size(9.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                ) {}
                Spacer(Modifier.size(8.dp))
            } else if (state.captureState == CaptureState.FINALIZING_SEGMENT) {
                CircularProgressIndicator(
                    modifier = Modifier.size(13.dp),
                    strokeWidth = 2.dp,
                    color = Color.White,
                )
                Spacer(Modifier.size(8.dp))
            }
            Text(
                text = statusText,
                color = accent,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun BoxScope.CameraBottomPanel(state: SetLogUiState) {
    val bottomPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Column(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .padding(start = 18.dp, end = 18.dp, bottom = bottomPadding + 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = formatDuration(state.displayedDurationMs),
            color = Color.White,
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Light,
        )

        if ((state.activeSession?.segments?.size ?: 0) > 0) {
            Spacer(Modifier.height(8.dp))
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Color.Black.copy(alpha = 0.56f),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                        tint = Color.White.copy(alpha = 0.86f),
                    )
                    Text(
                        text = stringResource(R.string.draft_safe),
                        modifier = Modifier.padding(start = 7.dp),
                        color = Color.White.copy(alpha = 0.88f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        Spacer(Modifier.height(18.dp))

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(26.dp),
            color = if (state.isRecording) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.94f)
            } else {
                Color.Black.copy(alpha = 0.67f)
            },
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.VolumeUp,
                        contentDescription = null,
                        modifier = Modifier.size(26.dp),
                        tint = Color.White,
                    )
                    Text(
                        text = if (state.isRecording) {
                            stringResource(R.string.release_to_pause)
                        } else {
                            stringResource(R.string.hold_volume_up)
                        },
                        modifier = Modifier.padding(start = 10.dp),
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.finish_hint),
                    color = Color.White.copy(alpha = 0.78f),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = stringResource(R.string.gallery_hint),
                    modifier = Modifier.padding(top = 3.dp),
                    color = Color.White.copy(alpha = 0.62f),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun RecordingCorners(visible: Boolean) {
    if (!visible) return
    val transition = rememberInfiniteTransition(label = "recording-corners")
    val alpha by transition.animateFloat(
        initialValue = 0.62f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 720),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "corner-alpha",
    )
    val density = LocalDensity.current
    val insetPx = with(density) { 15.dp.toPx() }
    val armPx = with(density) { 52.dp.toPx() }
    val strokePx = with(density) { 5.dp.toPx() }
    val color = MaterialTheme.colorScheme.primary.copy(alpha = alpha)

    Canvas(modifier = Modifier.fillMaxSize()) {
        val right = size.width - insetPx
        val bottom = size.height - insetPx

        drawLine(color, Offset(insetPx, insetPx), Offset(insetPx + armPx, insetPx), strokePx, StrokeCap.Round)
        drawLine(color, Offset(insetPx, insetPx), Offset(insetPx, insetPx + armPx), strokePx, StrokeCap.Round)

        drawLine(color, Offset(right, insetPx), Offset(right - armPx, insetPx), strokePx, StrokeCap.Round)
        drawLine(color, Offset(right, insetPx), Offset(right, insetPx + armPx), strokePx, StrokeCap.Round)

        drawLine(color, Offset(insetPx, bottom), Offset(insetPx + armPx, bottom), strokePx, StrokeCap.Round)
        drawLine(color, Offset(insetPx, bottom), Offset(insetPx, bottom - armPx), strokePx, StrokeCap.Round)

        drawLine(color, Offset(right, bottom), Offset(right - armPx, bottom), strokePx, StrokeCap.Round)
        drawLine(color, Offset(right, bottom), Offset(right, bottom - armPx), strokePx, StrokeCap.Round)
    }
}

@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.error.copy(alpha = 0.94f),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 11.dp, bottom = 11.dp, end = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message,
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onError,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.close),
                    tint = MaterialTheme.colorScheme.onError,
                )
            }
        }
    }
}

@Composable
private fun FirstGuideDialog(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = {}) {
        Surface(
            shape = RoundedCornerShape(30.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Surface(
                    modifier = Modifier.size(64.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.VolumeUp,
                            contentDescription = null,
                            modifier = Modifier.size(30.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Spacer(Modifier.height(18.dp))
                Text(
                    text = stringResource(R.string.first_guide_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                GuideLine(stringResource(R.string.first_guide_line_1))
                GuideLine(stringResource(R.string.first_guide_line_2))
                GuideLine(stringResource(R.string.first_guide_line_3))
                Spacer(Modifier.height(22.dp))
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    contentPadding = PaddingValues(vertical = 14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                    ),
                ) {
                    Text(
                        text = stringResource(R.string.understood),
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Composable
private fun GuideLine(text: String) {
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyLarge,
    )
}
