package org.mockserver.netty.mcp;

import java.util.function.LongSupplier;

public class McpSession {

    private final String sessionId;
    private final long createdAt;
    private volatile boolean initialized;
    private volatile long lastAccessedAt;
    private volatile String protocolVersion;
    private final LongSupplier clock;

    public McpSession(String sessionId) {
        this(sessionId, System::currentTimeMillis);
    }

    McpSession(String sessionId, LongSupplier clock) {
        this.sessionId = sessionId;
        this.clock = clock;
        this.createdAt = clock.getAsLong();
        this.lastAccessedAt = this.createdAt;
        this.initialized = false;
    }

    public String getSessionId() {
        return sessionId;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public boolean isInitialized() {
        return initialized;
    }

    public void markInitialized() {
        this.initialized = true;
    }

    public long getLastAccessedAt() {
        return lastAccessedAt;
    }

    /**
     * The MCP protocol version negotiated with the client during {@code initialize}
     * (see {@link McpRequestProcessor#negotiateProtocolVersion(String)}), or null before
     * initialize completes. Used to decide whether to emit version-specific response fields
     * (e.g. 2025-06-18 structured tool output) for this session.
     */
    public String getProtocolVersion() {
        return protocolVersion;
    }

    public void setProtocolVersion(String protocolVersion) {
        this.protocolVersion = protocolVersion;
    }

    public void touch() {
        this.lastAccessedAt = clock.getAsLong();
    }
}
