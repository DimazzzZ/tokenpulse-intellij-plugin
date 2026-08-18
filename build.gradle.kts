import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel

plugins {
    id("java")
    kotlin("jvm") version "2.1.20"
    id("io.gitlab.arturbosch.detekt") version "1.23.8"
    id("org.jetbrains.kotlinx.kover") version "0.9.1"
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = project.findProperty("pluginGroup") ?: "org.zhavoronkov.tokenpulse"
version = project.findProperty("pluginVersion") ?: "0.1.0"

// Read into a local first: referencing `version` inside the task action would
// capture the Project itself, which the configuration cache cannot serialize
// ("cannot serialize object of type 'DefaultProject'") and which was the only
// thing blocking configuration cache for this build.
val pluginVersionValue = version.toString()

tasks.processResources {
    // Re-bind to a local inside the task configuration block: a top-level
    // `val` in a .kts script is a field on the script object, so referencing
    // it directly from the action lambda below captures the script itself
    // ("cannot serialize Gradle script object references"). Capturing a plain
    // local does not.
    val version = pluginVersionValue
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
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0") {
        // Exclude the transitive kotlinx-coroutines-core-jvm — IntelliJ's
        // bundled lib/util-8.jar ships a patched version (1.10.1-intellij-5)
        // that includes `runBlockingWithParallelismCompensation`. If the plain
        // 1.9.0 core JAR lands on the classpath, the PathClassLoader resolves
        // `kotlinx.coroutines.BuildersKt` from it (missing the method) before
        // reaching util-8.jar, causing NoSuchMethodError during tearDown.
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core-jvm")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-bom")
    }
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
            // Three ways to pick what to verify against, fastest first:
            //  -PverifierLocalIde=/path/to/IDE.app  an already-installed IDE
            //                                       (local loop; no download)
            //  -PverifierIdes=IU-2025.3.6[,...]     an explicit, pinned set
            //                                       (PR CI; one IDE is enough
            //                                       to catch API breakage)
            //  neither                              recommended(), i.e. every
            //                                       supported line — thorough
            //                                       but several GB, so it is
            //                                       reserved for releases.
            val localIde = project.findProperty("verifierLocalIde") as String?
            val verifierIdesProperty = project.findProperty("verifierIdes") as String?
            val pinnedIdes = verifierIdesProperty
                ?.split(',')
                ?.map(String::trim)
                ?.filter(String::isNotEmpty)
                .orEmpty()

            // Fail loudly rather than quietly widening scope: if the property
            // was supplied but yields nothing (empty, blank, or a stray comma),
            // silently falling through to recommended() would turn a typo into
            // a multi-GB sweep of every supported line.
            if (verifierIdesProperty != null && pinnedIdes.isEmpty()) {
                throw GradleException(
                    "-PverifierIdes was supplied but resolved to no IDE notations " +
                        "(got \"$verifierIdesProperty\"). Pass e.g. -PverifierIdes=IU-2026.2.1, " +
                        "or omit it entirely to verify against recommended()."
                )
            }

            when {
                localIde != null -> local(localIde)
                // IntelliJ Platform Gradle Plugin 2.18.1 removed the
                // `ides(List<String>)` overload. Explicit, pinned notations
                // (e.g. IU-2025.3.6) now go through `create(Provider<List<String>>)`,
                // whose default configure block parses each notation into a
                // (type, version) installer dependency.
                pinnedIdes.isNotEmpty() -> create(providers.provider { pinnedIdes })
                else -> recommended()
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

// SARIF (for GitHub Code Scanning) is produced by the main `detekt` task
// itself — a second Detekt task re-analyzing src/main/kotlin just doubled the
// work in the lint job for the same findings.
//
// Note this deliberately WIDENS code-scanning scope: the removed `detektSarif`
// task carried exclude("**/test/**", "**/*Test.kt"), whereas `detekt` analyses
// test sources too. That is wanted — the first run under this config caught a
// real LoopWithTooManyJumpStatements in DslCommentHtmlGuardTest — but it does
// mean code-scanning alerts can now originate from src/test.
tasks.named<io.gitlab.arturbosch.detekt.Detekt>("detekt") {
    reports {
        // Only what something actually consumes: sarif -> GitHub code scanning,
        // txt -> the PR-summary script, html -> the uploaded artifact humans read.
        sarif.required.set(true)
        txt.required.set(true)
        html.required.set(true)
        xml.required.set(false)
    }
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

    // Configure Detekt tasks. Detekt runs inside the Gradle daemon, so it
    // depends on gradle/gradle-daemon-jvm.properties pinning that daemon to
    // Java 21 — on a JDK 26 daemon it aborts with a bare "> 26.0.2".
    // ignoreFailures is deliberately NOT set: lint gates the build.
    withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
        jvmTarget = "21"
    }

    // buildSearchableOptions launches a headless IDE (~23s) purely to index the
    // plugin's settings so individual options are findable in Settings search.
    // Re-enabled after upgrading IntelliJ Platform Gradle Plugin 2.3.0 → 2.18.1.
    // The plugin 2.3.0 generated a broken coroutines javaagent JAR that caused
    // SIGABRT at JVM startup on macOS aarch64 with JBR 21.0.11 and IDE 2025.3.6.
    // Plugin 2.18.1 fixes the agent generation, so buildSearchableOptions now works.

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

// Serial task for IntelliJ Platform tests that need the shared TestApplication.
//
// Uses the official `intellijPlatformTesting.testIde { ... }` API from the
// IntelliJ Platform Gradle plugin (2.18.1). This registers a `TestIdeTask` —
// a Gradle `Test` subtype the plugin fully wires with:
//   • the 9-step classpath order that puts `intellijPlatformClasspathConfiguration`
//     (bundled `lib/util-8.jar`, which carries IntelliJ's patched coroutines
//     with `runBlockingWithParallelismCompensation`) BEFORE the resolved
//     testRuntimeClasspath — so the plain `kotlinx-coroutines-core-jvm:1.9.0`
//     (transitive of `kotlinx-coroutines-test`) can't shadow IntelliJ's
//     patched `BuildersKt`
//   • `intelliJPlatformTestRuntimeFixClasspathConfiguration`
//     (see YouTrack IJPL-180516)
//   • sandbox directories, IDE home path, `coroutines-javaagent.jar`
//   • the IntelliJ Java launcher
// Handrolling this with `register<Test>("platformTest")` (previous approach)
// missed the classpath-fix step, which surfaced as
//   NoSuchMethodError: kotlinx.coroutines.BuildersKt.runBlockingWithParallelismCompensation
// during `BasePlatformTestCase.tearDown()`.
//
// Bonus: `TestIdeTask` is configuration-cache compatible, so the previous
// `notCompatibleWithConfigurationCache(...)` opt-out is no longer needed.
intellijPlatformTesting {
    testIde {
        register("platformTest") {
            task {
                description = "Runs IntelliJ Platform tests that need the shared TestApplication."
                group = "verification"

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
