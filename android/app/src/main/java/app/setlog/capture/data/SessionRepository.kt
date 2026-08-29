package app.setlog.capture.data

import android.content.Context
import app.setlog.capture.model.CaptureMarker
import app.setlog.capture.model.PendingSegment
import app.setlog.capture.model.SegmentRecord
import app.setlog.capture.model.SessionStatus
import app.setlog.capture.model.VideoSession
import app.setlog.capture.model.defaultTitleTimestamp
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
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
    fun getOrCreateDraft(nowEpochMs: Long): VideoSession {
        getActiveDraft()?.let { return it }
        val draft = VideoSession.newDraft(
            nowEpochMs = nowEpochMs,
            title = "SetLog ${defaultTitleTimestamp(nowEpochMs)}",
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
        val marker = CaptureMarker(
            id = UUID.randomUUID().toString(),
            pressedAtEpochMs = pending.startedAtEpochMs,
            timelineOffsetMs = pending.timelineOffsetMs,
            segmentId = pending.segmentId,
            ordinal = pending.ordinal,
        )
        val updated = current.copy(
            updatedAtEpochMs = System.currentTimeMillis(),
            status = SessionStatus.DRAFT,
            segments = current.segments + segment,
            markers = current.markers + marker,
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
        if (!manifest.exists()) {
            return null
        }
        return runCatching {
            videoSessionFromJson(JSONObject(manifest.readText(StandardCharsets.UTF_8)))
        }.getOrNull()
    }

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
        if (target.exists()) {
            target.delete()
        }
        check(temporary.renameTo(target)) {
            "Could not atomically save the session manifest"
        }
    }

    @Synchronized
    private fun recoverInterruptedFiles() {
        sessionsDir.mkdirs()
        sessionsDir.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".partial.mp4") }
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
    title = json.optString("title", "SetLog"),
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
)

private fun <T> JSONArray?.toObjectList(transform: (JSONObject) -> T): List<T> {
    if (this == null) return emptyList()
    return buildList {
        repeat(length()) { index -> add(transform(getJSONObject(index))) }
    }
}

private fun JSONObject.nullableString(key: String): String? =
    if (!has(key) || isNull(key)) null else getString(key)
