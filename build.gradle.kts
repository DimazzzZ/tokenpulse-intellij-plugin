import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel

plugins {
    id("java")
    kotlin("jvm") version "2.1.20"
    id("io.gitlab.arturbosch.detekt") version "1.23.8"
    id("org.jetbrains.kotlinx.kover") version "0.9.1"
    id("org.jetbrains.intellij.platform") version "2.3.0"
}

group = project.findProperty("pluginGroup") ?: "org.zhavoronkov.tokenpulse"
version = project.findProperty("pluginVersion") ?: "0.1.0"

tasks.processResources {
    filesMatching("tokenpulse.properties") {
        expand("pluginVersion" to version)
    }
}

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
    // Bridges JUnit 3/4-style tests (e.g. IntelliJ's BasePlatformTestCase,
    // which extends junit.framework.TestCase) onto the JUnit Platform so
    // `platformTest` actually discovers and runs TokenPulseSmokeTest.
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.11.4")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    // pty4j is provided by IntelliJ platform at runtime - no explicit dependency needed

    detektPlugins("io.gitlab.arturbosch.detekt:detekt-formatting:1.23.8")

    // IntelliJ Platform dependencies (2.x plugin style)
    intellijPlatform {
        // Keep this fallback in step with the pluginSinceBuild fallback below:
        // compiling against an older SDK than we declare compatibility with
        // would fail on APIs that only exist in the newer one.
        val platformVersion = project.findProperty("platformVersion") as String? ?: "2025.3.6"
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
            sinceBuild = project.findProperty("pluginSinceBuild") as String? ?: "253"
            untilBuild = provider { null }  // No upper bound - compatible with all future versions
        }
    }

    pluginVerification {
        // The verifier only FAILS on COMPATIBILITY_PROBLEMS by default — it
        // merely prints everything else. That is why 0.4.0 shipped with 2
        // scheduled-for-removal usages even though release CI ran
        // verifyPlugin. Fail the build on the whole non-public-API surface
        // instead: JetBrains penalizes plugins that reach into internal or
        // experimental platform API, and the plugin is currently clean, so
        // this is a guard against regressions rather than a migration.
        //
        // MISSING_DEPENDENCIES and NOT_DYNAMIC are deliberately NOT included:
        // the report already lists unavailable *optional* dependencies (e.g.
        // com.intellij.jetbrains.client) that we do not control, so they
        // would fail spuriously.
        failureLevel = listOf(
            FailureLevel.COMPATIBILITY_PROBLEMS,
            FailureLevel.DEPRECATED_API_USAGES,
            FailureLevel.SCHEDULED_FOR_REMOVAL_API_USAGES,
            FailureLevel.INTERNAL_API_USAGES,
            FailureLevel.EXPERIMENTAL_API_USAGES,
            FailureLevel.OVERRIDE_ONLY_API_USAGES,
            FailureLevel.NON_EXTENDABLE_API_USAGES,
        )

        ides {
            // Opt-in fast local loop: -PverifierLocalIde=/path/to/IDE.app verifies
            // against an installed IDE instead of downloading the recommended set.
            val localIde = project.findProperty("verifierLocalIde") as String?
            if (localIde != null) {
                local(localIde)
            } else {
                recommended()
            }
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

    buildUponDefaultConfig = true
    config.setFrom("$projectDir/config/detekt/detekt.yml")

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

    // buildSearchableOptions launches a headless IDE (~23s) purely to index the
    // plugin's settings so individual options are findable in Settings search.
    // Disabled outright: on the 2025.3.6 SDK its bundled JBR aborts at JVM
    // startup on macOS (SIGABRT in Threads::create_vm -> post_vm_initialized,
    // ~0.18s in, before any plugin code runs), so it breaks local release
    // builds and buys little — the TokenPulse settings PAGE is still reachable
    // from Settings search either way, only the individual option labels are
    // not indexed.
    named("buildSearchableOptions") {
        enabled = false
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
        // TokenPulseSmokeTest extends IntelliJ's BasePlatformTestCase — a
        // JUnit 3-style junit.framework.TestCase, run through the Vintage
        // engine, which does NOT honor Jupiter's @Tag (that's Jupiter-only;
        // Vintage only understands JUnit4 @Category). So the platform split
        // is done by class name instead of by tag; it needs a serial, shared
        // TestApplication fixture and runs in the `platformTest` task instead.
        filter {
            excludeTestsMatching("org.zhavoronkov.tokenpulse.TokenPulseSmokeTest")
        }
        systemProperty("tokenpulse.testMode", "true")

        // Pure-JVM unit tests (all but TokenPulseSmokeTest) parallelize safely.
        // GH ubuntu-latest has 4 vCPUs / 16 GB; 4 forks * 1 GB heap is safe.
        maxParallelForks = Runtime.getRuntime().availableProcessors().coerceAtMost(4)
        forkEvery = 100  // Recycle fork JVMs to bound native memory growth
        maxHeapSize = "1g"

        // Report test results even on failure
        reports {
            junitXml.required.set(true)
            html.required.set(true)
        }
    }

    // Serial task for IntelliJ Platform tests that need the shared TestApplication.
    register<Test>("platformTest") {
        description = "Runs IntelliJ Platform tests that need the shared TestApplication."
        group = "verification"

        // The IntelliJ Platform Gradle plugin only wires its sandbox/module
        // JVM args (IntelliJPlatformArgumentProvider, SandboxArgumentProvider,
        // the rearranged plugin/IDE classpath, a custom javaLauncher) onto the
        // task literally named "test". A separately `register<Test>(...)`
        // task does NOT get that configuration automatically — without it,
        // BasePlatformTestCase fails to bootstrap the IDE test application
        // (IllegalAccessError in UITestUtil). So reuse the already fully
        // configured "test" task's classpath/args/launcher here and just
        // retarget which tests run via `filter`.
        val testTask = named<Test>("test").get()
        // The reused jvmArgumentProviders/classpath resolve sandbox output
        // (prepareTestSandbox) at execution time without Gradle seeing it as
        // a task input, so the sandbox-prep dependency must be declared
        // explicitly here too.
        dependsOn("prepareTestSandbox")
        testClassesDirs = testTask.testClassesDirs
        classpath = testTask.classpath
        jvmArgumentProviders.addAll(testTask.jvmArgumentProviders)
        systemProperties = testTask.systemProperties
        javaLauncher.set(testTask.javaLauncher)

        useJUnitPlatform()
        filter {
            includeTestsMatching("org.zhavoronkov.tokenpulse.TokenPulseSmokeTest")
        }
        systemProperty("tokenpulse.testMode", "true")
        maxParallelForks = 1

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

    // Ensure the platform smoke test still runs as part of `check` / `build`.
    named("check") {
        dependsOn("platformTest")
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
                    "org.zhavoronkov.tokenpulse.ui.ProgressBarRenderer\$*",
                    "org.zhavoronkov.tokenpulse.ui.TokenPulseTooltipPanel",
                    "org.zhavoronkov.tokenpulse.ui.TokenPulseTooltipPanel\$*"
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
                    "org.zhavoronkov.tokenpulse.provider.anthropic.claudecode.ClaudeCliDetector",
                    "org.zhavoronkov.tokenpulse.provider.anthropic.claudecode.ClaudeCliDetector\$*"
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
