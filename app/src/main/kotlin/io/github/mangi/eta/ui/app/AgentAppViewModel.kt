package io.github.mangi.eta.ui.app

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.mangi.eta.agent.terminal.DetachedTaskSupervisor
import io.github.mangi.eta.agent.terminal.LinuxEnvironmentPaths
import io.github.mangi.eta.agent.terminal.SharedFolderMounts
import io.github.mangi.eta.agent.terminal.linuxPackageProfileReady
import io.github.mangi.eta.agent.terminal.terminalEnvironment
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.data.repository.LinuxEnvironmentSettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Activity 级状态所有者；配置变更只重建 UI，不替换正在运行的 Agent 会话。 */
internal class AgentAppViewModel(application: Application) : AndroidViewModel(application) {
    val state = AgentAppState(
        context = application,
        scope = viewModelScope,
    )
    val skills = AgentSkillsStore(application, viewModelScope)
    val memory = AgentMemoryStore(application, viewModelScope)
    val permissionHealth = PermissionHealthStore(application, viewModelScope)
    private val terminalHost = TerminalSessionHost.get(application)
    val terminalStore = terminalHost.terminal
    val consoleStore = terminalHost.console
    val harnessWebLauncher = HarnessWebLauncher(
        context = application,
        daemonSupervisor = DetachedTaskSupervisor(
            logger = AndroidAgentLogger,
            recordsFile = DetachedTaskSupervisor.defaultRecordsFile(application),
            linuxRootfsPathProvider = { environment ->
                environment.linuxDistribution?.let { distribution ->
                    LinuxEnvironmentPaths.rootfsDir(application, distribution).absolutePath
                }
            },
            linuxSharedMountsProvider = { SharedFolderMounts.current() },
        ),
    )

    /**
     * 菜单里“启动 Web UI”指向的 harness。默认是 DeepSeek Harness；
     * 用户从 Linux 环境页启动过哪个 harness，就把它记成下次的默认，并且跨启动保留。
     */
    var selectedHarness by mutableStateOf(
        LinuxAgentHarness.fromId(LinuxEnvironmentSettingsRepository.currentHarnessId())
            ?: LinuxAgentHarness.DEFAULT,
    )
        private set

    var harnessWebState by mutableStateOf(HarnessWebUiState(harness = selectedHarness))
        private set
    private var harnessWebJob: Job? = null

    init {
        // Linux 环境页可以启动任意一个 harness，选中的那个要能立刻反映到菜单项上。
        viewModelScope.launch {
            LinuxEnvironmentSettingsRepository.harnessIdFlow().collect { id ->
                val harness = LinuxAgentHarness.fromId(id) ?: LinuxAgentHarness.DEFAULT
                if (selectedHarness == harness) return@collect
                selectedHarness = harness
                harnessWebState = HarnessWebUiState(harness = harness)
                refreshHarnessWeb()
            }
        }
    }

    fun selectHarness(harness: LinuxAgentHarness) {
        if (selectedHarness == harness) return
        selectedHarness = harness
        harnessWebState = HarnessWebUiState(harness = harness)
        viewModelScope.launch { LinuxEnvironmentSettingsRepository.selectHarness(harness.id) }
        refreshHarnessWeb()
    }

    fun refreshHarnessWeb() {
        if (harnessWebJob?.isActive == true) return
        viewModelScope.launch {
            val harness = selectedHarness
            val status = withContext(Dispatchers.IO) {
                val distribution = LinuxEnvironmentSettingsRepository.current(getApplication())
                val rootfs = LinuxEnvironmentPaths.rootfsDir(getApplication(), distribution)
                if (!linuxPackageProfileReady(rootfs, harness.profile)) {
                    HarnessWebUiState(harness, HarnessWebPhase.NOT_INSTALLED)
                } else {
                    val runtime = harnessWebLauncher.status(distribution.terminalEnvironment, harness)
                    when {
                        runtime.running -> HarnessWebUiState(harness, HarnessWebPhase.RUNNING)
                        runtime.code != null -> HarnessWebUiState(harness, HarnessWebPhase.FAILED, runtime.code)
                        harnessWebState.harness == harness && harnessWebState.phase == HarnessWebPhase.FAILED ->
                            harnessWebState
                        else -> HarnessWebUiState(harness, HarnessWebPhase.READY)
                    }
                }
            }
            if (harnessWebJob?.isActive != true) harnessWebState = status
        }
    }

    fun launchHarnessWeb(onFinished: (HarnessWebLaunchResult) -> Unit) {
        if (harnessWebJob?.isActive == true) return
        harnessWebState = HarnessWebUiState(harness = selectedHarness, phase = HarnessWebPhase.STARTING)
        val harness = selectedHarness
        harnessWebJob = viewModelScope.launch {
            val distribution = LinuxEnvironmentSettingsRepository.current(getApplication())
            val result = try {
                harnessWebLauncher.launch(distribution.terminalEnvironment, harness)
            } finally {
                if (harnessWebState.phase == HarnessWebPhase.STARTING) {
                    harnessWebState = HarnessWebUiState(harness, HarnessWebPhase.READY)
                }
            }
            harnessWebState = when (result) {
                is HarnessWebLaunchResult.Opened -> HarnessWebUiState(harness, HarnessWebPhase.RUNNING)
                is HarnessWebLaunchResult.Failed -> HarnessWebUiState(harness, HarnessWebPhase.FAILED, result.code)
            }
            onFinished(result)
        }
    }

    fun stopHarnessWeb() {
        val preparation = harnessWebJob
        preparation?.cancel()
        val harness = selectedHarness
        harnessWebJob = viewModelScope.launch {
            preparation?.join()
            val distribution = LinuxEnvironmentSettingsRepository.current(getApplication())
            val status = harnessWebLauncher.status(distribution.terminalEnvironment, harness)
            val stopped = if (status.taskId == null) status.code == null
                else harnessWebLauncher.stop(distribution.terminalEnvironment, harness)
            harnessWebState = if (stopped) HarnessWebUiState(harness, HarnessWebPhase.READY)
                else HarnessWebUiState(harness, HarnessWebPhase.FAILED, status.code ?: "STOP_FAILED")
        }
    }
}
