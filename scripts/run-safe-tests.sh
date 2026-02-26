#!/bin/bash

# Safe test runner for TokenPulse
# Runs only unit tests that are fast and independent of the IntelliJ Platform UI.

echo "🧪 Running safe unit tests only..."

./gradlew test \
  --tests "org.zhavoronkov.tokenpulse.service.RefreshCoordinatorTest" \
  --tests "org.zhavoronkov.tokenpulse.provider.ClineProviderClientTest" \
  --tests "org.zhavoronkov.tokenpulse.provider.OpenRouterProviderClientTest" \
  --console=plain

echo "✅ Safe unit tests completed!"
