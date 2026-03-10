#!/bin/bash

# Safe test runner for TokenPulse
# Runs only unit tests that are fast and do not require external dependencies.
#
# Excluded tests:
# - *LiveTest* - require real API credentials
# - *FunctionalTest* - require external tools (Claude CLI, etc.)
# - *SmokeTest* - require IntelliJ Platform

echo "🧪 Running safe unit tests only..."

./gradlew test \
  --tests "org.zhavoronkov.tokenpulse.service.RefreshCoordinatorTest" \
  --tests "org.zhavoronkov.tokenpulse.provider.ClineProviderClientTest" \
  --tests "org.zhavoronkov.tokenpulse.provider.OpenRouterProviderClientTest" \
  --tests "org.zhavoronkov.tokenpulse.provider.OpenAiPlatformProviderClientTest" \
  --tests "org.zhavoronkov.tokenpulse.provider.ClaudeCodeProviderClientTest" \
  --tests "org.zhavoronkov.tokenpulse.provider.ClaudeCliOutputParserTest" \
  --tests "org.zhavoronkov.tokenpulse.provider.ClaudeCliExecutorTest" \
  --tests "org.zhavoronkov.tokenpulse.provider.NebiusProviderClientTest" \
  --tests "org.zhavoronkov.tokenpulse.provider.ProviderRegistryTest" \
  --tests "org.zhavoronkov.tokenpulse.ui.NebiusCurlParserTest" \
  --tests "org.zhavoronkov.tokenpulse.ui.BalanceFormatterTest" \
  --tests "org.zhavoronkov.tokenpulse.ui.SecretRedactorTest" \
  --tests "org.zhavoronkov.tokenpulse.model.ConnectionTypeTest" \
  --tests "org.zhavoronkov.tokenpulse.model.ProviderTest" \
  --tests "org.zhavoronkov.tokenpulse.model.ProviderResultTest" \
  --tests "org.zhavoronkov.tokenpulse.model.BalanceSnapshotTest" \
  --tests "org.zhavoronkov.tokenpulse.model.NebiusBalanceBreakdownTest" \
  --tests "org.zhavoronkov.tokenpulse.settings.AccountTest" \
  --tests "org.zhavoronkov.tokenpulse.settings.GenerateKeyPreviewTest" \
  --tests "org.zhavoronkov.tokenpulse.settings.NormalizeConnectionAuthTypesTest" \
  --tests "org.zhavoronkov.tokenpulse.settings.SanitizeAccountsTest" \
  --tests "org.zhavoronkov.tokenpulse.settings.AuthTypeTest" \
  --tests "org.zhavoronkov.tokenpulse.ui.ProgressBarRendererTest" \
  --tests "org.zhavoronkov.tokenpulse.model.CreditsTest" \
  --tests "org.zhavoronkov.tokenpulse.model.TokensTest" \
  --tests "org.zhavoronkov.tokenpulse.model.BalanceTest" \
  --tests "org.zhavoronkov.tokenpulse.settings.TokenPulseSettingsTest" \
  --console=plain

echo "✅ Safe unit tests completed!"
