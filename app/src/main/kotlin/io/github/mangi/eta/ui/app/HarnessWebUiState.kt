package io.github.mangi.eta.ui.app

import android.content.Context
import io.github.mangi.eta.R

internal enum class HarnessWebPhase { CHECKING, NOT_INSTALLED, READY, STARTING, RUNNING, FAILED }

internal data class HarnessWebUiState(
    val harness: LinuxAgentHarness = LinuxAgentHarness.DEFAULT,
    val phase: HarnessWebPhase = HarnessWebPhase.CHECKING,
    val errorCode: String? = null,
) {
    val canStop: Boolean get() = phase == HarnessWebPhase.STARTING || phase == HarnessWebPhase.RUNNING

    fun actionLabel(context: Context): String {
        val name = context.getString(harness.titleRes)
        return context.getString(
            when (phase) {
                HarnessWebPhase.CHECKING -> R.string.capability_harness_checking
                HarnessWebPhase.NOT_INSTALLED -> R.string.capability_harness_install
                HarnessWebPhase.READY -> R.string.action_launch_harness_web
                HarnessWebPhase.STARTING -> R.string.capability_harness_preparing
                HarnessWebPhase.RUNNING -> R.string.capability_harness_open
                HarnessWebPhase.FAILED -> R.string.capability_harness_retry
            },
            name,
        )
    }
}

internal fun HarnessWebLaunchResult.Failed.message(context: Context, harness: LinuxAgentHarness): String {
    val name = context.getString(harness.titleRes)
    return context.getString(
        when (code) {
            "ROOT_REQUIRED" -> R.string.capability_harness_root_required
            "BACKGROUND_START_NOT_ALLOWED" -> R.string.capability_background_failed
            HarnessWebSession.HARNESS_EXITED, "KIMI_EXITED", "PROCESS_EXITED" -> R.string.capability_harness_exited
            "LINUX_ENVIRONMENT_NOT_READY", "PROFILE_NOT_INSTALLED" -> R.string.capability_harness_not_installed
            "PROOT_UNAVAILABLE" -> R.string.capability_harness_proot_unavailable
            "LOGS_UNAVAILABLE" -> R.string.capability_harness_logs_unavailable
            "URL_TIMEOUT" -> R.string.linux_harness_web_failed_url
            "BROWSER_UNAVAILABLE" -> R.string.linux_harness_web_failed_browser
            else -> R.string.linux_harness_web_failed_start
        },
        name,
    )
}
