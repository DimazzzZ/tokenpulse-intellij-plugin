package org.zhavoronkov.tokenpulse.provider

import com.google.gson.Gson
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.zhavoronkov.tokenpulse.model.ConnectionType
import org.zhavoronkov.tokenpulse.model.ProviderResult
import org.zhavoronkov.tokenpulse.provider.nebius.NebiusProviderClient
import org.zhavoronkov.tokenpulse.settings.Account
import org.zhavoronkov.tokenpulse.settings.AuthType
import org.zhavoronkov.tokenpulse.ui.NebiusCurlParser
import java.io.File
import java.math.BigDecimal

/**
 * Live integration test using the real Nebius API via the curl.txt file.
 *
 * **Run on-demand only** - excluded from default test suite.
 *
 * Run with:
 * - `./gradlew test -Pfunctional` - run all tests including functional
 * - `./gradlew test --tests "*LiveTest*"` - run only live tests
 *
 * Skipped automatically if curl.txt is not present or not a valid curl command.
 */
@Tag("functional")
class NebiusProviderClientLiveTest {

    private val curlFile = File("curl.txt")

    private val testAccount = Account(
        id = "live-test-account",
        connectionType = ConnectionType.NEBIUS_BILLING,
        authType = AuthType.NEBIUS_BILLING_SESSION
    )

    @Test
    fun `fetchBalance against real Nebius API using curl_txt session`() {
        assumeTrue(curlFile.exists(), "curl.txt not found — skipping live test")

        val curlContent = curlFile.readText()
        assumeTrue(
            NebiusCurlParser.isCurlInput(curlContent),
            "curl.txt does not look like a curl command — skipping live test"
        )

        val session = NebiusCurlParser.parseCurl(curlContent)
        assumeTrue(session != null, "NebiusCurlParser returned null — skipping live test")
        println(
            "Parsed session: appSession=${!session!!.appSession.isNullOrBlank()}, " +
                "csrfToken=${!session.csrfToken.isNullOrBlank()}, " +
                "parentId=${session.parentId}, " +
                "hasParityData=${!session.rawCookieHeader.isNullOrBlank()}"
        )

        val client = NebiusProviderClient(
            httpClient = OkHttpClient(),
            gson = Gson()
        )
        val secret = Gson().toJson(session)
        val result = client.fetchBalance(testAccount, secret)

        println("fetchBalance result: $result")

        when (result) {
            is ProviderResult.Success -> {
                val credits = result.snapshot.balance.credits
                println("  credits.total    = ${credits?.total}")
                println("  credits.remaining = ${credits?.remaining}")
                assertInstanceOf(ProviderResult.Success::class.java, result)
                assertTrue(credits != null, "credits must not be null")
                assertTrue(
                    credits!!.total!! >= BigDecimal.ZERO,
                    "total must be >= 0, got ${credits.total}"
                )
                assertTrue(
                    credits.remaining!! >= BigDecimal.ZERO,
                    "remaining must be >= 0, got ${credits.remaining}"
                )
            }
            is ProviderResult.Failure.AuthError -> {
                // Session may be expired — that's a valid real-world result
                println("  AuthError: ${result.message}")
                assertTrue(
                    result.message.contains("expired") || result.message.contains("EBADCSRFTOKEN"),
                    "Expected session-expired message, got: ${result.message}"
                )
            }
            else -> {
                throw AssertionError("Unexpected result: $result")
            }
        }
    }
}
