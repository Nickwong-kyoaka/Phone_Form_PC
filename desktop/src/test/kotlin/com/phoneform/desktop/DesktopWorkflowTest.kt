package com.phoneform.desktop

import com.phoneform.ai.core.data.XlsxWriter
import com.phoneform.ai.core.data.toXlsxSheets
import com.phoneform.ai.core.model.*
import com.phoneform.desktop.data.DesktopRepositories
import com.phoneform.desktop.security.SecureWorkspace
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.lang.management.ManagementFactory
import java.lang.management.MemoryType
import java.nio.file.Files
import java.nio.file.Path
import java.time.OffsetDateTime
import java.time.ZoneOffset
import javax.imageio.ImageIO
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Real Windows DPAPI, encrypted SQLite/files, repository workflow and actual XLSX bytes.
 * Set PHONEFORM_SCALE_COUNT=1000 to run the larger dataset with the same assertions.
 * This exercises human review, independently of OCR accuracy or UI automation.
 */
class DesktopWorkflowTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun humanReviewWorkflowAndThreeLanguageExports() = runBlocking {
        val count = System.getenv("PHONEFORM_SCALE_COUNT")?.toIntOrNull() ?: 30
        require(count in 1..1000)
        val started = System.nanoTime()
        val retained = System.getenv("PHONEFORM_ACCEPTANCE_WORKSPACE")?.takeIf { it.isNotBlank() }?.let { Path.of(it).toAbsolutePath().normalize() }
        val root = if (retained != null) {
            require(!Files.exists(retained)) { "Acceptance workspace already exists; choose a new synthetic-only directory" }
            Files.createDirectories(retained.parent); Files.createDirectory(retained)
        } else folder.newFolder("encrypted-workflow").toPath()
        var workspace = SecureWorkspace.open(root)
        try {
            var db = DesktopRepositories(workspace)
            val projectId = java.util.UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            db.projects.saveProject(ProjectDetails(ProjectConfig(projectId, "中英問卷 / Bilingual questionnaire", count,
                QuestionnaireLanguage.ENGLISH, NumberingStrategy.PAPER_ID, now, now), OutputProfile(projectId, language = OutputHeaderLanguage.ENGLISH), emptyList()))
            val pages = ProjectOperationGate.shared.withLease(projectId) { (0..1).map { fixturePage(workspace, it) } }
            val questions = (0..8).map { index ->
                QuestionDefinition("q${index + 1}", pages[if (index < 5) 0 else 1].id, if (index < 5) index else index - 5,
                    "Q${index + 1}", "題目 ${index + 1} / Question ${index + 1}", QuestionType.TEXT,
                    NormalizedRect(.05f, .05f, .95f, .9f), NormalizedRect(.1f, .1f, .9f, .2f))
            }
            val opened = db.templates.beginDraft(projectId)
            val templateDraft = db.templates.saveDraft(opened.copy(pages = pages, questions = questions))
            val owner = PageProcessingOwner(projectId, PageProcessingOwnerKind.TEMPLATE_DRAFT, templateDraft.id, templateDraft.revision)
            db.imageProcessing.keepAllWarnings(owner, pages.associate { it.id to PageProcessingFingerprint.of(it) }, "Synthetic fixture manually inspected")
            val template = db.templates.confirmDraft(projectId, templateDraft.id, templateDraft.revision)
            val phaseStart = System.nanoTime()
            repeat(count) { index ->
                val begin = db.submissions.beginSubmission(projectId)
                val draft = db.submissions.saveDraft(begin.copy(respondentId = "%05d".format(index + 1), pages = template.document.pages.map { PageRevision(it.id, it.copy(id = java.util.UUID.randomUUID().toString())) }))
                val draftOwner = PageProcessingOwner(projectId, PageProcessingOwnerKind.SUBMISSION_DRAFT, draft.id, draft.editRevision)
                db.imageProcessing.keepAllWarnings(draftOwner, draft.pages.associate { it.image.id to PageProcessingFingerprint.of(it.image) }, "Synthetic fixture manually inspected")
                val revision = db.submissions.submitSubmission(draft.id, draft.editRevision, SubmissionConfirmation(duplicatePageSourcesConfirmed = true))
                val preparation = db.jobs.claimNext("acceptance", true)!!
                assertEquals(revision.id, preparation.submissionRevisionId)
                assertTrue(db.jobs.checkpoint(preparation, revision.pages)); assertTrue(db.jobs.complete(preparation))
                var version = 0
                questions.forEachIndexed { questionIndex, question ->
                    val text = if (questionIndex == 0) "%05d".format(index + 1) else if (questionIndex == 8) "=1+1" else "答案 $index / answer $questionIndex"
                    val saved = db.intelligence.saveAnswer(draft.submissionId, revision.id, version, AnswerValue("question:${question.id}", AnswerState.VALUE, text), "acceptance:$index:$questionIndex")
                    version = saved.version
                }
                db.intelligence.confirmReview(draft.submissionId, revision.id, version, "acceptance-confirm:$index")
                if ((index + 1) % 100 == 0) println("WORKFLOW_PROGRESS records=${index + 1} elapsed_ms=${(System.nanoTime() - phaseStart) / 1_000_000}")
            }
            val collectionMillis = (System.nanoTime() - phaseStart) / 1_000_000
            val summary = db.projects.observeProjectSummaries().first().single()
            assertEquals(count, summary.submittedCount); assertEquals(count, summary.confirmedCount)
            val exportStart = System.nanoTime()
            val exports = mutableListOf<Pair<String, Long>>()
            for (language in OutputHeaderLanguage.entries) {
                val current = db.projects.getProject(projectId)!!
                db.projects.saveOutputConfiguration(current.copy(output = current.output.copy(language = language)), emptyList(), current.output.version)
                val snapshot = db.intelligence.createExportSnapshot(projectId, false, "snapshot:${language.name}")
                assertEquals(count, snapshot.records.size); assertEquals(4, snapshot.sheets.size)
                assertEquals(count, snapshot.sheets.first().rows.size)
                assertTrue(snapshot.sheets.first().rows.first().any { it.value == "00001" })
                assertTrue(snapshot.sheets.first().rows.first().any { it.value == "=1+1" && it.type == ExportCellType.TEXT })
                val expectedHeader = when (language) { OutputHeaderLanguage.TRADITIONAL -> "問卷編號"; OutputHeaderLanguage.SIMPLIFIED -> "问卷编号"; OutputHeaderLanguage.ENGLISH -> "Questionnaire number" }
                assertEquals(expectedHeader, snapshot.sheets.first().headers.first())
                val bytes = ByteArrayOutputStream(); val rowCounts = XlsxWriter.write(bytes, snapshot.toXlsxSheets())
                XlsxWriter.validate(ByteArrayInputStream(bytes.toByteArray()), rowCounts)
                // Synthetic data only: retain the explicit exported workbook for Microsoft Excel QA.
                val reviewCopy = Path.of("..", "output", "validation", "uiux-0.2.0", "workflow-$count-${language.name}.xlsx")
                Files.createDirectories(reviewCopy.parent); Files.write(reviewCopy, bytes.toByteArray())
                val info = ProjectOperationGate.shared.withLease(projectId) { workspace.assets.put("exports/${language.name}/acceptance.xlsx", ByteArrayInputStream(bytes.toByteArray())) }
                db.intelligence.saveExportArtifact(ExportArtifact(snapshot.id, ExportArtifactStatus.READY, info.relativePath, info.sha256, info.byteCount))
                exports += language.name to info.byteCount
            }
            val exportMillis = (System.nanoTime() - exportStart) / 1_000_000
            workspace.close(); workspace = SecureWorkspace.open(root); db = DesktopRepositories(workspace)
            assertEquals(count, db.projects.observeProjectSummaries().first().single().confirmedCount)
            assertEquals(3, db.intelligence.observeExports(projectId).first().size)
            val peakHeap = ManagementFactory.getMemoryPoolMXBeans().filter { it.type == MemoryType.HEAP }.sumOf { it.peakUsage.used }
            val totalMillis = (System.nanoTime() - started) / 1_000_000
            val result = """{"date":"${OffsetDateTime.now(ZoneOffset.ofHours(8))}","records":$count,"pagesPerRecord":2,"targetsPerRecord":9,"headerLanguages":3,"collectionMillis":$collectionMillis,"exportMillis":$exportMillis,"totalMillis":$totalMillis,"peakHeapPoolBytes":$peakHeap,"encryptedDatabaseBytes":${Files.size(root.resolve("phoneform.db"))},"xlsxBytes":{${exports.joinToString(",") { "\"${it.first}\":${it.second}" }}}}"""
            val report = Path.of("build", "reports", "acceptance", "workflow-$count.json")
            Files.createDirectories(report.parent); Files.writeString(report, result)
            println("WORKFLOW_ACCEPTANCE $result")
            if (retained != null) println("WORKFLOW_WORKSPACE $retained")
        } finally { workspace.close() }
        Unit
    }

    private fun fixturePage(workspace: SecureWorkspace, position: Int): SamplePage {
        val image = BufferedImage(1200, 1600, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try {
            graphics.color = Color.WHITE; graphics.fillRect(0, 0, image.width, image.height)
            graphics.color = Color.BLACK; graphics.font = java.awt.Font("SansSerif", java.awt.Font.PLAIN, 28)
            graphics.drawString("PhoneForm synthetic questionnaire / page ${position + 1}", 50, 80)
            repeat(5) { row -> graphics.drawString("Q${position * 5 + row + 1}. Answer:", 60, 180 + row * 240); graphics.drawRect(60, 210 + row * 240, 1060, 140) }
        } finally { graphics.dispose() }
        val png = ByteArrayOutputStream(); check(ImageIO.write(image, "png", png))
        val info = workspace.assets.put("fixtures/page-$position.png", ByteArrayInputStream(png.toByteArray()))
        return SamplePage("slot-$position", SampleAsset("asset-$position", SampleKind.IMAGE, info.relativePath, info.sha256, info.byteCount), 0, position, info.relativePath, info.relativePath, image.width, image.height)
    }
}
