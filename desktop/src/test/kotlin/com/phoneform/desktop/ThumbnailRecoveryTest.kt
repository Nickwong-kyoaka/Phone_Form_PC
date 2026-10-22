package com.phoneform.desktop

import com.phoneform.ai.core.model.*
import com.phoneform.desktop.security.SecureWorkspace
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

class ThumbnailRecoveryTest {
    @get:Rule val folder=TemporaryFolder()
    private fun png(width:Int,height:Int):ByteArray {
        val image=BufferedImage(width,height,BufferedImage.TYPE_INT_RGB)
        return try {
            val graphics=image.createGraphics()
            try{graphics.color=Color.WHITE;graphics.fillRect(0,0,width,height)}finally{graphics.dispose()}
            ByteArrayOutputStream().also {check(ImageIO.write(image,"png",it))}.toByteArray()
        }finally{image.flush()}
    }
    private suspend fun withPage(width:Int=400,height:Int=600,block:suspend (Controller,SamplePage)->Unit) {
        val source=folder.newFile("source-${newId()}.png");source.writeBytes(png(width,height))
        Controller(SecureWorkspace.open(folder.newFolder("workspace-${newId()}").toPath()),Path.of("../runtime/models"),startWorkers=false).use { controller->
            controller.createProject("Anonymous thumbnail",null,QuestionnaireLanguage.ENGLISH,NumberingStrategy.GENERATED,OutputHeaderLanguage.ENGLISH)
            controller.importTemplate(source,listOf(0))
            block(controller,controller.templateDraft().pages.single())
        }
    }

    @Test fun missingThumbnailIsRebuiltFromExistingBaseWithoutNeedingTheOriginal()=runBlocking {
        withPage {controller,page->
            val originalBase=controller.workspace.assets.readBytes(page.renderPath)
            controller.workspace.assets.delete(page.thumbnailPath)
            controller.workspace.assets.delete(page.asset.relativePath)
            val restored=controller.loadThumbnail(page)
            try {assertTrue(restored.width<=160 && restored.height<=224)}finally{restored.flush()}
            assertTrue(controller.workspace.assets.exists(page.thumbnailPath))
            assertArrayEquals(originalBase,controller.workspace.assets.readBytes(page.renderPath))
        }
    }

    @Test fun legacyPortraitThumbnailFitsInMemoryWithoutRewritingTheEncryptedDerivative()=runBlocking {
        withPage {controller,page->
            controller.workspace.assets.put(page.thumbnailPath,ByteArrayInputStream(png(160,226)))
            val before=Files.readAllBytes(controller.workspace.assets.root.resolve(page.thumbnailPath))
            val compatible=controller.loadThumbnail(page)
            try {assertTrue(compatible.width<=160 && compatible.height<=224)}finally{compatible.flush()}
            assertArrayEquals(before,Files.readAllBytes(controller.workspace.assets.root.resolve(page.thumbnailPath)))
        }
    }

    @Test fun oversizedAuthenticatedLegacyThumbnailNeedsExplicitRebuild()=runBlocking {
        withPage {controller,page->
            controller.workspace.assets.put(page.thumbnailPath,ByteArrayInputStream(png(160,2000)))
            val before=Files.readAllBytes(controller.workspace.assets.root.resolve(page.thumbnailPath))
            try {controller.loadThumbnail(page).flush();fail("Oversized thumbnail silently accepted")}
            catch(expected:IllegalArgumentException){assertEquals("THUMBNAIL_REBUILD_REQUIRED",expected.message)}
            assertArrayEquals(before,Files.readAllBytes(controller.workspace.assets.root.resolve(page.thumbnailPath)))
            val repaired=controller.loadThumbnail(page,rebuild=true)
            try{assertTrue(repaired.width<=160 && repaired.height<=224)}finally{repaired.flush()}
            assertFalse(before.contentEquals(Files.readAllBytes(controller.workspace.assets.root.resolve(page.thumbnailPath))))
        }
    }

    @Test fun authenticationFailureIsNeverTreatedAsMissingEvenOnExplicitRetry()=runBlocking {
        withPage {controller,page->
            val target=controller.workspace.assets.root.resolve(page.thumbnailPath)
            val changed=Files.readAllBytes(target).also {it[it.lastIndex]=(it.last().toInt() xor 1).toByte()}
            Files.write(target,changed)
            for(rebuild in listOf(false,true)) {
                var failed=false
                try {controller.loadThumbnail(page,rebuild).flush()}catch(_:Exception){failed=true}
                assertTrue("Tampered thumbnail must fail authentication",failed)
                assertArrayEquals(changed,Files.readAllBytes(target))
            }
        }
    }

    @Test fun veryNarrowTallImportedImageProducesOnlyABoundedThumbnail()=runBlocking {
        withPage(1,100_000) {controller,page->
            assertEquals(1,page.width);assertEquals(2560,page.height)
            val thumb=controller.loadThumbnail(page)
            try{assertEquals(1,thumb.width);assertEquals(224,thumb.height)}finally{thumb.flush()}
        }
    }
}
