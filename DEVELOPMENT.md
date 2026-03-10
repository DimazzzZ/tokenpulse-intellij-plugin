# 🛠️ Development Guide

This guide covers development setup, building, testing, and contributing to the TokenPulse β IntelliJ Plugin.

## 📋 Prerequisites

- **JDK 17 (exactly)** — The build toolchain, Kotlin compiler, and Gradle daemon are all pinned to
  Java 17. Using a newer JDK (18+) may cause Kotlin compiler or IntelliJ Platform compatibility
  issues. Install Zulu JDK 17 or any other JDK 17 distribution.
- **IntelliJ IDEA** — Community or Ultimate Edition with Plugin Development support.

## 🚀 Quick Start

### 1. Fast Development Builds (Recommended)

For day-to-day development, use the fast build script to skip slow analysis tasks:

```bash
# ⚡ Fast compile only (~1-2 seconds with warm daemon)
./scripts/fast-build.sh compile

# 🧪 Fast tests (~8 seconds, no coverage)
./scripts/fast-build.sh test

# ✅ Quick check (compile + tests + detekt, no coverage)
./scripts/fast-build.sh check

# 🔨 Full build (same as ./gradlew build)
./scripts/fast-build.sh full
```

### 2. Standard Build Commands

```bash
# Clean build and build the plugin distribution
./gradlew clean buildPlugin

# Run all tests
./gradlew test

# Run static analysis
./gradlew detekt detektSarif
```

> **💡 Tip:** The Gradle daemon keeps builds fast. Always use `./gradlew` commands 
> (never `--no-daemon`). The first build after daemon restart is slower (~30s), 
> subsequent builds are much faster (~5-15s).

### 2. Development Run
```bash
# Launch a development instance of the IDE with the plugin installed
# Requires Java 17 — set JAVA_HOME to your JDK 17 installation if needed:
#   export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home
./gradlew runIde
```

> **Java 17 required for runs.** The `gradle.properties` file pins the Kotlin compiler and Gradle
> daemon to Java 17. If `./gradlew runIde` fails with a JDK version error, verify your active JDK:
> ```bash
> java -version   # must show 17.x
> ./gradlew -version  # shows the JDK Gradle is using
> ```
> On macOS with multiple JDKs installed, set `JAVA_HOME` explicitly before running Gradle.

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
- **`BalanceRefreshService`** — Manages the auto-refresh loop and single-flight coordination.
- **`RefreshCoordinator`** — Handles TTL caching and request coalescing.
- **`HttpClientService`** — Shared service for OkHttp and Gson instances.
- **`CredentialsStore`** — Secure storage for API keys using IntelliJ's `PasswordSafe`.
- **`BalanceHistoryService`** — Persists balance snapshots for chart visualization.

## 📝 Version Management

All versioning and platform compatibility info is centralized in **`gradle.properties`**:
```properties
pluginVersion = 0.1.0
pluginSinceBuild = 242
platformVersion = 2024.2
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
