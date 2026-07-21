package org.mockserver.configuration;

import org.junit.Test;
import org.mockserver.serialization.model.ConfigurationDTO;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;

/**
 * Layer A of the configuration-reachability guard: a generic, per-call-site bytecode check that no
 * enforcement site reads a configuration value from the static {@link ConfigurationProperties} store
 * in a way that is unreachable from a {@link Configuration} instance.
 *
 * <h2>The defect class this guards against</h2>
 * <p>{@code PUT /mockserver/configuration} calls {@code ConfigurationDTO.applyTo(configuration)},
 * which writes ONLY the {@link Configuration} instance and never the static
 * {@link ConfigurationProperties} store. {@code Configuration.foo()} falls back TO the static store
 * when its own field is unset, so the data flow is strictly one-directional:
 *
 * <pre>system property / env var / property file -&gt; static store -&gt; Configuration instance</pre>
 *
 * <p>Instance -&gt; static is never wired. Therefore any property whose ENFORCEMENT site reads only
 * the static store is silently unreachable from the instance, from the DTO and from the REST API —
 * while still round-tripping perfectly through {@code ConfigurationDTOTest} and appearing fully
 * configurable in the documentation. This has previously affected security controls such as
 * {@code wasmEnabled} and {@code redactSecretsInLog}.
 *
 * <h2>The rule</h2>
 * <p>Every static-store read {@code ConfigurationProperties.x()} of a name {@code x} that is ALSO a
 * {@link ConfigurationDTO} property must sit in a method that ALSO contains an instance read
 * {@code configuration.x()} of the same name — i.e. the sanctioned fallback:
 *
 * <pre>configuration != null ? configuration.x() : ConfigurationProperties.x()</pre>
 *
 * <p>...unless that {@code Class#method} appears in {@link #ALLOWED_STATIC_ONLY_CALL_SITES} with a
 * mandatory reason string.
 *
 * <h2>Why per-call-site, and why descriptor-qualified</h2>
 * <p>Granularity is load-bearing in two directions:
 * <ul>
 *   <li>A per-PROPERTY rule (does this property have a fallback anywhere?) would miss
 *       {@code wasmEnabled} and {@code sloTrackingEnabled}, whose instance reads live in
 *       {@code HttpState} while enforcement happened statically elsewhere.</li>
 *   <li>Keying by bare method NAME lets overloads sanction each other: an overload containing a
 *       correct fallback would mask a sibling overload that reads only the static store. Keying by
 *       name + descriptor found two real violations that a name-only key hid
 *       ({@code MockServerLogger#writeToSystemOut} and {@code WasmRuntime#<init>}).</li>
 * </ul>
 * Detection is therefore descriptor-qualified; the allowlist is keyed by {@code Class#method}
 * (covering all overloads) so it stays readable and does not churn on signature changes.
 *
 * <h2>Maintenance</h2>
 * <p>This guard needs zero per-property maintenance: it discovers both the property set and the call
 * sites automatically. If it fails on a new call site, the correct first response is to make the
 * site consult the {@link Configuration} instance — NOT to add an allowlist entry. Allowlist only
 * genuinely instance-unreachable bootstrap code, and say why.
 */
public class ConfigurationCallSiteGuardTest {

    private static final String CONFIGURATION_PROPERTIES = "org/mockserver/configuration/ConfigurationProperties";
    private static final String CONFIGURATION = "org/mockserver/configuration/Configuration";

    /**
     * Classes that DEFINE the static-store fallback rather than enforcing a value. Their getters are
     * the {@code field != null ? field : ConfigurationProperties.x()} implementation itself, so a
     * static read inside them is the mechanism under test, not a violation of it.
     */
    private static final Set<String> FALLBACK_DEFINITION_CLASSES = new HashSet<>(java.util.Arrays.asList(
        "org.mockserver.configuration.Configuration",
        "org.mockserver.configuration.ConfigurationProperties",
        // ClientConfiguration is the client-side analogue of Configuration: its getters are
        // themselves the sanctioned fallback into the static store, exactly as Configuration's are.
        "org.mockserver.configuration.ClientConfiguration"
    ));

