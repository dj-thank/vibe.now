package app.setlog.capture.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import app.setlog.capture.model.VideoSession
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.security.Permission
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Real repository/File operations, synthetic non-video bytes, no camera or media APIs. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@Suppress("DEPRECATION", "removal")
class SessionManifestRecoveryTest {
    @Test fun interruptionBeforePrimaryMutationKeepsTheCommittedClip() {
        val fixture = seedFixture()
        val fault = interruptSave(fixture, FaultPoint.BEFORE_PRIMARY_MUTATION)
        assertTrue("The old manifest must still exist before replacement", fault.primaryExisted)
        assertCommittedClipSurvives(fixture)
    }

    @Test fun interruptionImmediatelyBeforeRenameKeepsTheCommittedClipAfterRestart() {
        val fixture = seedFixture()
        interruptSave(fixture, FaultPoint.BEFORE_RENAME)
        assertCommittedClipSurvives(fixture)
    }

    @Test fun completedReplacementIsReadableByANewRepository() {
        val fixture = seedFixture()
        fixture.repository.updateDetails(fixture.saved.id, "after", "synthetic caption")
        val reopened = SessionRepository(fixture.context)
        val restored = requireNotNull(reopened.readSession(fixture.saved.id))
        assertEquals("after", restored.title)
        assertCommittedClipSurvives(fixture)
    }

    @Test fun aCompleteTemporaryManifestFromAnOlderInterruptedSaveIsRecovered() {
        val fixture = seedFixture()
        leaveLegacyInterruptedSave(fixture)
        assertCommittedClipSurvives(fixture)
        assertEquals("after", SessionRepository(fixture.context).readSession(fixture.saved.id)?.title)
    }

    @Test fun missingMalformedOrIncompleteTemporaryManifestsAreNotPromoted() {
        for (damage in listOf("missing", "syntax", "missing_segments", "wrong_schema")) {
            val fixture = seedFixture()
            val temporary = leaveLegacyInterruptedSave(fixture)
            when (damage) {
                "missing" -> check(temporary.renameTo(File(temporary.parentFile, "tmp.fixture-evidence")))
                "syntax" -> temporary.writeText("{unfinished", Charsets.UTF_8)
                else -> {
                    val json = JSONObject(temporary.readText(Charsets.UTF_8))
                    if (damage == "missing_segments") json.remove("segments") else json.put("schemaVersion", 999)
                    temporary.writeText(json.toString(), Charsets.UTF_8)
                }
            }
            assertNotPromoted(fixture)
        }
    }

    @Test fun anotherSessionsTemporaryManifestCannotBeAdopted() {
        val fixture = seedFixture()
        val other = seedFixture()
        val temporary = leaveLegacyInterruptedSave(fixture)
        temporary.writeBytes(other.manifest.readBytes())
        assertNotPromoted(fixture)
        assertCommittedClipSurvives(other)
    }

    @Test fun missingOrOutOfDirectoryClipReferencesAreNotPromoted() {
        for (fileName in listOf("absent.mp4", "../another-session/clip.mp4")) {
            val fixture = seedFixture()
            val temporary = leaveLegacyInterruptedSave(fixture)
            val json = JSONObject(temporary.readText(Charsets.UTF_8))
            json.getJSONArray("segments").getJSONObject(0).put("fileName", fileName)
            temporary.writeText(json.toString(), Charsets.UTF_8)
            assertNotPromoted(fixture)
        }
    }

    @Test fun aValidPrimaryWinsOverBadTemporaryDataAndNormalSavingContinues() {
        val fixture = seedFixture()
        File(fixture.manifest.parentFile, "manifest.json.tmp").writeText("{unfinished", Charsets.UTF_8)
        assertCommittedClipSurvives(fixture)
        val reopened = SessionRepository(fixture.context)
        reopened.updateDetails(fixture.saved.id, "next normal save", "")
        assertEquals("next normal save", SessionRepository(fixture.context).readSession(fixture.saved.id)?.title)
        assertCommittedClipSurvives(fixture)
    }

    @Test fun interruptedExportMetadataCannotDeleteACommittedSourceClip() {
        val fixture = seedFixture()
        val temporary = leaveLegacyInterruptedSave(fixture)
        val json = JSONObject(temporary.readText(Charsets.UTF_8))
        json.put("status", "EXPORTING")
        json.put("outputFileName", fixture.saved.segments.single().fileName)
        temporary.writeText(json.toString(), Charsets.UTF_8)
        assertNotPromoted(fixture)
    }

