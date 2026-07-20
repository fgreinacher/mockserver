package org.mockserver.serialization.model;

import org.junit.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;

/**
 * Layer B of the configuration-reachability guard: every RISKY configuration property must be
 * consciously classified as either enforcement-verified or enforcement-exempt.
 *
 * <h2>Why this exists</h2>
 * <p>{@link ConfigurationDTOTest} proves the {@code Configuration} &lt;-&gt; {@code ConfigurationDTO}
 * mirror is complete, but it never leaves that pair — a property can round-trip perfectly and still
 * never reach an enforcement site. Layer A (the bytecode call-site guard in {@code mockserver-netty})
 * catches the mechanical version of that failure. This layer catches the human version: a property
 * that nobody has ever checked actually does anything.
 *
 * <h2>The rule</h2>
 * <p>Enumeration is REFLECTIVE, so it cannot go stale — it reuses
 * {@link ConfigurationDTOTest#discoverProperties()}, the same source of truth as the round-trip guard.
 * Only the classification is manual. Every property matching {@link #RISK_HEURISTIC} must appear in
 * EXACTLY ONE of {@link #ENFORCEMENT_VERIFIED} or {@link #ENFORCEMENT_EXEMPT}.
 *
 * <p><strong>Omission fails closed.</strong> Adding a new {@code somethingEnabled} or {@code maxSomething}
 * property without classifying it breaks the build. That is the point: the default answer to "does this
 * new limit/flag actually do anything?" should be "prove it", not silence.
 *
 * <h2>Reading the two maps</h2>
 * <ul>
 *   <li>{@link #ENFORCEMENT_VERIFIED} — property name to the test that proves setting the value ON A
 *       CONFIGURATION INSTANCE changes observable behaviour. A getter round-trip does NOT qualify.</li>
 *   <li>{@link #ENFORCEMENT_EXEMPT} — property name to a documented reason. Reasons beginning
 *       {@code NO BEHAVIOURAL TEST YET} are acknowledged debt, not settled exemptions; they are
 *       deliberately greppable so the backlog is visible rather than buried.</li>
 * </ul>
 */
public class ConfigurationEnforcementClassificationTest {

    /**
     * Properties whose name suggests they gate, cap, or redact something — i.e. where being silently
     * unenforced is a correctness or security problem rather than a cosmetic one.
     */
    private static final Pattern RISK_HEURISTIC =
        Pattern.compile("^max.*|.*Enabled$|.*Budget.*|.*Threshold.*|.*Limit.*|^redact.*|^slo.*|^chaos.*");

    /**
     * Property to the test proving an instance-set value changes observable behaviour.
     */
    private static final Map<String, String> ENFORCEMENT_VERIFIED = new TreeMap<>();

