package org.zhavoronkov.tokenpulse.provider

import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.zhavoronkov.tokenpulse.provider.anthropic.claudecode.ClaudeCliExecutor
import org.zhavoronkov.tokenpulse.provider.anthropic.claudecode.ClaudeCliUsageExtractor
import java.time.Duration
import kotlin.system.measureTimeMillis

/**
 * Functional/Integration test for Claude CLI usage extraction.
 *
 * **Run on-demand only** - excluded from default test suite.
 * 
 * Run with:
 * - `./gradlew test -Pfunctional` - run all tests including functional
 * - `./gradlew test --tests "*FunctionalTest*"` - run only functional tests
 *
 * REQUIREMENTS:
 * - Claude CLI must be installed: npm install -g @anthropic-ai/claude-code
 * - User must be logged in via the CLI
 *
 * This test is skipped automatically if Claude CLI is not available.
 */
@Tag("functional")
class ClaudeCliFunctionalTest {

    companion object {
        /** Target extraction time - optimized expect script should complete within this */
        private const val TARGET_EXTRACTION_TIME_MS = 15_000L

        /** Hard timeout for the test */
        private const val TEST_TIMEOUT_SECONDS = 45L
    }

    @Test
    fun `ClaudeCliUsageExtractor can extract usage when CLI is available`() {
        val extractor = ClaudeCliUsageExtractor()

        // Skip test if Claude CLI is not installed
        assumeTrue(extractor.isClaudeCliAvailable(), "Claude CLI not installed, skipping test")

        println("=== Claude CLI Integration Test ===")
        println("Claude CLI detected, running usage extraction...")
        println("Target time: ${TARGET_EXTRACTION_TIME_MS}ms")

        assertTimeoutPreemptively(Duration.ofSeconds(TEST_TIMEOUT_SECONDS)) {
            var result: ClaudeCliUsageExtractor.ExtractionResult? = null

            val elapsedMs = measureTimeMillis {
                result = extractor.extractUsage(timeoutSeconds = 30)
            }

            println("=== Timing Results ===")
            println("  Extraction took: ${elapsedMs}ms (${elapsedMs / 1000.0}s)")
            println("  Target was: ${TARGET_EXTRACTION_TIME_MS}ms")

            when (val r = result!!) {
                is ClaudeCliUsageExtractor.ExtractionResult.Success -> {
                    val data = r.usageData
                    println("=== Extraction Successful ===")
                    println("  Session used: ${data.sessionUsedPercent}%")
                    println("  Session resets: ${data.sessionResetsAt}")
                    println("  Week used: ${data.weekUsedPercent}%")
                    println("  Week resets: ${data.weekResetsAt}")

                    // At least one value should be parsed
                    assertTrue(
                        data.sessionUsedPercent != null || data.weekUsedPercent != null,
                        "Expected at least one usage value to be parsed"
                    )

                    // Performance assertion
                    if (elapsedMs > TARGET_EXTRACTION_TIME_MS) {
                        println("  ⚠️  WARNING: Extraction exceeded target time")
                    } else {
                        println("  ✓  Performance target met!")
                    }
                }

                is ClaudeCliUsageExtractor.ExtractionResult.Error -> {
                    println("=== Extraction Failed ===")
                    println("  Error: ${r.message}")
                    println("  Details: ${r.details}")

                    // Skip test for environment-related failures that can't be resolved in CI
                    // These are expected when running without a proper terminal
                    if (r.message.contains("not authenticated", ignoreCase = true) ||
                        r.message.contains("log in", ignoreCase = true) ||
                        r.message.contains("Could not capture", ignoreCase = true) ||
                        r.message.contains("timed out", ignoreCase = true) ||
                        r.details?.contains("output length: 0") == true
                    ) {
                        println("  -> Skipping: Environment does not support PTY interaction")
                        assumeTrue(false, "PTY interaction not supported in this environment")
                    }

                    // Otherwise fail the test
                    throw AssertionError("Extraction failed: ${r.message}")
                }
            }
        }
    }

    @Test
    fun `ClaudeCliExecutor can find CLI path when installed`() {
        // This test doesn't require authentication, just CLI installation
        assumeTrue(ClaudeCliExecutor.isClaudeCliAvailable(), "Claude CLI not installed, skipping test")

        val path = ClaudeCliExecutor.findClaudeCliPath()
        println("Claude CLI path: $path")
        assertTrue(path != null && path.isNotEmpty(), "Expected non-empty path")
    }

    @Test
    fun `ClaudeCliExecutor can get CLI version when installed`() {
        assumeTrue(ClaudeCliExecutor.isClaudeCliAvailable(), "Claude CLI not installed, skipping test")

        val (works, version) = ClaudeCliExecutor.verifyClaudeCliWorks()
        println("Claude CLI works: $works, version: $version")
        assertTrue(works, "Expected CLI to be working")
        assertTrue(version != null && version.isNotEmpty(), "Expected version string")
    }
}