    @Test fun unsupportedAtomicReplacementRetainsThePrimaryAndDoesNotFallback() {
        val fixture = seedFixture()
        val previous = System.getSecurityManager()
        val fault = ManifestFault(fixture.manifest, FaultPoint.BEFORE_RENAME, previous) {
            AtomicMoveNotSupportedException("synthetic staging", "synthetic primary", "injected unsupported move")
        }
        System.setSecurityManager(fault)
        try {
            assertThrows(AtomicMoveNotSupportedException::class.java) {
                fixture.repository.updateDetails(fixture.saved.id, "must not be committed", "")
            }
        } finally {
            System.setSecurityManager(previous)
        }
        assertTrue(fault.reached)
        assertTrue(fixture.manifest.isFile)
        assertEquals(fixture.saved.title, SessionRepository(fixture.context).readSession(fixture.saved.id)?.title)
        assertCommittedClipSurvives(fixture)
    }

    @Test fun aPrimaryWithAnotherSessionIdentityIsNotReturned() {
        val fixture = seedFixture()
        val other = seedFixture()
        check(fixture.manifest.renameTo(File(fixture.manifest.parentFile, "original-primary.fixture-evidence")))
        fixture.manifest.writeBytes(other.manifest.readBytes())
        assertNull(SessionRepository(fixture.context).readSession(fixture.saved.id))
        assertEquals(SYNTHETIC_CLIP, fixture.clip.readText(Charsets.UTF_8))
        assertCommittedClipSurvives(other)
    }

    @Test fun aNormalEmptySessionCanBeRecoveredAndSavedAgain() {
        val context = newContext()
        val repository = SessionRepository(context)
        val draft = repository.getOrCreateDraft(2_000L)
        val primary = File(context.filesDir, "setlog/sessions/${draft.id}/manifest.json")
        val previous = System.getSecurityManager()
        val fault = ManifestFault(primary, FaultPoint.BEFORE_RENAME, previous)
        System.setSecurityManager(fault)
        try {
            assertThrows(SimulatedInterruption::class.java) {
                repository.updateDetails(draft.id, "empty after interruption", "")
            }
        } finally {
            System.setSecurityManager(previous)
        }
        assertTrue(fault.reached)
        check(primary.renameTo(File(primary.parentFile, "empty-primary.fixture-evidence")))
        val reopened = SessionRepository(context)
        val recovered = requireNotNull(reopened.readSession(draft.id))
        assertTrue(recovered.segments.isEmpty())
        assertTrue(recovered.markers.isEmpty())
        assertEquals(0L, recovered.totalDurationMs)
        assertEquals(draft.id, reopened.getActiveDraft()?.id)
        reopened.updateDetails(draft.id, "empty normal save", "")
        assertEquals("empty normal save", SessionRepository(context).readSession(draft.id)?.title)
    }

    @Test fun outputGuardFailedAliasCannotDeleteTheCommittedClipWhenResumed() {
        val fixture = seedFixture()
        val temporary = leaveLegacyInterruptedSave(fixture)
        val json = JSONObject(temporary.readText(Charsets.UTF_8))
        json.put("status", "FAILED")
        json.put("outputFileName", fixture.saved.segments.single().fileName)
        temporary.writeText(json.toString(), Charsets.UTF_8)

        val reopened = SessionRepository(fixture.context)
        val adopted = reopened.readSession(fixture.saved.id)
        if (adopted != null) reopened.markDraftResumable(fixture.saved.id)

        assertTrue("Recovery followed by resume must not delete the committed source clip", fixture.clip.isFile)
        assertEquals(SYNTHETIC_CLIP, fixture.clip.readText(Charsets.UTF_8))
        assertNull("A temporary output/source alias must not be promoted", adopted)
    }

