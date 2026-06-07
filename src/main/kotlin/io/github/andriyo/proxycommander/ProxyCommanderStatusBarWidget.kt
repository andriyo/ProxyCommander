package io.github.andriyo.proxycommander

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.util.Consumer
import java.awt.Component
import java.awt.event.MouseEvent

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
 * Glanceable status of the proxy: configured port plus the number of connected devices, fed cheaply
 * from the watcher's latest device snapshot. Clicking opens the Devices dialog. The text deliberately
 * avoids per-device proxy probing so the widget never triggers adb polling of its own.
 */
private class ProxyCommanderStatusBarWidget(private val project: Project) :
    StatusBarWidget, StatusBarWidget.TextPresentation {

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

    override fun getText(): String {
        val port = ProxyCommanderSettingsService.getInstance().getConfig().port
        val count = ProxyCommanderReconnectService.getInstance().connectedSerials().size
        return "Proxy :$port ($count)"
    }

    override fun getAlignment(): Float = Component.CENTER_ALIGNMENT

    override fun getTooltipText(): String {
        val count = ProxyCommanderReconnectService.getInstance().connectedSerials().size
        val devices = if (count == 1) "device" else "devices"
        return "Proxy Commander — $count $devices available. Click to manage."
    }

    override fun getClickConsumer(): Consumer<MouseEvent> = Consumer {
        if (!project.isDisposed) {
            ProxyCommanderUi.openDevicesDialog(project)
        }
    }

    override fun dispose() {
        ProxyCommanderReconnectService.getInstance().removeListener(devicesListener)
        statusBar = null
    }
}
