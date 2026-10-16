package io.github.mangi.eta.ui.app

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import io.github.mangi.eta.agent.runtime.AgentExecutionService
import io.github.mangi.eta.agent.terminal.DetachedTaskSupervisor
import io.github.mangi.eta.agent.terminal.LinuxEnvironmentPaths
import io.github.mangi.eta.agent.terminal.TerminalEnvironment
import io.github.mangi.eta.agent.terminal.TerminalRuntime
import io.github.mangi.eta.data.repository.ProviderRepository
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class HarnessWebRuntimeStatus(
    val taskId: String? = null,
    val running: Boolean = false,
    val url: String? = null,
    val code: String? = null,
)

internal sealed interface HarnessWebLaunchResult {
    data class Opened(val url: String) : HarnessWebLaunchResult
    data class Failed(val code: String) : HarnessWebLaunchResult
}

/**
 * 取得启动期前台引用，解析带 token 的本机地址后交给系统浏览器。
 * 同一时刻只允许一个启动事务，避免两个 harness 同时抢前台引用。
 */
internal class HarnessWebLauncher(
    context: Context,
    private val daemonSupervisor: DetachedTaskSupervisor,
) {
    private val appContext = context.applicationContext

    private fun session(harness: LinuxAgentHarness) = HarnessWebSession(
        harness = harness,
        tasks = object : HarnessWebSession.Tasks {
            override fun list() = daemonSupervisor.list()
            override fun start(environment: TerminalEnvironment, identity: String) = daemonSupervisor.start(
                command = harness.webCommand,
                cwd = "/workspace",
                identity = identity,
                environment = environment,
            )

            override fun logs(id: String) = daemonSupervisor.readLogs(id)

            override fun stop(id: String) {
                daemonSupervisor.stop(id)
            }
        },
        openUrl = { url ->
            try {
                appContext.startActivity(
                    Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                true
            } catch (_: android.content.ActivityNotFoundException) {
                false
            } catch (_: SecurityException) {
                false
            }
        },
    )

    suspend fun launch(
        environment: TerminalEnvironment,
        harness: LinuxAgentHarness,
    ): HarnessWebLaunchResult = launchMutex.withLock {
        withContext(Dispatchers.IO) {
            val distribution = environment.linuxDistribution
                ?: return@withContext HarnessWebLaunchResult.Failed("INVALID_ENVIRONMENT")
            val rootfs = LinuxEnvironmentPaths.rootfsDir(appContext, distribution)
            if (!LinuxEnvironmentPaths.rootfsReady(rootfs.absolutePath)) {
                return@withContext HarnessWebLaunchResult.Failed("LINUX_ENVIRONMENT_NOT_READY")
            }
            val identity = TerminalRuntime.defaultIdentity(environment, rootfs.absolutePath)
            val job = currentCoroutineContext().job
            if (identity == "user" && !AgentExecutionService.acquire(appContext, LAUNCH_ID) { job.cancel() }) {
                return@withContext HarnessWebLaunchResult.Failed("BACKGROUND_START_NOT_ALLOWED")
            }
            try {
                currentCoroutineContext().ensureActive()
                injectCredential(rootfs, harness)
                session(harness).launch(
                    environment = environment,
                    identity = identity,
                    backend = LinuxEnvironmentPaths.backendOf(rootfs.absolutePath),
                )
            } finally {
                if (identity == "user") AgentExecutionService.release(LAUNCH_ID)
            }
        }
    }

    suspend fun status(
        environment: TerminalEnvironment,
        harness: LinuxAgentHarness,
    ): HarnessWebRuntimeStatus = withContext(Dispatchers.IO) {
        val distribution = environment.linuxDistribution
            ?: return@withContext HarnessWebRuntimeStatus(code = "INVALID_ENVIRONMENT")
        val rootfs = LinuxEnvironmentPaths.rootfsDir(appContext, distribution)
        if (!LinuxEnvironmentPaths.rootfsReady(rootfs.path)) {
            return@withContext HarnessWebRuntimeStatus(code = "LINUX_ENVIRONMENT_NOT_READY")
        }
        val identity = TerminalRuntime.defaultIdentity(environment, rootfs.path)
        if (identity == "root" && !TerminalRuntime.rootAvailable) {
            return@withContext HarnessWebRuntimeStatus(code = "ROOT_REQUIRED")
        }
        val matches = daemonSupervisor.list().filter {
            it.task.environment == environment && it.task.identity == identity &&
                it.task.backend == LinuxEnvironmentPaths.backendOf(rootfs.path) &&
                harness.matchesCommand(it.task.command)
        }
        val task = matches.lastOrNull { it.running } ?: matches.lastOrNull()
            ?: return@withContext HarnessWebRuntimeStatus()
        if (!task.running) {
            return@withContext HarnessWebRuntimeStatus(taskId = task.task.id, code = HarnessWebSession.HARNESS_EXITED)
        }
        val logs = daemonSupervisor.readLogs(task.task.id)
        HarnessWebRuntimeStatus(
            taskId = task.task.id,
            running = true,
            url = if (logs.ok) harness.webAddress(logs.text) else null,
            code = if (logs.ok) null else logs.code,
        )
    }

    suspend fun stop(
        environment: TerminalEnvironment,
        harness: LinuxAgentHarness,
    ): Boolean = launchMutex.withLock {
        withContext(Dispatchers.IO) {
            val status = status(environment, harness)
            status.taskId?.let(daemonSupervisor::stop) ?: false
        }
    }

    /**
     * 预注入凭据：文档已存在或 Eta 里没配过该 provider 时什么都不做。
     * 这里失败只意味着首次启动后要在 harness 里手填密钥，不能因此挡住启动。
     */
    private suspend fun injectCredential(rootfs: File, harness: LinuxAgentHarness) {
        val providerId = harness.credentialProviderId ?: return
        val ref = harness.credentialRef ?: return
        val apiKey = runCatching { ProviderRepository.providerById(providerId)?.apiKey }.getOrNull()
        runCatching { HarnessCredentialStore.ensureInjected(rootfs, ref, apiKey) }
    }

    private companion object {
        val launchMutex = Mutex()
        const val LAUNCH_ID = "harness-launch"
    }
}
