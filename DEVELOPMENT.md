# 🛠️ Development Guide

This guide covers development setup, building, testing, and contributing to the TokenPulse IntelliJ Plugin.

## 📋 Prerequisites

- **JDK 17 or higher** - Required for IntelliJ Platform development.
- **IntelliJ IDEA** - Community or Ultimate Edition with Plugin Development support.

## 🚀 Quick Start

### 1. Build and Verify
```bash
# Clean build and build the plugin distribution
./gradlew clean buildPlugin

# Run all tests
./gradlew test

# Run static analysis
./gradlew detekt detektSarif
```

### 2. Development Run
```bash
# Launch a development instance of the IDE with the plugin installed
./gradlew runIde
```

## 🏗️ Project Architecture

### Directory Structure
```
token-pulse/
├── 📁 build.gradle.kts           # Build configuration & dependencies
├── 📁 gradle.properties          # Plugin metadata & versions
├── 📁 src/main/kotlin/org/zhavoronkov/tokenpulse/
│   ├── 📁 actions/               # Action definitions (Dashboard, Refresh, Settings)
│   ├── 📁 model/                 # Data models (Balance, Account)
│   ├── 📁 provider/              # Provider implementations (Cline, OpenRouter)
│   ├── 📁 service/               # Core services (Balance refresh, HTTP client)
│   ├── 📁 startup/               # First-run & Update notifications
│   ├── 📁 ui/                    # UI components (Settings, Dashboard, Status bar)
├── 📁 src/test/kotlin/           # Unit and smoke tests
└── 📁 config/detekt/             # Detekt static analysis configuration
```

### Key Components
- **`BalanceRefreshService`** - Manages the auto-refresh loop and single-flight coordination.
- **`RefreshCoordinator`** - Handles TTL caching and request coalescing.
- **`HttpClientService`** - Shared service for OkHttp and Gson instances.
- **`CredentialsStore`** - Secure storage for API keys using IntelliJ's `PasswordSafe`.

## 📝 Version Management

All versioning and platform compatibility info is centralized in **`gradle.properties`**:
```properties
pluginVersion = 0.1.0
pluginSinceBuild = 233
platformVersion = 2023.3.4
```

To update the version, use:
```bash
./scripts/update-version.sh <new-version>
```

## 🧪 Testing

We use JUnit 5 and Kotlin Coroutines Test.
```bash
# Run all tests
./gradlew test

# Run safe unit tests only (fast, no IDE classes)
./scripts/run-safe-tests.sh
```

## 🔍 Code Quality

Code quality is enforced via **Detekt**. The build will fail if any issues are found.
```bash
./gradlew detekt
```

---

## 🚀 Release Process

1. Update version in `gradle.properties`.
2. Add a new entry to `CHANGELOG.md`.
3. Create a git tag: `git tag v0.1.0` and push it.
4. CI will automatically create a GitHub Release and attach the ZIP artifact.
5. **Manually publish** the signed artifact to the JetBrains Marketplace (see `MARKETPLACE.md`).
