package io.github.mangi.eta.ui.app

import java.io.File

/**
 * 把 Eta 里已经配置好的密钥预注入给 harness，做到装完即可直接对话，同时保留被覆盖的余地。
 *
 * 刻意不用环境变量注入：dsh 的凭据解析里进程环境优先级最高且是只读层，被遮蔽的写入会被直接拒绝，
 * 用环境变量预注入会让它的设置页再也改不动密钥，正好破坏“预注入 + 可覆盖”的目标。
 * 写成凭据文档则相反——用户之后在 harness 设置页里保存的值落在同一份文档上，天然覆盖预注入值。
 *
 * 注入只发生在文档不存在时：一旦文档存在，它就归 harness 与用户所有，Eta 不再改写，
 * 以免把用户已经轮换过的密钥又覆盖回旧值。
 */
internal object HarnessCredentialStore {
    private const val DOCUMENT_VERSION = 1
    private const val DOCUMENT_PATH = "root/.dsh/.credentials.yaml"

    /** 凭据文档在 rootfs 内的宿主路径；harness 在容器里按同一位置读取。 */
    fun documentFile(rootfs: File): File = File(rootfs, DOCUMENT_PATH)

    /**
     * 文档不存在时按最小文档写入该引用。返回是否真的写入；
     * false 表示没有可用密钥或文档已存在（保持用户已有凭据）。
     */
    fun ensureInjected(rootfs: File, ref: String, apiKey: String?): Boolean {
        val key = apiKey?.trim().orEmpty()
        if (key.isEmpty() || key.any { it == '\n' || it == '\r' }) return false
        val document = documentFile(rootfs)
        if (document.exists()) return false
        if (!rootfs.isDirectory) return false
        document.parentFile?.mkdirs()
        val text = "version: $DOCUMENT_VERSION\nrefs:\n  $ref: ${quoted(key)}\n"
        return runCatching { document.writeText(text); true }.getOrDefault(false)
    }

    /** YAML 支持 JSON 标量，用双引号形式避免密钥里的 `:`、`#` 等字符改变文档结构。 */
    private fun quoted(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
