package app.setlog.capture.export

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.container.MdtaMetadataEntry
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.InAppMp4Muxer
import androidx.media3.transformer.Transformer
import app.setlog.capture.data.SessionRepository
import app.setlog.capture.model.VideoSession
import app.setlog.capture.model.iso8601Utc
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets

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
        if (outputFile.exists()) {
            outputFile.delete()
        }

        val editedItems = session.segments.map { segment ->
            val inputFile = repository.segmentFile(session.id, segment)
            require(inputFile.exists() && inputFile.length() > 0L) {
                "Missing source clip: ${segment.fileName}"
            }
            EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(inputFile))).build()
        }
        val sequence = EditedMediaItemSequence.withAudioAndVideoFrom(editedItems)
        val composition = Composition.Builder(sequence).build()

        val metadataProvider = InAppMp4Muxer.MetadataProvider { entries: MutableSet<Metadata.Entry> ->
            entries.add(
                MdtaMetadataEntry(
                    "app.setlog.schema",
                    "1".toByteArray(StandardCharsets.UTF_8),
                    MdtaMetadataEntry.TYPE_INDICATOR_STRING,
                ),
            )
            entries.add(
                MdtaMetadataEntry(
                    "app.setlog.session_id",
                    session.id.toByteArray(StandardCharsets.UTF_8),
                    MdtaMetadataEntry.TYPE_INDICATOR_STRING,
                ),
            )
            entries.add(
                MdtaMetadataEntry(
                    "app.setlog.title",
                    session.title.toByteArray(StandardCharsets.UTF_8),
                    MdtaMetadataEntry.TYPE_INDICATOR_STRING,
                ),
            )
            entries.add(
                MdtaMetadataEntry(
                    "app.setlog.caption",
                    session.caption.toByteArray(StandardCharsets.UTF_8),
                    MdtaMetadataEntry.TYPE_INDICATOR_STRING,
                ),
            )
            entries.add(
                MdtaMetadataEntry(
                    "app.setlog.volume_up_markers",
                    markersJson(session).toByteArray(StandardCharsets.UTF_8),
                    MdtaMetadataEntry.TYPE_INDICATOR_STRING,
                ),
            )
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
}