    /**
     * Call sites permitted to read the static store WITHOUT an instance fallback, each with a
     * mandatory reason. Add an entry ONLY when no {@link Configuration} instance can exist at that
     * point (bootstrap, static initialisation, CLI argument parsing before a server is built).
     *
     * <p>If a site merely does not HAVE a Configuration to hand but could be given one, that is an
     * instance-unreachable bug and belongs in a fix, not here.
     */
    private static final Map<String, String> ALLOWED_STATIC_ONLY_CALL_SITES = new TreeMap<>();

    static {
        ALLOWED_STATIC_ONLY_CALL_SITES.put("org.mockserver.cli.Main#main",
            "CLI entry point: reads disableSystemOut to configure logging before any Configuration instance is constructed");
        ALLOWED_STATIC_ONLY_CALL_SITES.put("org.mockserver.cli.Main#lambda$main$0",
            "CLI argument-parse error handler on the Main#main bootstrap path, before any Configuration exists");
        ALLOWED_STATIC_ONLY_CALL_SITES.put("org.mockserver.cli.Main#startServer",
            "CLI bootstrap: reads logLevel while building the server, before the Configuration instance is available");
        ALLOWED_STATIC_ONLY_CALL_SITES.put("org.mockserver.cli.Main$RunCommand#run",
            "picocli command body on the CLI bootstrap path, before any Configuration instance is constructed");
        ALLOWED_STATIC_ONLY_CALL_SITES.put("org.mockserver.logging.MockServerLogger#configureLogger",
            "static logging bootstrap invoked from ConfigurationProperties itself; runs before and independently of any Configuration instance");
        ALLOWED_STATIC_ONLY_CALL_SITES.put("org.mockserver.logging.MockServerLogger#isEnabled",
            "static overload with no instance in scope; the instance-aware equivalent is the differently-named "
                + "isEnabledForInstance(Level), which consults the Configuration field and is what logEvent uses");
        ALLOWED_STATIC_ONLY_CALL_SITES.put("org.mockserver.logging.MockServerLogger#writeToSystemOut",
            "legacy 2-arg overload retained for source compatibility; superseded by "
                + "writeToSystemOut(Logger, LogEntry, Configuration), which logEvent calls whenever a Configuration is held");
        ALLOWED_STATIC_ONLY_CALL_SITES.put("org.mockserver.wasm.WasmRuntime#<init>",
            "legacy single-arg constructor retained for source compatibility; superseded by "
                + "WasmRuntime(byte[], Configuration), which prefers the instance value");
        ALLOWED_STATIC_ONLY_CALL_SITES.put("org.mockserver.closurecallback.websocketregistry.LocalCallbackRegistry#<clinit>",
            "static class-initializer default for a wholly static registry; the live instance value is pushed in "
                + "by the HttpState constructor via setMaxWebSocketExpectations(configuration.maxWebSocketExpectations())");
        ALLOWED_STATIC_ONLY_CALL_SITES.put("org.mockserver.socket.tls.KeyAndCertificateFactory#writeCertificateAuthorityToDisk",
            "interface default method with no instance state in scope; the sole in-tree implementation "
                + "BCKeyAndCertificateFactory overrides it to use its Configuration field, and custom factory "
                + "suppliers are handed the Configuration too, so this body is unreachable in the shipped server");
        ALLOWED_STATIC_ONLY_CALL_SITES.put("org.mockserver.mock.audit.AuditStore#maxFromConfig",
            "static class-initializer default for the audit singleton; capacity is a volatile field, not final, "
                + "and HttpState.applyConfigurationUpdate pushes configuration.controlPlaneAuditMaxEntries() into "
                + "AuditStore.setMaxSize(int) on every PUT /mockserver/configuration, so the instance value does "
                + "take effect");
        ALLOWED_STATIC_ONLY_CALL_SITES.put("org.mockserver.testing.integration.mock.AbstractBasicMockingIntegrationTest#shouldRetrieveRecordedLogMessages",
            "mockserver-integration-testing ships test-support code in src/main so downstream suites can reuse it; "
                + "this is an assertion in a test body, not a server enforcement site");
        ALLOWED_STATIC_ONLY_CALL_SITES.put("org.mockserver.client.MockServerClient#stop",
            "client code: MockServerClient holds only a ClientConfiguration, never a server Configuration, so no "
                + "Configuration instance can exist here. stopDrainMillis is a server property; the client reads it "
                + "best-effort from its OWN JVM's static store to size how long to wait for a remote shutdown, and it "
                + "is inherently unreachable from a server-side PUT /mockserver/configuration");
        ALLOWED_STATIC_ONLY_CALL_SITES.put("org.mockserver.client.MockServerClient#lambda$stop$3",
            "client code: the ClientStop thread body inside MockServerClient.stop(boolean). MockServerClient holds "
                + "only a ClientConfiguration, never a server Configuration, so no Configuration instance can exist "
                + "here. stopDrainMillis is a server property read best-effort from the client's OWN JVM to size the "
                + "stop-wait deadline, and is inherently unreachable from a server-side PUT /mockserver/configuration");
    }

