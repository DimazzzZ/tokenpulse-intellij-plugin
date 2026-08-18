package org.zhavoronkov.tokenpulse.provider

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.zhavoronkov.tokenpulse.model.ConnectionType
import org.zhavoronkov.tokenpulse.model.ProviderResult
import org.zhavoronkov.tokenpulse.provider.github.GitHubCopilotProviderClient
import org.zhavoronkov.tokenpulse.provider.github.GitHubSecrets
import org.zhavoronkov.tokenpulse.settings.Account
import org.zhavoronkov.tokenpulse.settings.AuthType
import java.math.BigDecimal

/**
 * Unit tests for [GitHubCopilotProviderClient] using MockWebServer.
 *
 * Verifies:
 * - Successful parsing of premium_request and ai_credit usage responses
 * - Sum-of-netAmount aggregation across items
 * - Currency and username metadata
 * - Auth (401/403), rate-limit (429), and server (5xx) error mapping
 * - Graceful degradation when only ai_credit fetch fails
 * - Rejection of malformed secret payloads
 */
class GitHubCopilotProviderClientTest {
    private lateinit var mockWebServer: MockWebServer
    private lateinit var client: GitHubCopilotProviderClient
    private val account = Account(
        connectionType = ConnectionType.GITHUB_COPILOT_PAT,
        authType = AuthType.GITHUB_COPILOT_PAT
    )
    private val validSecret = GitHubSecrets.encodeCopilot("ghp_testtoken", "octocat")

    @BeforeEach
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.start()
        client = GitHubCopilotProviderClient(
            baseUrl = mockWebServer.url("/").toString().trimEnd('/')
        )
    }

    @AfterEach
    fun tearDown() {
        mockWebServer.shutdown()
    }

    @Test
    fun `fetchBalance sums netAmount across premium_request items`() {
        // premium_request response
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{
                    "usageItems": [
                        {"netAmount": 0.04, "product": "copilot"},
                        {"netAmount": 0.08, "product": "copilot"},
                        {"netAmount": 0.12, "product": "copilot"}
                    ]
                }"""
            )
        )
        // ai_credit response
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"usageItems": [{"netAmount": 1.50}]}"""
            )
        )

        val result = client.fetchBalance(account, validSecret)

        assertTrue(result is ProviderResult.Success)
        val success = result as ProviderResult.Success
        assertEquals(0, BigDecimal("0.24").compareTo(success.snapshot.balance.credits?.used))
        assertEquals("USD", success.snapshot.metadata["currency"])
        assertEquals("octocat", success.snapshot.metadata["username"])
        assertEquals("3", success.snapshot.metadata["premiumRequestItems"])
        assertEquals("1.50", success.snapshot.metadata["aiCreditsUsed"])
    }

    @Test
    fun `fetchBalance succeeds with zero usage when items array is missing`() {
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{}"""))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{}"""))

        val result = client.fetchBalance(account, validSecret)

        assertTrue(result is ProviderResult.Success)
        val success = result as ProviderResult.Success
        assertEquals(0, BigDecimal.ZERO.compareTo(success.snapshot.balance.credits?.used))
    }

    @Test
    fun `fetchBalance still succeeds when ai_credit call fails`() {
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"usageItems": [{"netAmount": 5.00}]}"""
            )
        )
        // ai_credit fails with 500 — primary should still return successfully
        mockWebServer.enqueue(MockResponse().setResponseCode(500))

        val result = client.fetchBalance(account, validSecret)

        assertTrue(result is ProviderResult.Success)
        val success = result as ProviderResult.Success
        assertEquals("unavailable", success.snapshot.metadata["aiCreditsUsed"])
    }

    @Test
    fun `fetchBalance maps 401 to AuthError`() {
        mockWebServer.enqueue(MockResponse().setResponseCode(401))
        val result = client.fetchBalance(account, validSecret)
        assertTrue(result is ProviderResult.Failure.AuthError)
    }

    @Test
    fun `fetchBalance maps 403 to AuthError`() {
        mockWebServer.enqueue(MockResponse().setResponseCode(403))
        val result = client.fetchBalance(account, validSecret)
        assertTrue(result is ProviderResult.Failure.AuthError)
    }

    @Test
    fun `fetchBalance maps 429 to RateLimited with Retry-After hint`() {
        mockWebServer.enqueue(
            MockResponse().setResponseCode(429).setHeader("Retry-After", "60")
        )
        val result = client.fetchBalance(account, validSecret)
        assertTrue(result is ProviderResult.Failure.RateLimited)
        val failure = result as ProviderResult.Failure.RateLimited
        assertTrue(failure.msg.contains("60"), "Retry-After seconds should be surfaced")
    }

    @Test
    fun `fetchBalance maps 500 to NetworkError`() {
        mockWebServer.enqueue(MockResponse().setResponseCode(500))
        val result = client.fetchBalance(account, validSecret)
        assertTrue(result is ProviderResult.Failure.NetworkError)
    }

    @Test
    fun `fetchBalance rejects malformed secret payload with AuthError`() {
        val result = client.fetchBalance(account, "not-a-json-blob")
        assertTrue(result is ProviderResult.Failure.AuthError)
    }

    @Test
    fun `fetchBalance rejects secret missing username with AuthError`() {
        val result = client.fetchBalance(account, """{"pat":"ghp_x"}""")
        assertTrue(result is ProviderResult.Failure.AuthError)
    }

    @Test
    fun `request sets Authorization Bearer header and GitHub API version`() {
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"usageItems": []}"""))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"usageItems": []}"""))

        client.fetchBalance(account, validSecret)

        val request = mockWebServer.takeRequest()
        assertEquals("Bearer ghp_testtoken", request.getHeader("Authorization"))
        assertNotNull(request.getHeader("X-GitHub-Api-Version"))
        assertEquals("application/vnd.github+json", request.getHeader("Accept"))
        assertTrue(request.path?.contains("/users/octocat/settings/billing/premium_request") == true)
    }

    @Test
    fun `testCredentials returns success without full snapshot`() {
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"usageItems": []}""")
        )
        val result = client.testCredentials(account, validSecret)
        assertTrue(result is ProviderResult.Success)
    }

    @Test
    fun `testCredentials returns AuthError on 401`() {
        mockWebServer.enqueue(MockResponse().setResponseCode(401))
        val result = client.testCredentials(account, validSecret)
        assertTrue(result is ProviderResult.Failure.AuthError)
    }
}
