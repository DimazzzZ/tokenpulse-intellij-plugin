package org.zhavoronkov.tokenpulse.provider

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.zhavoronkov.tokenpulse.model.ConnectionType
import org.zhavoronkov.tokenpulse.model.ProviderResult
import org.zhavoronkov.tokenpulse.provider.github.GitHubCopilotBudgetProviderClient
import org.zhavoronkov.tokenpulse.provider.github.GitHubSecrets
import org.zhavoronkov.tokenpulse.settings.Account
import org.zhavoronkov.tokenpulse.settings.AuthType
import java.math.BigDecimal

/**
 * Unit tests for [GitHubCopilotBudgetProviderClient] using MockWebServer.
 *
 * Verifies:
 * - Successful parsing of org budgets endpoint
 * - Filtering to Copilot-relevant SKUs (ai_credits, premium_requests)
 * - Sum-of-budget and sum-of-consumed aggregation
 * - Remaining balance calculation (total - used)
 * - RFC 5988 Link header pagination
 * - Auth (401/403), rate-limit (429), and server (5xx) error mapping
 * - Rejection of malformed secret payloads
 */
class GitHubCopilotBudgetProviderClientTest {
    private lateinit var mockWebServer: MockWebServer
    private lateinit var client: GitHubCopilotBudgetProviderClient
    private val account = Account(
        connectionType = ConnectionType.GITHUB_COPILOT_ORG_BUDGET,
        authType = AuthType.GITHUB_COPILOT_ORG_BUDGET_PAT
    )
    private val validSecret = GitHubSecrets.encodeOrgBudget("ghp_testtoken", "my-org")

    @BeforeEach
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.start()
        client = GitHubCopilotBudgetProviderClient(
            baseUrl = mockWebServer.url("/").toString().trimEnd('/')
        )
    }

    @AfterEach
    fun tearDown() {
        mockWebServer.shutdown()
    }

    @Test
    fun `fetchBalance sums budgets and consumed amounts for Copilot SKUs`() {
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{
                    "budgets": [
                        {
                            "budget_product_sku": "ai_credits",
                            "budget_amount": 1000.00,
                            "consumed_amount": 250.00
                        },
                        {
                            "budget_product_sku": "premium_requests",
                            "budget_amount": 500.00,
                            "consumed_amount": 100.00
                        }
                    ]
                }"""
            )
        )

        val result = client.fetchBalance(account, validSecret)

        assertTrue(result is ProviderResult.Success)
        val success = result as ProviderResult.Success
        assertEquals(0, BigDecimal("1500.00").compareTo(success.snapshot.balance.credits?.total))
        assertEquals(0, BigDecimal("350.00").compareTo(success.snapshot.balance.credits?.used))
        assertEquals(0, BigDecimal("1150.00").compareTo(success.snapshot.balance.credits?.remaining))
        assertEquals("USD", success.snapshot.metadata["currency"])
        assertEquals("my-org", success.snapshot.metadata["org"])
        assertEquals("2", success.snapshot.metadata["budgetCount"])
    }

    @Test
    fun `fetchBalance filters out non-Copilot SKUs`() {
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{
                    "budgets": [
                        {
                            "budget_product_sku": "ai_credits",
                            "budget_amount": 100.00,
                            "consumed_amount": 10.00
                        },
                        {
                            "budget_product_sku": "actions_minutes",
                            "budget_amount": 1000.00,
                            "consumed_amount": 500.00
                        }
                    ]
                }"""
            )
        )

        val result = client.fetchBalance(account, validSecret)

        assertTrue(result is ProviderResult.Success)
        val success = result as ProviderResult.Success
        // Only ai_credits should be counted
        assertEquals(0, BigDecimal("100.00").compareTo(success.snapshot.balance.credits?.total))
        assertEquals(0, BigDecimal("10.00").compareTo(success.snapshot.balance.credits?.used))
    }

    @Test
    fun `fetchBalance handles pagination with Link header`() {
        // First page
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader(
                    "Link",
                    "<${mockWebServer.url("/page2")}>; rel=\"next\""
                )
                .setBody(
                    """{
                        "budgets": [
                            {
                                "budget_product_sku": "ai_credits",
                                "budget_amount": 100.00,
                                "consumed_amount": 10.00
                            }
                        ]
                    }"""
                )
        )
        // Second page
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{
                    "budgets": [
                        {
                            "budget_product_sku": "premium_requests",
                            "budget_amount": 50.00,
                            "consumed_amount": 5.00
                        }
                    ]
                }"""
            )
        )

        val result = client.fetchBalance(account, validSecret)

        assertTrue(result is ProviderResult.Success)
        val success = result as ProviderResult.Success
        // Should sum both pages
        assertEquals(0, BigDecimal("150.00").compareTo(success.snapshot.balance.credits?.total))
        assertEquals(0, BigDecimal("15.00").compareTo(success.snapshot.balance.credits?.used))
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
    fun `fetchBalance maps 429 to RateLimited`() {
        mockWebServer.enqueue(MockResponse().setResponseCode(429))
        val result = client.fetchBalance(account, validSecret)
        assertTrue(result is ProviderResult.Failure.RateLimited)
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
    fun `fetchBalance rejects secret missing org with AuthError`() {
        val result = client.fetchBalance(account, """{"pat":"ghp_x"}""")
        assertTrue(result is ProviderResult.Failure.AuthError)
    }

    @Test
    fun `testCredentials returns success without full snapshot`() {
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"budgets": []}""")
        )
        val result = client.testCredentials(account, validSecret)
        assertTrue(result is ProviderResult.Success)
    }

    @Test
    fun `testCredentials returns AuthError on 403`() {
        mockWebServer.enqueue(MockResponse().setResponseCode(403))
        val result = client.testCredentials(account, validSecret)
        assertTrue(result is ProviderResult.Failure.AuthError)
    }
}