    /**
     * Instance-unreachable defects that exist TODAY and are not yet fixed. This is a ratchet, NOT an
     * excuse list: each entry is a real bug where a value settable over
     * {@code PUT /mockserver/configuration} is silently ignored.
     *
     * <p>The guard asserts this set is EXACTLY the set of currently-violating non-allowlisted sites, in
     * both directions:
     * <ul>
     *   <li>a NEW violation is not covered here, so it fails the build immediately;</li>
     *   <li>FIXING one of these makes its entry stale, which also fails the build — forcing the entry to
     *       be deleted so the defect can never silently regress afterwards.</li>
     * </ul>
     * The correct way to discharge an entry is to fix the call site and delete the line.
     *
     * <p><b>This map is now EMPTY, and must stay that way.</b> It previously carried 20 enforcement
     * sites created by one event: 27 properties that existed ONLY on {@link ConfigurationProperties} —
     * with no {@link Configuration} accessor and no {@link ConfigurationDTO} field — were wired through
     * to the instance/DTO/REST routes, which brought them into {@link #restReachableProperties()} for
     * the first time and so exposed their long-standing static-only enforcement sites to this guard.
     * All 20 have since been fixed to consult the {@link Configuration} instance, and their entries
     * deleted, so the guard now enforces them directly.
     *
     * <p>Do NOT add entries for new work. A newly-detected violation should be FIXED by making the site
     * consult the {@link Configuration} instance; if the site genuinely cannot reach one (bootstrap,
     * static initialisation), it belongs in {@link #ALLOWED_STATIC_ONLY_CALL_SITES} with a real reason.
     */
    private static final Map<String, String> KNOWN_INSTANCE_UNREACHABLE_DEFECTS = new TreeMap<>();

    @Test
    public void shouldNotReadStaticConfigurationStoreWithoutInstanceFallback() throws Exception {
        List<Path> moduleClassRoots = moduleClassRoots();
        Set<String> restReachableProperties = restReachableProperties();

        // sanity: the guard must be scanning real, representative bytecode so it cannot pass vacuously
        assertThat("guard must scan compiled module output — run this under `mvn verify`, not against an unbuilt tree",
            moduleClassRoots.size(), greaterThan(1));
        assertThat("guard must cover a large property set", restReachableProperties.size(), greaterThan(100));
        List<String> scannedModules = moduleClassRoots.stream()
            .map(p -> p.getParent().getParent().getFileName().toString())
            .sorted()
            .collect(Collectors.toList());
        assertThat("mockserver-core must be scanned", scannedModules, hasItem("mockserver-core"));
        assertThat("mockserver-netty must be scanned", scannedModules, hasItem("mockserver-netty"));

        CallSiteIndex index = new CallSiteIndex();
        for (Path root : moduleClassRoots) {
            try (Stream<Path> files = Files.walk(root)) {
                for (Path classFile : files.filter(f -> f.toString().endsWith(".class")).collect(Collectors.toList())) {
                    index.scan(classFile);
                }
            }
        }

        assertThat("guard must find configuration call sites — a scan that indexes nothing would pass vacuously",
            index.staticReads.size(), greaterThan(10));

        List<String> violations = new ArrayList<>();
        Set<String> observedKnownDefects = new TreeSet<>();
        for (Map.Entry<String, Set<String>> entry : index.staticReads.entrySet()) {
            String descriptorQualifiedMethod = entry.getKey();
            String allowlistKey = descriptorQualifiedMethod.substring(0, descriptorQualifiedMethod.indexOf('('));
            if (ALLOWED_STATIC_ONLY_CALL_SITES.containsKey(allowlistKey)) {
                continue;
            }
            Set<String> instanceReadsInSameMethod =
                index.instanceReads.getOrDefault(descriptorQualifiedMethod, java.util.Collections.emptySet());
            List<String> unreachable = entry.getValue().stream()
                .filter(restReachableProperties::contains)
                .filter(name -> !instanceReadsInSameMethod.contains(name))
                .sorted()
                .collect(Collectors.toList());
            if (unreachable.isEmpty()) {
                continue;
            }
            if (KNOWN_INSTANCE_UNREACHABLE_DEFECTS.containsKey(allowlistKey)) {
                observedKnownDefects.add(allowlistKey);
                continue;
            }
            violations.add(descriptorQualifiedMethod + " reads only the static store for " + unreachable);
        }

        // ratchet: a known defect that no longer violates has been FIXED — delete its entry so the fix
        // is locked in and can never silently regress
        Set<String> staleKnownDefects = new TreeSet<>(KNOWN_INSTANCE_UNREACHABLE_DEFECTS.keySet());
        staleKnownDefects.removeAll(observedKnownDefects);
        assertThat("these call sites are recorded in KNOWN_INSTANCE_UNREACHABLE_DEFECTS but no longer read the "
                + "static store without an instance fallback — they appear to have been FIXED. Delete their "
                + "entries so the guard starts enforcing them: " + staleKnownDefects,
            staleKnownDefects, is(empty()));

        assertThat("Configuration values read from the static ConfigurationProperties store with no "
                + "instance fallback — these are UNREACHABLE from PUT /mockserver/configuration even though "
                + "they round-trip through ConfigurationDTO. Fix by consulting the Configuration instance "
                + "(configuration != null ? configuration.x() : ConfigurationProperties.x()); only add to "
                + "ALLOWED_STATIC_ONLY_CALL_SITES, with a reason, if no Configuration instance can exist "
                + "at that point:\n  " + String.join("\n  ", violations) + "\n",
            violations, is(empty()));
    }

