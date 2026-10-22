package com.phoneform.desktop

import com.phoneform.ai.core.model.*
import com.phoneform.desktop.security.SecureWorkspace
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Path

class ControllerActionAdmissionTest {
    @get:Rule val folder=TemporaryFolder()
    private fun controller()=Controller(SecureWorkspace.open(folder.newFolder().toPath()),Path.of("../runtime/models"),startWorkers=false)
    private suspend fun settled(c:Controller)=withTimeout(15_000){while(c.state.value.busy)delay(10)}

    @Test fun immediateDoubleClickCreatesOnlyOneProjectAndKeepsInputsBlockedUntilCompletion()=runBlocking {
        controller().use {c->
            c.createProject("Base",null,QuestionnaireLanguage.ENGLISH,NumberingStrategy.GENERATED,OutputHeaderLanguage.ENGLISH)
            c.refresh()
            val started=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
            try {
                c.perform {started.complete(Unit);release.await();createProject("Accepted",null,QuestionnaireLanguage.ENGLISH,NumberingStrategy.GENERATED,OutputHeaderLanguage.ENGLISH)}
                assertTrue("Admission must block inputs before the IO coroutine runs",c.state.value.busy)
                c.perform {createProject("Duplicate",null,QuestionnaireLanguage.ENGLISH,NumberingStrategy.GENERATED,OutputHeaderLanguage.ENGLISH)}
                withTimeout(15_000){started.await()}
                assertTrue(c.state.value.busy)
                release.complete(Unit);settled(c)
                assertEquals(setOf("Base","Accepted"),c.state.value.projects.map {it.project.name}.toSet())
            }finally{release.complete(Unit)}
        }
    }

    @Test fun failedSaveReleasesAdmissionAndANewExplicitActionCanSucceed()=runBlocking {
        controller().use {c->
            c.createProject("Base",null,QuestionnaireLanguage.ENGLISH,NumberingStrategy.GENERATED,OutputHeaderLanguage.ENGLISH)
            c.refresh()
            c.perform {error("SIMULATED_SAVE_FAILURE")};settled(c)
            assertEquals("SIMULATED_SAVE_FAILURE",c.state.value.error)
            c.perform {createProject("After retry",null,QuestionnaireLanguage.ENGLISH,NumberingStrategy.GENERATED,OutputHeaderLanguage.ENGLISH)}
            settled(c)
            assertNull(c.state.value.error)
            assertTrue(c.state.value.projects.any {it.project.name=="After retry"})
        }
    }
}
