package app.setlog.capture.ui

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.LockClock
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.util.UnstableApi
import app.setlog.capture.R
import app.setlog.capture.SetLogViewModel
import app.setlog.capture.model.SessionStatus
import app.setlog.capture.model.SetLogUiState
import app.setlog.capture.model.VideoSession
import app.setlog.capture.model.formatDateTime
import app.setlog.capture.model.formatDuration
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@UnstableApi
@Composable
fun GalleryScreen(
    state: SetLogUiState,
    viewModel: SetLogViewModel,
    onShare: (VideoSession) -> Unit,
    onImportVideo: () -> Unit,
) {
    val unfinished = state.sessions.firstOrNull {
        it.status == SessionStatus.DRAFT ||
            it.status == SessionStatus.FAILED ||
            it.status == SessionStatus.EXPORTING
    }
    val finished = state.sessions.filter { it.status == SessionStatus.READY }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        GalleryTopBar(
            onBack = viewModel::openCamera,
            onImport = onImportVideo,
            importInProgress = state.importInProgress,
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 8.dp,
                bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 28.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            unfinished?.let { session ->
                item(key = "unfinished-${session.id}") {
                    UnfinishedCard(
                        session = session,
                        previewFile = viewModel.sessionPreviewFile(session),
                        onContinue = { viewModel.resumeSession(session.id) },
                        onRetry = { viewModel.retryExport(session.id) },
                        onOpen = { viewModel.selectSession(session.id) },
                    )
                }
            }

            if (finished.isNotEmpty()) {
                item(key = "finished-heading") {
                    Text(
                        text = stringResource(R.string.finished),
                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
                items(finished, key = { it.id }) { session ->
                    FinishedCard(
                        session = session,
                        previewFile = viewModel.sessionPreviewFile(session),
                        onOpen = { viewModel.selectSession(session.id) },
                    )
                }
            } else if (unfinished == null) {
                item(key = "empty") {
                    EmptyGallery(onBackToCamera = viewModel::openCamera)
                }
            }
        }
    }

    state.selectedSession?.let { selected ->
        SessionDetailDialog(
            session = selected,
            videoFile = viewModel.sessionOutputFile(selected),
            previewFile = viewModel.sessionPreviewFile(selected),
            onDismiss = { viewModel.selectSession(null) },
            onContinue = {
                viewModel.selectSession(null)
                viewModel.resumeSession(selected.id)
            },
            onRetry = { viewModel.retryExport(selected.id) },
            onSave = { title, caption, timestamp ->
                viewModel.updateSessionAndRebuild(selected.id, title, caption, timestamp)
                viewModel.selectSession(null)
            },
            onShare = { onShare(selected) },
            onDelete = {
                viewModel.deleteSession(selected.id)
                viewModel.selectSession(null)
            },
        )
    }

    state.errorMessage?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::clearError,
            title = { Text(stringResource(R.string.failed)) },
            text = { Text(message) },
            confirmButton = {
                Button(onClick = viewModel::clearError) {
                    Text(stringResource(R.string.close))
                }
            },
        )
    }
}

@Composable
private fun GalleryTopBar(
    onBack: () -> Unit,
    onImport: () -> Unit,
    importInProgress: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 8.dp,
                start = 8.dp,
                end = 12.dp,
                bottom = 8.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.Rounded.ArrowBack,
                contentDescription = stringResource(R.string.back_to_camera),
            )
        }
        Text(
            text = stringResource(R.string.gallery),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        FilledTonalButton(
            onClick = onImport,
            enabled = !importInProgress,
            shape = RoundedCornerShape(16.dp),
            contentPadding = PaddingValues(horizontal = 13.dp, vertical = 9.dp),
        ) {
            if (importInProgress) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.FolderOpen,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }
            Text(
                text = stringResource(R.string.import_video),
                modifier = Modifier.padding(start = 7.dp),
            )
        }
    }
}