    @Test fun outputGuardRejectsSourceAliasesInEverySessionStatus() {
        val adoptedStatuses = mutableListOf<String>()
        for (status in listOf("DRAFT", "FAILED", "READY", "EXPORTING")) {
            val fixture = seedFixture()
            val temporary = leaveLegacyInterruptedSave(fixture)
            val json = JSONObject(temporary.readText(Charsets.UTF_8))
            json.put("status", status)
            json.put("outputFileName", fixture.saved.segments.single().fileName)
            temporary.writeText(json.toString(), Charsets.UTF_8)
            if (SessionRepository(fixture.context).readSession(fixture.saved.id) != null) adoptedStatuses += status
            assertEquals(SYNTHETIC_CLIP, fixture.clip.readText(Charsets.UTF_8))
        }
        assertEquals("No status may admit an output that aliases a source clip", emptyList<String>(), adoptedStatuses)
    }

    @Test fun outputGuardRejectsReadyWithAnEmptyCompletedOutput() {
        val fixture = seedFixture()
        val temporary = leaveLegacyInterruptedSave(fixture)
        val output = File(fixture.manifest.parentFile, "empty-completed-output.mp4")
        output.writeBytes(byteArrayOf())
        val json = JSONObject(temporary.readText(Charsets.UTF_8))
        json.put("status", "READY")
        json.put("outputFileName", output.name)
        temporary.writeText(json.toString(), Charsets.UTF_8)

        assertNotPromoted(fixture)
        assertTrue(output.isFile)
        assertEquals(0L, output.length())
    }

    @Test fun outputGuardAllowsNormalReadyAndInterruptedExportRecovery() {
        for (mode in listOf("READY", "EXPORTING", "EXPORTING_EMPTY", "EXPORTING_MISSING")) {
            val ready = mode == "READY"
            val fixture = seedFixture()
            val outputName = "distinct-completed-output.mp4"
            fixture.repository.markExporting(fixture.saved.id, outputName)
            val output = fixture.repository.outputFile(fixture.saved.id, outputName)
            when (mode) {
                "EXPORTING_MISSING" -> Unit
                "EXPORTING_EMPTY" -> output.writeBytes(byteArrayOf())
                else -> output.writeText("synthetic output bytes, not a real video", Charsets.UTF_8)
            }
            if (ready) fixture.repository.markReady(fixture.saved.id)
            leaveLegacyInterruptedSave(fixture)

            val reopened = SessionRepository(fixture.context)
            val recovered = requireNotNull(reopened.readSession(fixture.saved.id))
            assertEquals(fixture.saved.segments, recovered.segments)
            assertEquals(fixture.saved.markers, recovered.markers)
            assertEquals(SYNTHETIC_CLIP, fixture.clip.readText(Charsets.UTF_8))
            if (ready) {
                assertEquals("READY", recovered.status.name)
                assertEquals(outputName, recovered.outputFileName)
                assertTrue(output.length() > 0L)
            } else {
                assertEquals("FAILED", recovered.status.name)
                assertNull(recovered.outputFileName)
                reopened.markDraftResumable(fixture.saved.id)
                assertEquals(SYNTHETIC_CLIP, fixture.clip.readText(Charsets.UTF_8))
            }
        }
    }

    @Test fun outputStateMatrixMatchesTheFourWriterStates() {
        val mismatches = mutableListOf<String>()
        for (status in listOf("DRAFT", "FAILED", "READY", "EXPORTING")) {
            for (reference in listOf("OMITTED", "NULL", "NAMED", "UNSAFE")) {
                val fixture = seedFixture()
                val temporary = leaveLegacyInterruptedSave(fixture)
                val json = JSONObject(temporary.readText(Charsets.UTF_8))
                json.put("status", status)
                when (reference) {
                    "OMITTED" -> json.remove("outputFileName")
                    "NULL" -> json.put("outputFileName", JSONObject.NULL)
                    "UNSAFE" -> json.put("outputFileName", "../other-session/output.mp4")
                    else -> {
                        val output = File(fixture.manifest.parentFile, "matrix-output.mp4")
                        output.writeText("synthetic output bytes", Charsets.UTF_8)
                        json.put("outputFileName", output.name)
                    }
                }
                temporary.writeText(json.toString(), Charsets.UTF_8)
                val recovered = SessionRepository(fixture.context).readSession(fixture.saved.id)
                val expected = if (status == "DRAFT" || status == "FAILED") {
                    reference == "OMITTED" || reference == "NULL"
                } else {
                    reference == "NAMED"
                }
                if ((recovered != null) != expected) {
                    mismatches += "$status/$reference: expected accepted=$expected, actual=${recovered?.status}"
                }
                assertEquals(SYNTHETIC_CLIP, fixture.clip.readText(Charsets.UTF_8))
            }
        }
        assertEquals("Only writer-consistent state/output pairs may recover", emptyList<String>(), mismatches)
    }

