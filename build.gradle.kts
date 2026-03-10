plugins {
    id("java")
    kotlin("jvm") version "2.0.21"
    id("io.gitlab.arturbosch.detekt") version "1.23.8"
    id("org.jetbrains.kotlinx.kover") version "0.9.1"
    id("org.jetbrains.intellij.platform") version "2.3.0"
}

group = project.findProperty("pluginGroup") ?: "org.zhavoronkov.tokenpulse"
version = project.findProperty("pluginVersion") ?: "0.1.0"

repositories {
    mavenCentral()
    // IntelliJ Platform Gradle Plugin 2.x repositories
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    // pty4j is provided by IntelliJ platform at runtime - no explicit dependency needed

    detektPlugins("io.gitlab.arturbosch.detekt:detekt-formatting:1.23.8")

    // IntelliJ Platform dependencies (2.x plugin style)
    intellijPlatform {
        val platformVersion = project.findProperty("platformVersion") as String? ?: "2024.2.5"
        intellijIdeaUltimate(platformVersion)

        // Test framework for plugin tests
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    }
}

// Configure Java toolchain to use Java 21 (LTS)
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

// Configure IntelliJ Platform Plugin (2.x)
intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "242"  // 2024.2
            untilBuild = "263.*"  // Up to 2026.3.x
        }
    }

    pluginVerification {
        ides {
            recommended()
        }
    }

    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
}

// Configure Detekt
detekt {
    buildUponDefaultConfig = true
    allRules = false
    config.setFrom("$projectDir/config/detekt/detekt.yml")
    basePath = projectDir.absolutePath
}

// Configure Detekt SARIF reporting task
tasks.register<io.gitlab.arturbosch.detekt.Detekt>("detektSarif") {
    description = "Runs detekt and generates SARIF report for GitHub Code Scanning"
    group = "verification"

    setSource(files("src/main/kotlin"))
    include("**/*.kt")
    exclude("**/test/**", "**/*Test.kt")

    reports {
        sarif.required.set(true)
        sarif.outputLocation.set(file("build/reports/detekt/detekt.sarif"))
        html.required.set(false)
        txt.required.set(true)
        xml.required.set(false)
    }

    jvmTarget = "21"
    basePath = projectDir.absolutePath
    ignoreFailures = true
}

tasks {
    // Set JVM compatibility versions
    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }

    // Configure Detekt tasks
    withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
        jvmTarget = "21"
        ignoreFailures = true  // Don't fail the build on Detekt issues during development
    }

    // Configure tests
    test {
        useJUnitPlatform {
            // Exclude functional/integration tests by default
            // Run them on-demand with: ./gradlew test -Pfunctional
            if (!project.hasProperty("functional")) {
                excludeTags("functional")
            }
        }
        systemProperty("tokenpulse.testMode", "true")

        // Parallel test execution disabled for IntelliJ Platform tests
        // (Platform tests share state and don't parallelize well)
        maxParallelForks = 1

        // Report test results even on failure
        reports {
            junitXml.required.set(true)
            html.required.set(true)
        }
    }

    // Separate task to run only functional tests
    register<Test>("functionalTest") {
        description = "Runs functional/integration tests that require external dependencies"
        group = "verification"

        useJUnitPlatform {
            includeTags("functional")
        }
        systemProperty("tokenpulse.testMode", "true")
        maxParallelForks = 1

        reports {
            junitXml.required.set(true)
            html.required.set(true)
        }
    }
}

// Configure Kover code coverage exclusions
kover {
    reports {
        filters {
            excludes {
                // UI dialogs that require IntelliJ Platform
                classes(
                    "org.zhavoronkov.tokenpulse.ui.*Dialog",
                    "org.zhavoronkov.tokenpulse.ui.*Dialog\$*",
                    "org.zhavoronkov.tokenpulse.ui.TokenPulseConfigurable",
                    "org.zhavoronkov.tokenpulse.ui.TokenPulseConfigurable\$*",
                    "org.zhavoronkov.tokenpulse.ui.TokenPulseStatusBarWidget",
                    "org.zhavoronkov.tokenpulse.ui.TokenPulseStatusBarWidget\$*",
                    "org.zhavoronkov.tokenpulse.ui.TokenPulseNotifier",
                    "org.zhavoronkov.tokenpulse.ui.TokenPulseNotifier\$*",
                    "org.zhavoronkov.tokenpulse.ui.*TableModel",
                    "org.zhavoronkov.tokenpulse.ui.*TableModel\$*",
                    "org.zhavoronkov.tokenpulse.ui.ProgressBarRenderer",
                    "org.zhavoronkov.tokenpulse.ui.ProgressBarRenderer\$*"
                )
                // Platform services with IntelliJ dependencies
                classes(
                    "org.zhavoronkov.tokenpulse.service.BalanceRefreshService",
                    "org.zhavoronkov.tokenpulse.service.BalanceRefreshService\$*",
                    "org.zhavoronkov.tokenpulse.service.HttpClientService",
                    "org.zhavoronkov.tokenpulse.service.HttpClientService\$*",
                    "org.zhavoronkov.tokenpulse.settings.TokenPulseSettingsService",
                    "org.zhavoronkov.tokenpulse.settings.TokenPulseSettingsService\$*",
                    "org.zhavoronkov.tokenpulse.settings.CredentialsStore",
                    "org.zhavoronkov.tokenpulse.settings.CredentialsStore\$*"
                )
                // OAuth/CLI components with external dependencies
                classes(
                    "org.zhavoronkov.tokenpulse.provider.openai.chatgpt.ChatGptOAuthManager",
                    "org.zhavoronkov.tokenpulse.provider.openai.chatgpt.ChatGptOAuthManager\$*",
                    "org.zhavoronkov.tokenpulse.provider.openai.chatgpt.CodexAppServerClient",
                    "org.zhavoronkov.tokenpulse.provider.openai.chatgpt.CodexAppServerClient\$*",
                    "org.zhavoronkov.tokenpulse.provider.anthropic.claudecode.ClaudeCliExecutor",
                    "org.zhavoronkov.tokenpulse.provider.anthropic.claudecode.ClaudeCliExecutor\$*",
                    "org.zhavoronkov.tokenpulse.provider.anthropic.claudecode.ClaudeCliUsageExtractor",
                    "org.zhavoronkov.tokenpulse.provider.anthropic.claudecode.ClaudeCliUsageExtractor\$*"
                )
                // Startup activities
                classes(
                    "org.zhavoronkov.tokenpulse.startup.*"
                )
                // Actions
                classes(
                    "org.zhavoronkov.tokenpulse.actions.*"
                )
            }
        }
    }
}