@Composable
private fun UnfinishedCard(
    session: VideoSession,
    previewFile: File?,
    onContinue: () -> Unit,
    onRetry: () -> Unit,
    onOpen: () -> Unit,
) {
    val exporting = session.status == SessionStatus.EXPORTING
    val failed = session.status == SessionStatus.FAILED
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (failed) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.primaryContainer
            },
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (failed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                ) {
                    Text(
                        text = when {
                            exporting -> stringResource(R.string.exporting)
                            failed -> stringResource(R.string.failed)
                            else -> stringResource(R.string.draft)
                        },
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Text(
                    text = session.title,
                    modifier = Modifier
                        .padding(start = 10.dp)
                        .weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }

            Spacer(Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Thumbnail(
                    file = previewFile,
                    modifier = Modifier
                        .width(118.dp)
                        .aspectRatio(16f / 10f),
                )
                Column(
                    modifier = Modifier
                        .padding(start = 14.dp)
                        .weight(1f),
                ) {
                    Text(
                        text = formatDuration(session.totalDurationMs),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.clips_count, session.segments.size),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = stringResource(R.string.markers_count, session.markers.size),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            when {
                exporting -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.5.dp,
                        )
                        Text(
                            text = stringResource(R.string.exporting_body),
                            modifier = Modifier.padding(start = 10.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                failed -> {
                    Button(
                        onClick = onRetry,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(17.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(19.dp),
                        )
                        Text(
                            text = stringResource(R.string.retry_export),
                            modifier = Modifier.padding(start = 8.dp),
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                else -> {
                    Button(
                        onClick = onContinue,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(17.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CameraAlt,
                            contentDescription = null,
                            modifier = Modifier.size(19.dp),
                        )
                        Text(
                            text = stringResource(R.string.continue_recording),
                            modifier = Modifier.padding(start = 8.dp),
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FinishedCard(
    session: VideoSession,
    previewFile: File?,
    onOpen: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Thumbnail(
                file = previewFile,
                modifier = Modifier
                    .width(126.dp)
                    .aspectRatio(16f / 10f),
            )
            Column(
                modifier = Modifier
                    .padding(start = 14.dp)
                    .weight(1f),
            ) {
                Text(
                    text = session.title,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = formatDateTime(session.createdAtEpochMs),
                    modifier = Modifier.padding(top = 4.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                if (session.imported) {
                    Text(
                        text = stringResource(R.string.imported_video),
                        modifier = Modifier.padding(top = 4.dp),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Row(
                    modifier = Modifier.padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MiniChip(formatDuration(session.totalDurationMs))
                    MiniChip(stringResource(R.string.clips_count, session.segments.size))
                }
                if (session.caption.isNotBlank()) {
                    Text(
                        text = session.caption,
                        modifier = Modifier.padding(top = 9.dp),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun MiniChip(text: String) {
    Surface(
        shape = RoundedCornerShape(9.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun EmptyGallery(onBackToCamera: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 80.dp, start = 24.dp, end = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            modifier = Modifier.size(84.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Rounded.VideoLibrary,
                    contentDescription = null,
                    modifier = Modifier.size(38.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Spacer(Modifier.height(22.dp))
        Text(
            text = stringResource(R.string.empty_gallery),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = stringResource(R.string.empty_gallery_body),
            modifier = Modifier.padding(top = 8.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(22.dp))
        Button(
            onClick = onBackToCamera,
            shape = RoundedCornerShape(18.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.CameraAlt,
                contentDescription = null,
            )
            Text(
                text = stringResource(R.string.back_to_camera),
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

@Composable
private fun Thumbnail(file: File?, modifier: Modifier = Modifier) {
    val bitmap by produceState<Bitmap?>(
        initialValue = null,
        key1 = file?.absolutePath,
        key2 = file?.lastModified(),
    ) {
        value = if (file != null && file.exists()) {
            withContext(Dispatchers.IO) { extractThumbnail(file) }
        } else {
            null
        }
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = checkNotNull(bitmap).asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Rounded.VideoLibrary,
                    contentDescription = null,
                    modifier = Modifier.size(30.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun extractThumbnail(file: File): Bitmap? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(file.absolutePath)
        retriever.getFrameAtTime(
            0L,
            MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
        )
    } catch (_: RuntimeException) {
        null
    } finally {
        runCatching { retriever.release() }
    }
}

@Composable
private fun SessionDetailDialog(
    session: VideoSession,
    videoFile: File?,
    previewFile: File?,
    onDismiss: () -> Unit,
    onContinue: () -> Unit,
    onRetry: () -> Unit,
    onSave: (String, String, app.setlog.capture.model.TimestampOverlaySettings) -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    var title by remember(session.id, session.updatedAtEpochMs) {
        mutableStateOf(session.title)
    }
    var caption by remember(session.id, session.updatedAtEpochMs) {
        mutableStateOf(session.caption)
    }
    var timestamp by remember(session.id, session.updatedAtEpochMs) {
        mutableStateOf(session.timestampOverlay)
    }
    var showDeleteConfirmation by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.94f)
                .padding(horizontal = 12.dp),
            shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp, bottomStart = 22.dp, bottomEnd = 22.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(
                        start = 18.dp,
                        end = 18.dp,
                        top = 14.dp,
                        bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 20.dp,
                    ),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (session.status == SessionStatus.READY) {
                            stringResource(R.string.finished)
                        } else {
                            stringResource(R.string.draft)
                        },
                        modifier = Modifier.weight(1f),
                        color = if (session.status == SessionStatus.READY) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.close),
                        )
                    }
                }

                if (videoFile != null && videoFile.exists()) {
                    VideoPlayer(
                        file = videoFile,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f),
                    )
                } else {
                    Thumbnail(
                        file = previewFile,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f),
                    )
                }

                Spacer(Modifier.height(18.dp))

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(80) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.rename)) },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = caption,
                    onValueChange = { caption = it.take(2_000) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp),
                    label = { Text(stringResource(R.string.caption)) },
                    minLines = 4,
                    shape = RoundedCornerShape(16.dp),
                )

                TimestampSettingsEditor(
                    settings = timestamp,
                    onSettingsChange = { timestamp = it },
                    modifier = Modifier.padding(top = 22.dp),
                )

                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    InfoBlock(
                        label = stringResource(R.string.duration, formatDuration(session.totalDurationMs)),
                        value = stringResource(R.string.clips_count, session.segments.size),
                        modifier = Modifier.weight(1f),
                    )
                    InfoBlock(
                        label = formatDateTime(session.createdAtEpochMs),
                        value = stringResource(R.string.markers_count, session.markers.size),
                        modifier = Modifier.weight(1f),
                    )
                }

                Spacer(Modifier.height(18.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
                Spacer(Modifier.height(16.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.LockClock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = stringResource(R.string.press_times),
                        modifier = Modifier.padding(start = 9.dp),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
                if (session.markers.isEmpty()) {
                    Text(
                        text = "—",
                        modifier = Modifier.padding(top = 10.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    session.markers.forEach { marker ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Surface(
                                modifier = Modifier.size(28.dp),
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primaryContainer,
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = marker.ordinal.toString(),
                                        color = MaterialTheme.colorScheme.primary,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }
                            Column(modifier = Modifier.padding(start = 10.dp)) {
                                Text(
                                    text = formatDateTime(marker.pressedAtEpochMs),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = stringResource(R.string.timeline_offset, formatDuration(marker.timelineOffsetMs)),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))

                Button(
                    onClick = { onSave(title, caption, timestamp) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(17.dp),
                    contentPadding = PaddingValues(vertical = 14.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(19.dp),
                    )
                    Text(
                        text = if (session.status == SessionStatus.READY) {
                            stringResource(R.string.save_and_rebuild)
                        } else {
                            stringResource(R.string.save)
                        },
                        modifier = Modifier.padding(start = 8.dp),
                        fontWeight = FontWeight.Bold,
                    )
                }

                when (session.status) {
                    SessionStatus.READY -> {
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(
                            onClick = onShare,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(17.dp),
                            contentPadding = PaddingValues(vertical = 13.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Share,
                                contentDescription = null,
                                modifier = Modifier.size(19.dp),
                            )
                            Text(
                                text = stringResource(R.string.share),
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                    SessionStatus.FAILED -> {
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(
                            onClick = onRetry,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(17.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = null,
                            )
                            Text(
                                text = stringResource(R.string.retry_export),
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                    SessionStatus.DRAFT -> {
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(
                            onClick = onContinue,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(17.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.CameraAlt,
                                contentDescription = null,
                            )
                            Text(
                                text = stringResource(R.string.continue_recording),
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                    SessionStatus.EXPORTING -> {
                        Spacer(Modifier.height(14.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                            Text(
                                text = stringResource(R.string.exporting),
                                modifier = Modifier.padding(start = 10.dp),
                            )
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { showDeleteConfirmation = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(17.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Delete,
                        contentDescription = null,
                    )
                    Text(
                        text = stringResource(R.string.delete),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }

    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text(stringResource(R.string.delete_confirm_title)) },
            text = { Text(stringResource(R.string.delete_confirm_body)) },
            dismissButton = {
                OutlinedButton(onClick = { showDeleteConfirmation = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirmation = false
                        onDelete()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
        )
    }
}

@Composable
private fun InfoBlock(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = value,
                modifier = Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun VideoPlayer(file: File, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val videoView = remember(file.absolutePath) {
        VideoView(context).apply {
            val controller = MediaController(context)
            controller.setAnchorView(this)
            setMediaController(controller)
            setVideoPath(file.absolutePath)
            setOnPreparedListener { player ->
                player.isLooping = false
                seekTo(1)
            }
        }
    }
    DisposableEffect(videoView) {
        onDispose {
            runCatching { videoView.stopPlayback() }
        }
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = Color.Black,
    ) {
        AndroidView(
            factory = { videoView },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
