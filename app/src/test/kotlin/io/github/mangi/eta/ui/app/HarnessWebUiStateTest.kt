package io.github.mangi.eta.ui.app

import io.github.mangi.eta.R
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HarnessWebUiStateTest {
    @Test
    fun preparationAndRunningBothOfferStopButIdleAndFailuresDoNot() {
        assertTrue(HarnessWebUiState(phase = HarnessWebPhase.STARTING).canStop)
        assertTrue(HarnessWebUiState(phase = HarnessWebPhase.RUNNING).canStop)
        assertFalse(HarnessWebUiState(phase = HarnessWebPhase.NOT_INSTALLED).canStop)
        assertFalse(HarnessWebUiState(phase = HarnessWebPhase.FAILED).canStop)
    }

    @Test
    fun permissionAndProcessFailuresHaveTheirOwnRecoveryMessage() {
        val context = RuntimeEnvironment.getApplication()
        val harness = LinuxAgentHarness.DEEPSEEK_HARNESS
        val name = context.getString(harness.titleRes)
        assertEquals(
            context.getString(R.string.capability_harness_root_required, name),
            HarnessWebLaunchResult.Failed("ROOT_REQUIRED").message(context, harness),
        )
        assertEquals(
            context.getString(R.string.capability_background_failed),
            HarnessWebLaunchResult.Failed("BACKGROUND_START_NOT_ALLOWED").message(context, harness),
        )
        assertEquals(
            context.getString(R.string.capability_harness_exited, name),
            HarnessWebLaunchResult.Failed(HarnessWebSession.HARNESS_EXITED).message(context, harness),
        )
    }

    @Test
    fun menuLabelNamesTheSelectedHarness() {
        val context = RuntimeEnvironment.getApplication()
        val kimi = LinuxAgentHarness.KIMI_CODE
        assertEquals(
            context.getString(R.string.action_launch_harness_web, context.getString(kimi.titleRes)),
            HarnessWebUiState(harness = kimi, phase = HarnessWebPhase.READY).actionLabel(context),
        )
    }

    /** 默认 harness 是 DeepSeek Harness，Kimi Code 仍然可选。 */
    @Test
    fun deepSeekHarnessIsTheDefaultAndEveryHarnessIsReachableById() {
        assertEquals(LinuxAgentHarness.DEEPSEEK_HARNESS, LinuxAgentHarness.DEFAULT)
        LinuxAgentHarness.entries.forEach { harness ->
            assertEquals(harness, LinuxAgentHarness.fromId(harness.id))
        }
        assertNull(LinuxAgentHarness.fromId("nope"))
    }

    @Test
    fun harnessProfilesAndCommandsAreDistinct() {
        assertEquals(LinuxAgentHarness.entries.size, LinuxAgentHarness.entries.map { it.id }.toSet().size)
        assertEquals(LinuxAgentHarness.entries.size, LinuxAgentHarness.entries.map { it.webCommand }.toSet().size)
        assertEquals(LinuxAgentHarness.entries.size, LinuxAgentHarness.entries.map { it.profile.id }.toSet().size)
        LinuxAgentHarness.entries.forEach { harness ->
            assertTrue(harness.matchesCommand(harness.webCommand))
            harness.legacyCommands.forEach { legacy -> assertTrue(harness.matchesCommand(legacy)) }
        }
    }

    /**
     * 两种 harness 的地址分隔符都出现过（`/?token=` 与 `/#token=`），
     * 正则必须两种都认，否则换成 DeepSeek Harness 后启动会一直等不到地址。
     */
    @Test
    fun bothTokenSeparatorsAreParsedAndForeignHostsAreIgnored() {
        val query = "dsh web: http://127.0.0.1:53667/?token=ATZY_lku_Z1mnucQyUxUIvqWWHtIOQUO2TOPocWQhwk"
        val fragment = "kimi: http://127.0.0.1:5494/#token=abc_123"
        LinuxAgentHarness.entries.forEach { harness ->
            assertEquals("http://127.0.0.1:53667/?token=ATZY_lku_Z1mnucQyUxUIvqWWHtIOQUO2TOPocWQhwk", harness.webAddress(query))
            assertEquals("http://127.0.0.1:5494/#token=abc_123", harness.webAddress(fragment))
            assertNull(harness.webAddress("http://192.168.1.5:3000/?token=abc"))
            assertNull(harness.webAddress("no address yet"))
        }
    }

    /** 预注入只写凭据文档、且只在文档不存在时写；环境变量注入会让 harness 设置页改不动密钥。 */
    @Test
    fun credentialDocumentIsWrittenOnceAndNeverRewritten() {
        val rootfs = Files.createTempDirectory("eta-harness-credential").toFile()
        File(rootfs, "root/.dsh").mkdirs()
        assertTrue(HarnessCredentialStore.ensureInjected(rootfs, "DEEPSEEK_API_KEY", "  sk-first  "))
        val document = HarnessCredentialStore.documentFile(rootfs)
        assertEquals("version: 1\nrefs:\n  DEEPSEEK_API_KEY: \"sk-first\"\n", document.readText())

        assertFalse(HarnessCredentialStore.ensureInjected(rootfs, "DEEPSEEK_API_KEY", "sk-second"))
        assertEquals("version: 1\nrefs:\n  DEEPSEEK_API_KEY: \"sk-first\"\n", document.readText())
    }

    @Test
    fun blankOrMultiLineKeysAreNotWritten() {
        val rootfs = Files.createTempDirectory("eta-harness-credential-blank").toFile()
        File(rootfs, "root").mkdirs()
        assertFalse(HarnessCredentialStore.ensureInjected(rootfs, "DEEPSEEK_API_KEY", "   "))
        assertFalse(HarnessCredentialStore.ensureInjected(rootfs, "DEEPSEEK_API_KEY", null))
        val document = HarnessCredentialStore.documentFile(rootfs)
        assertFalse(document.exists())

        assertFalse(HarnessCredentialStore.ensureInjected(rootfs, "DEEPSEEK_API_KEY", "sk-a\nsk-b"))
        assertFalse(document.exists())
    }
}
