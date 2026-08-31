package app.setlog.capture.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import app.setlog.capture.model.CaptureMarker
import app.setlog.capture.model.InputSettings
import app.setlog.capture.model.PhysicalVolumeKey
import app.setlog.capture.model.ShortcutAction
import app.setlog.capture.model.PendingSegment
import app.setlog.capture.model.SegmentRecord
import app.setlog.capture.model.SessionStatus
import app.setlog.capture.model.VideoSession
import app.setlog.capture.model.TimestampOverlaySettings
import app.setlog.capture.model.TimestampStyle
import app.setlog.capture.model.defaultTitleTimestamp
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

class SessionRepository(context: Context) {
    private val appContext = context.applicationContext
    private val rootDir = File(appContext.filesDir, ROOT_DIR_NAME)
    private val sessionsDir = File(rootDir, "sessions")
    private val preferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        sessionsDir.mkdirs()
        recoverInterruptedFiles()
    }

    @Synchronized
    fun loadAll(): List<VideoSession> {
        recoverInterruptedFiles()
        return sessionsDir.listFiles()
            .orEmpty()
            .filter { it.isDirectory }
            .mapNotNull { directory -> readManifest(directory) }
            .sortedWith(
                compareByDescending<VideoSession> { it.isDraft }
                    .thenByDescending { it.updatedAtEpochMs },
            )
    }

    @Synchronized
    fun getActiveDraft(): VideoSession? {
        val id = preferences.getString(KEY_ACTIVE_SESSION_ID, null) ?: return null
        val session = readSession(id) ?: run {
            clearActiveId()
            return null
        }
        return if (session.isDraft) session else {
            clearActiveId()
            null
        }
    }

    @Synchronized
    fun getOrCreateDraft(
        nowEpochMs: Long,
        timestampOverlay: TimestampOverlaySettings = loadTimestampSettings(),
    ): VideoSession {
        getActiveDraft()?.let { return it }
        val draft = VideoSession.newDraft(
            nowEpochMs = nowEpochMs,
            title = "Vibe.now ${defaultTitleTimestamp(nowEpochMs)}",
            timestampOverlay = timestampOverlay,
        )
        sessionDirectory(draft.id).mkdirs()
        writeManifest(draft)
        preferences.edit().putString(KEY_ACTIVE_SESSION_ID, draft.id).commit()
        return draft
    }

    @Synchronized
    fun readSession(sessionId: String): VideoSession? =
        readManifest(sessionDirectory(sessionId))

    @Synchronized
    fun createPendingSegment(
        sessionId: String,
        pressedAtEpochMs: Long,
        createsMarker: Boolean = true,
    ): PendingSegment {
        val session = requireNotNull(readSession(sessionId)) {
            "Session does not exist: $sessionId"
        }
        require(session.isDraft) { "Session is not resumable: $sessionId" }
        val ordinal = session.segments.size + 1
        val segmentId = UUID.randomUUID().toString()
        val partialFileName = "segment-%04d-%s.partial.mp4".format(ordinal, segmentId)
        val finalFileName = "segment-%04d-%s.mp4".format(ordinal, segmentId)
        val directory = sessionDirectory(sessionId)
        directory.mkdirs()
        val partial = File(directory, partialFileName)
        if (partial.exists()) {
            partial.delete()
        }
        return PendingSegment(
            sessionId = sessionId,
            segmentId = segmentId,
            partialFileName = partialFileName,
            finalFileName = finalFileName,
            startedAtEpochMs = pressedAtEpochMs,
            timelineOffsetMs = session.totalDurationMs,
            ordinal = ordinal,
            createsMarker = createsMarker,
        )
    }

    fun partialFile(pending: PendingSegment): File =
        File(sessionDirectory(pending.sessionId), pending.partialFileName)

    @Synchronized
    fun commitSegment(
        pending: PendingSegment,
        durationMs: Long,
    ): VideoSession {
        val current = requireNotNull(readSession(pending.sessionId)) {
            "Session disappeared while finalizing: ${pending.sessionId}"
        }
        val partial = partialFile(pending)
        require(partial.exists() && partial.length() > 0L) {
            "Recorded clip was empty"
        }
        val finalFile = File(sessionDirectory(pending.sessionId), pending.finalFileName)
        if (finalFile.exists()) {
            finalFile.delete()
        }
        if (!partial.renameTo(finalFile)) {
            partial.inputStream().use { input ->
                FileOutputStream(finalFile).use { output -> input.copyTo(output) }
            }
            partial.delete()
        }

        val safeDuration = durationMs.coerceAtLeast(1L)
        val segment = SegmentRecord(
            id = pending.segmentId,
            fileName = pending.finalFileName,
            startedAtEpochMs = pending.startedAtEpochMs,
            durationMs = safeDuration,
            ordinal = pending.ordinal,
        )
        val marker = if (pending.createsMarker) {
            CaptureMarker(
                id = UUID.randomUUID().toString(),
                pressedAtEpochMs = pending.startedAtEpochMs,
                timelineOffsetMs = pending.timelineOffsetMs,
                segmentId = pending.segmentId,
                ordinal = current.markers.size + 1,
            )
        } else {
            null
        }
        val updated = current.copy(
            updatedAtEpochMs = System.currentTimeMillis(),
            status = SessionStatus.DRAFT,
            segments = current.segments + segment,
            markers = marker?.let { current.markers + it } ?: current.markers,
            totalDurationMs = current.totalDurationMs + safeDuration,
            errorMessage = null,
        )
        writeManifest(updated)
        preferences.edit().putString(KEY_ACTIVE_SESSION_ID, updated.id).commit()
        return updated
    }

    @Synchronized
    fun discardPending(pending: PendingSegment) {
        partialFile(pending).delete()
    }

    @Synchronized
    fun markExporting(sessionId: String, outputFileName: String): VideoSession {
        val current = requireNotNull(readSession(sessionId))
        val output = File(sessionDirectory(sessionId), outputFileName)
        if (output.exists()) {
            output.delete()
        }
        val updated = current.copy(
            status = SessionStatus.EXPORTING,
            outputFileName = outputFileName,
            updatedAtEpochMs = System.currentTimeMillis(),
            errorMessage = null,
        )
        writeManifest(updated)
        return updated
    }

    @Synchronized
    fun markReady(sessionId: String): VideoSession {
        val current = requireNotNull(readSession(sessionId))
        val output = requireNotNull(current.outputFileName)
        require(File(sessionDirectory(sessionId), output).exists()) {
            "Finished video does not exist"
        }
        val updated = current.copy(
            status = SessionStatus.READY,
            updatedAtEpochMs = System.currentTimeMillis(),
            errorMessage = null,
        )
        writeManifest(updated)
        if (preferences.getString(KEY_ACTIVE_SESSION_ID, null) == sessionId) {
            clearActiveId()
        }
        return updated
    }

    @Synchronized
    fun markExportFailed(sessionId: String, message: String): VideoSession {
        val current = requireNotNull(readSession(sessionId))
        current.outputFileName?.let { File(sessionDirectory(sessionId), it).delete() }
        val updated = current.copy(
            status = SessionStatus.FAILED,
            outputFileName = null,
            updatedAtEpochMs = System.currentTimeMillis(),
            errorMessage = message,
        )
        writeManifest(updated)
        preferences.edit().putString(KEY_ACTIVE_SESSION_ID, sessionId).commit()
        return updated
    }

    @Synchronized
    fun updateDetails(sessionId: String, title: String, caption: String): VideoSession {
        val current = requireNotNull(readSession(sessionId))
        val updated = current.copy(
            title = title.trim().ifBlank { current.title },
            caption = caption.trim(),
            updatedAtEpochMs = System.currentTimeMillis(),
        )
        writeManifest(updated)
        return updated
    }

    @Synchronized
    fun deleteSession(sessionId: String) {
        sessionDirectory(sessionId).deleteRecursively()
        if (preferences.getString(KEY_ACTIVE_SESSION_ID, null) == sessionId) {
            clearActiveId()
        }
    }

    @Synchronized
    fun markDraftResumable(sessionId: String): VideoSession {
        val current = requireNotNull(readSession(sessionId))
        val updated = current.copy(
            status = SessionStatus.DRAFT,
            outputFileName = null,
            updatedAtEpochMs = System.currentTimeMillis(),
            errorMessage = null,
        )
        current.outputFileName?.let { File(sessionDirectory(sessionId), it).delete() }
        writeManifest(updated)
        preferences.edit().putString(KEY_ACTIVE_SESSION_ID, sessionId).commit()
        return updated
    }

    fun segmentFile(sessionId: String, segment: SegmentRecord): File =
        File(sessionDirectory(sessionId), segment.fileName)

    fun outputFile(session: VideoSession): File? =
        session.outputFileName?.let { File(sessionDirectory(session.id), it) }

    fun outputFile(sessionId: String, outputFileName: String): File =
        File(sessionDirectory(sessionId), outputFileName)


    @Synchronized
    fun installRebuiltOutput(
        sessionId: String,
        temporaryFile: File,
        outputFileName: String,
    ): VideoSession {
        val current = requireNotNull(readSession(sessionId))
        require(temporaryFile.exists() && temporaryFile.length() > 0L) {
            "Rebuilt video was empty."
        }
        val target = File(sessionDirectory(sessionId), outputFileName)
        val backup = File(sessionDirectory(sessionId), "$outputFileName.backup")
        backup.delete()
        if (target.exists() && !target.renameTo(backup)) {
            target.inputStream().use { input ->
                FileOutputStream(backup).use { output -> input.copyTo(output) }
            }
            target.delete()
        }
        try {
            if (!temporaryFile.renameTo(target)) {
                temporaryFile.inputStream().use { input ->
                    FileOutputStream(target).use { output -> input.copyTo(output) }
                }
                temporaryFile.delete()
            }
            require(target.exists() && target.length() > 0L) {
                "Rebuilt video could not be installed."
            }
            backup.delete()
        } catch (error: Throwable) {
            target.delete()
            if (backup.exists()) {
                backup.renameTo(target)
            }
            throw error
        }
        val updated = current.copy(
            status = SessionStatus.READY,
            outputFileName = outputFileName,
            updatedAtEpochMs = System.currentTimeMillis(),
            errorMessage = null,
        )
        writeManifest(updated)
        return updated
    }

    fun loadInputSettings(): InputSettings = InputSettings(
        recordKey = enumValueOrDefault(
            preferences.getString(KEY_RECORD_KEY, null),
            PhysicalVolumeKey.UP,
        ),
        doublePressAction = enumValueOrDefault(
            preferences.getString(KEY_DOUBLE_PRESS_ACTION, null),
            ShortcutAction.FINISH,
        ),
        triplePressAction = enumValueOrDefault(
            preferences.getString(KEY_TRIPLE_PRESS_ACTION, null),
            ShortcutAction.OPEN_GALLERY,
        ),
    )

    fun saveInputSettings(settings: InputSettings) {
        preferences.edit()
            .putString(KEY_RECORD_KEY, settings.recordKey.name)
            .putString(KEY_DOUBLE_PRESS_ACTION, settings.doublePressAction.name)
            .putString(KEY_TRIPLE_PRESS_ACTION, settings.triplePressAction.name)
            .apply()
    }

    fun loadTimestampSettings(): TimestampOverlaySettings = TimestampOverlaySettings(
        enabled = preferences.getBoolean(KEY_TIMESTAMP_ENABLED, true),
        x = preferences.getFloat(KEY_TIMESTAMP_X, 0.50f),
        y = preferences.getFloat(KEY_TIMESTAMP_Y, 0.14f),
        scale = preferences.getFloat(KEY_TIMESTAMP_SCALE, 1.0f),
        style = enumValueOrDefault(
            preferences.getString(KEY_TIMESTAMP_STYLE, null),
            TimestampStyle.BOXED,
        ),
    ).sanitized()

    fun saveTimestampSettings(settings: TimestampOverlaySettings) {
        val safe = settings.sanitized()
        preferences.edit()
            .putBoolean(KEY_TIMESTAMP_ENABLED, safe.enabled)
            .putFloat(KEY_TIMESTAMP_X, safe.x)
            .putFloat(KEY_TIMESTAMP_Y, safe.y)
            .putFloat(KEY_TIMESTAMP_SCALE, safe.scale)
            .putString(KEY_TIMESTAMP_STYLE, safe.style.name)
            .apply()
    }

    @Synchronized
    fun updateTimestampOverlay(
        sessionId: String,
        settings: TimestampOverlaySettings,
    ): VideoSession {
        val current = requireNotNull(readSession(sessionId))
        val updated = current.copy(
            timestampOverlay = settings.sanitized(),
            updatedAtEpochMs = System.currentTimeMillis(),
        )
        writeManifest(updated)
        return updated
    }

    @Synchronized
    fun importVideo(sourceUri: Uri): VideoSession {
        val now = System.currentTimeMillis()
        val sessionId = UUID.randomUUID().toString()
        val directory = sessionDirectory(sessionId).apply { mkdirs() }
        val resolver = appContext.contentResolver
        val displayName = resolver.query(
            sourceUri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
        val mimeType = resolver.getType(sourceUri)
        val fallbackExtension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
            ?.takeIf { it.isNotBlank() }
            ?: "mp4"
        val extension = displayName
            ?.substringAfterLast('.', missingDelimiterValue = "")
            ?.takeIf { it.matches(Regex("[A-Za-z0-9]{1,8}")) }
            ?: fallbackExtension
        val sourceFileName = "segment-0001-import.${extension.lowercase()}"
        val outputFileName = "vibe-${sessionId}.${extension.lowercase()}"
        val sourceFile = File(directory, sourceFileName)
        val outputFile = File(directory, outputFileName)

        try {
            resolver.openInputStream(sourceUri).use { input ->
                requireNotNull(input) { "Selected video could not be opened." }
                FileOutputStream(sourceFile).use { output -> input.copyTo(output) }
            }
            require(sourceFile.length() > 0L) { "Selected video was empty." }
            sourceFile.inputStream().use { input ->
                FileOutputStream(outputFile).use { output -> input.copyTo(output) }
            }

            val retriever = MediaMetadataRetriever()
            val durationMs = try {
                retriever.setDataSource(appContext, sourceUri)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()
                    ?.coerceAtLeast(1L)
                    ?: 1L
            } finally {
                runCatching { retriever.release() }
            }
            val title = displayName
                ?.let { name -> name.substringBeforeLast('.', missingDelimiterValue = name) }
                ?.trim()
                ?.take(80)
                ?.ifBlank { null }
                ?: "Vibe.now ${defaultTitleTimestamp(now)}"
            val segmentId = UUID.randomUUID().toString()
            val session = VideoSession(
                id = sessionId,
                title = title,
                caption = "",
                createdAtEpochMs = now,
                updatedAtEpochMs = now,
                status = SessionStatus.READY,
                segments = listOf(
                    SegmentRecord(
                        id = segmentId,
                        fileName = sourceFileName,
                        startedAtEpochMs = now,
                        durationMs = durationMs,
                        ordinal = 1,
                    ),
                ),
                markers = emptyList(),
                outputFileName = outputFileName,
                totalDurationMs = durationMs,
                errorMessage = null,
                timestampOverlay = loadTimestampSettings(),
                imported = true,
            )
            writeManifest(session)
            return session
        } catch (error: Throwable) {
            directory.deleteRecursively()
            throw error
        }
    }

    fun firstGuideSeen(): Boolean = preferences.getBoolean(KEY_GUIDE_SEEN, false)

    fun markFirstGuideSeen() {
        preferences.edit().putBoolean(KEY_GUIDE_SEEN, true).apply()
    }

    private fun sessionDirectory(sessionId: String): File =
        File(sessionsDir, sessionId)

    private fun manifestFile(directory: File): File =
        File(directory, MANIFEST_FILE_NAME)

    private fun readManifest(directory: File): VideoSession? {
        val manifest = manifestFile(directory)
        if (manifest.exists()) {
            return runCatching {
                videoSessionFromJson(JSONObject(manifest.readText(StandardCharsets.UTF_8)))
                    .takeIf { it.id == directory.name }
            }.getOrNull()
        }

        // Older builds could remove the primary before installing this completed staging file.
        // Never prefer staging data over an existing primary or adopt another session's data.
        val temporary = File(directory, "$MANIFEST_FILE_NAME.tmp")
        return runCatching {
            val json = JSONObject(temporary.readText(StandardCharsets.UTF_8))
            require(json.getInt("schemaVersion") == 1)
            json.getJSONArray("segments")
            json.getJSONArray("markers")
            json.getLong("totalDurationMs")
            val recovered = videoSessionFromJson(json)
            require(recovered.id == directory.name)
            require(json.getString("status") == recovered.status.name)
            require(recovered.segments.all { segment ->
                segment.durationMs > 0 && isLocalSessionFileName(segment.fileName) &&
                    File(directory, segment.fileName).let { it.isFile && it.length() > 0L }
            })
            require(recovered.totalDurationMs == recovered.segments.sumOf { it.durationMs })
            val segmentIds = recovered.segments.map { it.id }.toSet()
            require(recovered.markers.all { it.segmentId in segmentIds })
            recovered.outputFileName?.let { output ->
                require(isLocalSessionFileName(output))
                // Existing interrupted-export cleanup must never delete a committed source clip.
                require(recovered.status != SessionStatus.EXPORTING || recovered.segments.none { it.fileName == output })
            }
            require(recovered.status != SessionStatus.READY ||
                recovered.outputFileName?.let { File(directory, it).isFile } == true)
            replaceManifest(temporary, manifest)
            recovered
        }.getOrNull()
    }

    private fun isLocalSessionFileName(name: String): Boolean =
        name.isNotEmpty() && name != "." && name != ".." &&
            name.none { it == '/' || it == '\\' || it == ':' } &&
            name != MANIFEST_FILE_NAME && name != "$MANIFEST_FILE_NAME.tmp"

    private fun writeManifest(session: VideoSession) {
        val directory = sessionDirectory(session.id)
        directory.mkdirs()
        val target = manifestFile(directory)
        val temporary = File(directory, "$MANIFEST_FILE_NAME.tmp")
        val bytes = videoSessionToJson(session).toString(2).toByteArray(StandardCharsets.UTF_8)
        FileOutputStream(temporary).use { stream ->
            stream.write(bytes)
            stream.fd.sync()
        }
        replaceManifest(temporary, target)
    }

    private fun replaceManifest(temporary: File, target: File) {
        // No delete/copy fallback: unsupported atomic replacement must retain the old data.
        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    @Synchronized
    private fun recoverInterruptedFiles() {
        sessionsDir.mkdirs()
        sessionsDir.walkTopDown()
            .filter { it.isFile && it.name.contains(".partial.") }
            .forEach { it.delete() }

        sessionsDir.listFiles().orEmpty()
            .filter { it.isDirectory }
            .forEach { directory ->
                val session = readManifest(directory) ?: return@forEach
                if (session.status == SessionStatus.EXPORTING) {
                    session.outputFileName?.let { File(directory, it).delete() }
                    writeManifest(
                        session.copy(
                            status = SessionStatus.FAILED,
                            outputFileName = null,
                            updatedAtEpochMs = System.currentTimeMillis(),
                            errorMessage = "Export was interrupted. All source clips are still available.",
                        ),
                    )
                }
            }
    }

    private fun clearActiveId() {
        preferences.edit().remove(KEY_ACTIVE_SESSION_ID).commit()
    }

    companion object {
        private const val ROOT_DIR_NAME = "setlog"
        private const val MANIFEST_FILE_NAME = "manifest.json"
        private const val PREFS_NAME = "setlog_state"
        private const val KEY_ACTIVE_SESSION_ID = "active_session_id"
        private const val KEY_GUIDE_SEEN = "guide_seen"
        private const val KEY_RECORD_KEY = "record_key"
        private const val KEY_DOUBLE_PRESS_ACTION = "double_press_action"
        private const val KEY_TRIPLE_PRESS_ACTION = "triple_press_action"
        private const val KEY_TIMESTAMP_ENABLED = "timestamp_enabled"
        private const val KEY_TIMESTAMP_X = "timestamp_x"
        private const val KEY_TIMESTAMP_Y = "timestamp_y"
        private const val KEY_TIMESTAMP_SCALE = "timestamp_scale"
        private const val KEY_TIMESTAMP_STYLE = "timestamp_style"
    }
}

private fun videoSessionToJson(session: VideoSession): JSONObject = JSONObject().apply {
    put("schemaVersion", 1)
    put("id", session.id)
    put("title", session.title)
    put("caption", session.caption)
    put("createdAtEpochMs", session.createdAtEpochMs)
    put("updatedAtEpochMs", session.updatedAtEpochMs)
    put("status", session.status.name)
    put("outputFileName", session.outputFileName ?: JSONObject.NULL)
    put("totalDurationMs", session.totalDurationMs)
    put("errorMessage", session.errorMessage ?: JSONObject.NULL)
    put("imported", session.imported)
    put("timestampOverlay", JSONObject().apply {
        put("enabled", session.timestampOverlay.enabled)
        put("x", session.timestampOverlay.x.toDouble())
        put("y", session.timestampOverlay.y.toDouble())
        put("scale", session.timestampOverlay.scale.toDouble())
        put("style", session.timestampOverlay.style.name)
    })
    put("segments", JSONArray().apply {
        session.segments.forEach { segment ->
            put(JSONObject().apply {
                put("id", segment.id)
                put("fileName", segment.fileName)
                put("startedAtEpochMs", segment.startedAtEpochMs)
                put("durationMs", segment.durationMs)
                put("ordinal", segment.ordinal)
            })
        }
    })
    put("markers", JSONArray().apply {
        session.markers.forEach { marker ->
            put(JSONObject().apply {
                put("id", marker.id)
                put("pressedAtEpochMs", marker.pressedAtEpochMs)
                put("timelineOffsetMs", marker.timelineOffsetMs)
                put("segmentId", marker.segmentId)
                put("ordinal", marker.ordinal)
            })
        }
    })
}

private fun videoSessionFromJson(json: JSONObject): VideoSession = VideoSession(
    id = json.getString("id"),
    title = json.optString("title", "Vibe.now"),
    caption = json.optString("caption", ""),
    createdAtEpochMs = json.getLong("createdAtEpochMs"),
    updatedAtEpochMs = json.getLong("updatedAtEpochMs"),
    status = runCatching {
        SessionStatus.valueOf(json.getString("status"))
    }.getOrDefault(SessionStatus.DRAFT),
    segments = json.optJSONArray("segments").toObjectList { item ->
        SegmentRecord(
            id = item.getString("id"),
            fileName = item.getString("fileName"),
            startedAtEpochMs = item.getLong("startedAtEpochMs"),
            durationMs = item.getLong("durationMs"),
            ordinal = item.getInt("ordinal"),
        )
    },
    markers = json.optJSONArray("markers").toObjectList { item ->
        CaptureMarker(
            id = item.getString("id"),
            pressedAtEpochMs = item.getLong("pressedAtEpochMs"),
            timelineOffsetMs = item.getLong("timelineOffsetMs"),
            segmentId = item.getString("segmentId"),
            ordinal = item.getInt("ordinal"),
        )
    },
    outputFileName = json.nullableString("outputFileName"),
    totalDurationMs = json.optLong("totalDurationMs", 0L),
    errorMessage = json.nullableString("errorMessage"),
    timestampOverlay = json.optJSONObject("timestampOverlay")?.let { overlay ->
        TimestampOverlaySettings(
            enabled = overlay.optBoolean("enabled", true),
            x = overlay.optDouble("x", 0.50).toFloat(),
            y = overlay.optDouble("y", 0.14).toFloat(),
            scale = overlay.optDouble("scale", 1.0).toFloat(),
            style = enumValueOrDefault(
                overlay.optString("style", null),
                TimestampStyle.BOXED,
            ),
        ).sanitized()
    } ?: TimestampOverlaySettings(),
    imported = json.optBoolean("imported", false),
)

private fun <T> JSONArray?.toObjectList(transform: (JSONObject) -> T): List<T> {
    if (this == null) return emptyList()
    return buildList {
        repeat(length()) { index -> add(transform(getJSONObject(index))) }
    }
}

private fun JSONObject.nullableString(key: String): String? =
    if (!has(key) || isNull(key)) null else getString(key)

private inline fun <reified T : Enum<T>> enumValueOrDefault(raw: String?, fallback: T): T =
    raw?.let { value -> enumValues<T>().firstOrNull { it.name == value } } ?: fallback
