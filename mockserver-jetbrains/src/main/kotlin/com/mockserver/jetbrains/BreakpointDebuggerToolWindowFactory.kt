package com.mockserver.jetbrains

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel

/**
 * The **In-IDE HTTP Debugger** tool window (Phase 5 of the editor-extensions roadmap):
 * pause traffic flowing THROUGH MockServer at a request matcher, inspect the paused
 * REQUEST/RESPONSE, and resolve it — Continue / Modify / Abort — over the frozen
 * callback WebSocket, entirely within IntelliJ Community.
 *
 * **Prerequisite (stated in the panel):** breakpoints fire only on proxied/forwarded
 * exchanges, matched mock responses, and the unmatched-404 path. The developer must
 * point their app at MockServer first; this is not a JVM attach.
 *
 * Scope: REQUEST/RESPONSE pause/inspect/modify (ABORT is REQUEST-phase only, per
 * `docs/code/breakpoints.md`). Per-frame stream editing (RESPONSE_STREAM /
 * INBOUND_STREAM) is also handled here: when the server pushes a paused stream frame
 * it appears in the Live Streams list, where it can be Continued, Modified (with a
 * new Base64 payload), or Dropped.
 */
class BreakpointDebuggerToolWindowFactory : ToolWindowFactory {
    /**
     * See MockServerDashboardToolWindowFactory.init for why this sets a spaced stripe title.
     */
     override fun init(toolWindow: ToolWindow) {
        toolWindow.stripeTitle = "MockServer Debugger"
    }


    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = BreakpointDebuggerPanel(project)
        Disposer.register(toolWindow.disposable, panel)
        val content = ContentFactory.getInstance().createContent(panel.component, "Debugger", false)
        toolWindow.contentManager.addContent(content)
    }
}

/**
 * The debugger UI + lifecycle. Owns a single [BreakpointWsClient] (opened on Connect),
 * a list of paused exchanges, and a detail/decision pane. Network I/O runs off the EDT
 * (the WS listener thread, or a pooled thread for the matcher REST calls); every UI
 * mutation is marshalled back onto the EDT via [runOnEdt].
 */
class BreakpointDebuggerPanel(private val project: Project) : Disposable {

    private val root = JPanel(BorderLayout())

    private val statusLabel = JBLabel("Disconnected", AllIcons.Debugger.Db_muted_breakpoint, JBLabel.LEFT)
    private val pathField = JBTextField(".*", 20)
    private val methodField = JBTextField("", 6)
    private val requestPhase = JCheckBox("Request", true)
    private val responsePhase = JCheckBox("Response", true)

    private val connectButton = JButton("Connect")
    private val setBreakpointButton = JButton("Set Breakpoint")
    private val clearButton = JButton("Clear Breakpoints")

    private val pausedModel = DefaultListModel<BreakpointProtocol.PausedExchange>()
    private val pausedList = JList(pausedModel)

    private val detailArea = JBTextArea()

    private val continueButton = JButton("Continue")
    private val modifyButton = JButton("Modify…")
    private val abortButton = JButton("Abort")

    // Live Streams: paused stream frames (RESPONSE_STREAM / INBOUND_STREAM).
    private val streamModel = DefaultListModel<BreakpointProtocol.PausedStreamFrame>()
    private val streamList = JList(streamModel)
    private val streamContinueButton = JButton("Continue")
    private val streamModifyButton = JButton("Modify…")
    private val streamDropButton = JButton("Drop")

    private var wsClient: BreakpointWsClient? = null
    private val registeredMatcherIds = mutableListOf<String>()

    val component: JComponent get() = root

    init {
        buildUi()
        wireActions()
        updateDecisionButtons(null)
        updateStreamButtons(null)
    }

    private fun buildUi() {
        root.border = JBUI.Borders.empty(8)

        val header = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
        header.add(statusLabel.apply { alignmentX = Component.LEFT_ALIGNMENT })
        header.add(JBLabel(
            "Breakpoints fire only on traffic flowing through MockServer — proxied/forwarded " +
                "exchanges, matched mock responses, and the unmatched-404 path."
        ).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            font = font.deriveFont(font.size2D - 1f)
        })