    @Test fun outputStateWriterTransitionsUseNullOnlyForDraftAndFailed() {
        val fixture = seedFixture()
        assertEquals("DRAFT", fixture.saved.status.name)
        assertNull(fixture.saved.outputFileName)
        val name = "writer-output.mp4"
        val exporting = fixture.repository.markExporting(fixture.saved.id, name)
        assertEquals("EXPORTING", exporting.status.name)
        assertEquals(name, exporting.outputFileName)
        val failed = fixture.repository.markExportFailed(fixture.saved.id, "synthetic export failure")
        assertEquals("FAILED", failed.status.name)
        assertNull(failed.outputFileName)
        val resumed = fixture.repository.markDraftResumable(fixture.saved.id)
        assertEquals("DRAFT", resumed.status.name)
        assertNull(resumed.outputFileName)
        fixture.repository.markExporting(fixture.saved.id, name)
        fixture.repository.outputFile(fixture.saved.id, name).writeText("synthetic completed output", Charsets.UTF_8)
        val ready = fixture.repository.markReady(fixture.saved.id)
        assertEquals("READY", ready.status.name)
        assertEquals(name, ready.outputFileName)
        assertEquals(SYNTHETIC_CLIP, fixture.clip.readText(Charsets.UTF_8))
    }

    @Test fun outputStateExportingDoesNotRequireCompletedBytesButReadyDoes() {
        val mismatches = mutableListOf<String>()
        for (status in listOf("READY", "EXPORTING")) {
            for (bytes in listOf("MISSING", "EMPTY", "NONEMPTY")) {
                val fixture = seedFixture()
                val temporary = leaveLegacyInterruptedSave(fixture)
                val output = File(fixture.manifest.parentFile, "state-bytes-output.mp4")
                when (bytes) {
                    "EMPTY" -> output.writeBytes(byteArrayOf())
                    "NONEMPTY" -> output.writeText("synthetic completed output", Charsets.UTF_8)
                }
                val json = JSONObject(temporary.readText(Charsets.UTF_8))
                json.put("status", status)
                json.put("outputFileName", output.name)
                temporary.writeText(json.toString(), Charsets.UTF_8)
                val recovered = SessionRepository(fixture.context).readSession(fixture.saved.id)
                val expected = status == "EXPORTING" || bytes == "NONEMPTY"
                if ((recovered != null) != expected) mismatches += "$status/$bytes"
                assertEquals(SYNTHETIC_CLIP, fixture.clip.readText(Charsets.UTF_8))
            }
        }
        assertEquals(emptyList<String>(), mismatches)
    }

    private fun assertNotPromoted(fixture: Fixture) {
        val reopened = SessionRepository(fixture.context)
        assertNull(reopened.readSession(fixture.saved.id))
        assertTrue(reopened.loadAll().none { it.id == fixture.saved.id })
        assertFalse(fixture.manifest.exists())
        assertEquals(SYNTHETIC_CLIP, fixture.clip.readText(Charsets.UTF_8))
    }

    private fun leaveLegacyInterruptedSave(fixture: Fixture): File {
        interruptSave(fixture, FaultPoint.BEFORE_RENAME)
        // Preserve the old primary as test evidence when the writer no longer deletes it.
        if (fixture.manifest.exists()) {
            check(fixture.manifest.renameTo(File(fixture.manifest.parentFile, "old-primary.fixture-evidence")))
        }
        return File(fixture.manifest.parentFile, "manifest.json.tmp")
    }

    private fun interruptSave(fixture: Fixture, point: FaultPoint): ManifestFault {
        val previous = System.getSecurityManager()
        val fault = ManifestFault(fixture.manifest, point, previous)
        System.setSecurityManager(fault)
        try {
            try {
                fixture.repository.updateDetails(fixture.saved.id, "after", "synthetic caption")
                throw AssertionError("The filesystem fault point was not reached")
            } catch (_: SimulatedInterruption) {
                // The real temporary stream has already been flushed, synced, and closed.
            }
        } finally {
            System.setSecurityManager(previous)
        }
        assertTrue(fault.reached)
        val temporary = File(fixture.manifest.parentFile, "manifest.json.tmp")
        val json = JSONObject(temporary.readText(Charsets.UTF_8))
        assertEquals(fixture.saved.id, json.getString("id"))
        assertEquals("after", json.getString("title"))
        assertEquals(1, json.getJSONArray("segments").length())
        assertEquals(SYNTHETIC_CLIP, fixture.clip.readText(Charsets.UTF_8))
        println("fault=$point primary_exists=${fault.primaryExisted} complete_tmp=true")
        return fault
    }

