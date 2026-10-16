package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.terminal.DaemonLogsResult
import io.github.mangi.eta.agent.terminal.DaemonStartResult
import io.github.mangi.eta.agent.terminal.DetachedTaskStatus
import io.github.mangi.eta.agent.terminal.LinuxExecutionBackend
import io.github.mangi.eta.agent.terminal.TerminalEnvironment
import kotlinx.coroutines.delay

/**
 * Harness 启动和复用的事务边界：只有本次创建且未打开浏览器的任务会被回收，
 * 复用的既有任务在失败时保持原样，避免把用户已经跑起来的实例关掉。
 */
internal class HarnessWebSession(
    private val harness: LinuxAgentHarness,
    private val tasks: Tasks,
    private val openUrl: (String) -> Boolean,
    private val waitAttempts: Int = 30,
    private val waitIntervalMs: Long = 500,
) {
    interface Tasks {
        fun list(): List<DetachedTaskStatus>
        fun start(environment: TerminalEnvironment, identity: String): DaemonStartResult
        fun logs(id: String): DaemonLogsResult
        fun stop(id: String)
    }

    suspend fun launch(
        environment: TerminalEnvironment,
        identity: String,
        backend: LinuxExecutionBackend,
    ): HarnessWebLaunchResult {
        var createdTaskId: String? = null
        var opened = false
        try {
            val existing = tasks.list().firstOrNull {
                it.running && it.task.environment == environment && it.task.identity == identity &&
                    it.task.backend == backend && harness.matchesCommand(it.task.command)
            }
            val taskId = existing?.task?.id ?: when (val started = tasks.start(environment, identity)) {
                is DaemonStartResult.Started -> started.task.id.also { createdTaskId = it }
                is DaemonStartResult.Failed -> return HarnessWebLaunchResult.Failed(started.code)
            }
            repeat(waitAttempts) {
                val status = tasks.list().firstOrNull { it.task.id == taskId }
                if (status == null || !status.running) return HarnessWebLaunchResult.Failed(HARNESS_EXITED)
                val logs = tasks.logs(taskId)
                if (!logs.ok) return HarnessWebLaunchResult.Failed(logs.code.ifBlank { "LOGS_UNAVAILABLE" })
                val url = harness.webAddress(logs.text)
                if (url != null) {
                    opened = openUrl(url)
                    return if (opened) HarnessWebLaunchResult.Opened(url) else HarnessWebLaunchResult.Failed("BROWSER_UNAVAILABLE")
                }
                delay(waitIntervalMs)
            }
            return HarnessWebLaunchResult.Failed("URL_TIMEOUT")
        } finally {
            if (!opened) createdTaskId?.let(tasks::stop)
        }
    }

    companion object {
        /** 任务在解析出地址前就退出；历史记录里的 PROCESS_EXITED 仍按同一含义映射。 */
        const val HARNESS_EXITED = "HARNESS_EXITED"
    }
}
