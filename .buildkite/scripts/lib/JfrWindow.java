// Cuts a JFR recording down to the events that started inside a wall-clock window.
//
// `jcmd JFR.dump begin=... end=...` selects whole CHUNKS, and a chunk can span many minutes, so a
// "ceiling" dump bounded that way can still hold most of the run. This keeps an event only when its
// start time is inside [begin, end), plus the configuration events the views and the per-thread CPU
// figure need (container CPU count, JVM and GC configuration), which JFR writes at chunk start.
// Needs JDK 19+ (RecordingFile.write with a filter). Run in a JDK sidecar with the recording mounted:
//     java JfrWindow.java <in.jfr> <out.jfr> <beginEpochMs> <endEpochMs>
// Prints "kept=<n> dropped=<n>" and exits non-zero when no event falls in the window.

import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import jdk.jfr.consumer.RecordingFile;

public final class JfrWindow {

    private static final Set<String> CONFIGURATION_EVENTS = Set.of(
        "jdk.ContainerConfiguration", "jdk.CPUInformation", "jdk.JVMInformation",
        "jdk.OSInformation", "jdk.GCConfiguration", "jdk.GCHeapConfiguration");

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            System.err.println("usage: java JfrWindow.java <in.jfr> <out.jfr> <beginEpochMs> <endEpochMs>");
            System.exit(2);
        }
        Instant begin = Instant.ofEpochMilli(Long.parseLong(args[2]));
        Instant end = Instant.ofEpochMilli(Long.parseLong(args[3]));
        AtomicLong kept = new AtomicLong();
        AtomicLong dropped = new AtomicLong();
        try (RecordingFile in = new RecordingFile(Path.of(args[0]))) {
            in.write(Path.of(args[1]), event -> {
                Instant start = event.getStartTime();
                boolean inside = !start.isBefore(begin) && start.isBefore(end);
                if (inside) {
                    kept.incrementAndGet();
                } else {
                    dropped.incrementAndGet();
                }
                return inside || CONFIGURATION_EVENTS.contains(event.getEventType().getName());
            });
        }
        System.out.println("kept=" + kept.get() + " dropped=" + dropped.get());
        if (kept.get() == 0) {
            System.exit(1);
        }
    }
}