        val matcherRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), JBUI.scale(4))).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            add(JBLabel("Method:")); add(methodField)
            add(JBLabel("Path regex:")); add(pathField)
            add(requestPhase); add(responsePhase)
        }
        val buttonRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), JBUI.scale(4))).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            add(connectButton); add(setBreakpointButton); add(clearButton)
        }
        header.add(matcherRow)
        header.add(buttonRow)
        root.add(header, BorderLayout.NORTH)

        // Center: paused list (left) + detail/decision (right).
        pausedList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        pausedList.cellRenderer = PausedExchangeRenderer()
        val listScroll = JBScrollPane(pausedList).apply { preferredSize = Dimension(260, 200) }

        detailArea.isEditable = false
        detailArea.font = Font(Font.MONOSPACED, Font.PLAIN, detailArea.font.size)
        val detailScroll = JBScrollPane(detailArea)

        val decisionRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), JBUI.scale(4))).apply {
            add(JBLabel("Decision:").apply { font = font.deriveFont(Font.BOLD) })
            add(continueButton); add(modifyButton); add(abortButton)
        }
        val rightPanel = JPanel(BorderLayout()).apply {
            add(detailScroll, BorderLayout.CENTER)
            add(decisionRow, BorderLayout.SOUTH)
        }

        val split = com.intellij.openapi.ui.Splitter(false, 0.4f).apply {
            firstComponent = listScroll
            secondComponent = rightPanel
        }

        // Live Streams panel (below the buffered exchanges): paused stream frames.
        streamList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        streamList.cellRenderer = StreamFrameRenderer()
        val streamScroll = JBScrollPane(streamList).apply { preferredSize = Dimension(260, 120) }
        val streamDecisionRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), JBUI.scale(4))).apply {
            add(JBLabel("Live Streams:").apply { font = font.deriveFont(Font.BOLD) })
            add(streamContinueButton); add(streamModifyButton); add(streamDropButton)
        }
        val streamPanel = JPanel(BorderLayout()).apply {
            add(streamScroll, BorderLayout.CENTER)
            add(streamDecisionRow, BorderLayout.SOUTH)
        }

        val verticalSplit = com.intellij.openapi.ui.Splitter(true, 0.7f).apply {
            firstComponent = split
            secondComponent = streamPanel
        }
        root.add(verticalSplit, BorderLayout.CENTER)
    }

    private fun wireActions() {
        connectButton.addActionListener { if (wsClient == null) connect() else disconnect() }
        setBreakpointButton.addActionListener { setBreakpoint() }
        clearButton.addActionListener { clearBreakpoints() }
        pausedList.addListSelectionListener {
            val selected = pausedList.selectedValue
            detailArea.text = selected?.let(::renderDetail) ?: ""
            updateDecisionButtons(selected)
        }
        continueButton.addActionListener { resolveContinue() }
        modifyButton.addActionListener { resolveModify() }
        abortButton.addActionListener { resolveAbort() }
        streamList.addListSelectionListener { updateStreamButtons(streamList.selectedValue) }
        streamContinueButton.addActionListener { resolveStreamFrame(BreakpointProtocol.StreamFrameAction.CONTINUE) }
        streamModifyButton.addActionListener { resolveStreamModify() }
        streamDropButton.addActionListener { resolveStreamFrame(BreakpointProtocol.StreamFrameAction.DROP) }
        setBreakpointButton.isEnabled = false
        clearButton.isEnabled = false
    }

    // ---- connection ----------------------------------------------------

    private fun connect() {
        val port = MockServerSettings.getInstance().effectivePort()
        val client = BreakpointWsClient(
            port = port,
            onPaused = { exchange -> runOnEdt(project) { pausedModel.addElement(exchange) } },
            onStreamFrame = { frame -> runOnEdt(project) { streamModel.addElement(frame) } },
            onState = { connected, message ->
                runOnEdt(project) {
                    if (connected) {
                        statusLabel.text = "Connected (clientId ${wsClient?.clientId?.takeLast(8) ?: "?"})"
                        statusLabel.icon = AllIcons.Debugger.Db_set_breakpoint
                        connectButton.text = "Disconnect"
                        setBreakpointButton.isEnabled = true
                        clearButton.isEnabled = true
                    } else {
                        statusLabel.text = if (message != null) "Disconnected — $message" else "Disconnected"
                        statusLabel.icon = AllIcons.Debugger.Db_muted_breakpoint
                        connectButton.text = "Connect"
                        setBreakpointButton.isEnabled = false
                        clearButton.isEnabled = false
                        wsClient = null
                        registeredMatcherIds.clear()
                        streamModel.clear()
                        updateStreamButtons(null)
                    }
                }
            },
        )
        wsClient = client
        statusLabel.text = "Connecting to localhost:$port…"
        // buildAsync runs off the EDT; failures surface via onState.
        client.connect().exceptionally {
            runOnEdt(project) {
                MockServerNotifier.notify(
                    project,
                    "Could not open the breakpoint WebSocket on localhost:$port. " +
                        "Is MockServer running there?",
                    com.intellij.notification.NotificationType.ERROR
                )
            }
            null
        }
    }

    private fun disconnect() {
        wsClient?.close()
        wsClient = null
    }

    // ---- matcher registration -----------------------------------------

    private fun setBreakpoint() {
        val client = wsClient ?: return
        val phases = buildSet {
            if (requestPhase.isSelected) add(BreakpointProtocol.Phase.REQUEST)
            if (responsePhase.isSelected) add(BreakpointProtocol.Phase.RESPONSE)
        }
        if (phases.isEmpty()) {
            MockServerNotifier.notify(project, "Select at least one phase (Request and/or Response).", com.intellij.notification.NotificationType.WARNING)
            return
        }
        val definition = com.google.gson.JsonObject().apply {
            val method = methodField.text.trim()
            if (method.isNotEmpty()) addProperty("method", method)
            val path = pathField.text.trim().ifEmpty { ".*" }
            addProperty("path", path)
        }
        val baseUrl = MockServerRestClient.buildBaseUrl(MockServerSettings.getInstance().effectivePort())
        val request = BreakpointProtocol.buildRegisterMatcherRequest(
            baseUrl, definition.toString(), phases, client.clientId
        )
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val result = MockServerRestClient.send(request)
                runOnEdt(project) {
                    if (result.ok) {
                        val id = BreakpointProtocol.parseRegisteredId(result.body)
                        if (id != null) registeredMatcherIds.add(id)
                        statusLabel.text = "Breakpoint set on ${methodField.text.trim().ifEmpty { "*" }} ${pathField.text.trim().ifEmpty { ".*" }} (${phases.joinToString("/") { it.name }})"
                    } else {
                        MockServerNotifier.notify(project, "MockServer returned ${result.status}: ${result.body}", com.intellij.notification.NotificationType.ERROR)
                    }
                }
            } catch (ex: Exception) {
                runOnEdt(project) { MockServerNotifier.notify(project, "Failed to set breakpoint: ${ex.message}", com.intellij.notification.NotificationType.ERROR) }
            }
        }
    }

    private fun clearBreakpoints() {
        val baseUrl = MockServerRestClient.buildBaseUrl(MockServerSettings.getInstance().effectivePort())
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                MockServerRestClient.send(BreakpointProtocol.buildClearMatchersRequest(baseUrl))
            } catch (_: Exception) {
                // best-effort; the server also clears on disconnect/reset.
            }
            runOnEdt(project) {
                registeredMatcherIds.clear()
                statusLabel.text = if (wsClient != null) "Breakpoints cleared" else "Disconnected"
            }
        }
    }

    // ---- decisions -----------------------------------------------------

    private fun resolveContinue() {
        val exchange = pausedList.selectedValue ?: return
        val reply = if (exchange.phase == BreakpointProtocol.Phase.REQUEST) {
            BreakpointProtocol.buildContinueRequestReply(exchange)
        } else {
            BreakpointProtocol.buildContinueResponseReply(exchange)
        }
        sendAndRemove(exchange, reply)
    }

    private fun resolveModify() {
        val exchange = pausedList.selectedValue ?: return
        val isRequest = exchange.phase == BreakpointProtocol.Phase.REQUEST
        val seed = if (isRequest) exchange.requestJson else (exchange.responseJson ?: "{}")
        val edited = Messages.showMultilineInputDialog(
            project,
            if (isRequest) "Edit the request to forward (CONTINUE if unchanged):" else "Edit the response to write to the client:",
            if (isRequest) "Modify Request" else "Modify Response",
            seed,
            Messages.getQuestionIcon(),
            null
        ) ?: return
        val reply = try {
            if (isRequest) BreakpointProtocol.buildModifyRequestReply(exchange, edited)
            else BreakpointProtocol.buildModifyResponseReply(exchange, edited)
        } catch (ex: IllegalArgumentException) {
            MockServerNotifier.notify(project, ex.message ?: "Invalid edit.", com.intellij.notification.NotificationType.ERROR)
            return
        }
        sendAndRemove(exchange, reply)
    }

    private fun resolveAbort() {
        val exchange = pausedList.selectedValue ?: return
        if (exchange.phase != BreakpointProtocol.Phase.REQUEST) return
        val reply = BreakpointProtocol.buildAbortReply(exchange)
        sendAndRemove(exchange, reply)
    }

    private fun sendAndRemove(exchange: BreakpointProtocol.PausedExchange, reply: String) {
        val client = wsClient
        if (client == null || !client.connected) {
            MockServerNotifier.notify(project, "The breakpoint WebSocket is not connected — cannot resolve.", com.intellij.notification.NotificationType.ERROR)
            return
        }
        try {
            client.sendDecision(reply)
        } catch (ex: Exception) {
            MockServerNotifier.notify(project, "Failed to send decision: ${ex.message}", com.intellij.notification.NotificationType.ERROR)
            return
        }
        pausedModel.removeElement(exchange)
        detailArea.text = ""
        updateDecisionButtons(null)
    }

    // ---- stream-frame decisions ---------------------------------------

    private fun resolveStreamFrame(action: BreakpointProtocol.StreamFrameAction, bodyBase64: String? = null) {
        val frame = streamList.selectedValue ?: return
        val reply = try {
            BreakpointProtocol.buildStreamFrameReply(frame, action, bodyBase64)
        } catch (ex: IllegalArgumentException) {
            MockServerNotifier.notify(project, ex.message ?: "Invalid stream-frame decision.", com.intellij.notification.NotificationType.ERROR)
            return
        }
        sendStreamDecisionAndRemove(frame, reply)
    }

    private fun resolveStreamModify() {
        val frame = streamList.selectedValue ?: return
        val edited = Messages.showMultilineInputDialog(
            project,
            "Base64-encoded replacement bytes for this frame:",
            "Modify Stream Frame",
            frame.body ?: "",
            Messages.getQuestionIcon(),
            null
        ) ?: return
        if (edited.isBlank()) {
            MockServerNotifier.notify(project, "A Base64 body is required to MODIFY a stream frame.", com.intellij.notification.NotificationType.WARNING)
            return
        }
        resolveStreamFrame(BreakpointProtocol.StreamFrameAction.MODIFY, edited.trim())
    }

    private fun sendStreamDecisionAndRemove(frame: BreakpointProtocol.PausedStreamFrame, reply: String) {
        val client = wsClient
        if (client == null || !client.connected) {
            MockServerNotifier.notify(project, "The breakpoint WebSocket is not connected — cannot resolve.", com.intellij.notification.NotificationType.ERROR)
            return
        }
        try {
            client.sendDecision(reply)
        } catch (ex: Exception) {
            MockServerNotifier.notify(project, "Failed to send stream decision: ${ex.message}", com.intellij.notification.NotificationType.ERROR)
            return
        }
        streamModel.removeElement(frame)
        updateStreamButtons(null)
    }

    private fun updateStreamButtons(frame: BreakpointProtocol.PausedStreamFrame?) {
        val enabled = frame != null
        streamContinueButton.isEnabled = enabled
        streamModifyButton.isEnabled = enabled
        streamDropButton.isEnabled = enabled
    }

    // ---- rendering -----------------------------------------------------

    private fun updateDecisionButtons(exchange: BreakpointProtocol.PausedExchange?) {
        val enabled = exchange != null
        continueButton.isEnabled = enabled
        modifyButton.isEnabled = enabled
        // ABORT is REQUEST-phase only.
        abortButton.isEnabled = enabled && exchange?.phase == BreakpointProtocol.Phase.REQUEST
    }

    private fun renderDetail(exchange: BreakpointProtocol.PausedExchange): String {
        val builder = StringBuilder()
        builder.append("Phase: ").append(exchange.phase.name).append('\n')
        builder.append("Request: ").append(exchange.method ?: "?").append(' ').append(exchange.path ?: "?").append('\n')
        exchange.breakpointId?.let { builder.append("Breakpoint: ").append(it).append('\n') }
        builder.append("\n--- REQUEST ---\n").append(exchange.requestJson)
        if (exchange.responseJson != null) {
            builder.append("\n\n--- RESPONSE ---\n").append(exchange.responseJson)
        }
        return builder.toString()
    }

    private class PausedExchangeRenderer : javax.swing.DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean
        ): Component {
            val component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            if (value is BreakpointProtocol.PausedExchange) {
                text = "[${value.phase.name}] ${value.method ?: "?"} ${value.path ?: "?"}"
                icon = if (value.phase == BreakpointProtocol.Phase.REQUEST) AllIcons.Actions.Upload else AllIcons.Actions.Download
            }
            return component
        }
    }

    private class StreamFrameRenderer : javax.swing.DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean
        ): Component {
            val component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            if (value is BreakpointProtocol.PausedStreamFrame) {
                val phase = value.phase ?: "STREAM"
                val seq = value.sequenceNumber?.let { "#$it" } ?: ""
                val target = "${value.requestMethod ?: ""} ${value.requestPath ?: ""}".trim()
                text = "[$phase] $seq ${if (target.isNotEmpty()) target else value.streamId ?: ""}".trim()
                icon = if (value.direction == "INBOUND") AllIcons.Actions.Upload else AllIcons.Actions.Download
            }
            return component
        }
    }

    override fun dispose() {
        wsClient?.close()
        wsClient = null
    }
}
