package com.phoneform.desktop.security

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.encryption.AccessPermission
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

class PdfClientTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun isolatedRangeReaderRendersAndRecoversAcrossTwentyFaultCycles() {
        SecureWorkspace.open(temporary.newFolder().toPath(), TestKeyProtector()).use { workspace ->
            workspace.assets.put("valid.pdf", ByteArrayInputStream(pdf()))
            workspace.assets.put("protected.pdf", ByteArrayInputStream(pdf(protected = true)))
            workspace.assets.put("corrupt.pdf", ByteArrayInputStream("%PDF-1.7\nbroken".toByteArray()))
            val client = PdfClient(workspace.assets)
            repeat(20) {
                val session = client.open("valid.pdf")
                session.use {
                    assertEquals(1, session.pageCount)
                    assertTrue(session.isAlive)
                    assertNotEquals(ProcessHandle.current().pid(), session.processId)
                    val png = session.render(0, 36)
                    val image = ImageIO.read(ByteArrayInputStream(png))
                    assertTrue(image.width > 100); assertTrue(image.height > 100)
                    image.flush()
                }
                // Check the original Process object. Windows may reuse a terminated PID
                // for a later helper or another concurrent test JVM.
                assertFalse(session.isAlive)
                assertEquals("PASSWORD_PROTECTED", (expectFailure { client.open("protected.pdf").close() } as PdfFailure).reason)
                expectFailure { client.open("corrupt.pdf").close() }
                client.open("valid.pdf").use { assertTrue(it.render(0, 36).isNotEmpty()) }
            }
            assertEquals(setOf("valid.pdf", "protected.pdf", "corrupt.pdf"), FilesIn(workspace.assets.root))
        }
    }

    @Test fun killedOrCancelledHelperCannotPoisonFollowingDocument() {
        SecureWorkspace.open(temporary.newFolder().toPath(), TestKeyProtector()).use { workspace ->
            workspace.assets.put("valid.pdf", ByteArrayInputStream(pdf()))
            val client = PdfClient(workspace.assets)
            val killed = client.open("valid.pdf")
            ProcessHandle.of(killed.processId).get().destroyForcibly()
            expectFailure { killed.render(0, 36) }
            killed.close()
            client.open("valid.pdf").use { session ->
                session.cancel()
                expectFailure { session.render(0, 36) }
                assertFalse(ProcessHandle.of(session.processId).map { it.isAlive }.orElse(false))
            }
            client.open("valid.pdf").use { assertTrue(it.render(0, 36).isNotEmpty()) }
        }
    }

    private fun pdf(protected: Boolean = false): ByteArray = ByteArrayOutputStream().also { output ->
        PDDocument().use { document ->
            val page = PDPage(); document.addPage(page)
            PDPageContentStream(document, page).use { stream -> stream.addRect(20f, 20f, 100f, 100f); stream.fill() }
            if (protected) document.protect(StandardProtectionPolicy("owner-key", "reader-key", AccessPermission()).apply { encryptionKeyLength = 128 })
            document.save(output)
        }
    }.toByteArray()

    private fun FilesIn(path: java.nio.file.Path): Set<String> = java.nio.file.Files.list(path).use { files ->
        files.map { it.fileName.toString() }.toList().toSet()
    }
}
