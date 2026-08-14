plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.21"
    id("org.jetbrains.intellij.platform") version "2.16.0"
}

group = "io.github.andriyo"
version = "1.4.1"

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
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
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
            <h3>1.4.1</h3>
            <ul>
              <li>Fixed: proxy and reverse changes are now transactional, verified, and rolled back safely when adb operations fail.</li>
              <li>Fixed: manual actions and auto-reconnect mutations now run in FIFO order; stale reconnect work can no longer overwrite newer device intent or status.</li>
              <li>Better: disconnect verifies direct device internet access using a real HTTP response and reports a non-fatal warning when connectivity cannot be confirmed.</li>
              <li>Fixed: <b>Connect Proxy to Current</b> no longer guesses a target when several devices are attached without active IDE context.</li>
              <li>Fixed: migration preserves a disabled clock-reset setting, and disposed projects/dialogs no longer receive late background UI updates.</li>
              <li>Better: release builds now validate the tag, plugin structure, compatibility, signature, license, and signed artifact before publication.</li>
            </ul>
            <h3>1.4.0</h3>
            <ul>
              <li>New: per-device <b>Unproxy</b> and <b>Forget</b> (disable auto-connect) buttons in the Devices dialog.</li>
              <li>New: the "new device" offer has a <b>Don't Offer Again</b> action; muted devices never trigger the offer again (connecting one unmutes it).</li>
              <li>New: <b>Connect Proxy to Current</b> now works with physical devices, not just emulators.</li>
              <li>New: the status bar widget shows <b>proxied/connected counts</b> (e.g. <code>Proxy :8888 (1/2)</code>) and opens a quick-action popup.</li>
              <li>Better: changing the port now cleans up reverse mappings left over from the previous port on connect/disconnect.</li>
              <li>Better: the host-proxy connection test can name the listening process on <b>Windows</b> too (netstat/tasklist).</li>
              <li>Better: device details load in parallel, so the Devices dialog opens faster with many devices.</li>
              <li>Better: the Devices dialog refreshes in place (no more list flashing), shows a colored proxy-state indicator per device, displays a subtle loading spinner in the status line, and remembers its size and position.</li>
              <li>Fixed: adb commands with large output (e.g. <code>getprop</code> on prop-heavy devices) no longer stall until the timeout.</li>
              <li>Fixed: the clock-reset step now reports honestly when <code>time_detector</code> is unsupported instead of claiming success.</li>
              <li>Fixed: migration from per-project settings now preserves a disabled "Reset device clock on connect".</li>
            </ul>
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
        certificateChainFile = layout.file(
            providers.environmentVariable("CERTIFICATE_CHAIN_FILE").map(::File)
        )
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
    jar {
        // Apache-2.0 redistribution requires the license to accompany the plugin artifact.
        from(rootProject.file("LICENSE")) {
            into("META-INF")
        }
    }
    test {
        useJUnitPlatform()
    }
    named("verifyPluginSignature") {
        // The verifier consumes the signed ZIP; make that producer/consumer relationship explicit
        // for Gradle 9 task validation and let the release workflow invoke one ordered pipeline.
        dependsOn("signPlugin")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}
