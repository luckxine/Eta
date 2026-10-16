package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.terminal.DaemonLogsResult
import io.github.mangi.eta.agent.terminal.DaemonStartResult
import io.github.mangi.eta.agent.terminal.DetachedTask
import io.github.mangi.eta.agent.terminal.DetachedTaskStatus
import io.github.mangi.eta.agent.terminal.LinuxExecutionBackend
import io.github.mangi.eta.agent.terminal.TerminalEnvironment
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HarnessWebLauncherTest {
    @Test
    fun reusesMatchingDaemonWithoutStartingAnother() = runBlocking {
        val tasks = FakeTasks(existing = true)
        val result = session(tasks).launch(ENV, "user", BACKEND)
        assertTrue(result is HarnessWebLaunchResult.Opened)
        assertEquals(0, tasks.starts)
        assertTrue(tasks.stops.isEmpty())
    }

    @Test
    fun browserFailureDoesNotStopReusedDaemon() = runBlocking {
        val tasks = FakeTasks(existing = true)
        val result = session(tasks, open = false).launch(ENV, "user", BACKEND)
        assertEquals(HarnessWebLaunchResult.Failed("BROWSER_UNAVAILABLE"), result)
        assertTrue(tasks.stops.isEmpty())
    }

    @Test
    fun exitedNewDaemonFailsImmediatelyAndIsCleanedUp() = runBlocking {
        val tasks = FakeTasks(existing = false, running = false)
        val result = session(tasks).launch(ENV, "user", BACKEND)
        assertEquals(HarnessWebLaunchResult.Failed(HarnessWebSession.HARNESS_EXITED), result)
        assertEquals(listOf("new"), tasks.stops)
    }

    @Test
    fun timeoutCleansOnlyNewDaemon() = runBlocking {
        val tasks = FakeTasks(existing = false, output = "still loading")
        val result = session(tasks).launch(ENV, "user", BACKEND)
        assertEquals(HarnessWebLaunchResult.Failed("URL_TIMEOUT"), result)
        assertEquals(listOf("new"), tasks.stops)
    }

    /** 另一个 harness 已在跑的任务不能被当成自己复用，否则会打开错的 Web UI。 */
    @Test
    fun otherHarnessDaemonIsNotReused() = runBlocking {
        val tasks = FakeTasks(
            existing = true,
            harness = LinuxAgentHarness.KIMI_CODE,
            output = "http://127.0.0.1:5494/#token=abc_123",
        )
        val result = session(tasks).launch(ENV, "user", BACKEND)
        assertTrue(result is HarnessWebLaunchResult.Opened)
        assertEquals(1, tasks.starts)
        // 新起的那个任务已经打开了，不应该被回收。
        assertTrue(tasks.stops.isEmpty())
    }

    @Test
    fun cancellationCleansNewDaemonAndPreservesReusedDaemon() = runBlocking {
        for (existing in listOf(false, true)) {
            val tasks = FakeTasks(existing = existing, output = "loading")
            val read = CompletableDeferred<Unit>()
            tasks.onLogs = { read.complete(Unit) }
            val job = launch {
                session(tasks, waitIntervalMs = 60_000).launch(ENV, "user", BACKEND)
            }
            read.await()
            job.cancelAndJoin()
            assertEquals(if (existing) emptyList<String>() else listOf("new"), tasks.stops)
        }
    }

    private fun session(
        tasks: FakeTasks,
        open: Boolean = true,
        harness: LinuxAgentHarness = LinuxAgentHarness.DEEPSEEK_HARNESS,
        waitIntervalMs: Long = 0,
    ) = HarnessWebSession(harness, tasks, { open }, waitAttempts = 1, waitIntervalMs = waitIntervalMs)

    private class FakeTasks(
        existing: Boolean,
        private val running: Boolean = true,
        private val output: String = "http://127.0.0.1:5494/?token=abc_123",
        private val harness: LinuxAgentHarness = LinuxAgentHarness.DEEPSEEK_HARNESS,
    ) : HarnessWebSession.Tasks {
        private var task: DetachedTask? = if (existing) task("old") else null
        var starts = 0
        val stops = mutableListOf<String>()
        var onLogs: () -> Unit = {}
        override fun list() = task?.let { listOf(DetachedTaskStatus(it, running)) }.orEmpty()
        override fun start(environment: TerminalEnvironment, identity: String): DaemonStartResult {
            starts++
            return DaemonStartResult.Started(task("new").also { task = it })
        }
        override fun logs(id: String): DaemonLogsResult {
            onLogs()
            return DaemonLogsResult(ok = true, text = output)
        }
        override fun stop(id: String) {
            stops += id
        }

        private fun task(id: String) = DetachedTask(
            id, 10, "owner", harness.webCommand, "/workspace", "user", ENV, "/log", 0, BACKEND,
        )
    }

    companion object {
        private val ENV = TerminalEnvironment.DEBIAN
        private val BACKEND = LinuxExecutionBackend.PROOT
    }
}