    private fun assertCommittedClipSurvives(fixture: Fixture) {
        val reopened = SessionRepository(fixture.context)
        val restored = reopened.readSession(fixture.saved.id)
        assertNotNull("A completed clip must remain discoverable after repository restart", restored)
        assertEquals(fixture.saved.id, restored!!.id)
        assertEquals(fixture.saved.segments, restored.segments)
        assertEquals(fixture.saved.markers, restored.markers)
        assertEquals(fixture.saved.totalDurationMs, restored.totalDurationMs)
        assertEquals(fixture.saved.id, reopened.getActiveDraft()?.id)
        assertTrue(reopened.loadAll().any { it.id == fixture.saved.id })
        val clip = reopened.segmentFile(restored.id, restored.segments.single())
        assertEquals(fixture.clip.canonicalPath, clip.canonicalPath)
        assertEquals(SYNTHETIC_CLIP, clip.readText(Charsets.UTF_8))
    }

    private fun seedFixture(): Fixture {
        val context = newContext()
        val repository = SessionRepository(context)
        val draft = repository.getOrCreateDraft(1_000L)
        val pending = repository.createPendingSegment(draft.id, 1_010L)
        repository.partialFile(pending).writeText(SYNTHETIC_CLIP, Charsets.UTF_8)
        val saved = repository.commitSegment(pending, 1_234L)
        val clip = repository.segmentFile(saved.id, saved.segments.single())
        val manifest = File(clip.parentFile, "manifest.json")
        return Fixture(context, repository, saved, clip, manifest)
    }

    private fun newContext(): Context {
        val root = File(
            System.getProperty("vibe.test.fixtureRoot", System.getProperty("java.io.tmpdir")),
            "manifest-test-${UUID.randomUUID()}",
        ).apply { check(mkdirs()) }
        return object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
            override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                super.getSharedPreferences("${root.name}-$name", mode)
        }
    }

    private data class Fixture(
        val context: Context,
        val repository: SessionRepository,
        val saved: VideoSession,
        val clip: File,
        val manifest: File,
    )

    private enum class FaultPoint { BEFORE_PRIMARY_MUTATION, BEFORE_RENAME }
    private class SimulatedInterruption : Error("synthetic interruption at manifest replacement")

    /** A process-local JDK17 fault injector, scoped to one freshly created fixture manifest. */
    private class ManifestFault(
        private val primary: File,
        private val point: FaultPoint,
        private val previous: SecurityManager?,
        private val failure: () -> Throwable = { SimulatedInterruption() },
    ) : SecurityManager() {
        private val temporary = File(primary.parentFile, "manifest.json.tmp")
        private var temporaryWriteChecks = 0
        var reached = false
            private set
        var primaryExisted = false
            private set

        override fun checkPermission(permission: Permission) { previous?.checkPermission(permission) }
        override fun checkPermission(permission: Permission, context: Any) {
            previous?.checkPermission(permission, context)
        }

        override fun checkDelete(file: String) {
            previous?.checkDelete(file)
            if (!reached && point == FaultPoint.BEFORE_PRIMARY_MUTATION && matches(file, primary)) interrupt()
        }

        override fun checkWrite(file: String) {
            previous?.checkWrite(file)
            if (!reached && matches(file, temporary)) {
                temporaryWriteChecks += 1
                // First check opens the real staging stream; the next is its rename/move.
                if (temporaryWriteChecks == 2) interrupt()
            }
        }

        private fun interrupt(): Nothing {
            reached = true
            primaryExisted = primary.isFile
            throw failure()
        }

        private fun matches(path: String, file: File): Boolean =
            File(path).absolutePath.equals(file.absolutePath, ignoreCase = true)
    }

    companion object {
        private const val SYNTHETIC_CLIP = "synthetic fixture bytes, deliberately not a real video"
    }
}
