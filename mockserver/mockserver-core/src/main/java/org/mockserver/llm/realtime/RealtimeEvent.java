package org.mockserver.llm.realtime;

/**
 * A single scripted realtime WebSocket event: the fully-rendered JSON text frame the mock will send to the
 * client, plus the delay (milliseconds) to wait before sending it. The codecs are pure and produce an ordered
 * {@code List<RealtimeEvent>}; the client-side builder maps each one onto a
 * {@link org.mockserver.model.WebSocketMessage} carrying the same delay.
 *
 * @param json        the JSON text of the event frame (already escaped; never {@code null})
 * @param delayMillis milliseconds to wait before sending this frame (0 = immediate)
 */
public record RealtimeEvent(String json, long delayMillis) {

    public static RealtimeEvent immediate(String json) {
        return new RealtimeEvent(json, 0L);
    }

    public static RealtimeEvent delayed(String json, long delayMillis) {
        return new RealtimeEvent(json, Math.max(0L, delayMillis));
    }
}