    static {
        // ---- end-to-end through PUT /mockserver/configuration (Layer C) ----
        ENFORCEMENT_VERIFIED.put("maxRequestBodySize",
            "org.mockserver.configuration.ConfigurationRestApiEnforcementIntegrationTest#shouldEnforceMaxRequestBodySizeSetOverTheConfigurationEndpoint");
        ENFORCEMENT_VERIFIED.put("wasmEnabled",
            "org.mockserver.configuration.ConfigurationRestApiEnforcementIntegrationTest#shouldEnforceWasmEnabledSetOverTheConfigurationEndpoint");
        ENFORCEMENT_VERIFIED.put("redactSecretsInLog",
            "org.mockserver.configuration.ConfigurationRestApiEnforcementIntegrationTest#shouldEnforceRedactSecretsInLogSetOverTheConfigurationEndpoint");

        // ---- chaos / SLO ----
        ENFORCEMENT_VERIFIED.put("chaosAutoHaltEnabled",
            "org.mockserver.mock.action.http.ChaosAutoHaltMonitorTest#shouldHaltWhenEnabledOnConfigurationInstanceButDisabledStatically");
        ENFORCEMENT_VERIFIED.put("chaosAutoHaltErrorThreshold",
            "org.mockserver.mock.action.http.ChaosAutoHaltMonitorTest#shouldUseErrorThresholdFromConfigurationInstance");
        ENFORCEMENT_VERIFIED.put("chaosAutoHaltWindowMillis",
            "org.mockserver.mock.action.http.ChaosAutoHaltMonitorTest#shouldUseWindowMillisFromConfigurationInstance");
        ENFORCEMENT_VERIFIED.put("sloTrackingEnabled",
            "org.mockserver.slo.SloSampleStoreTest#shouldRecordWhenTrackingEnabledOnConfigurationInstance");
        ENFORCEMENT_VERIFIED.put("sloWindowMaxSamples",
            "org.mockserver.slo.SloSampleStoreTest#shouldBoundSamplesByMaxSamplesFromConfigurationInstance");
        ENFORCEMENT_VERIFIED.put("sloWindowRetentionMillis",
            "org.mockserver.slo.SloSampleStoreTest#shouldEvictByRetentionFromConfigurationInstance");

        // ---- limits and budgets ----
        // wired independently on master (GrpcFrameCodec / IncrementalGrpcFrameDecoder both prefer the
        // instance value and fall back to the static store), with a behavioural test that proves an
        // instance-set limit actually rejects an oversized frame.
        ENFORCEMENT_VERIFIED.put("maxGrpcMessageSize",
            "org.mockserver.grpc.GrpcMessageSizeConfigurationTest#shouldEnforceTheLimitFromAConfigurationInstance");
        ENFORCEMENT_VERIFIED.put("llmCostBudgetUsd",
            "org.mockserver.configuration.ConfigurationInstanceEnforcementTest#shouldEnforceLlmCostBudgetFromConfigurationInstance");
        ENFORCEMENT_VERIFIED.put("maxLlmConversationBodySize",
            "org.mockserver.configuration.ConfigurationInstanceEnforcementTest#shouldEnforceMaxLlmConversationBodySizeFromConfigurationInstance");
        ENFORCEMENT_VERIFIED.put("rateLimitMaxNamedQuotas",
            "org.mockserver.configuration.ConfigurationInstanceEnforcementTest#shouldEnforceRateLimitMaxNamedQuotasFromConfigurationInstance");
        ENFORCEMENT_VERIFIED.put("maxEventLogSizeInBytes",
            "org.mockserver.log.MockServerEventLogCaptureTest#shouldCapRetainedEntriesByByteBudget");
        // both resized in place on the running server by HttpState#applyConfigurationUpdate (the event-log
        // deque and the expectation store respectively), so a PUT /mockserver/configuration that lowers them
        // evicts immediately — proven end-to-end here rather than merely round-tripped through a getter.
        ENFORCEMENT_VERIFIED.put("maxLogEntries",
            "org.mockserver.mock.HttpStateConfigurationUpdateTest#shouldResizeEventLogWhenMaxLogEntriesReduced");
        ENFORCEMENT_VERIFIED.put("maxExpectations",
            "org.mockserver.mock.HttpStateConfigurationUpdateTest#shouldResizeExpectationStoreWhenMaxExpectationsReduced");
        ENFORCEMENT_VERIFIED.put("maxLoggedBodyBytes",
            "org.mockserver.log.MockServerEventLogCaptureTest#shouldPersistFullBodyToHookWhileTruncatingInMemoryCopy");
        ENFORCEMENT_VERIFIED.put("maximumNumberOfRequestToReturnInVerificationFailure",
            "org.mockserver.log.MockServerEventLogRequestLogEntryVerificationTest#shouldFailVerificationWithLimitedReturnedRequestsViaConfiguration");
        ENFORCEMENT_VERIFIED.put("maxSocketTimeoutInMillis",
            "org.mockserver.httpclient.netty.NettyHttpClientConnectionPoolTest#shouldTimeOutAStalledReusedPooledConnectionInsteadOfHanging");
        ENFORCEMENT_VERIFIED.put("forwardProxyCircuitBreakerFailureThreshold",
            "org.mockserver.mock.action.http.HttpForwardActionResilienceTest#shouldOpenCircuitAfterConsecutiveFailuresAndFailFast");

        // ---- feature gates ----
        ENFORCEMENT_VERIFIED.put("closestMatchHintEnabled",
            "org.mockserver.mock.action.http.HttpActionHandlerClosestMatchHintTest#whenEnabled_nearMiss_headerNamesExpectationAndField");
        ENFORCEMENT_VERIFIED.put("clusterEnabled",
            "org.mockserver.state.infinispan.ClusteredTwoNodeTest#expectationWrittenOnNodeAShouldBeVisibleOnNodeB");
        ENFORCEMENT_VERIFIED.put("clusterSharedTimesEnabled",
            "org.mockserver.mock.RequestMatchersStateBackendTest#clusteredLimitedTimesFallsBackToNodeLocalWhenSharedTimesDisabled");
        ENFORCEMENT_VERIFIED.put("controlPlaneAuditEnabled",
            "org.mockserver.mock.HttpStateAuditEndpointTest#returnsEntriesNewestFirst");
        ENFORCEMENT_VERIFIED.put("controlPlaneAuthorizationEnabled",
            "org.mockserver.mock.HttpStateAuthorizationTest#authorizationDisabledDoesNotForbid");
        ENFORCEMENT_VERIFIED.put("dnsEnabled",
            "org.mockserver.netty.EpollTransportIntegrationTest#shouldBootWithDnsEnabledAndNativeTransport");
        ENFORCEMENT_VERIFIED.put("forwardConnectionPoolEnabled",
            "org.mockserver.httpclient.netty.NettyHttpClientConnectionPoolTest#shouldReuseSingleConnectionForSequentialBurstWhenPoolingEnabled");
        ENFORCEMENT_VERIFIED.put("forwardProxyCircuitBreakerEnabled",
            "org.mockserver.mock.action.http.ForwardCircuitBreakerTest#shouldAllowAllRequestsWhenDisabled");
        ENFORCEMENT_VERIFIED.put("forwardProxyHttp2Enabled",
            "org.mockserver.mock.action.http.HttpForwardActionHttp2Test#shouldPreserveHttp2WhenFlagEnabledAndInboundIsHttp2");
        ENFORCEMENT_VERIFIED.put("grpcBidiStreamingEnabled",
            "org.mockserver.netty.http3.Http3GrpcStreamingIntegrationTest#shouldHandleBidiStreamingGrpcOverHttp3");
        ENFORCEMENT_VERIFIED.put("http2Enabled",
            "org.mockserver.socket.tls.NettySslContextFactoryTest#shouldNotAdvertiseHttp2ViaAlpnWhenHttp2Disabled");
        ENFORCEMENT_VERIFIED.put("http3ConnectUdpEnabled",
            "org.mockserver.netty.http3.Http3ConnectUdpIntegrationTest#shouldRelayWhenTargetIsInAllowlist");
        ENFORCEMENT_VERIFIED.put("llmMetricsEnabled",
            "org.mockserver.metrics.LlmMetricsTest#shouldNotIncrementWhenLlmMetricsDisabled");
        ENFORCEMENT_VERIFIED.put("loadGenerationEnabled",
            "org.mockserver.mock.HttpStateLoadScenarioEndpointTest#startReturns403WhenGenerationDisabled");
        ENFORCEMENT_VERIFIED.put("metricsEnabled",
            "org.mockserver.metrics.MetricsHandlerTest#shouldReflectOriginWhenMetricsEnabled");
        ENFORCEMENT_VERIFIED.put("perExpectationMetricsEnabled",
            "org.mockserver.metrics.PerExpectationMetricsTest#propertyOnRegistersTheCounter");
        ENFORCEMENT_VERIFIED.put("redactSecretsInRecordedExpectations",
            "org.mockserver.mock.RecordedExpectationRedactionHttpStateTest#shouldRedactRecordedSecretsWhenFlagOn");
        ENFORCEMENT_VERIFIED.put("streamingResponsesEnabled",
            "org.mockserver.codec.StreamingAwareHttpObjectAggregatorTest#shouldStreamWhenDisableResponseStreamingAttributeIsNotSet");
        ENFORCEMENT_VERIFIED.put("transparentProxyEnabled",
            "org.mockserver.netty.proxy.TransparentProxyHandlerTest#shouldSetRemoteSocketWhenOriginalDstResolved");
    }

