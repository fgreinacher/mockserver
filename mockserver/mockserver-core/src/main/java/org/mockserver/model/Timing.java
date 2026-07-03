package org.mockserver.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Objects;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class Timing {
    private Long requestStartedMillis;
    private Long connectionEstablishedMillis;
    private Long responseReceivedMillis;
    private Long connectionTimeInMillis;
    private Long timeToFirstByteInMillis;
    private Long totalTimeInMillis;
    // Injected-vs-real latency waterfall (additive): the portion of totalTimeInMillis that MockServer
    // deliberately injected, split by source, versus real connect/processing/upstream time. All optional.
    private Long injectedChaosLatencyMillis;
    private Long injectedDelayMillis;
    private Long breakpointHeldMillis;

    public static Timing timing() {
        return new Timing();
    }

    public Long getRequestStartedMillis() {
        return requestStartedMillis;
    }

    public Timing withRequestStartedMillis(Long requestStartedMillis) {
        this.requestStartedMillis = requestStartedMillis;
        return this;
    }

    public Long getConnectionEstablishedMillis() {
        return connectionEstablishedMillis;
    }

    public Timing withConnectionEstablishedMillis(Long connectionEstablishedMillis) {
        this.connectionEstablishedMillis = connectionEstablishedMillis;
        return this;
    }

    public Long getResponseReceivedMillis() {
        return responseReceivedMillis;
    }

    public Timing withResponseReceivedMillis(Long responseReceivedMillis) {
        this.responseReceivedMillis = responseReceivedMillis;
        return this;
    }

    public Long getConnectionTimeInMillis() {
        return connectionTimeInMillis;
    }

    public Timing withConnectionTimeInMillis(Long connectionTimeInMillis) {
        this.connectionTimeInMillis = connectionTimeInMillis;
        return this;
    }

    public Long getTimeToFirstByteInMillis() {
        return timeToFirstByteInMillis;
    }

    public Timing withTimeToFirstByteInMillis(Long timeToFirstByteInMillis) {
        this.timeToFirstByteInMillis = timeToFirstByteInMillis;
        return this;
    }

    public Long getTotalTimeInMillis() {
        return totalTimeInMillis;
    }

    public Timing withTotalTimeInMillis(Long totalTimeInMillis) {
        this.totalTimeInMillis = totalTimeInMillis;
        return this;
    }

    /**
     * @return the latency (in milliseconds) MockServer injected via a chaos-profile latency fault, or
     * {@code null} when no chaos latency was applied to this exchange.
     */
    public Long getInjectedChaosLatencyMillis() {
        return injectedChaosLatencyMillis;
    }

    public Timing withInjectedChaosLatencyMillis(Long injectedChaosLatencyMillis) {
        this.injectedChaosLatencyMillis = injectedChaosLatencyMillis;
        return this;
    }

    /**
     * @return the delay (in milliseconds) MockServer injected from the matched action's configured
     * {@code delay}, or {@code null} when the action had no delay.
     */
    public Long getInjectedDelayMillis() {
        return injectedDelayMillis;
    }

    public Timing withInjectedDelayMillis(Long injectedDelayMillis) {
        this.injectedDelayMillis = injectedDelayMillis;
        return this;
    }

    /**
     * @return how long (in milliseconds) the exchange was held paused at a response-phase breakpoint
     * before it was resumed, or {@code null} when no breakpoint held this exchange.
     */
    public Long getBreakpointHeldMillis() {
        return breakpointHeldMillis;
    }

    public Timing withBreakpointHeldMillis(Long breakpointHeldMillis) {
        this.breakpointHeldMillis = breakpointHeldMillis;
        return this;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        Timing timing = (Timing) o;
        return Objects.equals(requestStartedMillis, timing.requestStartedMillis) &&
            Objects.equals(connectionEstablishedMillis, timing.connectionEstablishedMillis) &&
            Objects.equals(responseReceivedMillis, timing.responseReceivedMillis) &&
            Objects.equals(connectionTimeInMillis, timing.connectionTimeInMillis) &&
            Objects.equals(timeToFirstByteInMillis, timing.timeToFirstByteInMillis) &&
            Objects.equals(totalTimeInMillis, timing.totalTimeInMillis) &&
            Objects.equals(injectedChaosLatencyMillis, timing.injectedChaosLatencyMillis) &&
            Objects.equals(injectedDelayMillis, timing.injectedDelayMillis) &&
            Objects.equals(breakpointHeldMillis, timing.breakpointHeldMillis);
    }

    @Override
    public int hashCode() {
        return Objects.hash(requestStartedMillis, connectionEstablishedMillis, responseReceivedMillis, connectionTimeInMillis, timeToFirstByteInMillis, totalTimeInMillis, injectedChaosLatencyMillis, injectedDelayMillis, breakpointHeldMillis);
    }
}
