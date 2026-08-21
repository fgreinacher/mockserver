package org.mockserver.netty.integration;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Boots the assembly-built {@code mockserver-netty-X.Y.Z-jar-with-dependencies.jar}
 * in a forked JVM via {@code java -jar} and asserts it starts and logs correctly.
 *
 * <p>This is the executable uber-jar that the MockServer Maven plugin runs (both
 * in-process and forked) and that the Homebrew distribution ships. It is a
 * separate artifact — built by maven-assembly-plugin, not maven-shade-plugin —
 * from the {@code mockserver-netty-no-dependencies} jar, so it needs its own
 * logging regression guard (that one is covered by
 * {@code ExtendedNoDependenciesJarMockingIntegrationTest}).
 *
 * <p>Guards against issue #2097-style breakage: if the jar ships without an
 * SLF4J logging provider, SLF4J falls back to a silent no-op logger — the
 * server runs but produces no output. This asserts observable behaviour (the
 * jar boots and logs its startup banner with no SLF4J provider warnings), not
 * jar internals, so it stays valid across SLF4J upgrades.
 *
 * <p>The free-port lookup has an unavoidable TOCTOU window — the {@link
 * ServerSocket} is closed before the forked JVM binds the port — so on a rare
 * race loss the forked server fails to bind and the test reports a missing
 * startup banner.
 */
public class JarWithDependenciesLoggingIntegrationTest {

    /** Substring of the INFO line logged by {@code LifeCycle.startedServer()}. */
    private static final String STARTUP_BANNER = "started on port";
    private static final Duration BOOT_TIMEOUT = Duration.ofSeconds(60);
    private static final long POLL_INTERVAL_MILLIS = 250;

    @Test
    public void shouldBootAndLogFromExecutableJar() throws IOException {
        File jar = locateJarWithDependencies();
        int port = findFreePort();

        List<String> command = new ArrayList<>();
        command.add(javaBin());
        command.add("-Dfile.encoding=UTF-8");
        command.add("-jar");
        command.add(jar.getAbsolutePath());
        command.add("-serverPort");
        command.add(Integer.toString(port));

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        // merge stderr into stdout so a single reader captures everything the
        // jar prints — including the SLF4J diagnostics, which go to stderr
        processBuilder.redirectErrorStream(true);

        Process process = processBuilder.start();
        Thread gobbler = null;
        StringBuilder output = new StringBuilder();
        try {
            gobbler = startOutputGobbler(process, output);
            boolean loggedStartupBanner = awaitOutput(process, output, STARTUP_BANNER, BOOT_TIMEOUT);
            String captured = snapshot(output);

            // SLF4J 2.x emits "SLF4J(W):" / "SLF4J(E):" diagnostics on a provider
            // problem; revisit these markers if SLF4J is ever downgraded to 1.x.
            assertFalse(
                "the executable jar logged an SLF4J provider failure on startup — "
                    + "it is missing a logging backend (see issue #2097):\n" + captured,
                captured.contains("SLF4J(W)") || captured.contains("SLF4J(E)"));

            assertTrue(
                "the executable jar did not log its '" + STARTUP_BANNER + "' banner within "
                    + BOOT_TIMEOUT + " — it either failed to start or its SLF4J logging "
                    + "backend is silently dropping output:\n" + captured,
                loggedStartupBanner);
        } finally {
            process.destroy();
            awaitProcessExit(process);
            joinQuietly(gobbler);
        }
    }

    /**
     * The startup banner is a de-facto readiness API — the Testcontainers modules in this
     * repository wait on it, and so does a great deal of user code — but its wording is
     * singular/plural dependent: {@code started on port: 1080} for one port,
     * {@code started on ports: [1080, 1090]} for several. A readiness check written against the
     * singular form <em>including</em> its trailing colon therefore stops matching the moment a
     * second port is configured, and the caller blocks until it times out with nothing
     * diagnostic in the log. This asserts the {@link #STARTUP_BANNER} prefix — the part both
     * forms share, and the part the Rust and Node modules match on — survives in the multi-port
     * case, so a rewording fails here rather than as a mystery hang downstream.
     */
    @Test
    public void shouldLogStartupBannerPrefixWhenSeveralPortsAreConfigured() throws IOException {
        File jar = locateJarWithDependencies();
        int[] ports = findTwoFreePorts();
        int firstPort = ports[0];
        int secondPort = ports[1];

        List<String> command = new ArrayList<>();
        command.add(javaBin());
        command.add("-Dfile.encoding=UTF-8");
        command.add("-jar");
        command.add(jar.getAbsolutePath());
        command.add("-serverPort");
        command.add(firstPort + "," + secondPort);

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);

