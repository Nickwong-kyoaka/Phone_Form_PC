package com.phoneform.desktop.security

import com.phoneform.desktop.data.DesktopRepositories
import com.phoneform.desktop.data.RecordCodec
import com.phoneform.ai.core.model.*
import java.io.IOException
import java.nio.file.Path
import java.sql.Connection
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.sqlite.mc.SQLiteMCSqlCipherConfig

class SchemaMigrationTest {
    @get:Rule val folder = TemporaryFolder()
    private val key = ByteArray(32) { (it + 7).toByte() }
    private fun raw(path: Path): Connection {
        Class.forName("org.sqlite.JDBC")
        val properties = SQLiteMCSqlCipherConfig.getV4Defaults().setHmacUse(true).setPlaintextHeaderSize(0)
            .withKey(key.joinToString("") { "%02x".format(it) }).build().toProperties()
        return java.sql.DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}", properties)
    }
    private fun version(db: Connection) = db.createStatement().use { s -> s.executeQuery("PRAGMA user_version").use { it.next(); it.getInt(1) } }
    private fun insert(db: Connection, kind: String, id: String, payload: String) = db.prepareStatement("INSERT INTO pc_records VALUES(?,?,?,?)").use {
        it.setString(1, kind); it.setString(2, id); it.setString(3, "project"); it.setString(4, payload); it.executeUpdate()
    }
    private fun payload(db: Connection, kind: String, id: String) = db.prepareStatement("SELECT payload FROM pc_records WHERE kind=? AND id=?").use {
        it.setString(1, kind); it.setString(2, id); it.executeQuery().use { row -> row.next(); row.getString(1) }
    }
    private fun legacy(id: String, revision: String, status: String) = """{"id":"$id","projectId":"project","submissionId":"submission","submissionRevisionId":"$revision","status":"$status","completedPageIds":[],"observations":[],"alignments":[],"attempt":1,"processSessionId":"old","runToken":"old","createdAtEpochMillis":1}"""
    private inline fun <reified T: Throwable> fails(block: () -> Unit) { try { block(); fail("Expected ${T::class.java.simpleName}") } catch (e: Throwable) { if (e !is T) throw e } }

    @Test fun migratesLegacyJobsWithoutInventingProfilesOrChangingFrozenRecords() {
        val path = folder.newFolder().toPath().resolve("data.db")
        val frozen = """{"immutable":"original frozen snapshot"}"""
        val answer = """{"immutable":"saved manual answer"}"""
        SecureDatabase.open(path, key).use { db -> db.transaction { c ->
            assertEquals(2, version(c))
            listOf("QUEUED", "RUNNING", "PAUSED", "FAILED", "READY").forEachIndexed { index, status ->
                insert(c, "recognition", "job-$index", legacy("job-$index", "revision-$index", status))
                insert(c, "recognition_head", "revision-$index", """{"id":"job-$index"}""")
            }
            insert(c, "export", "snapshot", frozen); insert(c, "answers", "answers", answer)
            c.createStatement().use { it.execute("PRAGMA user_version=1") }
        } }
        SecureDatabase.open(path, key).use { db -> db.read { c ->
            assertEquals(2, version(c)); assertEquals(frozen, payload(c, "export", "snapshot")); assertEquals(answer, payload(c, "answers", "answers"))
            (0..4).forEach { index ->
                val job = RecordCodec.decode(payload(c, "recognition", "job-$index"), RecognitionJob::class.java)
                assertEquals(if(index==4) RecognitionJobStatus.READY else RecognitionJobStatus.BLOCKED, job.status)
                assertEquals(RecognitionProvenance.LEGACY_UNBOUND, job.provenance); assertNull(job.profileId)
                assertNull(job.runToken); assertNull(job.processSessionId); assertEquals("", job.inputFingerprint)
                assertEquals("""{"id":"job-$index"}""", payload(c, "recognition_head", "revision-$index"))
            }
        } }
        SecureDatabase.open(path, key).use { db -> assertEquals(2, db.read(::version)) }
    }

    @Test fun missingUniqueHeadIsRecoveredButAmbiguousHeadsRollbackMigration() {
        val path = folder.newFolder().toPath().resolve("data.db")
        SecureDatabase.open(path, key).use { db -> db.transaction { c -> insert(c,"recognition","one",legacy("one","r","QUEUED")); c.createStatement().use { it.execute("PRAGMA user_version=1") } } }
        SecureDatabase.open(path,key).use { db -> assertEquals("one", com.google.gson.JsonParser.parseString(db.read { payload(it,"recognition_head","r") }).asJsonObject.get("id").asString) }
        SecureDatabase.open(path,key).use { db -> db.transaction { c ->
            c.createStatement().use { it.execute("DELETE FROM pc_records WHERE kind='recognition_head'"); it.execute("PRAGMA user_version=1") }
            insert(c,"recognition","two",legacy("two","r","QUEUED"))
        } }
        fails<IOException> { SecureDatabase.open(path,key).close() }
        raw(path).use { assertEquals(1, version(it)); assertTrue(payload(it,"recognition","two").contains("QUEUED")) }
    }

    @Test fun failureRollsBackEveryChangedRowAndFutureOrUnversionedSchemasAreRejected() {
        val path = folder.newFolder().toPath().resolve("data.db")
        val original = legacy("01","r","RUNNING")
        SecureDatabase.open(path,key).use { db -> db.transaction { c -> insert(c,"recognition","01",original); insert(c,"recognition","02","{}"); c.createStatement().use { it.execute("PRAGMA user_version=1") } } }
        fails<Exception> { SecureDatabase.open(path,key).close() }
        raw(path).use { assertEquals(1,version(it)); assertEquals(original,payload(it,"recognition","01")) }
        raw(path).use { it.createStatement().use { s -> s.execute("PRAGMA user_version=3") } }
        fails<IOException> { SecureDatabase.open(path,key).close() }
        raw(path).use { assertEquals(3,version(it)) }
        val unversioned = folder.newFolder().toPath().resolve("unversioned.db")
        raw(unversioned).use { it.createStatement().use { s -> s.execute("CREATE TABLE user_data(value TEXT)") } }
        fails<IOException> { SecureDatabase.open(unversioned,key).close() }
        raw(unversioned).use { assertEquals(0,version(it)) }
    }

    @Test fun codecRejectsUnknownVersionTwoEnumsAndNormalizesOldOptionalFields() {
        val job = com.google.gson.JsonParser.parseString(legacy("j","r","READY")).asJsonObject
        val old = RecordCodec.decode(job.toString(),RecognitionJob::class.java)
        assertEquals(RecognitionProvenance.LEGACY_UNBOUND,old.provenance); assertEquals("",old.engineVersion)
        for ((field,value) in listOf("status" to "NEW_STATUS", "provenance" to "NEW_PROVENANCE")) {
            val changed=job.deepCopy().apply { addProperty("recordVersion",2); addProperty(field,value) }
            fails<IOException> { RecordCodec.decode(changed.toString(),RecognitionJob::class.java) }
        }
        val profile="""{"templateVersionId":"t","engineVersion":"legacy","pageFingerprints":{},"createdAtEpochMillis":1,"settings":[{"questionId":"q","style":"NEW_STYLE"}],"recordVersion":2}"""
        fails<IOException> { RecordCodec.decode(profile,TemplateRecognitionProfile::class.java) }
        val question="""{"questions":[{"recognition":{"questionId":"q","style":"NEW_STYLE"}}],"recordVersion":2}"""
        fails<IOException> { RecordCodec.decode(question,TemplateDraft::class.java) }
        val decision=job.deepCopy().apply { addProperty("recordVersion",2); add("observations",com.google.gson.JsonParser.parseString("""[{"optionObservations":[{"decision":"NEW_DECISION"}]}]""")) }
        fails<IOException> { RecordCodec.decode(decision.toString(),RecognitionJob::class.java) }
        val newer=job.deepCopy().apply { addProperty("recordVersion",3) }
        fails<IOException> { RecordCodec.decode(newer.toString(),RecognitionJob::class.java) }
    }
}
