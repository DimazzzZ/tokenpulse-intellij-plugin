# 🧪 Testing Guide

This document describes the testing approach and procedures for the TokenPulse β plugin.

## 📊 Test Overview

TokenPulse uses **JUnit 5** and **Kotlin Coroutines Test**.

| Test Type | Naming Convention | Description |
|-----------|------------------|-------------|
| **Unit Tests** | `*Test.kt` | Fast, isolated tests with mocks |
| **Integration Tests** | `*Test.kt` | Tests with MockWebServer for HTTP |
| **Functional Tests** | `*FunctionalTest.kt` | Require external tools (CLI, etc.) |
| **Live Tests** | `*LiveTest.kt` | Require real API credentials |
| **Smoke Tests** | `*SmokeTest.kt` | Require IntelliJ Platform |

## 🚀 Running Tests

### 1. Run Unit Tests (Default - Fast)
```bash
./gradlew test
```
Runs only fast, isolated unit tests. **Functional tests are excluded by default.**
Safe for CI and local development.

### 2. Run All Tests Including Functional
```bash
./gradlew test -Pfunctional
```
Includes functional/integration tests that require external dependencies.

### 3. Run Functional Tests Only
```bash
./gradlew functionalTest
```
Or with pattern matching:
```bash
./gradlew test --tests "*FunctionalTest*" --tests "*LiveTest*"
```
Requires:
- **Claude CLI**: `npm install -g @anthropic-ai/claude-code`
- User must be logged in for Claude
- `curl.txt` file for Nebius live tests

### 4. Run Specific Test Class
```bash
./gradlew test --tests "org.zhavoronkov.tokenpulse.provider.OpenRouterProviderClientTest"
```

### 5. Legacy: Run Safe Unit Tests
```bash
./scripts/run-safe-tests.sh
```
Same as `./gradlew test` - functional tests are now excluded by default.

## 📁 Test Structure

```
src/test/kotlin/org/zhavoronkov/tokenpulse/
├── TokenPulseSmokeTest.kt              # IDE initialization (SmokeTest)
├── model/
│   ├── BalanceTest.kt                  # Unit: Balance, Credits, Tokens (14)
│   ├── ConnectionTypeTest.kt           # Unit: enum methods (16)
│   ├── ProviderTest.kt                 # Unit: enum methods (10)
│   └── ProviderResultTest.kt           # Unit: sealed class (18)
├── provider/
│   ├── ClaudeCliOutputParserTest.kt    # Unit: parsing logic (27)
│   ├── ClaudeCliFunctionalTest.kt      # Functional: real CLI (3)
│   ├── ClaudeCodeProviderClientTest.kt # Unit: mocked
│   ├── ClineProviderClientTest.kt      # Integration: MockWebServer
│   ├── NebiusProviderClientTest.kt     # Integration: MockWebServer
│   ├── NebiusProviderClientLiveTest.kt # Live: real API
│   ├── OpenAiPlatformProviderClientTest.kt
│   ├── OpenRouterProviderClientTest.kt
│   └── ProviderRegistryTest.kt         # Unit: registry logic (9)
├── service/
│   └── RefreshCoordinatorTest.kt       # Unit: clock injection
├── settings/
│   ├── AccountTest.kt                  # Unit: account logic (24)
│   └── TokenPulseSettingsTest.kt       # Unit: settings data class (10)
└── ui/
    ├── BalanceFormatterTest.kt         # Unit: formatting (17)
    ├── NebiusCurlParserTest.kt         # Unit: parsing
    ├── ProgressBarRendererTest.kt      # Unit: progress bars (17)
    └── SecretRedactorTest.kt           # Unit: redaction (16)
```

## 🛠️ Testing Tools

- **MockWebServer**: Simulates HTTP API responses for provider clients
- **Clock Injection**: `RefreshCoordinator` accepts `java.time.Clock` for deterministic TTL testing
- **Constructor Injection**: All services accept dependencies for easy mocking

## 🧪 Best Practices

1. **Prefer Unit Tests**: Use `*Test.kt` for fast, isolated tests
2. **Mock External Dependencies**: Use MockWebServer for HTTP, mocks for singletons
3. **Use assumeTrue for Optional Tests**: Functional tests should skip gracefully when dependencies are unavailable
4. **Coroutines**: Use `runTest` from `kotlinx-coroutines-test` for async logic
5. **Keep UI Tests Minimal**: UI components are hard to test; extract logic to testable classes

## 📈 Code Coverage

Generate coverage report:
```bash
./scripts/run-safe-tests.sh && ./gradlew koverXmlReport -x test
```

Report location: `build/reports/kover/report.xml`

### Kover Exclusions

The following classes are excluded from coverage reports as they require IntelliJ Platform or external dependencies:

| Category | Excluded Classes |
|----------|------------------|
| **UI Dialogs** | `*Dialog`, `TokenPulseConfigurable`, `TokenPulseStatusBarWidget`, `*TableModel` |
| **Platform Services** | `BalanceRefreshService`, `HttpClientService`, `TokenPulseSettingsService`, `CredentialsStore` |
| **OAuth/CLI** | `ChatGptOAuthManager`, `CodexAppServerClient`, `ClaudeCliExecutor`, `ClaudeCliUsageExtractor` |
| **Startup** | `WelcomeNotificationActivity`, `WhatsNewNotificationActivity` |
| **Actions** | `TokenPulseActions` |

These exclusions ensure the coverage report reflects testable business logic rather than platform-dependent code.
