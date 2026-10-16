package io.github.mangi.eta.ui.app

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.terminal.LinuxPackageProfile
import io.github.mangi.eta.agent.terminal.LinuxPackageProfiles
import io.github.mangi.eta.data.provider.BuiltinProviders

/**
 * Linux 环境里可用的 Coding Agent Harness。
 *
 * 各 harness 的差异只有四处：安装 profile、守护任务命令行、启动日志里的 Web UI 地址格式、
 * 预注入凭据用的 provider 引用。安装、就绪判定、启动、复用已有任务、停止的流程完全共用，
 * 因此新增一个 harness 只要在这里加一项，再补上对应的 profile 与文案资源即可。
 *
 * 默认 harness 是 DeepSeek Harness（[DEFAULT]）。Kimi Code 作为可选项保留，两个 harness
 * 都能独立安装和启动，互不影响。
 */
internal enum class LinuxAgentHarness(
    val id: String,
    val profile: LinuxPackageProfile,
    /** 守护任务命令行；不含任何凭据，密钥由 rootfs 内的凭据文件或 harness 自身登录流程提供。 */
    val webCommand: String,
    /** 升级前已经在跑的旧命令行，仍按同一 harness 识别，避免重复起一个实例。 */
    val legacyCommands: Set<String>,
    /**
     * 预注入凭据取自哪个 Eta provider；null 表示该 harness 自己管理登录，不做预注入。
     */
    val credentialProviderId: String?,
    /** 凭据文档里承载密钥的引用名，harness 侧按这个名字解析。 */
    val credentialRef: String?,
    /** 菜单里代表当前 harness 的图标。 */
    @param:DrawableRes val iconRes: Int,
    @param:StringRes val titleRes: Int,
    @param:StringRes val summaryRes: Int,
    @param:StringRes val readyRes: Int,
) {
    /**
     * DeepSeek Harness（`@deepseek-ai/dsh`）。`--port 0` 让 dsh 自己挑空闲端口，
     * 避免与上一次没清干净的任务抢 3080；`NARB_DISABLE_NATIVE_CACHE=1` 与
     * `DSH_HOME` 都是这个 harness 在免 Root 容器里的既有约束：
     * 原生扩展默认的 link+unlink 缓存在 `--link2symlink` 下首次会悬空，
     * 而 DSH_HOME 需要固定为 rootfs 内的固定路径，预注入的凭据才落在 dsh 会读的位置。
     */
    DEEPSEEK_HARNESS(
        id = "deepseek-harness",
        profile = LinuxPackageProfiles.DEEPSEEK_HARNESS,
        webCommand = "NARB_DISABLE_NATIVE_CACHE=1 DSH_HOME=/root/.dsh dsh web --no-open --host 127.0.0.1 --port 0",
        legacyCommands = setOf("dsh web"),
        credentialProviderId = BuiltinProviders.DEEPSEEK_ID,
        credentialRef = "DEEPSEEK_API_KEY",
        iconRes = R.drawable.ic_deepseek_harness,
        titleRes = R.string.linux_deepseek_harness,
        summaryRes = R.string.linux_deepseek_harness_summary,
        readyRes = R.string.linux_deepseek_harness_ready,
    ),
    KIMI_CODE(
        id = "kimi-code",
        profile = LinuxPackageProfiles.KIMI,
        webCommand = "kimi web --no-open",
        legacyCommands = setOf("kimi web"),
        credentialProviderId = null,
        credentialRef = null,
        iconRes = R.drawable.ic_kimi_code,
        titleRes = R.string.linux_kimi_tools,
        summaryRes = R.string.linux_kimi_tools_summary,
        readyRes = R.string.linux_kimi_tools_ready,
    ),
    ;

    /** 命令是否由本 harness 启动；守护任务记录里的命令原文用于复用判断。 */
    fun matchesCommand(command: String): Boolean {
        val trimmed = command.trim()
        return trimmed == webCommand || trimmed in legacyCommands
    }

    /** 从启动日志里取出带 token 的本机 Web UI 地址。 */
    fun webAddress(logText: String): String? = WEB_URL_PATTERN.find(logText)?.value

    companion object {
        val DEFAULT = DEEPSEEK_HARNESS

        /**
         * token 字符集收紧到 URL safe，避免把日志里的 ANSI 序列尾巴吃进来；
         * 两个 harness 的分隔符都出现过（DeepSeek Harness 用 `/?token=`，Kimi Code 用 `/#token=`），
         * 所以这里两种都接受，harness 之间的差异不落在正则的严格程度上。
         */
        private val WEB_URL_PATTERN = Regex("""http://127\.0\.0\.1:\d+/[?#]token=[A-Za-z0-9_-]+""")

        fun fromId(id: String?): LinuxAgentHarness? = entries.firstOrNull { it.id == id }
    }
}
