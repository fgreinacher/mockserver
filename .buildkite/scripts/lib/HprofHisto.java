// Streaming class histogram for a HotSpot .hprof heap dump — the "what was retained?" answer.
//
// WHY THIS EXISTS. When a perf SUT dies of heap exhaustion, -XX:+HeapDumpOnOutOfMemoryError leaves a
// multi-gigabyte .hprof on the mounted /diag volume. perf-test-run.sh refuses to upload a file that
// large, but a CLASS HISTOGRAM of it is kilobytes and names the retaining class outright. The obvious
// tools do NOT do this: jmap / jhsdb read a LIVE pid or a CORE dump, not an .hprof file, and jhat was
// removed in JDK 9. Eclipse MAT can, but needs a big download and builds dump-sized indexes (a
// step-timeout risk). This is a single-pass, memory-bounded streaming parser instead: it holds only
// the class-id -> name map and a per-class {count, bytes} counter, reads the file once, and self-bounds
// on a wall-clock deadline so a huge dump can never hang the build step (a truncated histogram naming
// the top classes is worth far more than none). Run in a throwaway JDK sidecar with /diag mounted:
//     java HprofHisto.java <dump.hprof> [topN] [deadlineSeconds]
// Output: shallow-size histogram (bytes, instances, class), highest first — the same shape as
// `jmap -histo`, which is what identified the retaining class in the local experiment.
//
// Shallow size = instance field bytes (+ a nominal object header) for INSTANCE_DUMP, element bytes for
// arrays. That is deliberately jmap's shallow accounting, not retained size; it is enough to rank the
// dominant retainers, and it needs one linear pass with no object graph in memory.

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.FileInputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;

public final class HprofHisto {

    // Top-level record tags.
    private static final int TAG_STRING = 0x01;
    private static final int TAG_LOAD_CLASS = 0x02;
    private static final int TAG_HEAP_DUMP = 0x0C;
    private static final int TAG_HEAP_DUMP_SEGMENT = 0x1C;

    // Heap-dump sub-record tags.
    private static final int GC_ROOT_UNKNOWN = 0xFF;
    private static final int GC_ROOT_JNI_GLOBAL = 0x01;
    private static final int GC_ROOT_JNI_LOCAL = 0x02;
    private static final int GC_ROOT_JAVA_FRAME = 0x03;
    private static final int GC_ROOT_NATIVE_STACK = 0x04;
    private static final int GC_ROOT_STICKY_CLASS = 0x05;
    private static final int GC_ROOT_THREAD_BLOCK = 0x06;
    private static final int GC_ROOT_MONITOR_USED = 0x07;
    private static final int GC_ROOT_THREAD_OBJ = 0x08;
    private static final int GC_CLASS_DUMP = 0x20;
    private static final int GC_INSTANCE_DUMP = 0x21;
    private static final int GC_OBJ_ARRAY_DUMP = 0x22;
    private static final int GC_PRIM_ARRAY_DUMP = 0x23;

    // Approximate object header (12-byte mark+klass with compressed oops, rounded to 8-byte alignment).
    private static final long OBJ_HEADER = 16;
    private static final long ARR_HEADER = 16;

    private int idSize = 8;
    private final Map<Long, Long> classNameStringId = new HashMap<>();   // classObjId -> nameStringId
    private final Map<Long, String> strings = new HashMap<>();            // stringId -> UTF8 text
    private final Map<Long, long[]> byClass = new HashMap<>();            // classObjId -> {count, bytes}
    private final Map<String, long[]> byName = new HashMap<>();           // synthetic (arrays) -> {count, bytes}