        Process process = processBuilder.start();
        Thread gobbler = null;
        StringBuilder output = new StringBuilder();
        try {
            gobbler = startOutputGobbler(process, output);
            boolean loggedStartupBanner = awaitOutput(process, output, STARTUP_BANNER, BOOT_TIMEOUT);
            String captured = snapshot(output);

            assertTrue(
                "the executable jar did not log its '" + STARTUP_BANNER + "' banner within "
                    + BOOT_TIMEOUT + " when started on two ports — readiness detection that keys "
                    + "off this prefix would hang:\n" + captured,
                loggedStartupBanner);
        } finally {
            process.destroy();
            awaitProcessExit(process);
            joinQuietly(gobbler);
        }
    }

    /**
     * Drain the forked JVM's merged stdout/stderr on a daemon thread: append
     * every line to {@code output} and echo it to this JVM's stdout so it
     * lands in the failsafe log. Draining is also required for correctness —
     * if the pipe buffer fills because nobody reads it, the forked JVM blocks.
     */
    private static Thread startOutputGobbler(Process process, StringBuilder output) {
        Thread gobbler = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (output) {
                        output.append(line).append(System.lineSeparator());
                    }
                    System.out.println(line);
                }
            } catch (IOException streamClosed) {
                // forked JVM exited and closed the stream — nothing more to read
            }
        }, "jar-with-dependencies-output");
        gobbler.setDaemon(true);
        gobbler.start();
        return gobbler;
    }

    /**
     * Poll the captured output for {@code token} until it appears, the process
     * exits, or {@code timeout} elapses. Restores the interrupt flag and gives
     * up rather than propagating {@link InterruptedException}.
     */
    private static boolean awaitOutput(Process process, StringBuilder output, String token, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (snapshot(output).contains(token)) {
                return true;
            }
            if (!process.isAlive()) {
                // process exited — let the gobbler drain the final lines from
                // the now-closed pipe before the last check
                try {
                    Thread.sleep(POLL_INTERVAL_MILLIS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                return snapshot(output).contains(token);
            }
            try {
                Thread.sleep(POLL_INTERVAL_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return snapshot(output).contains(token);
            }
        }
        return snapshot(output).contains(token);
    }

    private static String snapshot(StringBuilder output) {
        synchronized (output) {
            return output.toString();
        }
    }

    private static void awaitProcessExit(Process process) {
        try {
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private static void joinQuietly(Thread thread) {
        if (thread == null) {
            return;
        }
        try {
            thread.join(TimeUnit.SECONDS.toMillis(5));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Locate the assembly jar. Prefers the exact filename built from the
     * {@code project.version} system property (injected by the failsafe
     * plugin) so a stale jar from an earlier build is never picked up; falls
     * back to scanning {@code target/} only for IDE runs where the property is
     * absent.
     */
    private static File locateJarWithDependencies() {
        String basedir = System.getProperty("project.basedir", System.getProperty("user.dir", "."));
        File targetDir = new File(basedir, "target");

        String version = System.getProperty("project.version", "");
        if (!version.isEmpty()) {
            File exact = new File(targetDir, "mockserver-netty-" + version + "-jar-with-dependencies.jar");
            if (exact.isFile()) {
                return exact;
            }
        }

        File[] candidates = targetDir.listFiles((dir, name) ->
            new File(dir, name).isFile()
                && name.startsWith("mockserver-netty-") && name.endsWith("-jar-with-dependencies.jar"));
        if (candidates != null && candidates.length > 0) {
            // pick the most recently built jar so a stale earlier-version jar
            // is not chosen when project.version is unavailable (IDE runs)
            Arrays.sort(candidates, Comparator.comparingLong(File::lastModified).reversed());
            return candidates[0];
        }

        throw new IllegalStateException(
            "could not locate mockserver-netty-*-jar-with-dependencies.jar under "
                + targetDir.getAbsolutePath() + " — was the assembly built (package phase)?");
    }

    /**
     * Two distinct free ports. Both sockets are held open until each has reported its port, so the
     * two cannot collide — calling {@link #findFreePort()} twice in a row can hand back the same
     * ephemeral port, which would start the server with {@code -serverPort P,P}, fail the second
     * bind and surface as a 60-second timeout rather than anything diagnostic. The TOCTOU window
     * this class already documents still applies to each port individually.
     */
    private static int[] findTwoFreePorts() throws IOException {
        try (ServerSocket first = new ServerSocket(0);
             ServerSocket second = new ServerSocket(0)) {
            return new int[]{first.getLocalPort(), second.getLocalPort()};
        }
    }

    private static int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static String javaBin() {
        File javaHome = new File(System.getProperty("java.home"));
        for (String executable : new String[]{"java", "java.exe"}) {
            File candidate = new File(javaHome, "bin" + File.separator + executable);
            if (candidate.exists() && candidate.isFile()) {
                return candidate.getAbsolutePath();
            }
        }
        return "java";
    }
}