    /**
     * Property to a documented reason it is not enforcement-verified.
     *
     * <p>Two distinct kinds of entry live here, and the distinction matters:
     * <ul>
     *   <li><strong>Structural exemptions</strong> — the property genuinely cannot have a
     *       "change it at runtime and observe the difference" test: it is consumed once at bind time,
     *       it is init-only by design, or it has no server-side read site at all.</li>
     *   <li><strong>{@code NO BEHAVIOURAL TEST YET}</strong> — acknowledged debt. Layer A confirms the
     *       value is read through the {@link org.mockserver.configuration.Configuration} instance, so it
     *       is REACHABLE, but nothing yet proves the behaviour it is supposed to drive. Discharge these
     *       by writing the test and moving the entry to {@link #ENFORCEMENT_VERIFIED}.</li>
     * </ul>
     */
    private static final Map<String, String> ENFORCEMENT_EXEMPT = new TreeMap<>();

    static {
        // ---- structural: consumed during pipeline/server construction, not re-read per request ----
        ENFORCEMENT_EXEMPT.put("maxChunkSize",
            "consumed when the HTTP codec is installed during pipeline construction; there is no per-request "
                + "read site to observe a runtime change at");
        ENFORCEMENT_EXEMPT.put("maxHeaderSize",
            "consumed when the HTTP codec is installed during pipeline construction; there is no per-request "
                + "read site to observe a runtime change at");
        ENFORCEMENT_EXEMPT.put("maxInitialLineLength",
            "consumed when the HTTP codec is installed during pipeline construction; there is no per-request "
                + "read site to observe a runtime change at");
        ENFORCEMENT_EXEMPT.put("maxResponseBodySize",
            "consumed when the relay/forward pipeline is constructed; the inbound analogue "
                + "maxRequestBodySize is the one reachable over the control plane and IS verified end-to-end");
        ENFORCEMENT_EXEMPT.put("grpcEnabled",
            "bind-time protocol selection: decides which handlers are installed as the server binds, so it "
                + "cannot take effect on an already-bound server");
        ENFORCEMENT_EXEMPT.put("mcpEnabled",
            "bind-time protocol selection: decides which handlers are installed as the server binds, so it "
                + "cannot take effect on an already-bound server");

        // ---- structural: init-only by design, flagged as such by the server itself ----
        ENFORCEMENT_EXEMPT.put("maxWebSocketExpectations",
            "init-only property: pushed into the static LocalCallbackRegistry once by the HttpState "
                + "constructor and reported through warnInitOnlyProperty, so changing it post-start is "
                + "explicitly unsupported");

        // ---- structural: no server-side enforcement site exists ----
        ENFORCEMENT_EXEMPT.put("dashboardAnalyticsEnabled",
            "no Java read site exists: the value is only serialized out over the configuration endpoint and "
                + "consumed by the dashboard UI, so there is no server behaviour to assert");
        ENFORCEMENT_EXEMPT.put("maxFutureTimeoutInMillis",
            "drives await timeouts in the test-support module (mockserver-integration-testing), not server "
                + "request handling; it has no production enforcement site");

        // ---- acknowledged debt: reachable (Layer A) but behaviour not yet asserted ----
        ENFORCEMENT_EXEMPT.put("connectionLifecycleChaosEnabled",
            "NO BEHAVIOURAL TEST YET — read through the Configuration instance, but no test asserts that "
                + "disabling it suppresses lifecycle fault injection");
        ENFORCEMENT_EXEMPT.put("driftDetectionEnabled",
            "NO BEHAVIOURAL TEST YET — DriftDetectionConfigTest re-implements the production gate rather than "
                + "exercising HttpActionHandler, so it would pass even if the call site read the static store");
        ENFORCEMENT_EXEMPT.put("driftAlertSeverityThreshold",
            "NO BEHAVIOURAL TEST YET — no test asserts the threshold changes which drift alerts are emitted");
        ENFORCEMENT_EXEMPT.put("driftAlertWebhookEnabled",
            "NO BEHAVIOURAL TEST YET — no test asserts the flag gates drift webhook delivery");
        ENFORCEMENT_EXEMPT.put("driftResponseTimeThresholdMs",
            "NO BEHAVIOURAL TEST YET — no test asserts the threshold changes which responses are flagged as drifted");
        ENFORCEMENT_EXEMPT.put("driftSemanticAnalysisEnabled",
            "NO BEHAVIOURAL TEST YET — no test asserts the flag gates semantic drift analysis");
        ENFORCEMENT_EXEMPT.put("maxStreamingCaptureBytes",
            "NO BEHAVIOURAL TEST YET — no test asserts streaming capture is truncated at an instance-set limit");
        ENFORCEMENT_EXEMPT.put("slowRequestThresholdMillis",
            "NO BEHAVIOURAL TEST YET — only a getter round-trip exists; the production read site is "
                + "NettyHttpClient, and nothing asserts the slow-request classification follows the value");

        // ---- acknowledged debt: newly reachable via Configuration/ConfigurationDTO, enforcement sites
        // ---- still read the static ConfigurationProperties store and are wired separately
        ENFORCEMENT_EXEMPT.put("llmInferUsageEnabled",
            "NO BEHAVIOURAL TEST YET — reachable through the Configuration instance and the configuration "
                + "DTO, but the token-usage inference gate is not yet asserted to follow an instance-set value");
        ENFORCEMENT_EXEMPT.put("llmSemanticMatchingEnabled",
            "NO BEHAVIOURAL TEST YET — reachable through the Configuration instance and the configuration "
                + "DTO, but no test asserts the semanticMatch predicate gate follows an instance-set value");
        ENFORCEMENT_EXEMPT.put("otelMetricsEnabled",
            "NO BEHAVIOURAL TEST YET — reachable through the Configuration instance and the configuration "
                + "DTO, but no test asserts OTLP metric export follows an instance-set value");
        ENFORCEMENT_EXEMPT.put("otelTracesEnabled",
            "NO BEHAVIOURAL TEST YET — reachable through the Configuration instance and the configuration "
                + "DTO, but no test asserts GenAI span emission follows an instance-set value");
        ENFORCEMENT_EXEMPT.put("prometheusRemoteWriteEnabled",
            "NO BEHAVIOURAL TEST YET — reachable through the Configuration instance and the configuration "
                + "DTO, but no test asserts remote-write export follows an instance-set value");
    }

