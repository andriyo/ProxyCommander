plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.21"
    id("org.jetbrains.intellij.platform") version "2.16.0"
}

group = "io.github.andriyo"
version = "1.0.0"

val androidStudioVersion = providers.gradleProperty("androidStudioVersion").orElse("2024.2.1.11")
val androidStudioPath = providers.gradleProperty("androidStudioPath")

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.14.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    intellijPlatform {
        if (androidStudioPath.isPresent && androidStudioPath.get().isNotBlank()) {
            local(androidStudioPath)
        } else {
            androidStudio(androidStudioVersion)
        }
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)

        // Add plugin dependencies for compilation here.
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "242"
        }

        changeNotes = """
            <h3>1.0.0</h3>
            <ul>
              <li>Initial release.</li>
              <li>Bulk connect/disconnect of <code>adb reverse</code> and device HTTP proxy across all connected devices.</li>
              <li>Active-emulator detection from the Running Devices toolbar context.</li>
              <li>Action to keep one selected emulator connected and disconnect the rest.</li>
              <li>Configurable proxy port and ADB executable path.</li>
            </ul>
        """.trimIndent()
    }

    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }

    pluginVerification {
        ides {
            recommended()
        }
    }
}

tasks {
    // Set the JVM compatibility versions
    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }
    test {
        useJUnitPlatform()
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}
