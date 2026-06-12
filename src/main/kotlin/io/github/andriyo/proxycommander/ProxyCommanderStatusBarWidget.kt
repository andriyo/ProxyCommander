package io.github.andriyo.proxycommander

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory

class ProxyCommanderStatusBarWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = WIDGET_ID

    override fun getDisplayName(): String = "Proxy Commander"

    override fun isAvailable(project: Project): Boolean = true

    override fun createWidget(project: Project): StatusBarWidget = ProxyCommanderStatusBarWidget(project)

    override fun disposeWidget(widget: StatusBarWidget) {
        Disposer.dispose(widget)
    }

    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true

    companion object {
        const val WIDGET_ID = "ProxyCommanderStatusBarWidget"
    }
}

/**
 * Glanceable status of the proxy: configured port plus proxied/connected device counts, fed
 * cheaply from the watcher's latest snapshot so the widget never triggers adb polling of its own.
 * Clicking opens a popup with the plugin's actions.
 */
private class ProxyCommanderStatusBarWidget(private val project: Project) :
    StatusBarWidget, StatusBarWidget.MultipleTextValuesPresentation {

    private var statusBar: StatusBar? = null

    private val devicesListener = ProxyCommanderReconnectService.DevicesListener {
        ApplicationManager.getApplication().invokeLater(
            { statusBar?.updateWidget(ID()) },
            ModalityState.any()
        )
    }

    override fun ID(): String = ProxyCommanderStatusBarWidgetFactory.WIDGET_ID

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
        ProxyCommanderReconnectService.getInstance().addListener(devicesListener)
    }

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun getSelectedValue(): String {
        val port = ProxyCommanderSettingsService.getInstance().getConfig().port
        val service = ProxyCommanderReconnectService.getInstance()
        val connected = service.connectedSerials().size
        val proxied = service.proxiedSerials().size
        return "Proxy :$port ($proxied/$connected)"
    }

    override fun getTooltipText(): String {
        val service = ProxyCommanderReconnectService.getInstance()
        val connected = service.connectedSerials().size
        val proxied = service.proxiedSerials().size
        val devices = if (connected == 1) "device" else "devices"
        return "Proxy Commander — $proxied of $connected connected $devices proxied. Click for actions."
    }

    override fun getPopup(): JBPopup? {
        if (project.isDisposed) {
            return null
        }

        val actionManager = ActionManager.getInstance()
        val group = DefaultActionGroup()
        POPUP_ACTION_IDS.forEach { actionId ->
            if (actionId == null) {
                group.addSeparator()
            } else {
                actionManager.getAction(actionId)?.let(group::add)
            }
        }

        return JBPopupFactory.getInstance().createActionGroupPopup(
            "Proxy Commander",
            group,
            SimpleDataContext.getProjectContext(project),
            JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
            true
        )
    }

    override fun dispose() {
        ProxyCommanderReconnectService.getInstance().removeListener(devicesListener)
        statusBar = null
    }

    private companion object {
        // null marks a separator.
        val POPUP_ACTION_IDS = listOf(
            "ProxyCommander.KeepSelectedDeviceAction",
            null,
            "ProxyCommander.ConnectAllDevicesAction",
            "ProxyCommander.ConnectActiveEmulatorClearOthersProxyAction",
            "ProxyCommander.DisconnectAllDevicesAction",
            null,
            "ProxyCommander.SettingsAction"
        )
    }
}
