package org.zhavoronkov.tokenpulse.provider.openai.chatgpt.oauth

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64

class IdTokenClaimsTest {

    private fun jwt(payload: String): String {
        val enc = Base64.getUrlEncoder().withoutPadding()
        val header = enc.encodeToString("""{"alg":"none"}""".toByteArray())
        val body = enc.encodeToString(payload.toByteArray())
        return "$header.$body.sig"
    }

    @Test
    fun `parse extracts email plan account and fedramp`() {
        val token = jwt(
            """{"email":"a@b.com","https://api.openai.com/auth":{"chatgpt_plan_type":"pro","chatgpt_account_id":"acc","chatgpt_account_is_fedramp":true}}"""
        )
        val c = IdTokenClaims.parse(token)
        assertEquals("a@b.com", c.email)
        assertEquals("pro", c.chatgptPlanType)
        assertEquals("acc", c.chatgptAccountId)
        assertTrue(c.isFedramp)
    }

    @Test
    fun `parse returns empty for malformed token`() {
        val c = IdTokenClaims.parse("not.a.jwt.at-all")
        assertNull(c.email)
        assertNull(c.chatgptPlanType)
        assertFalse(c.isFedramp)
    }

    @Test
    fun `parse tolerates missing auth claim block`() {
        val token = jwt("""{"email":"a@b.com"}""")
        val c = IdTokenClaims.parse(token)
        assertEquals("a@b.com", c.email)
        assertNull(c.chatgptAccountId)
    }

    @Test
    fun `parse returns empty for blank input`() {
        val c = IdTokenClaims.parse("")
        assertNull(c.email)
    }
}
