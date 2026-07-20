package org.zhavoronkov.tokenpulse.provider.xiaomi

import com.google.gson.Gson
import com.google.gson.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.zhavoronkov.tokenpulse.model.ConnectionType
import org.zhavoronkov.tokenpulse.model.ProviderResult
import org.zhavoronkov.tokenpulse.settings.Account

class XiaomiResponseParserTest {

    private val gson = Gson()

    private fun account() = Account(
        id = "test-account",
        connectionType = ConnectionType.XIAOMI_API,
        authType = ConnectionType.XIAOMI_API.defaultAuthType
    )

    private fun json(raw: String): JsonObject = gson.fromJson(raw, JsonObject::class.java)

    // ── parseApiBalance ──────────────────────────────────────────────

    @Nested
    inner class ParseApiBalance {

        @Test
        fun `parses full balance response`() {
            val body =
                """{"code":0,"data":{"balance":"55.48","giftBalance":"10.00","cashBalance":"45.48","currency":"USD"}}"""
            val result = XiaomiResponseParser.parseApiBalance(
                json(body),
                account()
            )

            assertTrue(result is ProviderResult.Success)
            val snapshot = (result as ProviderResult.Success).snapshot
            assertEquals("55.48", snapshot.balance.credits?.remaining?.toPlainString())
            assertEquals(ConnectionType.XIAOMI_API, snapshot.connectionType)
            assertEquals("USD", snapshot.metadata["currency"])
            assertEquals("10.00", snapshot.metadata["giftBalance"])
            assertEquals("45.48", snapshot.metadata["cashBalance"])
        }

        @Test
        fun `defaults missing fields to zero and USD`() {
            val result = XiaomiResponseParser.parseApiBalance(
                json("""{"code":0,"data":{}}"""),
                account()
            )

            assertTrue(result is ProviderResult.Success)
            val snapshot = (result as ProviderResult.Success).snapshot
            assertEquals("0", snapshot.balance.credits?.remaining?.toPlainString())
            assertEquals("USD", snapshot.metadata["currency"])
            assertEquals("0", snapshot.metadata["giftBalance"])
            assertEquals("0", snapshot.metadata["cashBalance"])
        }

        @Test
        fun `handles null data`() {
            val result = XiaomiResponseParser.parseApiBalance(
                json("""{"code":0,"data":null}"""),
                account()
            )

            assertTrue(result is ProviderResult.Success)
            val snapshot = (result as ProviderResult.Success).snapshot
            assertEquals("0", snapshot.balance.credits?.remaining?.toPlainString())
        }

        @Test
        fun `handles missing data field`() {
            val result = XiaomiResponseParser.parseApiBalance(
                json("""{"code":0}"""),
                account()
            )

            assertTrue(result is ProviderResult.Success)
            val snapshot = (result as ProviderResult.Success).snapshot
            assertEquals("0", snapshot.balance.credits?.remaining?.toPlainString())
        }

        @Test
        fun `returns AuthError for token expired message`() {
            val result = XiaomiResponseParser.parseApiBalance(
                json("""{"code":-1,"message":"Token expired"}"""),
                account()
            )

            assertTrue(result is ProviderResult.Failure.AuthError)
            assertEquals(
                "Xiaomi API error: Token expired",
                (result as ProviderResult.Failure.AuthError).message
            )
        }

        @Test
        fun `returns AuthError for session message`() {
            val result = XiaomiResponseParser.parseApiBalance(
                json("""{"code":-1,"message":"Session timeout"}"""),
                account()
            )

            assertTrue(result is ProviderResult.Failure.AuthError)
        }

        @Test
        fun `returns AuthError for unauthorized message`() {
            val result = XiaomiResponseParser.parseApiBalance(
                json("""{"code":-1,"message":"Unauthorized access"}"""),
                account()
            )

            assertTrue(result is ProviderResult.Failure.AuthError)
        }

        @Test
        fun `returns AuthError for login message`() {
            val result = XiaomiResponseParser.parseApiBalance(
                json("""{"code":-1,"message":"Please login again"}"""),
                account()
            )

            assertTrue(result is ProviderResult.Failure.AuthError)
        }

        @Test
        fun `returns RateLimited for rate message`() {
            val result = XiaomiResponseParser.parseApiBalance(
                json("""{"code":-1,"message":"Rate limit exceeded"}"""),
                account()
            )

            assertTrue(result is ProviderResult.Failure.RateLimited)
            assertEquals(
                "Xiaomi rate limit: Rate limit exceeded",
                (result as ProviderResult.Failure.RateLimited).message
            )
        }

        @Test
        fun `returns RateLimited for throttle message`() {
            val result = XiaomiResponseParser.parseApiBalance(
                json("""{"code":-1,"message":"Throttled"}"""),
                account()
            )

            assertTrue(result is ProviderResult.Failure.RateLimited)
        }

        @Test
        fun `returns UnknownError for unrecognized error`() {
            val result = XiaomiResponseParser.parseApiBalance(
                json("""{"code":-1,"message":"Something went wrong"}"""),
                account()
            )

            assertTrue(result is ProviderResult.Failure.UnknownError)
            assertEquals(
                "Xiaomi API error (code=-1): Something went wrong",
                (result as ProviderResult.Failure.UnknownError).message
            )
        }

        @Test
        fun `returns UnknownError with default message when message is missing`() {
            val result = XiaomiResponseParser.parseApiBalance(
                json("""{"code":-1}"""),
                account()
            )

            assertTrue(result is ProviderResult.Failure.UnknownError)
            assertEquals(
                "Xiaomi API error (code=-1): Unknown error",
                (result as ProviderResult.Failure.UnknownError).message
            )
        }
    }

