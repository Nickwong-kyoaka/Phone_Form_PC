package com.phoneform.ai.core.model

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.*
import org.junit.Test

class ProjectOperationGateTest {
    @Test fun deletionWaitsForOwnLeaseAndRejectsLateWorkWithoutBlockingOtherProjects() = runTest {
        val gate = ProjectOperationGate()
        val release = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val running = launch { gate.withLease("a") { entered.complete(Unit); release.await() } }
        entered.await(); gate.blockProject("a")
        val idle = async { gate.awaitProjectIdle("a") }
        runCurrent(); assertFalse(idle.isCompleted)
        assertEquals(7, gate.withLease("b") { 7 })
        try { gate.withLease("a") { fail("Late work was admitted") }; fail() } catch (_: ProjectDeletedException) { }
        release.complete(Unit); running.join(); idle.await()
    }
    @Test fun fileCleanupWaitsForReadersAndAlwaysReleasesExclusiveBarrier() = runTest {
        val gate = ProjectOperationGate()
        val lease = gate.openFileLease("image")
        var cleaned = false
        val cleanup = async { gate.withExclusiveFiles { cleaned = true } }
        runCurrent(); assertFalse(cleaned)
        try { gate.openFileLease("other"); fail() } catch (_: ProjectStorageBusyException) { }
        lease.close(); lease.close(); cleanup.await(); assertTrue(cleaned)
        try { gate.withExclusiveFiles<Unit> { error("failure") } } catch (_: IllegalStateException) { }
        gate.openFileLease("image").close()
    }
    @Test fun ownerContextSurvivesDispatcherChangeAndIsRestoredAfterNestedWork() = runTest {
        val gate = ProjectOperationGate()
        assertNull(gate.currentProjectId())
        gate.withLease("a") {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { assertEquals("a",gate.currentProjectId()) }
            gate.withLease("b") { assertEquals("b",gate.currentProjectId()) }
            assertEquals("a",gate.currentProjectId())
        }
        assertNull(gate.currentProjectId())
        gate.withExclusiveFiles { gate.openFileLease("ownership manifest").use { assertNotNull(it) } }
        gate.openFileLease("normal read").close()
    }
    @Test fun cancelledOperationReleasesLease() = runTest {
        val gate = ProjectOperationGate()
        val entered = CompletableDeferred<Unit>()
        val active = launch { gate.withLease("a") { entered.complete(Unit); CompletableDeferred<Unit>().await() } }
        entered.await(); gate.blockProject("a"); active.cancel(); active.join(); gate.awaitProjectIdle("a")
    }
}
