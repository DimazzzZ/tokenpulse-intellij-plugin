package org.zhavoronkov.tokenpulse.provider.openai.chatgpt.oauth

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CodexConfigLocatorTest {

    @Test
    fun `defaults to userHome dot codex`() {
        val home = CodexConfigLocator.codexHome(env = emptyMap(), userHome = "/home/dev")
        assertEquals("/home/dev/.codex", home.path)
    }

    @Test
    fun `honors CODEX_HOME env when set`() {
        val home = CodexConfigLocator.codexHome(env = mapOf("CODEX_HOME" to "/tmp/cx"), userHome = "/home/dev")
        assertEquals("/tmp/cx", home.path)
    }

    @Test
    fun `blank CODEX_HOME falls back to default`() {
        val home = CodexConfigLocator.codexHome(env = mapOf("CODEX_HOME" to "   "), userHome = "/home/dev")
        assertEquals("/home/dev/.codex", home.path)
    }

    @Test
    fun `authFile joins codexHome with auth json`() {
        val f = CodexConfigLocator.authFile(env = emptyMap(), userHome = "/home/dev")
        assertEquals("/home/dev/.codex/auth.json", f.path)
    }
}