    // ── parseTokenPlanUsage ──────────────────────────────────────────

    @Nested
    inner class ParseTokenPlanUsage {

        @Test
        fun `parses full token plan response`() {
            val body =
                """{"code":0,"data":{"monthUsage":{"percent":0.248,"items":[{"used":2727524596,"limit":11000000000}]}}}"""
            val result = XiaomiResponseParser.parseTokenPlanUsage(
                json(body),
                account()
            )

            assertTrue(result is ProviderResult.Success)
            val snapshot = (result as ProviderResult.Success).snapshot
            assertEquals(ConnectionType.XIAOMI_TOKEN_PLAN, snapshot.connectionType)
            assertEquals(2727524596L, snapshot.balance.tokens?.used)
            assertEquals(11000000000L, snapshot.balance.tokens?.total)
            assertEquals(8272475404L, snapshot.balance.tokens?.remaining)
            assertEquals("active", snapshot.metadata["planStatus"])
            // 0.248 * 100 = 24 (int truncation)
            assertEquals("24", snapshot.metadata["sessionUsed"])
        }

        @Test
        fun `handles null data`() {
            val result = XiaomiResponseParser.parseTokenPlanUsage(
                json("""{"code":0,"data":null}"""),
                account()
            )

            assertTrue(result is ProviderResult.Success)
            val snapshot = (result as ProviderResult.Success).snapshot
            assertEquals(0L, snapshot.balance.tokens?.used)
            assertEquals(0L, snapshot.balance.tokens?.total)
            assertEquals(0L, snapshot.balance.tokens?.remaining)
            assertEquals("100", snapshot.metadata["sessionUsed"])
            assertEquals("inactive", snapshot.metadata["planStatus"])
        }

        @Test
        fun `handles missing data field`() {
            val result = XiaomiResponseParser.parseTokenPlanUsage(
                json("""{"code":0}"""),
                account()
            )

            assertTrue(result is ProviderResult.Success)
            val snapshot = (result as ProviderResult.Success).snapshot
            assertEquals(0L, snapshot.balance.tokens?.total)
            assertEquals("inactive", snapshot.metadata["planStatus"])
        }

        @Test
        fun `handles null monthUsage`() {
            val result = XiaomiResponseParser.parseTokenPlanUsage(
                json("""{"code":0,"data":{"monthUsage":null}}"""),
                account()
            )

            assertTrue(result is ProviderResult.Success)
            val snapshot = (result as ProviderResult.Success).snapshot
            assertEquals(0L, snapshot.balance.tokens?.total)
            assertEquals("100", snapshot.metadata["sessionUsed"])
            assertEquals("inactive", snapshot.metadata["planStatus"])
        }

        @Test
        fun `handles missing monthUsage field`() {
            val result = XiaomiResponseParser.parseTokenPlanUsage(
                json("""{"code":0,"data":{}}"""),
                account()
            )

            assertTrue(result is ProviderResult.Success)
            val snapshot = (result as ProviderResult.Success).snapshot
            assertEquals(0L, snapshot.balance.tokens?.total)
            assertEquals("inactive", snapshot.metadata["planStatus"])
        }

        @Test
        fun `handles null items array`() {
            val result = XiaomiResponseParser.parseTokenPlanUsage(
                json("""{"code":0,"data":{"monthUsage":{"percent":0.5,"items":null}}}"""),
                account()
            )

            assertTrue(result is ProviderResult.Success)
            val snapshot = (result as ProviderResult.Success).snapshot
            assertEquals(0L, snapshot.balance.tokens?.used)
            assertEquals(0L, snapshot.balance.tokens?.total)
            assertEquals("100", snapshot.metadata["sessionUsed"])
            assertEquals("inactive", snapshot.metadata["planStatus"])
        }

        @Test
        fun `handles empty items array`() {
            val result = XiaomiResponseParser.parseTokenPlanUsage(
                json("""{"code":0,"data":{"monthUsage":{"percent":0.5,"items":[]}}}"""),
                account()
            )

            assertTrue(result is ProviderResult.Success)
            val snapshot = (result as ProviderResult.Success).snapshot
            assertEquals(0L, snapshot.balance.tokens?.used)
            assertEquals(0L, snapshot.balance.tokens?.total)
            assertEquals("100", snapshot.metadata["sessionUsed"])
            assertEquals("inactive", snapshot.metadata["planStatus"])
        }

        @Test
        fun `handles missing items field`() {
            val result = XiaomiResponseParser.parseTokenPlanUsage(
                json("""{"code":0,"data":{"monthUsage":{"percent":0.5}}}"""),
                account()
            )

            assertTrue(result is ProviderResult.Success)
            val snapshot = (result as ProviderResult.Success).snapshot
            assertEquals(0L, snapshot.balance.tokens?.total)
            assertEquals("inactive", snapshot.metadata["planStatus"])
        }

        @Test
        fun `handles missing percent field`() {
            val result = XiaomiResponseParser.parseTokenPlanUsage(
                json("""{"code":0,"data":{"monthUsage":{"items":[{"used":100,"limit":200}]}}}"""),
                account()
            )

            assertTrue(result is ProviderResult.Success)
            val snapshot = (result as ProviderResult.Success).snapshot
            assertEquals(100L, snapshot.balance.tokens?.used)
            assertEquals(200L, snapshot.balance.tokens?.total)
            assertEquals("active", snapshot.metadata["planStatus"])
            // percent defaults to 0.0 → 0 * 100 = 0
            assertEquals("0", snapshot.metadata["sessionUsed"])
        }

        @Test
        fun `reports inactive plan when totalCredits is zero`() {
            val result = XiaomiResponseParser.parseTokenPlanUsage(
                json("""{"code":0,"data":{"monthUsage":{"percent":0.0,"items":[{"used":0,"limit":0}]}}}"""),
                account()
            )

            assertTrue(result is ProviderResult.Success)
            val snapshot = (result as ProviderResult.Success).snapshot
            assertEquals("inactive", snapshot.metadata["planStatus"])
            assertEquals("100", snapshot.metadata["sessionUsed"])
        }

        @Test
        fun `reports active plan when totalCredits is positive`() {
            val result = XiaomiResponseParser.parseTokenPlanUsage(
                json("""{"code":0,"data":{"monthUsage":{"percent":0.75,"items":[{"used":750,"limit":1000}]}}}"""),
                account()
            )

            assertTrue(result is ProviderResult.Success)
            val snapshot = (result as ProviderResult.Success).snapshot
            assertEquals("active", snapshot.metadata["planStatus"])
            assertEquals("75", snapshot.metadata["sessionUsed"])
            assertEquals(750L, snapshot.balance.tokens?.used)
            assertEquals(1000L, snapshot.balance.tokens?.total)
            assertEquals(250L, snapshot.balance.tokens?.remaining)
        }

        @Test
        fun `returns AuthError for expired session`() {
            val result = XiaomiResponseParser.parseTokenPlanUsage(
                json("""{"code":-1,"message":"Token expired"}"""),
                account()
            )

            assertTrue(result is ProviderResult.Failure.AuthError)
        }

        @Test
        fun `returns RateLimited for rate limit error`() {
            val result = XiaomiResponseParser.parseTokenPlanUsage(
                json("""{"code":-1,"message":"Rate limit exceeded"}"""),
                account()
            )

            assertTrue(result is ProviderResult.Failure.RateLimited)
        }

        @Test
        fun `returns UnknownError for other errors`() {
            val result = XiaomiResponseParser.parseTokenPlanUsage(
                json("""{"code":500,"message":"Internal server error"}"""),
                account()
            )

            assertTrue(result is ProviderResult.Failure.UnknownError)
            assertEquals(
                "Xiaomi API error (code=500): Internal server error",
                (result as ProviderResult.Failure.UnknownError).message
            )
        }
    }
}
