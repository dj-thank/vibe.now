package app.setlog.capture.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.setlog.capture.R
import app.setlog.capture.model.TimestampOverlaySettings
import app.setlog.capture.model.TimestampStyle
import app.setlog.capture.model.formatDateTime
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

@Composable
fun TimestampSettingsEditor(
    settings: TimestampOverlaySettings,
    onSettingsChange: (TimestampOverlaySettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.timestamp_overlay),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(R.string.timestamp_overlay_body),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(
                checked = settings.enabled,
                onCheckedChange = { onSettingsChange(settings.copy(enabled = it)) },
            )
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 14.dp)
                .height(210.dp),
            shape = RoundedCornerShape(22.dp),
            color = Color(0xFF17181B),
        ) {
            TimestampOverlayPreview(
                settings = settings,
                interactive = settings.enabled,
                onSettingsChange = onSettingsChange,
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (settings.enabled) {
            Text(
                text = stringResource(R.string.drag_timestamp_hint),
                modifier = Modifier.padding(top = 8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = stringResource(R.string.timestamp_size),
                modifier = Modifier.padding(top = 16.dp),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
            Slider(
                value = settings.scale,
                onValueChange = { onSettingsChange(settings.copy(scale = it).sanitized()) },
                valueRange = 0.60f..1.80f,
            )
            Text(
                text = stringResource(R.string.timestamp_style),
                modifier = Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TimestampStyle.entries.forEach { style ->
                    FilterChip(
                        selected = settings.style == style,
                        onClick = { onSettingsChange(settings.copy(style = style)) },
                        label = { Text(style.label()) },
                    )
                }
            }
            Text(
                text = stringResource(R.string.timestamp_position),
                modifier = Modifier.padding(top = 14.dp),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PositionPreset.entries.forEach { preset ->
                    FilterChip(
                        selected = preset.matches(settings),
                        onClick = {
                            onSettingsChange(
                                settings.copy(x = preset.x, y = preset.y).sanitized(),
                            )
                        },
                        label = { Text(preset.label()) },
                    )
                }
            }
        }
    }
}

@Composable
fun TimestampOverlayPreview(
    settings: TimestampOverlaySettings,
    modifier: Modifier = Modifier,
    interactive: Boolean = false,
    onSettingsChange: (TimestampOverlaySettings) -> Unit = {},
) {
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var overlaySize by remember { mutableStateOf(IntSize.Zero) }
    val currentSettings by rememberUpdatedState(settings)
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000L)
        }
    }

    Box(
        modifier = modifier.onSizeChanged { containerSize = it },
    ) {
        if (!settings.enabled || containerSize == IntSize.Zero) return@Box
        val left = containerSize.width * settings.x - overlaySize.width / 2f
        val top = containerSize.height * settings.y - overlaySize.height / 2f
        val fontFamily = if (settings.style == TimestampStyle.MONOSPACED) {
            FontFamily.Monospace
        } else {
            FontFamily.Default
        }
        val background = if (settings.style == TimestampStyle.BOXED) {
            Color.Black.copy(alpha = 0.68f)
        } else {
            Color.Transparent
        }

        Text(
            text = formatDateTime(now),
            modifier = Modifier
                .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
                .onSizeChanged { overlaySize = it }
                .clip(RoundedCornerShape(8.dp))
                .background(background)
                .padding(horizontal = 9.dp, vertical = 5.dp)
                .then(
                    if (interactive) {
                        Modifier.pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                val current = currentSettings
                                val size = Size(
                                    width = containerSize.width.toFloat().coerceAtLeast(1f),
                                    height = containerSize.height.toFloat().coerceAtLeast(1f),
                                )
                                onSettingsChange(
                                    current.copy(
                                        x = current.x + pan.x / size.width,
                                        y = current.y + pan.y / size.height,
                                        scale = current.scale * zoom,
                                    ).sanitized(),
                                )
                            }
                        }
                    } else {
                        Modifier
                    },
                ),
            color = Color.White,
            fontSize = (18f * settings.scale).sp,
            fontFamily = fontFamily,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

private enum class PositionPreset(val x: Float, val y: Float) {
    TOP(0.50f, 0.14f),
    CENTER(0.50f, 0.50f),
    BOTTOM(0.50f, 0.82f),
    ;

    fun matches(settings: TimestampOverlaySettings): Boolean =
        kotlin.math.abs(settings.x - x) < 0.04f && kotlin.math.abs(settings.y - y) < 0.04f

    @Composable
    fun label(): String = when (this) {
        TOP -> stringResource(R.string.position_top)
        CENTER -> stringResource(R.string.position_center)
        BOTTOM -> stringResource(R.string.position_bottom)
    }
}

@Composable
private fun TimestampStyle.label(): String = when (this) {
    TimestampStyle.CLEAN -> stringResource(R.string.style_clean)
    TimestampStyle.BOXED -> stringResource(R.string.style_boxed)
    TimestampStyle.MONOSPACED -> stringResource(R.string.style_monospace)
}