    /**
     * Write-only credential properties: settable over the control plane, masked on read.
     *
     * <p>None of these match {@link #RISK_HEURISTIC}, so the classification guard above would say
     * nothing about them. That silence is the gap this constant closes: a credential's security
     * property is not "is it enforced?" but "is it masked on read?", which is asserted by
     * {@link ConfigurationDTOCredentialMaskingTest}. The list is duplicated there deliberately —
     * if a new credential is added to one and not the other,
     * {@link #shouldKeepTheWriteOnlyCredentialListInSyncWithTheMaskingGuard()} fails.
     *
     * <p><strong>KNOWN GAP — deliberately not listed here.</strong> This masking is keyed on the
     * whole-property name, so it only covers properties whose <em>entire value</em> is a credential.
     * Two properties carry secrets <em>embedded inside</em> a structured value and are therefore NOT
     * masked — {@code GET /mockserver/configuration} discloses them in clear once set:
     * <ul>
     *   <li>{@code llmBackendsConfig} — a JSON document whose backend entries each hold an
     *       {@code apiKey} (parsed at {@code LlmBackendResolver} {@code node.path("apiKey")}).</li>
     *   <li>{@code prometheusRemoteWriteHeaders} — an arbitrary header list that typically carries
     *       {@code Authorization} or an {@code Api-Key}.</li>
     * </ul>
     * They are intentionally absent from this set because listing them here would make the masking
     * guard assert a masking that does not happen. The current (unmasked) behaviour is pinned by
     * {@link ConfigurationDTOCredentialMaskingTest#embeddedValueCredentialsAreNotYetMaskedOnRead()}
     * so a future reader sees it and it cannot silently regress to "assumed masked". Per-field /
     * per-header redaction is a planned follow-up (see docs/plans); when it lands, move these two in
     * here, add them to the masking guard, and flip that pinning test to assert redaction.
     */
    static final java.util.Set<String> WRITE_ONLY_CREDENTIALS = new TreeSet<>(java.util.Arrays.asList(
        "llmApiKey",
        "prometheusRemoteWriteBasicAuthPassword",
        "prometheusRemoteWriteBearerToken"
    ));