    /**
     * A wildcard static import of the store makes call sites invisible to review (they read as bare
     * {@code maxRequestBodySize()} rather than {@code ConfigurationProperties.maxRequestBodySize()}),
     * so main source must not use one. {@code fileExists} is a stateless path-checking utility that
     * carries no configuration value, and Configuration itself imports it.
     */
    @Test
    public void shouldNotStaticallyImportConfigurationPropertiesInMainSource() throws Exception {
        Path mockserverRoot = mockserverRoot();
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> modules = Files.list(mockserverRoot)) {
            for (Path module : modules.filter(Files::isDirectory).collect(Collectors.toList())) {
                Path mainJava = module.resolve("src/main/java");
                if (!Files.isDirectory(mainJava)) {
                    continue;
                }
                try (Stream<Path> sources = Files.walk(mainJava)) {
                    for (Path source : sources.filter(f -> f.toString().endsWith(".java")).collect(Collectors.toList())) {
                        for (String line : Files.readAllLines(source, StandardCharsets.UTF_8)) {
                            String trimmed = line.trim();
                            if (trimmed.startsWith("import static org.mockserver.configuration.ConfigurationProperties.")) {
                                String imported = trimmed
                                    .substring("import static org.mockserver.configuration.ConfigurationProperties.".length())
                                    .replace(";", "").trim();
                                if (!ALLOWED_STATIC_IMPORTS.containsKey(imported)) {
                                    offenders.add(mockserverRoot.relativize(source) + " -> " + imported);
                                }
                            }
                        }
                    }
                }
            }
        }
        assertThat("static imports of ConfigurationProperties in main source hide static-store reads from "
                + "review and from the call-site guard above; read through a Configuration instance instead: "
                + offenders,
            offenders, is(empty()));
    }

    /**
     * Static imports of {@link ConfigurationProperties} members permitted in main source, with reasons.
     */
    private static final Map<String, String> ALLOWED_STATIC_IMPORTS = new TreeMap<>();

    static {
        ALLOWED_STATIC_IMPORTS.put("fileExists",
            "stateless filesystem predicate, carries no configuration value; used by Configuration itself");
        ALLOWED_STATIC_IMPORTS.put("maxFutureTimeout",
            "mockserver-integration-testing is test-support code shipped in src/main so downstream test suites "
                + "can reuse it; it drives test await timeouts, not server behaviour");
    }

    private static final class CallSiteIndex {
        /** descriptor-qualified {@code fqcn#name(desc)ret} -> property names read from the static store */
        final Map<String, Set<String>> staticReads = new TreeMap<>();
        /** descriptor-qualified {@code fqcn#name(desc)ret} -> property names read from a Configuration instance */
        final Map<String, Set<String>> instanceReads = new HashMap<>();

        void scan(Path classFile) {
            try {
                ClassReader reader = new ClassReader(Files.readAllBytes(classFile));
                String internalName = reader.getClassName();
                String className = internalName.replace('/', '.');
                if (FALLBACK_DEFINITION_CLASSES.contains(className)) {
                    return;
                }
                reader.accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                        String key = className + "#" + name + descriptor;
                        return new MethodVisitor(Opcodes.ASM9) {
                            @Override
                            public void visitMethodInsn(int opcode, String owner, String calledName, String calledDescriptor, boolean isInterface) {
                                if (!isNoArgReader(calledDescriptor)) {
                                    return;
                                }
                                if (opcode == Opcodes.INVOKESTATIC && CONFIGURATION_PROPERTIES.equals(owner)) {
                                    staticReads.computeIfAbsent(key, k -> new TreeSet<>()).add(calledName);
                                } else if (opcode == Opcodes.INVOKEVIRTUAL && CONFIGURATION.equals(owner)) {
                                    instanceReads.computeIfAbsent(key, k -> new TreeSet<>()).add(calledName);
                                }
                            }
                        };
                    }
                }, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
            } catch (IOException e) {
                throw new UncheckedIOException("failed reading " + classFile, e);
            }
        }

        private static boolean isNoArgReader(String descriptor) {
            Type type = Type.getMethodType(descriptor);
            return type.getArgumentTypes().length == 0 && !Type.VOID_TYPE.equals(type.getReturnType());
        }
    }

    /**
     * Property names that a client can actually set over {@code PUT /mockserver/configuration}: the
     * intersection of {@link ConfigurationDTO}'s fields with {@link Configuration}'s getter/fluent-setter
     * pairs. Deriving this reflectively means the guard cannot go stale as properties are added.
     */
    private static Set<String> restReachableProperties() {
        Set<String> dtoFields = new HashSet<>();
        for (Field field : ConfigurationDTO.class.getDeclaredFields()) {
            if (!field.isSynthetic() && !java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                dtoFields.add(field.getName());
            }
        }
        Set<String> setters = new HashSet<>();
        Set<String> getters = new HashSet<>();
        for (Method method : Configuration.class.getDeclaredMethods()) {
            if (method.isSynthetic() || !java.lang.reflect.Modifier.isPublic(method.getModifiers())) {
                continue;
            }
            if (method.getParameterCount() == 1 && Configuration.class.equals(method.getReturnType())) {
                setters.add(method.getName());
            } else if (method.getParameterCount() == 0 && !void.class.equals(method.getReturnType())) {
                getters.add(method.getName());
            }
        }
        getters.retainAll(setters);
        getters.retainAll(dtoFields);
        return getters;
    }

    /** Every {@code <module>/target/classes} directory present under the {@code mockserver/} reactor root. */
    private static List<Path> moduleClassRoots() throws IOException {
        try (Stream<Path> modules = Files.list(mockserverRoot())) {
            return modules
                .map(module -> module.resolve("target/classes"))
                .filter(Files::isDirectory)
                .sorted()
                .collect(Collectors.toList());
        }
    }

    /**
     * Locate the {@code mockserver/} reactor root from THIS test class's own output directory, so the
     * guard works regardless of the working directory the build runs from.
     *
     * <p>Deliberately anchored on the test classes rather than on {@link Configuration}: when this module
     * is built alone ({@code -pl mockserver-netty}) core is resolved as a jar from the local Maven
     * repository, whose layout also contains a {@code mockserver-core} directory — anchoring there
     * silently resolved into {@code ~/.m2} and scanned nothing. This module's own test output is always
     * exploded and always inside the working tree.
     */
    private static Path mockserverRoot() {
        URL location = ConfigurationCallSiteGuardTest.class.getProtectionDomain().getCodeSource().getLocation();
        Path testClasses = Paths.get(location.getPath());
        // <mockserver>/mockserver-netty/target/test-classes -> <mockserver>
        Path root = testClasses.getParent().getParent().getParent();
        if (!Files.isDirectory(root.resolve("mockserver-core/src/main/java"))) {
            throw new IllegalStateException("could not locate the mockserver reactor root from " + testClasses
                + " (resolved " + root + ")");
        }
        return root;
    }
}
