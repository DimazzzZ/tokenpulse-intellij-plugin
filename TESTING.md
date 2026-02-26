# 🧪 Testing Guide

This document describes the testing approach and procedures for the TokenPulse plugin.

## 📊 Test Overview

TokenPulse uses **JUnit 5** and **Kotlin Coroutines Test**.

- **Unit Tests**: Test core logic (RefreshCoordinator, ProviderRegistry) in isolation.
- **Integration Tests**: Test provider implementations (OpenRouter, Cline) with a real HTTP client and `MockWebServer`.
- **Smoke Tests**: Verify plugin initialization and core services in the IDE environment.

## 🚀 Running Tests

### 1. Run All Tests
```bash
./gradlew test
```

### 2. Run Safe Unit Tests Only
```bash
./scripts/run-safe-tests.sh
```
The "Safe" runner skips tests that require the IntelliJ platform or heavy UI initialization, making it ideal for fast local development.

### 3. Run Specific Test Category
```bash
./gradlew test --tests "org.zhavoronkov.tokenpulse.service.RefreshCoordinatorTest"
```

## 🛠️ Testing Tools

- **MockWebServer**: Used in `ClineProviderClientTest` and `OpenRouterProviderClientTest` to simulate API responses.
- **Clock**: `RefreshCoordinator` accepts a `java.time.Clock`, allowing for deterministic testing of TTL caching and timeouts.

## 🧪 Best Practices

- **Constructor Injection**: Services and coordinators accept dependencies via constructor, making it easy to mock them in tests.
- **Coroutines**: Use `runTest` from `kotlinx-coroutines-test` for testing async logic.
- **Headless**: UI-related tests are kept minimal or use `JBPopupFactory` / `DialogWrapper` mocks.