    @Test
    public void shouldKeepTheWriteOnlyCredentialListInSyncWithTheMaskingGuard() {
        assertThat("the write-only credential list here and the one the masking guard actually asserts "
                + "against have diverged — a credential is masked-but-unclassified or classified-but-unmasked",
            new TreeSet<>(ConfigurationDTOCredentialMaskingTest.WRITE_ONLY_CREDENTIALS),
            is(WRITE_ONLY_CREDENTIALS));
    }

    @Test
    public void shouldNotClassifyWriteOnlyCredentialsAsEnforcementExempt() {
        // a credential must never be quietly parked in ENFORCEMENT_EXEMPT as a way of avoiding the
        // masking guard — the two mechanisms are complementary, not alternatives
        java.util.Set<String> parked = new TreeSet<>(WRITE_ONLY_CREDENTIALS);
        parked.retainAll(ENFORCEMENT_EXEMPT.keySet());

        assertThat("write-only credentials found in ENFORCEMENT_EXEMPT — they are covered by the masking "
                + "guard instead, so an exemption entry here only hides them: " + parked,
            parked, is(empty()));
    }

    @Test
    public void shouldClassifyEveryRiskyConfigurationPropertyAsVerifiedOrExempt() {
        List<String> riskyProperties = new ArrayList<>();
        for (ConfigurationDTOTest.PropertyAccessor accessor : ConfigurationDTOTest.discoverProperties()) {
            if (RISK_HEURISTIC.matcher(accessor.name).matches()) {
                riskyProperties.add(accessor.name);
            }
        }

        // sanity: reflective enumeration must actually be finding the risky set, so a regression that
        // silently stops discovering properties cannot make this guard pass vacuously
        assertThat("risk heuristic should match a substantial set of Configuration properties",
            riskyProperties.size(), greaterThan(40));

        List<String> unclassified = new ArrayList<>();
        List<String> doubleClassified = new ArrayList<>();
        for (String property : riskyProperties) {
            boolean verified = ENFORCEMENT_VERIFIED.containsKey(property);
            boolean exempt = ENFORCEMENT_EXEMPT.containsKey(property);
            if (verified && exempt) {
                doubleClassified.add(property);
            } else if (!verified && !exempt) {
                unclassified.add(property);
            }
        }

        assertThat("risky configuration properties with NO enforcement classification. Each gates, caps or "
                + "redacts something, so being silently unenforced is a correctness or security problem. "
                + "Add each to ENFORCEMENT_VERIFIED (naming the test that proves an instance-set value "
                + "changes observable behaviour) or to ENFORCEMENT_EXEMPT (with a documented reason): "
                + unclassified,
            unclassified, is(empty()));

        assertThat("properties classified as BOTH verified and exempt — pick one: " + doubleClassified,
            doubleClassified, is(empty()));
    }

