plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.21"
    id("org.jetbrains.intellij.platform") version "2.16.0"
}

group = "io.github.andriyo"
version = "1.3.0"

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
            <h3>1.3.0</h3>
            <ul>
              <li>New: <b>status bar widget</b> showing the proxy port and connected-device count; click it to open the Devices dialog.</li>
              <li>New: the <b>Devices dialog updates live</b> as devices connect and disconnect, instead of needing a manual Refresh.</li>
              <li>Changed: settings are now <b>application-wide</b> (Settings &rarr; Tools &rarr; Proxy Commander) and shared across projects, with a single background watcher instead of one per open project. Existing per-project settings are migrated automatically.</li>
              <li>Better: the host-proxy connection test now recognizes <b>mitmproxy, Burp, and Fiddler</b> (not just Charles/Proxyman) and reports any other process listening on the port.</li>
              <li>Better: auto-reconnect no longer blocks device tracking while a device boots; device details are read with a single <code>getprop</code> call.</li>
              <li>Notifications are now a registered group, configurable under Settings &rarr; Notifications.</li>
            </ul>
            <h3>1.2.0</h3>
            <ul>
              <li>New: <b>auto-reconnect</b> remembered devices when they reappear. A background <code>adb track-devices</code> watcher restores reverse/proxy without a manual action.</li>
              <li>New: <b>Devices dialog</b> for per-device proxy/test/auto-connect management.</li>
              <li>New: <b>host-proxy connection test</b> that verifies the device can reach your local Charles/Proxyman/mitmproxy.</li>
              <li>New: notification offering to connect a newly-appeared unremembered device (devices present at startup form a silent baseline).</li>
              <li>Better: clock reset now uses <code>time_detector</code> to align directly to host wall-clock time, so it works on offline workstations without NTP reachability.</li>
              <li>Better: broader <code>adb</code> autodetection from the Android SDK.</li>
              <li>Better: clearer action labels; shorter variants on the Running Devices toolbar.</li>
            </ul>
            <h3>1.1.1</h3>
            <ul>
              <li>Refreshed plugin icon.</li>
              <li>Clearer action labels: "Connect Active Emulator and Clear Others' Proxy" and "Select Emulator and Disconnect Others".</li>
              <li>Plugin description and README updates.</li>
            </ul>
            <h3>1.1.0</h3>
            <ul>
              <li>New: reset the device clock on every connect by forcing an NTP resync (toggles <code>auto_time</code> and <code>auto_time_zone</code>). Useful when emulators or devices have drifted.</li>
              <li>New setting "Reset device clock on connect" (Tools &rarr; Proxy Commander &rarr; Settings...), enabled by default.</li>
              <li>Replaced the deprecated <code>TextFieldWithBrowseButton.addBrowseFolderListener</code> 4-arg overload with the current API.</li>
            </ul>
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
