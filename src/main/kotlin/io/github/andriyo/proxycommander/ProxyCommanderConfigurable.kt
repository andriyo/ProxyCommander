package io.github.andriyo.proxycommander

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.ui.TextBrowseFolderListener
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import java.io.File
import javax.swing.JComponent

/**
 * Application-level settings page shown under Settings > Tools > Proxy Commander. Replaces the
 * former standalone dialog so the configuration is searchable and lives where users expect it.
 */
class ProxyCommanderConfigurable : SearchableConfigurable {

    private val settings = ProxyCommanderSettingsService.getInstance()

    private val portField = JBTextField()
    private val adbPathField = TextFieldWithBrowseButton()
    private val resetTimeCheckbox = JBCheckBox("Reset device clock on connect (aligns the device clock to host time)")

    override fun getId(): String = "io.github.andriyo.proxycommander.settings"

    override fun getDisplayName(): String = "Proxy Commander"

    override fun createComponent(): JComponent {
        adbPathField.addBrowseFolderListener(
            TextBrowseFolderListener(
                FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor()
                    .withTitle("Select adb Executable")
                    .withDescription("Choose adb executable from Android SDK platform-tools (optional)")
            )
        )
        reset()

        val panel = FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("Port:"), portField)
            .addLabeledComponent(JBLabel("ADB Path (optional):"), adbPathField)
            .addComponent(JBLabel("Leave ADB Path empty to use \$ADB, autodetect from the Android SDK, or fall back to adb from PATH."))
            .addComponent(resetTimeCheckbox)
            .addComponentFillVertically(javax.swing.JPanel(), 0)
            .panel
        panel.border = JBUI.Borders.empty(8)
        return panel
    }

    override fun isModified(): Boolean {
        val config = settings.getConfig()
        return portField.text.trim() != config.port.toString() ||
            adbPathField.text.trim() != config.adbPath ||
            resetTimeCheckbox.isSelected != config.resetTimeOnConnect
    }

    override fun apply() {
        val port = portField.text.trim().toIntOrNull()
            ?: throw ConfigurationException("Port must be a number.")
        if (port !in VALID_PORT_RANGE) {
            throw ConfigurationException("Port must be between ${VALID_PORT_RANGE.first} and ${VALID_PORT_RANGE.last}.")
        }

        val adbPath = adbPathField.text.trim()
        if (adbPath.isNotEmpty()) {
            val adbFile = File(adbPath)
            if (!adbFile.exists()) {
                throw ConfigurationException("ADB path does not exist.")
            }
            if (!adbFile.isFile) {
                throw ConfigurationException("ADB path must point to the adb executable file.")
            }
            if (!isWindowsHost() && !adbFile.canExecute()) {
                throw ConfigurationException("ADB path is not executable.")
            }
        }

        settings.updateConfig(port = port, adbPath = adbPath, resetTimeOnConnect = resetTimeCheckbox.isSelected)
        // Pick up a possibly-changed port/adb path in the running watcher.
        ProxyCommanderReconnectService.getInstance().refreshTracking()
    }

    override fun reset() {
        val config = settings.getConfig()
        portField.text = config.port.toString()
        adbPathField.text = config.adbPath
        resetTimeCheckbox.isSelected = config.resetTimeOnConnect
    }
}
