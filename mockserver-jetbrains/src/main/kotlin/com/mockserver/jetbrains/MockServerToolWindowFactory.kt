package com.mockserver.jetbrains

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Bottom "MockServer" tool window — a launcher that surfaces every MockServer
 * action as a button so users don't have to hunt through the Tools > MockServer
 * menu. A status line at the top shows the configured target; the buttons are
 * grouped under bold Server / Editor actions / WASM headers that mirror the
 * Tools > MockServer menu sections.
 *
 * The editor-dependent actions are the registered [com.intellij.openapi.actionSystem.AnAction]
 * instances themselves (looked up by id via [ActionManager]) — firing them here is
 * identical to choosing them from the menu, so their editor/notification/threading
 * behaviour is reused verbatim rather than duplicated.
 */
class MockServerToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = createPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "MockServer", false)
        toolWindow.contentManager.addContent(content)
    }

    private fun createPanel(project: Project): JPanel {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.border = JBUI.Borders.empty(8)

        val statusLabel = JBLabel(statusText(), AllIcons.General.Web, JBLabel.LEFT).apply {
            alignmentX = Component.LEFT_ALIGNMENT
        }
        panel.add(statusLabel)
        panel.add(portRow(statusLabel))
        panel.add(sectionGap())

        panel.add(sectionHeader("Server"))
        panel.add(row().apply {
            add(button("Open Dashboard in IDE", AllIcons.Toolwindows.WebToolWindow) {
                ToolWindowManager.getInstance(project).getToolWindow("MockServerDashboard")?.activate(null)
            })
            add(button("Open Dashboard (Browser)", AllIcons.General.Web) {
                com.intellij.ide.BrowserUtil.browse(MockServerSettings.getInstance().dashboardUrl())
            })
            add(actionButton("Start (Docker)", "MockServer.StartDocker", project, AllIcons.Actions.Execute))
            add(actionButton("Start (binary)", "MockServer.StartBinary", project, AllIcons.Actions.Execute))
            add(actionButton("Stop (binary)", "MockServer.StopBinary", project, AllIcons.Actions.Suspend))
            add(actionButton("Reset", "MockServer.Reset", project, AllIcons.Actions.GC))
        })

        panel.add(sectionGap())
        panel.add(sectionHeader("Editor actions (use the active file)"))
        panel.add(row().apply {
            add(actionButton("Load Expectations", "MockServer.LoadExpectations", project, AllIcons.Actions.Upload))
            add(actionButton("Save Recorded", "MockServer.SaveRecordedExpectations", project, AllIcons.Actions.Download))
            add(actionButton("Generate From OpenAPI", "MockServer.GenerateFromOpenApi", project, AllIcons.Actions.Compile))
            add(actionButton("Send Test Request", "MockServer.SendRequest", project, AllIcons.Actions.Lightning))
            add(actionButton("Show Drift Report", "MockServer.ShowDriftReport", project, AllIcons.Actions.Diff))
            add(actionButton("Find Requests by Trace", "MockServer.FindByTrace", project, AllIcons.Actions.Find))
            add(actionButton("View Trace in Backend", "MockServer.ViewTrace", project, AllIcons.General.Web))
        })

        panel.add(sectionGap())
        panel.add(sectionHeader("WASM"))
        panel.add(row().apply {
            add(actionButton("Upload WASM Module", "MockServer.UploadWasm", project, AllIcons.Actions.Upload))
            add(actionButton("List WASM Modules", "MockServer.ListWasm", project, AllIcons.Actions.ListFiles))
        })

        return panel
    }

    private fun statusText(): String =
        "MockServer · localhost:${MockServerSettings.getInstance().effectivePort()}"

    /**
     * An inline "Port:" field bound to the single source of truth, the persistent
     * [MockServerSettings] port. The field is prefilled from the saved setting and
     * commits on Enter and on focus lost: a value that parses as an integer in
     * 1..65535 is persisted back into the setting and the [statusLabel] is refreshed;
     * any invalid value is silently reverted to the current saved value (never
     * persisted). Because Start (Docker), the dashboard, and every REST action read
     * [MockServerSettings.effectivePort] at click time, they automatically pick up the
     * new value with no further wiring.
     */
    private fun portRow(statusLabel: JBLabel): JPanel {
        val field = JBTextField(MockServerSettings.getInstance().effectivePort().toString(), 6)

        fun revert() {
            field.text = MockServerSettings.getInstance().effectivePort().toString()
        }

        fun commit() {
            val parsed = field.text.trim().toIntOrNull()?.takeIf { it in 1..65535 }
            if (parsed == null) {
                revert()
                return
            }
            MockServerSettings.getInstance().state.port = parsed
            field.text = parsed.toString()
            statusLabel.text = statusText()
        }

        // Enter commits.
        field.addActionListener { commit() }
        // Focus lost commits (and normalises / reverts).
        field.addFocusListener(object : FocusAdapter() {
            override fun focusLost(e: FocusEvent) = commit()
        })

        return row().apply {
            add(JBLabel("Port:"))
            add(field)
        }
    }

    private fun sectionHeader(text: String): JComponent =
        JBLabel(text).apply {
            font = font.deriveFont(Font.BOLD)
            alignmentX = Component.LEFT_ALIGNMENT
        }

    private fun row(): JPanel {
        val flow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), JBUI.scale(4)))
        flow.alignmentX = Component.LEFT_ALIGNMENT
        flow.border = BorderFactory.createEmptyBorder()
        return flow
    }

    private fun sectionGap(): JComponent =
        JPanel().apply {
            preferredSize = JBUI.size(0, 8)
            isOpaque = false
        }

    private fun button(text: String, icon: Icon?, onClick: () -> Unit): JButton {
        val b = JButton(text)
        if (icon != null) b.icon = icon
        b.addActionListener { onClick() }
        return b
    }

    /**
     * A button that fires the registered action with [actionId]. The action is
     * resolved at click time via [ActionManager] and invoked with a [SimpleDataContext]
     * carrying the project and the currently selected text editor, so the
     * editor-dependent actions (Load/Save/Generate/SendRequest) resolve `e.project`
     * and `e.getData(CommonDataKeys.EDITOR)` exactly as they do from the menu — the
     * action's own validation handles the "no editor open" case.
     *
     * The action is fired via the platform's most stable, non-deprecated primitives so
     * the Plugin Verifier stays green across the whole supported IDE range: an
     * [AnActionEvent] is built from the action and the [SimpleDataContext] with
     * [AnActionEvent.createEvent] at place [ActionPlaces.TOOLWINDOW_CONTENT], the action's
     * presentation is refreshed via [com.intellij.openapi.actionSystem.AnAction.update],
     * and — only when it reports itself enabled and visible — it is run via
     * [com.intellij.openapi.actionSystem.AnAction.actionPerformed]. The `ActionUtil`
     * helpers are intentionally avoided: their event-based overloads are deprecated on
     * 2025.2+ while their non-deprecated successors (`performAction`/`updateAction`) do
     * not exist on the 2024.3 (243) compile target, so no single `ActionUtil` call is
     * clean across all verified IDEs.
     *
     * Because [update] is called here directly on the EDT (it is not dispatched through
     * the platform's `getActionUpdateThread()`), actions wired to a tool-window button via
     * this helper MUST keep their `update()` cheap and EDT-safe — no PSI or index access,
     * no `ActionUpdateThread.BGT` dependency. An action needing background-thread update
     * should be added to the tool window with a direct `button { }` lambda instead.
     */
    private fun actionButton(text: String, actionId: String, project: Project, icon: Icon?): JButton {
        val b = JButton(text)
        if (icon != null) b.icon = icon
        b.addActionListener {
            val action = ActionManager.getInstance().getAction(actionId) ?: return@addActionListener
            val editor = FileEditorManager.getInstance(project).selectedTextEditor
            val builder = SimpleDataContext.builder()
                .add(CommonDataKeys.PROJECT, project)
                .add(PlatformDataKeys.CONTEXT_COMPONENT, b)
            if (editor != null) {
                builder.add(CommonDataKeys.EDITOR, editor)
            }
            val event = AnActionEvent.createEvent(
                action,
                builder.build(),
                null,
                ActionPlaces.TOOLWINDOW_CONTENT,
                ActionUiKind.NONE,
                null
            )
            action.update(event)
            if (event.presentation.isEnabledAndVisible) {
                action.actionPerformed(event)
            }
        }
        return b
    }
}
