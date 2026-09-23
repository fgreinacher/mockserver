package com.mockserver.jetbrains

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.icons.AllIcons
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandler
import org.cef.handler.CefLoadHandlerAdapter
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JPanel

/**
 * Tool window that embeds the live MockServer dashboard inside the IDE using the
 * bundled JCEF (Chromium) engine.
 *
 * When JCEF is available (see [JcefSupport]) it hosts a [JBCefBrowser] pointed
 * at [MockServerSettings.dashboardUrl] with Reload / Open-in-Browser controls. When
 * JCEF is unavailable (e.g. a JRE without the JCEF runtime, or a remote/headless
 * environment) it degrades gracefully to a panel offering the external browser.
 *
 * The embedded browser is registered with the tool window's disposable so its native
 * Chromium process is released when the tool window is disposed (otherwise the process
 * and its memory leak).
 */
class MockServerDashboardToolWindowFactory : ToolWindowFactory {
    /**
     * The stripe label must contain a SPACE or the platform cannot wrap it: the stripe is
         * narrow, and an unbreakable CamelCase token like "MockServerDashboard" is simply clipped
         * ("MockServerDash..."). Every neighbouring plugin wraps for this reason - "AI Chat",
         * "Python Console", "Jupyter Variables". The tool window ID stays unchanged, because it is
         * referenced in code and persisted in the user's window layout; renaming it would move
         * their windows.
     */
     override fun init(toolWindow: ToolWindow) {
        toolWindow.stripeTitle = "MockServer Dashboard"
    }


    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val dashboardUrl = MockServerSettings.getInstance().dashboardUrl()
        val component = if (JcefSupport.isAvailable()) {
            createEmbeddedBrowser(toolWindow, dashboardUrl)
        } else {
            createUnsupportedPanel(dashboardUrl)
        }
        val content = ContentFactory.getInstance().createContent(component, "Dashboard", false)
        toolWindow.contentManager.addContent(content)
    }

    private fun createEmbeddedBrowser(toolWindow: ToolWindow, dashboardUrl: String): JPanel {
        val browser = JBCefBrowser.createBuilder().setUrl(dashboardUrl).build()
        // Release the native Chromium process when the tool window is disposed.
        Disposer.register(toolWindow.disposable, browser)

        // When MockServer is not running, the embedded Chromium would otherwise show a
        // raw ERR_CONNECTION_REFUSED page. Replace any load failure for the dashboard
        // URL with a friendly panel explaining how to start the server. errorCode 0 is
        // ERR_NONE (a normal load) and -3 is ERR_ABORTED (e.g. a reload superseding an
        // in-flight load) — neither should trigger the friendly page.
        browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadError(
                cefBrowser: CefBrowser?,
                frame: CefFrame?,
                errorCode: CefLoadHandler.ErrorCode?,
                errorText: String?,
                failedUrl: String?
            ) {
                if (frame?.isMain != true) return
                if (errorCode == CefLoadHandler.ErrorCode.ERR_NONE ||
                    errorCode == CefLoadHandler.ErrorCode.ERR_ABORTED
                ) {
                    return
                }
                browser.loadHTML(unreachableHtml(dashboardUrl), failedUrl ?: dashboardUrl)
            }
        }, browser.cefBrowser)

        val panel = JPanel(BorderLayout())

        val actionGroup = DefaultActionGroup().apply {
            add(object : AnAction("Reload", "Reload the dashboard", AllIcons.Actions.Refresh) {
                override fun actionPerformed(e: AnActionEvent) {
                    browser.cefBrowser.reload()
                }

                override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
            })
            add(object : AnAction("Open in Browser", "Open the dashboard in your default browser", AllIcons.General.Web) {
                override fun actionPerformed(e: AnActionEvent) {
                    BrowserUtil.browse(dashboardUrl)
                }

                override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
            })
        }
        val toolbar = ActionManager.getInstance()
            .createActionToolbar("MockServerDashboard", actionGroup, true)
        toolbar.targetComponent = panel
        panel.add(toolbar.component, BorderLayout.NORTH)
        panel.add(browser.component, BorderLayout.CENTER)
        return panel
    }

    /**
     * Friendly in-browser HTML shown when the dashboard URL cannot be reached
     * (typically because no MockServer is running on the configured port). The
     * page is themed for a dark IDE and tells the user how to recover. The "Retry"
     * link simply re-navigates to the dashboard URL, so it works again the moment a
     * server is started.
     */
    private fun unreachableHtml(dashboardUrl: String): String {
        val port = MockServerSettings.getInstance().effectivePort()
        return """
            <!DOCTYPE html>
            <html>
            <head><meta charset="utf-8"><title>MockServer</title></head>
            <body style="font-family: -apple-system, Segoe UI, sans-serif; background:#2b2b2b; color:#bbbbbb; margin:0; padding:48px; text-align:center;">
              <div style="max-width:520px; margin:0 auto;">
                <h2 style="color:#00bcd4; font-weight:600;">No MockServer running</h2>
                <p>Nothing is responding at <code style="color:#dddddd;">localhost:$port</code>.</p>
                <p>Start MockServer from the <b>MockServer</b> tool window (Start (Docker)),
                   or check the port under <b>Settings &gt; Tools &gt; MockServer</b>.</p>
                <p style="margin-top:32px;">
                  <a href="$dashboardUrl" style="color:#00bcd4;">Retry</a>
                </p>
              </div>
            </body>
            </html>
        """.trimIndent()
    }

    private fun createUnsupportedPanel(dashboardUrl: String): JPanel {
        val panel = JPanel(BorderLayout())
        panel.border = BorderFactory.createEmptyBorder(10, 10, 10, 10)

        val label = JBLabel(
            "<html>The embedded dashboard requires JCEF (the bundled Chromium engine), " +
                "which is not available in this IDE or runtime.<br>" +
                "Open the MockServer dashboard in your default browser instead.</html>"
        )
        panel.add(label, BorderLayout.NORTH)

        val buttonPanel = JPanel(FlowLayout(FlowLayout.LEFT))
        val openButton = JButton("Open in external browser")
        openButton.addActionListener { BrowserUtil.browse(dashboardUrl) }
        buttonPanel.add(openButton)
        panel.add(buttonPanel, BorderLayout.CENTER)
        return panel
    }
}
