package app.setlog.capture.ui

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import app.setlog.capture.R
import app.setlog.capture.SetLogViewModel
import app.setlog.capture.model.MainScreen

@UnstableApi
@Composable
fun SetLogApp(
    viewModel: SetLogViewModel,
    onRequestPermissions: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    BackHandler(enabled = state.screen == MainScreen.GALLERY) {
        viewModel.selectSession(null)
        viewModel.openCamera()
    }

    if (!state.cameraPermissionGranted) {
        PermissionGate(
            onRequestPermissions = onRequestPermissions,
            onOpenSettings = onOpenSettings,
        )
        return
    }

    when (state.screen) {
        MainScreen.CAMERA -> CameraScreen(
            state = state,
            viewModel = viewModel,
        )
        MainScreen.GALLERY -> GalleryScreen(
            state = state,
            viewModel = viewModel,
            onShare = { session ->
                viewModel.sessionOutputFile(session)?.let { file ->
                    shareVideo(context, file, session.caption)
                }
            },
        )
    }
}

@Composable
private fun PermissionGate(
    onRequestPermissions: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Surface(
                modifier = Modifier.size(84.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.CameraAlt,
                        contentDescription = null,
                        modifier = Modifier.size(36.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.camera_permission_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.camera_permission_body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(28.dp))
            Button(
                onClick = onRequestPermissions,
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                ),
            ) {
                Text(
                    text = stringResource(R.string.grant_access),
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onOpenSettings,
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Settings,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = stringResource(R.string.open_settings),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

private fun shareVideo(context: Context, file: java.io.File, caption: String) {
    val uri = androidx.core.content.FileProvider.getUriForFile(
        context,
        "${context.packageName}.files",
        file,
    )
    val message = if (caption.isBlank()) {
        context.getString(R.string.share_text_without_caption)
    } else {
        context.getString(R.string.share_text_with_caption, caption)
    }
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = "video/mp4"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TEXT, message)
        putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.share_subject))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(sendIntent, context.getString(R.string.share)))
}
