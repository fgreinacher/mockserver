package com.mockserver.jetbrains

import com.intellij.ide.plugins.cl.PluginAwareClassLoader
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

/**
 * Application-level settings for the MockServer plugin: the Docker image,
 * container name, and port used by the Start/Dashboard actions.
 *
 * The Docker image defaults to `mockserver/mockserver:<this plugin's version>`,
 * so the image tag stays in lockstep with the plugin release and never drifts
 * from a hardcoded constant (the previous behaviour).
 */
@Service(Service.Level.APP)
@State(name = "MockServerSettings", storages = [Storage("mockserver.xml")])
class MockServerSettings : PersistentStateComponent<MockServerSettings.State> {

    class State {
        // Blank means "derive from the plugin version" (see effectiveImage()).
        @JvmField var dockerImage: String = ""
        @JvmField var containerName: String = DEFAULT_CONTAINER_NAME
        @JvmField var port: Int = DEFAULT_PORT
        // Trace-backend URL template with a {traceId} placeholder, e.g.
        // http://localhost:16686/trace/{traceId}. Blank disables the View Trace action's
        // browser hop (it then just surfaces the trace id).
        @JvmField var traceUrlTemplate: String = ""
        // Path to a locally-installed binary bundle for the Start (binary) action:
        // either the bin/mockserver launcher itself or the unpacked bundle directory.
        // Blank ⇒ download and cache the bundle matching the plugin version on first use.
        @JvmField var binaryPath: String = ""
    }

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    /** Explicit image override, or `mockserver/mockserver:<plugin-version>` when blank. */
    fun effectiveImage(): String =
        myState.dockerImage.trim().ifBlank { "mockserver/mockserver:${pluginVersion()}" }

    fun effectiveContainerName(): String =
        myState.containerName.trim().ifBlank { DEFAULT_CONTAINER_NAME }

    fun effectivePort(): Int =
        if (myState.port in 1..65535) myState.port else DEFAULT_PORT

    fun dashboardUrl(): String = "http://localhost:${effectivePort()}/mockserver/dashboard"

    /** The configured trace-backend URL template (trimmed), or blank when unset. */
    fun traceUrlTemplate(): String = myState.traceUrlTemplate.trim()

    /** The configured local binary-bundle path (trimmed), or blank to download on demand. */
    fun effectiveBinaryPath(): String = myState.binaryPath.trim()

    /**
     * Version of the binary bundle to download/use — the plugin's own version, so
     * the bundle stays in lockstep with the release (matching [effectiveImage]).
     */
    fun effectiveBinaryVersion(): String = pluginVersion()

    companion object {
        const val DEFAULT_CONTAINER_NAME = "mockserver-ide"
        const val DEFAULT_PORT = 1080

        fun getInstance(): MockServerSettings = service()

        /**
         * Plugin version, or "latest" if it cannot be resolved (e.g. outside the IDE).
         *
         * Resolved from this class's own plugin class loader, which implements
         * [PluginAwareClassLoader] and exposes the plugin descriptor — a public, stable
         * API across the supported platform range. Looking the descriptor up by id via
         * `PluginManagerCore.getPlugin(PluginId)` is avoided because that method is marked
         * `@ApiStatus.Internal` on newer platforms (the Plugin Verifier rejects it).
         */
        private fun pluginVersion(): String =
            try {
                (MockServerSettings::class.java.classLoader as? PluginAwareClassLoader)
                    ?.pluginDescriptor?.version ?: "latest"
            } catch (_: Throwable) {
                "latest"
            }
    }
}
