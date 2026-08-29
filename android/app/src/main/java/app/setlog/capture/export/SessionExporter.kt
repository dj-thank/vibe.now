package app.setlog.capture.export

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.text.Spannable
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
import androidx.media3.common.OverlaySettings
import androidx.media3.common.util.UnstableApi
import androidx.media3.container.MdtaMetadataEntry
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.effect.TextOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.InAppMp4Muxer
import androidx.media3.transformer.Transformer
import app.setlog.capture.data.SessionRepository
import app.setlog.capture.model.SegmentRecord
import app.setlog.capture.model.TimestampOverlaySettings
import app.setlog.capture.model.TimestampStyle
import app.setlog.capture.model.VideoSession
import app.setlog.capture.model.iso8601Utc
import java.io.File
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.json.JSONArray
import org.json.JSONObject

@UnstableApi
class SessionExporter(
    context: Context,
    private val repository: SessionRepository,
) {
    interface Callback {
        fun onExportCompleted(sessionId: String, outputFile: File)
        fun onExportFailed(sessionId: String, message: String)
    }

    private val appContext = context.applicationContext
    private var activeTransformer: Transformer? = null
    private var activeSessionId: String? = null

    fun export(session: VideoSession, outputFile: File, callback: Callback) {
        check(activeTransformer == null) { "An export is already running." }
        require(session.segments.isNotEmpty()) { "Cannot export an empty session." }
        outputFile.parentFile?.mkdirs()
        if (outputFile.exists()) outputFile.delete()

        val editedItems = session.segments.map { segment ->
            val inputFile = repository.segmentFile(session.id, segment)
            require(inputFile.exists() && inputFile.length() > 0L) {
                "Missing source clip: ${segment.fileName}"
            }
            val builder = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(inputFile)))
            if (session.timestampOverlay.enabled) {
                builder.setEffects(timestampEffects(segment, session.timestampOverlay))
            }
            builder.build()
        }
        val sequence = EditedMediaItemSequence.withAudioAndVideoFrom(editedItems)
        val composition = Composition.Builder(sequence).build()

        val metadataProvider = InAppMp4Muxer.MetadataProvider { entries: MutableSet<Metadata.Entry> ->
            entries.add(stringMetadata("app.vibenow.schema", "2"))
            entries.add(stringMetadata("app.vibenow.session_id", session.id))
            entries.add(stringMetadata("app.vibenow.title", session.title))
            entries.add(stringMetadata("app.vibenow.caption", session.caption))
            entries.add(stringMetadata("app.vibenow.capture_markers", markersJson(session)))
            entries.add(stringMetadata("app.vibenow.timestamp_overlay", overlayJson(session)))
        }
        val muxerFactory = InAppMp4Muxer.Factory(metadataProvider)
            .setAttemptStreamableOutputEnabled(true)

        val transformer = Transformer.Builder(appContext)
            .setMuxerFactory(muxerFactory)
            .addListener(
                object : Transformer.Listener {
                    override fun onCompleted(
                        composition: Composition,
                        exportResult: ExportResult,
                    ) {
                        activeTransformer = null
                        activeSessionId = null
                        callback.onExportCompleted(session.id, outputFile)
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException,
                    ) {
                        activeTransformer = null
                        activeSessionId = null
                        callback.onExportFailed(
                            session.id,
                            exportException.localizedMessage ?: "Media3 export failed.",
                        )
                    }
                },
            )
            .build()

        activeTransformer = transformer
        activeSessionId = session.id
        runCatching {
            transformer.start(composition, outputFile.absolutePath)
        }.onFailure { error ->
            activeTransformer = null
            activeSessionId = null
            outputFile.delete()
            callback.onExportFailed(
                session.id,
                error.localizedMessage ?: "Media3 export could not start.",
            )
        }
    }

    fun cancel() {
        activeTransformer?.cancel()
        activeTransformer = null
        activeSessionId = null
    }

    fun isExporting(): Boolean = activeTransformer != null

    private fun timestampEffects(
        segment: SegmentRecord,
        settings: TimestampOverlaySettings,
    ): Effects {
        val safe = settings.sanitized()
        val staticSettings = StaticOverlaySettings.Builder()
            .setBackgroundFrameAnchor(
                safe.x * 2f - 1f,
                1f - safe.y * 2f,
            )
            .setOverlayFrameAnchor(0f, 0f)
            .setScale(safe.scale, safe.scale)
            .build()
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd  HH:mm:ss")
            .withZone(ZoneId.systemDefault())
        val textOverlay = object : TextOverlay() {
            override fun getText(presentationTimeUs: Long): SpannableString {
                val epochMs = segment.startedAtEpochMs + presentationTimeUs / 1_000L
                return styledTimestamp(formatter.format(Instant.ofEpochMilli(epochMs)), safe.style)
            }

            override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings =
                staticSettings
        }
        return Effects(
            emptyList(),
            listOf(OverlayEffect(listOf(textOverlay))),
        )
    }

    private fun styledTimestamp(text: String, style: TimestampStyle): SpannableString {
        val rendered = when (style) {
            TimestampStyle.BOXED -> "  $text  "
            else -> text
        }
        return SpannableString(rendered).apply {
            setSpan(
                ForegroundColorSpan(Color.WHITE),
                0,
                length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            setSpan(
                StyleSpan(Typeface.BOLD),
                0,
                length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            if (style == TimestampStyle.BOXED) {
                setSpan(
                    BackgroundColorSpan(Color.argb(176, 0, 0, 0)),
                    0,
                    length,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
            if (style == TimestampStyle.MONOSPACED) {
                setSpan(
                    TypefaceSpan("monospace"),
                    0,
                    length,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }
    }

    private fun stringMetadata(key: String, value: String): MdtaMetadataEntry =
        MdtaMetadataEntry(
            key,
            value.toByteArray(StandardCharsets.UTF_8),
            MdtaMetadataEntry.TYPE_INDICATOR_STRING,
        )

    private fun markersJson(session: VideoSession): String = JSONObject().apply {
        put("sessionId", session.id)
        put("createdAt", iso8601Utc(session.createdAtEpochMs))
        put("totalDurationMs", session.totalDurationMs)
        put("markers", JSONArray().apply {
            session.markers.forEach { marker ->
                put(JSONObject().apply {
                    put("ordinal", marker.ordinal)
                    put("pressedAtEpochMs", marker.pressedAtEpochMs)
                    put("pressedAt", iso8601Utc(marker.pressedAtEpochMs))
                    put("timelineOffsetMs", marker.timelineOffsetMs)
                    put("segmentId", marker.segmentId)
                })
            }
        })
    }.toString()

    private fun overlayJson(session: VideoSession): String = JSONObject().apply {
        put("enabled", session.timestampOverlay.enabled)
        put("x", session.timestampOverlay.x.toDouble())
        put("y", session.timestampOverlay.y.toDouble())
        put("scale", session.timestampOverlay.scale.toDouble())
        put("style", session.timestampOverlay.style.name)
    }.toString()
}