    private long deadlineNanos;
    private boolean truncated = false;
    private long instancesSeen = 0;

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: java HprofHisto.java <dump.hprof> [topN] [deadlineSeconds]");
            System.exit(2);
        }
        String path = args[0];
        int topN = args.length > 1 ? Integer.parseInt(args[1]) : 30;
        long deadlineS = args.length > 2 ? Long.parseLong(args[2]) : 90;
        new HprofHisto().run(path, topN, deadlineS);
    }

    private void run(String path, int topN, long deadlineS) throws Exception {
        deadlineNanos = System.nanoTime() + deadlineS * 1_000_000_000L;
        long t0 = System.currentTimeMillis();
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(path), 1 << 20))) {
            readHeader(in);
            parse(in);
        } catch (EOFException eof) {
            // A dump truncated mid-write (a hard OOM exit can leave one) — aggregate what we have.
            truncated = true;
        } catch (DeadlineException de) {
            truncated = true;
        } catch (Throwable t) {
            // Any malformed record: keep whatever we aggregated, mark partial, name the culprit anyway.
            // Throwable, not Exception, deliberately: a corrupt length field can size an allocation
            // past the sidecar heap, and an OutOfMemoryError is an Error. Catching only Exception
            // meant the one input we most need a histogram for -- a dump from a JVM that died of
            // memory -- produced no histogram at all. Whatever was aggregated before the failure is
            // still worth printing, and STATUS says it is partial.
            truncated = true;
            System.err.println("hprof parse stopped early: " + t);
        }
        print(path, topN, System.currentTimeMillis() - t0);
    }

    /** A class/field name is tens of bytes; anything past this is a corrupt length, not a name. */
    private static final long MAX_STRING_BYTES = 4L * 1024 * 1024;
    /** The header version string is ~18 bytes ("JAVA PROFILE 1.0.2"); bound the scan regardless. */
    private static final int MAX_HEADER_BYTES = 256;

    private void readHeader(DataInputStream in) throws Exception {
        // Null-terminated version string, e.g. "JAVA PROFILE 1.0.2".
        StringBuilder sb = new StringBuilder();
        int b;
        while ((b = in.read()) > 0) {
            sb.append((char) b);
            if (sb.length() > MAX_HEADER_BYTES) {
                throw new java.io.IOException("no NUL within " + MAX_HEADER_BYTES
                    + " bytes — not an hprof header");
            }
        }
        if (b < 0) throw new EOFException("no header");
        idSize = in.readInt();
        in.readLong(); // timestamp (hi/lo as one u8)
    }

    private void parse(DataInputStream in) throws Exception {
        while (true) {
            int tag = in.read();
            if (tag < 0) return; // clean EOF
            in.readInt();                       // u4 time (micros) — ignored
            long len = in.readInt() & 0xFFFFFFFFL; // u4 record length
            switch (tag) {
                case TAG_STRING: {
                    long id = readId(in);
                    long utfLen = len - idSize;
                    // Sanity-cap a length read from the file. A class or field name is tens of
                    // bytes; a corrupt u4 can claim two gigabytes. Sizing an array straight from
                    // untrusted input is how a diagnostic turns into an OutOfMemoryError while
                    // diagnosing an OutOfMemoryError.
                    if (utfLen < 0 || utfLen > MAX_STRING_BYTES) {
                        throw new java.io.IOException("implausible string record length " + utfLen
                            + " (cap " + MAX_STRING_BYTES + ") — treating the dump as corrupt here");
                    }
                    byte[] utf = new byte[(int) utfLen];
                    in.readFully(utf);
                    strings.put(id, new String(utf, java.nio.charset.StandardCharsets.UTF_8));
                    break;
                }
                case TAG_LOAD_CLASS: {
                    in.readInt();               // class serial
                    long classObjId = readId(in);
                    in.readInt();               // stack trace serial
                    long nameId = readId(in);
                    classNameStringId.put(classObjId, nameId);
                    break;
                }
                case TAG_HEAP_DUMP:
                case TAG_HEAP_DUMP_SEGMENT:
                    parseHeap(in, len);
                    break;
                default:
                    skip(in, len);
            }
        }
    }

    private void parseHeap(DataInputStream in, long remaining) throws Exception {
        while (remaining > 0) {
            if ((++instancesSeen & 0x3FFFF) == 0 && System.nanoTime() > deadlineNanos) {
                throw new DeadlineException();
            }
            int sub = in.read();
            if (sub < 0) throw new EOFException("heap segment truncated");
            remaining -= 1;
            switch (sub) {
                case GC_ROOT_UNKNOWN:        remaining -= skip(in, idSize); break;
                case GC_ROOT_STICKY_CLASS:   remaining -= skip(in, idSize); break;
                case GC_ROOT_MONITOR_USED:   remaining -= skip(in, idSize); break;
                case GC_ROOT_JNI_GLOBAL:     remaining -= skip(in, idSize * 2L); break;
                case GC_ROOT_NATIVE_STACK:   remaining -= skip(in, idSize + 4L); break;
                case GC_ROOT_THREAD_BLOCK:   remaining -= skip(in, idSize + 4L); break;
                case GC_ROOT_JNI_LOCAL:      remaining -= skip(in, idSize + 8L); break;
                case GC_ROOT_JAVA_FRAME:     remaining -= skip(in, idSize + 8L); break;
                case GC_ROOT_THREAD_OBJ:     remaining -= skip(in, idSize + 8L); break;
                case GC_CLASS_DUMP:          remaining -= classDump(in); break;
                case GC_INSTANCE_DUMP:       remaining -= instanceDump(in); break;
                case GC_OBJ_ARRAY_DUMP:      remaining -= objArrayDump(in); break;
                case GC_PRIM_ARRAY_DUMP:     remaining -= primArrayDump(in); break;
                default:
                    // Unknown sub-tag: we can no longer know record boundaries — stop cleanly with
                    // what we have rather than misread the rest.
                    throw new IllegalStateException("unknown heap sub-tag 0x" + Integer.toHexString(sub));
            }
        }
    }

    private long instanceDump(DataInputStream in) throws Exception {
        long read = 0;
        readId(in); read += idSize;             // object id
        in.readInt(); read += 4;                // stack serial
        long classId = readId(in); read += idSize;
        long nbytes = in.readInt() & 0xFFFFFFFFL; read += 4;
        read += skip(in, nbytes);
        long[] c = byClass.computeIfAbsent(classId, k -> new long[2]);
        c[0] += 1;
        c[1] += nbytes + OBJ_HEADER;
        return read;
    }

    private long objArrayDump(DataInputStream in) throws Exception {
        long read = 0;
        readId(in); read += idSize;             // array object id
        in.readInt(); read += 4;                // stack serial
        long n = in.readInt() & 0xFFFFFFFFL; read += 4;
        long arrClassId = readId(in); read += idSize;
        read += skip(in, n * (long) idSize);
        long[] c = byClass.computeIfAbsent(arrClassId, k -> new long[2]);
        c[0] += 1;
        c[1] += n * (long) idSize + ARR_HEADER;
        return read;
    }

    private long primArrayDump(DataInputStream in) throws Exception {
        long read = 0;
        readId(in); read += idSize;             // array object id
        in.readInt(); read += 4;                // stack serial
        long n = in.readInt() & 0xFFFFFFFFL; read += 4;
        int type = in.read(); read += 1;        // element type
        int esz = typeSize(type);
        read += skip(in, n * (long) esz);
        String name = primArrayName(type);
        long[] c = byName.computeIfAbsent(name, k -> new long[2]);
        c[0] += 1;
        c[1] += n * (long) esz + ARR_HEADER;
        return read;
    }

    // CLASS_DUMP is variable length (constant pool + static + instance field descriptors); we only
    // need to consume it exactly so the segment stays aligned.
    private long classDump(DataInputStream in) throws Exception {
        long read = 0;
        readId(in); read += idSize;             // class object id
        in.readInt(); read += 4;                // stack serial
        read += skip(in, idSize * 6L);          // super, loader, signers, protdomain, 2 reserved
        in.readInt(); read += 4;                // instance size
        int cpCount = in.readUnsignedShort(); read += 2;
        for (int i = 0; i < cpCount; i++) {
            in.readUnsignedShort(); read += 2;  // cp index
            int t = in.read(); read += 1;
            read += skip(in, typeSize(t));
        }
        int sfCount = in.readUnsignedShort(); read += 2;
        for (int i = 0; i < sfCount; i++) {
            readId(in); read += idSize;         // name string id
            int t = in.read(); read += 1;
            read += skip(in, typeSize(t));
        }
        int ifCount = in.readUnsignedShort(); read += 2;
        for (int i = 0; i < ifCount; i++) {
            readId(in); read += idSize;         // name string id
            in.read(); read += 1;               // type (no value)
        }
        return read;
    }

    private int typeSize(int type) {
        switch (type) {
            case 2: return idSize;   // object
            case 4: return 1;        // boolean
            case 5: return 2;        // char
            case 6: return 4;        // float
            case 7: return 8;        // double
            case 8: return 1;        // byte
            case 9: return 2;        // short
            case 10: return 4;       // int
            case 11: return 8;       // long
            default: throw new IllegalStateException("bad basic type " + type);
        }
    }

    private String primArrayName(int type) {
        switch (type) {
            case 4: return "boolean[]";
            case 5: return "char[]";
            case 6: return "float[]";
            case 7: return "double[]";
            case 8: return "byte[]";
            case 9: return "short[]";
            case 10: return "int[]";
            case 11: return "long[]";
            default: return "prim[]";
        }
    }

    private long readId(DataInputStream in) throws Exception {
        return idSize == 4 ? (in.readInt() & 0xFFFFFFFFL) : in.readLong();
    }

    private long skip(DataInputStream in, long n) throws Exception {
        long left = n;
        while (left > 0) {
            long s = in.skip(left);
            if (s <= 0) {
                if (in.read() < 0) throw new EOFException("skip past end");
                s = 1;
            }
            left -= s;
        }
        return n;
    }

    private String className(long classObjId) {
        Long nameId = classNameStringId.get(classObjId);
        String raw = nameId == null ? null : strings.get(nameId);
        if (raw == null) return "<unresolved class 0x" + Long.toHexString(classObjId) + ">";
        return raw.replace('/', '.');
    }

    private void print(String path, int topN, long ms) {
        List<long[]> rows = new ArrayList<>();      // {bytes, count, key} where key resolved to name below
        List<String> names = new ArrayList<>();
        long totalBytes = 0, totalCount = 0;
        for (Map.Entry<Long, long[]> e : byClass.entrySet()) {
            rows.add(new long[]{e.getValue()[1], e.getValue()[0], names.size()});
            names.add(className(e.getKey()));
            totalBytes += e.getValue()[1]; totalCount += e.getValue()[0];
        }
        for (Map.Entry<String, long[]> e : byName.entrySet()) {
            rows.add(new long[]{e.getValue()[1], e.getValue()[0], names.size()});
            names.add(e.getKey());
            totalBytes += e.getValue()[1]; totalCount += e.getValue()[0];
        }
        rows.sort((a, b) -> Long.compare(b[0], a[0]));

        System.out.println("# HprofHisto — class histogram of " + path);
        System.out.println("# idSize=" + idSize + "  classes=" + names.size()
            + "  total_instances=" + totalCount + "  total_shallow_bytes=" + totalBytes
            + "  parse_ms=" + ms + (truncated ? "  STATUS=TRUNCATED(partial — deadline/EOF/parse-stop)" : "  STATUS=complete"));
        System.out.println("# shallow size (jmap -histo shape); ranks the dominant RETAINERS.");
        System.out.printf("%-5s %18s %14s  %s%n", "rank", "shallow_bytes", "instances", "class");
        int n = Math.min(topN, rows.size());
        for (int i = 0; i < n; i++) {
            long[] r = rows.get(i);
            System.out.printf("%-5d %18d %14d  %s%n", i + 1, r[0], r[1], names.get((int) r[2]));
        }
    }

    // Control-flow signal for the wall-clock deadline; no stack capture, no serialisation.
    private static final class DeadlineException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        @Override public Throwable fillInStackTrace() { return this; }
    }
}