    @Test
    public void shouldNotRetainClassificationsForPropertiesThatNoLongerExist() {
        java.util.Set<String> riskyProperties = new TreeSet<>();
        for (ConfigurationDTOTest.PropertyAccessor accessor : ConfigurationDTOTest.discoverProperties()) {
            if (RISK_HEURISTIC.matcher(accessor.name).matches()) {
                riskyProperties.add(accessor.name);
            }
        }

        java.util.Set<String> stale = new TreeSet<>();
        stale.addAll(ENFORCEMENT_VERIFIED.keySet());
        stale.addAll(ENFORCEMENT_EXEMPT.keySet());
        stale.removeAll(riskyProperties);

        assertThat("classifications retained for properties that no longer exist or no longer match the risk "
                + "heuristic — delete them so the maps stay an accurate picture of what is actually guarded: "
                + stale,
            stale, is(empty()));
    }

    @Test
    public void shouldReferenceEnforcementTestsThatActuallyExist() {
        List<String> broken = new ArrayList<>();
        for (Map.Entry<String, String> entry : ENFORCEMENT_VERIFIED.entrySet()) {
            String reference = entry.getValue();
            int separator = reference.indexOf('#');
            assertThat("ENFORCEMENT_VERIFIED values must be Class#method references, got: " + reference,
                separator > 0, is(true));
            String className = reference.substring(0, separator);
            String methodName = reference.substring(separator + 1);
            Class<?> testClass;
            try {
                testClass = Class.forName(className);
            } catch (ClassNotFoundException e) {
                // the referenced test lives in a module not on this module's test classpath (e.g. the
                // Layer C integration test in mockserver-netty); it cannot be validated from here
                continue;
            }
            boolean found = false;
            for (Method method : testClass.getDeclaredMethods()) {
                if (method.getName().equals(methodName)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                broken.add(entry.getKey() + " -> " + reference);
            }
        }

        assertThat("ENFORCEMENT_VERIFIED points at test methods that no longer exist — the evidence for these "
                + "properties has been renamed or deleted, so they are no longer actually verified: " + broken,
            broken, is(empty()));
    }
}
