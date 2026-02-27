import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    kotlin("jvm") version "1.9.22"
    id("org.jetbrains.intellij.platform") version "2.11.0"
    id("io.gitlab.arturbosch.detekt") version "1.23.7"
}

group = project.findProperty("pluginGroup") ?: "org.zhavoronkov.tokenpulse"
version = project.findProperty("pluginVersion") ?: "0.1.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaUltimate(project.findProperty("platformVersion") as String? ?: "2023.3.4")
        testFramework(TestFrameworkType.Platform)
    }

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.10.1")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")

    detektPlugins("io.gitlab.arturbosch.detekt:detekt-formatting:1.23.7")
}

kotlin {
    jvmToolchain(17)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

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

    jvmTarget = "17"
    basePath = projectDir.absolutePath
}

tasks {
    patchPluginXml {
        sinceBuild.set(project.findProperty("pluginSinceBuild") as String? ?: "233")
        // untilBuild intentionally left unset – plugin is forward-compatible
    }

    signPlugin {
        certificateChain.set(providers.environmentVariable("CERTIFICATE_CHAIN"))
        privateKey.set(providers.environmentVariable("PRIVATE_KEY"))
        password.set(providers.environmentVariable("PRIVATE_KEY_PASSWORD"))
    }

    publishPlugin {
        token.set(providers.environmentVariable("PUBLISH_TOKEN"))
    }

    test {
        useJUnitPlatform()
        systemProperty("tokenpulse.testMode", "true")
    }

    withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
        jvmTarget = "17"
        ignoreFailures = false
    }
}

detekt {
    toolVersion = "1.23.7"
}
