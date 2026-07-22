package org.zhavoronkov.tokenpulse.provider.xiaomi

import com.google.gson.Gson
import com.google.gson.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.zhavoronkov.tokenpulse.model.ProviderResult

class XiaomiResponseParserTest {

    private val gson = Gson()

    private fun json(raw: String): JsonObject = gson.fromJson(raw, JsonObject::class.java)

    private fun creditsOf(part: XiaomiResponseParser.BalancePart) = part as XiaomiResponseParser.BalancePart.Credits
    private fun tokensOf(part: XiaomiResponseParser.BalancePart) = part as XiaomiResponseParser.BalancePart.TokensPart
    private fun failureOf(part: XiaomiResponseParser.BalancePart) =
        (part as XiaomiResponseParser.BalancePart.Failure).error

    // ── parseApiBalance ──────────────────────────────────────────────

    @Nested
    inner class ParseApiBalance {

        @Test
        fun `parses full balance response`() {
            val body =
                """{"code":0,"data":{"balance":"55.48","giftBalance":"10.00","cashBalance":"45.48","currency":"USD"}}"""
            val part = creditsOf(XiaomiResponseParser.parseApiBalance(json(body)))

            assertEquals("55.48", part.credits.remaining?.toPlainString())
            assertEquals("USD", part.metadata["currency"])
            assertEquals("10.00", part.metadata["giftBalance"])
            assertEquals("45.48", part.metadata["cashBalance"])
        }

        @Test
        fun `defaults missing fields to zero and USD`() {
            val part = creditsOf(XiaomiResponseParser.parseApiBalance(json("""{"code":0,"data":{}}""")))

            assertEquals("0", part.credits.remaining?.toPlainString())
            assertEquals("USD", part.metadata["currency"])
            assertEquals("0", part.metadata["giftBalance"])
            assertEquals("0", part.metadata["cashBalance"])
        }

        @Test
        fun `handles null data`() {
            val part = creditsOf(XiaomiResponseParser.parseApiBalance(json("""{"code":0,"data":null}""")))

            assertEquals("0", part.credits.remaining?.toPlainString())
        }

        @Test
        fun `handles missing data field`() {
            val part = creditsOf(XiaomiResponseParser.parseApiBalance(json("""{"code":0}""")))

            assertEquals("0", part.credits.remaining?.toPlainString())
        }

        @Test
        fun `returns AuthError for token expired message`() {
            val error =
                failureOf(XiaomiResponseParser.parseApiBalance(json("""{"code":-1,"message":"Token expired"}""")))

            assertTrue(error is ProviderResult.Failure.AuthError)
            assertEquals(
                "Xiaomi API error: Token expired",
                (error as ProviderResult.Failure.AuthError).message
            )
        }

        @Test
        fun `returns AuthError for session message`() {
            val error =
                failureOf(XiaomiResponseParser.parseApiBalance(json("""{"code":-1,"message":"Session timeout"}""")))

            assertTrue(error is ProviderResult.Failure.AuthError)
        }

        @Test
        fun `returns AuthError for unauthorized message`() {
            val error = failureOf(
                XiaomiResponseParser.parseApiBalance(json("""{"code":-1,"message":"Unauthorized access"}"""))
            )

            assertTrue(error is ProviderResult.Failure.AuthError)
        }

        @Test
        fun `returns AuthError for login message`() {
            val error = failureOf(
                XiaomiResponseParser.parseApiBalance(json("""{"code":-1,"message":"Please login again"}"""))
            )

            assertTrue(error is ProviderResult.Failure.AuthError)
        }

        @Test
        fun `returns RateLimited for rate message`() {
            val error = failureOf(
                XiaomiResponseParser.parseApiBalance(json("""{"code":-1,"message":"Rate limit exceeded"}"""))
            )

            assertTrue(error is ProviderResult.Failure.RateLimited)
            assertEquals(
                "Xiaomi rate limit: Rate limit exceeded",
                (error as ProviderResult.Failure.RateLimited).message
            )
        }

        @Test
        fun `returns RateLimited for throttle message`() {
            val error = failureOf(
                XiaomiResponseParser.parseApiBalance(json("""{"code":-1,"message":"Throttled"}"""))
            )

            assertTrue(error is ProviderResult.Failure.RateLimited)
        }

        @Test
        fun `returns UnknownError for unrecognized error`() {
            val error = failureOf(
                XiaomiResponseParser.parseApiBalance(json("""{"code":-1,"message":"Something went wrong"}"""))
            )

            assertTrue(error is ProviderResult.Failure.UnknownError)
            assertEquals(
                "Xiaomi API error (code=-1): Something went wrong",
                (error as ProviderResult.Failure.UnknownError).message
            )
        }

        @Test
        fun `returns UnknownError with default message when message is missing`() {
            val error = failureOf(XiaomiResponseParser.parseApiBalance(json("""{"code":-1}""")))

            assertTrue(error is ProviderResult.Failure.UnknownError)
            assertEquals(
                "Xiaomi API error (code=-1): Unknown error",
                (error as ProviderResult.Failure.UnknownError).message
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
            val part = tokensOf(XiaomiResponseParser.parseTokenPlanUsage(json(body)))

            assertEquals(2727524596L, part.tokens.used)
            assertEquals(11000000000L, part.tokens.total)
            assertEquals(8272475404L, part.tokens.remaining)
            assertEquals("active", part.metadata["planStatus"])
            // 0.248 * 100 = 24 (int truncation)
            assertEquals("24", part.metadata["sessionUsed"])
        }

        @Test
        fun `handles null data`() {
            val part = tokensOf(XiaomiResponseParser.parseTokenPlanUsage(json("""{"code":0,"data":null}""")))

            assertEquals(0L, part.tokens.used)
            assertEquals(0L, part.tokens.total)
            assertEquals(0L, part.tokens.remaining)
            assertEquals("100", part.metadata["sessionUsed"])
            assertEquals("inactive", part.metadata["planStatus"])
        }

        @Test
        fun `handles missing data field`() {
            val part = tokensOf(XiaomiResponseParser.parseTokenPlanUsage(json("""{"code":0}""")))

            assertEquals(0L, part.tokens.total)
            assertEquals("inactive", part.metadata["planStatus"])
        }

        @Test
        fun `handles null monthUsage`() {
            val part = tokensOf(
                XiaomiResponseParser.parseTokenPlanUsage(json("""{"code":0,"data":{"monthUsage":null}}"""))
            )

            assertEquals(0L, part.tokens.total)
            assertEquals("100", part.metadata["sessionUsed"])
            assertEquals("inactive", part.metadata["planStatus"])
        }

        @Test
        fun `handles missing monthUsage field`() {
            val part = tokensOf(XiaomiResponseParser.parseTokenPlanUsage(json("""{"code":0,"data":{}}""")))

            assertEquals(0L, part.tokens.total)
            assertEquals("inactive", part.metadata["planStatus"])
        }

        @Test
        fun `handles null items array`() {
            val part = tokensOf(
                XiaomiResponseParser.parseTokenPlanUsage(
                    json("""{"code":0,"data":{"monthUsage":{"percent":0.5,"items":null}}}""")
                )
            )

            assertEquals(0L, part.tokens.used)
            assertEquals(0L, part.tokens.total)
            assertEquals("100", part.metadata["sessionUsed"])
            assertEquals("inactive", part.metadata["planStatus"])
        }

        @Test
        fun `handles empty items array`() {
            val part = tokensOf(
                XiaomiResponseParser.parseTokenPlanUsage(
                    json("""{"code":0,"data":{"monthUsage":{"percent":0.5,"items":[]}}}""")
                )
            )

            assertEquals(0L, part.tokens.used)
            assertEquals(0L, part.tokens.total)
            assertEquals("100", part.metadata["sessionUsed"])
            assertEquals("inactive", part.metadata["planStatus"])
        }

        @Test
        fun `handles missing items field`() {
            val part = tokensOf(
                XiaomiResponseParser.parseTokenPlanUsage(
                    json("""{"code":0,"data":{"monthUsage":{"percent":0.5}}}""")
                )
            )

            assertEquals(0L, part.tokens.total)
            assertEquals("inactive", part.metadata["planStatus"])
        }

        @Test
        fun `handles missing percent field`() {
            val part = tokensOf(
                XiaomiResponseParser.parseTokenPlanUsage(
                    json("""{"code":0,"data":{"monthUsage":{"items":[{"used":100,"limit":200}]}}}""")
                )
            )

            assertEquals(100L, part.tokens.used)
            assertEquals(200L, part.tokens.total)
            assertEquals("active", part.metadata["planStatus"])
            // percent defaults to 0.0 → 0 * 100 = 0
            assertEquals("0", part.metadata["sessionUsed"])
        }

        @Test
        fun `reports inactive plan when totalCredits is zero`() {
            val part = tokensOf(
                XiaomiResponseParser.parseTokenPlanUsage(
                    json("""{"code":0,"data":{"monthUsage":{"percent":0.0,"items":[{"used":0,"limit":0}]}}}""")
                )
            )

            assertEquals("inactive", part.metadata["planStatus"])
            assertEquals("100", part.metadata["sessionUsed"])
        }

        @Test
        fun `reports active plan when totalCredits is positive`() {
            val part = tokensOf(
                XiaomiResponseParser.parseTokenPlanUsage(
                    json("""{"code":0,"data":{"monthUsage":{"percent":0.75,"items":[{"used":750,"limit":1000}]}}}""")
                )
            )

            assertEquals("active", part.metadata["planStatus"])
            assertEquals("75", part.metadata["sessionUsed"])
            assertEquals(750L, part.tokens.used)
            assertEquals(1000L, part.tokens.total)
            assertEquals(250L, part.tokens.remaining)
        }

        @Test
        fun `returns AuthError for expired session`() {
            val error = failureOf(
                XiaomiResponseParser.parseTokenPlanUsage(json("""{"code":-1,"message":"Token expired"}"""))
            )

            assertTrue(error is ProviderResult.Failure.AuthError)
        }

        @Test
        fun `returns RateLimited for rate limit error`() {
            val error = failureOf(
                XiaomiResponseParser.parseTokenPlanUsage(json("""{"code":-1,"message":"Rate limit exceeded"}"""))
            )

            assertTrue(error is ProviderResult.Failure.RateLimited)
        }

        @Test
        fun `returns UnknownError for other errors`() {
            val error = failureOf(
                XiaomiResponseParser.parseTokenPlanUsage(json("""{"code":500,"message":"Internal server error"}"""))
            )

            assertTrue(error is ProviderResult.Failure.UnknownError)
            assertEquals(
                "Xiaomi API error (code=500): Internal server error",
                (error as ProviderResult.Failure.UnknownError).message
            )
        }
    }
}
