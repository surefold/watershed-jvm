package dev.surefold.watershed;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.io.File;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.ref.Cleaner;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The engine's C functions (see {@code src/ffi.rs}), called through the JDK's
 * foreign function API. A call costs tens of nanoseconds.
 *
 * <p>A graph or collection is a {@code long}: its address in the engine.
 * The typed Kotlin and Scala layers hold those and are what a program uses.
 * This class is not for calling directly: an address that is wrong, or used
 * after it is freed, crashes the JVM.
 *
 * <p>The library is found through the system property
 * {@code watershed.library} (the file) or {@code watershed.library.path}
 * (a folder holding it), or else on {@code java.library.path}.
 */
public final class Native {
    private Native() {}

    /** A node that could not be calculated, and why. Returned by {@link #value} in place of a value. */
    public record Failed(String why) {}

    private static final Cleaner CLEANER = Cleaner.create();
    private static final SymbolLookup LIBRARY = SymbolLookup.libraryLookup(library(), Arena.global());

    private static Path library() {
        String file = System.getProperty("watershed.library");
        if (file != null) {
            return Path.of(file);
        }
        String name = System.mapLibraryName("watershed_jvm");
        String folders = System.getProperty("watershed.library.path", "")
                + File.pathSeparator + System.getProperty("java.library.path", "");
        for (String folder : folders.split(File.pathSeparator)) {
            File candidate = new File(folder, name);
            if (!folder.isEmpty() && candidate.isFile()) {
                return candidate.toPath();
            }
        }
        throw new WatershedException("cannot find " + name
                + ": set -Dwatershed.library.path to the folder it was built into (target/release)");
    }

    private static MethodHandle function(String name, MemoryLayout result, MemoryLayout... arguments) {
        FunctionDescriptor shape = result == null
                ? FunctionDescriptor.ofVoid(arguments)
                : FunctionDescriptor.of(result, arguments);
        return Linker.nativeLinker().downcallHandle(LIBRARY.find(name).orElseThrow(), shape);
    }

    private static final MethodHandle ERROR = function("ws_error", JAVA_LONG, ADDRESS, JAVA_LONG);
    private static final MethodHandle RESULT = function("ws_result", JAVA_LONG, ADDRESS, JAVA_LONG);
    private static final MethodHandle GRAPH_NEW = function("ws_graph_new", JAVA_LONG);
    private static final MethodHandle GRAPH_FREE = function("ws_graph_free", null, JAVA_LONG);
    private static final MethodHandle INPUT = function("ws_input", JAVA_INT,
            JAVA_LONG, ADDRESS, JAVA_LONG, JAVA_INT, JAVA_DOUBLE, JAVA_LONG, JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG);
    private static final MethodHandle DEFINE = function("ws_define", JAVA_INT,
            JAVA_LONG, ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG);
    private static final MethodHandle DEFINE_MANY = function("ws_define_many", JAVA_INT,
            JAVA_LONG, ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG);
    private static final MethodHandle SET = function("ws_set", JAVA_INT,
            JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_DOUBLE, JAVA_LONG, JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG);
    private static final MethodHandle SET_NUMBER = function("ws_set_number", JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_DOUBLE);
    private static final MethodHandle SET_NUMBERS = function("ws_set_numbers", JAVA_INT,
            JAVA_LONG, ADDRESS, ADDRESS, JAVA_LONG);
    private static final MethodHandle NUMBER = function("ws_number", JAVA_DOUBLE, JAVA_LONG, JAVA_INT);
    private static final MethodHandle VALUE = function("ws_value", JAVA_INT, JAVA_LONG, JAVA_INT, ADDRESS);
    private static final MethodHandle STABILIZE = function("ws_stabilize", JAVA_INT, JAVA_LONG, ADDRESS);
    private static final MethodHandle EXPLAIN = function("ws_explain", JAVA_LONG, JAVA_LONG, JAVA_INT);
    private static final MethodHandle NAME = function("ws_name", JAVA_LONG, JAVA_LONG, JAVA_INT);
    private static final MethodHandle LAST_RECALCULATED = function("ws_last_recalculated", JAVA_LONG, JAVA_LONG);
    private static final MethodHandle SIZE = function("ws_size", JAVA_LONG, JAVA_LONG);
    private static final MethodHandle REMOVE = function("ws_remove", JAVA_INT, JAVA_LONG, JAVA_INT);
    private static final MethodHandle WATCH = function("ws_watch", JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_INT);
    private static final MethodHandle ONLY_WATCHED = function("ws_calculate_only_watched", JAVA_INT, JAVA_LONG, JAVA_INT);
    private static final MethodHandle CHECK = function("ws_check_consistency", JAVA_INT, JAVA_LONG);
    private static final MethodHandle COLLECTION_NEW = function("ws_collection_new", JAVA_LONG, JAVA_LONG, ADDRESS, JAVA_LONG);
    private static final MethodHandle COLLECTION_FREE = function("ws_collection_free", null, JAVA_LONG);
    private static final MethodHandle FIELD = function("ws_field", JAVA_INT, JAVA_LONG, ADDRESS, JAVA_LONG, JAVA_INT);
    private static final MethodHandle COLUMN = function("ws_column", JAVA_INT,
            JAVA_LONG, ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG);
    private static final MethodHandle TOTAL = function("ws_total", JAVA_INT,
            JAVA_LONG, ADDRESS, JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG);
    private static final MethodHandle UPSERT = function("ws_upsert", JAVA_INT, JAVA_LONG, ADDRESS, JAVA_LONG, JAVA_LONG);
    private static final MethodHandle ROW_REMOVE = function("ws_row_remove", JAVA_INT, JAVA_LONG, ADDRESS, JAVA_LONG);
    private static final MethodHandle ROW_VALUE = function("ws_row_value", JAVA_INT,
            JAVA_LONG, ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG, ADDRESS);
    private static final MethodHandle ROWS = function("ws_rows", JAVA_LONG, JAVA_LONG);

    private static final MethodHandle PLAN_NEW = function("ws_plan_new", JAVA_LONG,
            JAVA_LONG, ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG);
    private static final MethodHandle PLAN_FREE = function("ws_plan_free", null, JAVA_LONG);
    private static final MethodHandle PLAN_SAVE = function("ws_plan_save", JAVA_INT, JAVA_LONG, ADDRESS, JAVA_LONG);
    private static final MethodHandle PLAN_LOAD = function("ws_plan_load", JAVA_LONG, ADDRESS, JAVA_LONG);
    private static final MethodHandle PLAN_SIZES = function("ws_plan_sizes", JAVA_INT, JAVA_LONG, ADDRESS);
    private static final MethodHandle PLAN_SET_SHARED = function("ws_plan_set_shared", JAVA_INT,
            JAVA_LONG, ADDRESS, JAVA_LONG);
    private static final MethodHandle PLAN_RUN_CPU = function("ws_plan_run_cpu", JAVA_INT,
            JAVA_LONG, ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG);
    private static final MethodHandle GPU_NEW = function("ws_gpu_new", JAVA_LONG);
    private static final MethodHandle GPU_FREE = function("ws_gpu_free", null, JAVA_LONG);
    private static final MethodHandle GPU_FREE_MEMORY = function("ws_gpu_free_memory", JAVA_LONG, JAVA_LONG);
    private static final MethodHandle GPU_RUN = function("ws_gpu_run", JAVA_INT,
            JAVA_LONG, JAVA_LONG, ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG, JAVA_LONG);

    private static final MethodHandle KEPT_NEW = function("ws_kept_new", JAVA_LONG,
            JAVA_LONG, ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG);
    private static final MethodHandle KEPT_FREE = function("ws_kept_free", null, JAVA_LONG);
    private static final MethodHandle KEPT_PLAN = function("ws_kept_plan", JAVA_LONG, JAVA_LONG);
    private static final MethodHandle KEPT_COLUMNS = function("ws_kept_columns", JAVA_LONG, JAVA_LONG);
    private static final MethodHandle KEPT_PATCH = function("ws_kept_patch", JAVA_INT,
            JAVA_LONG, JAVA_LONG, ADDRESS, JAVA_LONG, ADDRESS);
    private static final MethodHandle PATCH_FREE = function("ws_patch_free", null, JAVA_LONG);
    private static final MethodHandle PATCH_SAVE = function("ws_patch_save", JAVA_INT, JAVA_LONG, ADDRESS, JAVA_LONG);
    private static final MethodHandle PATCH_LOAD = function("ws_patch_load", JAVA_LONG, ADDRESS, JAVA_LONG);
    private static final MethodHandle PATCH_SIZES = function("ws_patch_sizes", JAVA_INT, JAVA_LONG, ADDRESS);
    private static final MethodHandle HELD_NEW = function("ws_held_new", JAVA_LONG,
            JAVA_LONG, ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG);
    private static final MethodHandle HELD_FREE = function("ws_held_free", null, JAVA_LONG);
    private static final MethodHandle HELD_COLUMNS = function("ws_held_columns", JAVA_INT,
            JAVA_LONG, ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG);
    private static final MethodHandle HELD_REFRESH = function("ws_held_refresh", JAVA_INT,
            JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG);

    // Room for what a call writes back (up to four numbers), per thread, so
    // reading a value or running an update allocates nothing.
    private static final ThreadLocal<MemorySegment> OUT =
            ThreadLocal.withInitial(() -> Arena.global().allocate(JAVA_LONG, 4));

    private static final int DONE = 0;

    // --------------------------------------------------------------- helpers

    /** A call into the engine never throws; this is for the compiler, which cannot know that. */
    private static RuntimeException impossible(Throwable thrown) {
        return thrown instanceof RuntimeException known ? known : new IllegalStateException(thrown);
    }

    private static MemorySegment bytes(Arena arena, byte[] bytes) {
        return bytes.length == 0 ? MemorySegment.NULL : arena.allocateFrom(JAVA_BYTE, bytes);
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** What the engine kept for this thread: the last error's reason, or the last text or list. */
    private static byte[] kept(MethodHandle which) {
        try (Arena arena = Arena.ofConfined()) {
            long room = 256;
            MemorySegment to = arena.allocate(room);
            long length = (long) which.invokeExact(to, room);
            if (length > room) {
                to = arena.allocate(length);
                length = (long) which.invokeExact(to, length);
            }
            return to.asSlice(0, length).toArray(JAVA_BYTE);
        } catch (Throwable thrown) {
            throw impossible(thrown);
        }
    }

    private static WatershedException refused() {
        return new WatershedException(new String(kept(ERROR), StandardCharsets.UTF_8));
    }

    private static void done(int status) {
        if (status != DONE) {
            throw refused();
        }
    }

    private static int node(int node) {
        if (node < 0) {
            throw refused();
        }
        return node;
    }

    private static String text(long length) {
        if (length < 0) {
            throw refused();
        }
        return new String(kept(RESULT), StandardCharsets.UTF_8);
    }

    private static List<String> texts() {
        ByteBuffer bytes = ByteBuffer.wrap(kept(RESULT)).order(ByteOrder.LITTLE_ENDIAN);
        int count = bytes.getInt();
        List<String> texts = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            byte[] utf8 = new byte[bytes.getInt()];
            bytes.get(utf8);
            texts.add(new String(utf8, StandardCharsets.UTF_8));
        }
        return texts;
    }

    /** A value the engine wrote to {@code out}: a Double, Long, BigDecimal, Boolean, String, list of Strings, or {@link Failed}. */
    private static Object read(int kind, MemorySegment out) {
        return switch (kind) {
            case Wire.NUMBER -> Double.longBitsToDouble(out.getAtIndex(JAVA_LONG, 0));
            case Wire.WHOLE -> out.getAtIndex(JAVA_LONG, 0);
            case Wire.DECIMAL -> Wire.decimal(
                    out.getAtIndex(JAVA_LONG, 0), out.getAtIndex(JAVA_LONG, 1), (int) out.getAtIndex(JAVA_LONG, 2));
            case Wire.FLAG -> out.getAtIndex(JAVA_LONG, 0) != 0;
            case Wire.TEXT -> new String(kept(RESULT), StandardCharsets.UTF_8);
            case Wire.TEXTS -> texts();
            case Wire.FAILED -> new Failed(new String(kept(RESULT), StandardCharsets.UTF_8));
            default -> throw refused();
        };
    }

    /** Frees a graph or collection once nothing can reach {@code owner}. */
    private static void freeWith(Object owner, MethodHandle free, long address) {
        CLEANER.register(owner, () -> {
            try {
                free.invokeExact(address);
            } catch (Throwable thrown) {
                throw impossible(thrown);
            }
        });
    }

    // ----------------------------------------------------------------- graph

    /** A new graph, freed once nothing can reach {@code owner}. */
    public static long graph(Object owner) {
        try {
            long address = (long) GRAPH_NEW.invokeExact();
            freeWith(owner, GRAPH_FREE, address);
            return address;
        } catch (Throwable thrown) {
            throw impossible(thrown);
        }
    }

    /** Adds an input holding a Double, Long, BigDecimal, Boolean or String. */
    public static int input(long graph, String name, Object value) {
        try (Arena arena = Arena.ofConfined()) {
            byte[] named = utf8(name);
            MemorySegment at = bytes(arena, named);
            return node(switch (value) {
                case Double number -> (int) INPUT.invokeExact(graph, at, (long) named.length,
                        (int) Wire.NUMBER, number.doubleValue(), 0L, 0L, 0, MemorySegment.NULL, 0L);
                case Long whole -> (int) INPUT.invokeExact(graph, at, (long) named.length,
                        (int) Wire.WHOLE, 0.0, whole.longValue(), 0L, 0, MemorySegment.NULL, 0L);
                case BigDecimal decimal -> {
                    long[] parts = Wire.parts(decimal);
                    yield (int) INPUT.invokeExact(graph, at, (long) named.length,
                            (int) Wire.DECIMAL, 0.0, parts[0], parts[1], (int) parts[2], MemorySegment.NULL, 0L);
                }
                case Boolean flag -> (int) INPUT.invokeExact(graph, at, (long) named.length,
                        (int) Wire.FLAG, 0.0, flag ? 1L : 0L, 0L, 0, MemorySegment.NULL, 0L);
                case String text -> {
                    byte[] held = utf8(text);
                    yield (int) INPUT.invokeExact(graph, at, (long) named.length,
                            (int) Wire.TEXT, 0.0, 0L, 0L, 0, bytes(arena, held), (long) held.length);
                }
                case null, default -> throw new WatershedException(
                        "a value is a Double, a Long, a BigDecimal, a Boolean or a String, not " + value);
            });
        } catch (Throwable thrown) {
            throw impossible(thrown);
        }
    }

    /** Adds a calculated node from an expression written by {@link Wire}. */
    public static int define(long graph, String name, byte[] expression) {
        try (Arena arena = Arena.ofConfined()) {
            byte[] named = utf8(name);
            return node((int) DEFINE.invokeExact(graph, bytes(arena, named), (long) named.length,
                    bytes(arena, expression), (long) expression.length));
        } catch (Throwable thrown) {
            throw impossible(thrown);
        }
    }

    /** Adds many calculated nodes in one call, in order. */
    public static int[] defineMany(long graph, List<String> names, List<byte[]> expressions) {
        Wire data = new Wire();
        for (int i = 0; i < names.size(); i++) {
            byte[] expression = expressions.get(i);
            data.put(names.get(i)).put(expression.length).put(expression);
        }
        byte[] written = data.bytes();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment nodes = arena.allocate(JAVA_INT, Math.max(1, names.size()));
            done((int) DEFINE_MANY.invokeExact(graph, bytes(arena, written), (long) written.length,
                    nodes, (long) names.size()));
            return nodes.asSlice(0, 4L * names.size()).toArray(JAVA_INT);
        } catch (Throwable thrown) {
            throw impossible(thrown);
        }
    }

    /** Records a new value for a Double input. */
    public static void setNumber(long graph, int node, double value) {
        try {
            done((int) SET_NUMBER.invokeExact(graph, node, value));
        } catch (Throwable thrown) {
            throw impossible(thrown);
        }
    }

    /** Records a new value for a Long input. */
    public static void setWhole(long graph, int node, long value) {
        try {
            done((int) SET.invokeExact(graph, node, (int) Wire.WHOLE, 0.0, value, 0L, 0, MemorySegment.NULL, 0L));
        } catch (Throwable thrown) {
            throw impossible(thrown);
        }
    }

    /** Records a new value for a Boolean input. */
    public static void setFlag(long graph, int node, boolean value) {
        try {
            done((int) SET.invokeExact(graph, node, (int) Wire.FLAG, 0.0, value ? 1L : 0L, 0L, 0, MemorySegment.NULL, 0L));
        } catch (Throwable thrown) {
            throw impossible(thrown);
        }
    }

    /** Records a new value for a BigDecimal input. */
    public static void setDecimal(long graph, int node, BigDecimal value) {
        long[] parts = Wire.parts(value);
        try {
            done((int) SET.invokeExact(graph, node, (int) Wire.DECIMAL, 0.0, parts[0], parts[1], (int) parts[2],
                    MemorySegment.NULL, 0L));
        } catch (Throwable thrown) {
            throw impossible(thrown);
        }
    }

    /** Records a new value for a String input. */
    public static void setText(long graph, int node, String value) {
        try (Arena arena = Arena.ofConfined()) {
            byte[] held = utf8(value);
            done((int) SET.invokeExact(graph, node, (int) Wire.TEXT, 0.0, 0L, 0L, 0, bytes(arena, held), (long) held.length));
        } catch (Throwable thrown) {
            throw impossible(thrown);
        }
    }

    /** Records new values for many Double inputs in one call. */
    public static void setNumbers(long graph, int[] nodes, double[] values) {
        if (nodes.length != values.length) {
            throw new WatershedException(nodes.length + " nodes were given " + values.length + " values");
        }
        if (nodes.length == 0) {
            return;
        }
        try (Arena arena = Arena.ofConfined()) {
            done((int) SET_NUMBERS.invokeExact(graph, arena.allocateFrom(JAVA_INT, nodes),
                    arena.allocateFrom(JAVA_DOUBLE, values), (long) nodes.length));
        } catch (Throwable thrown) {
            throw impossible(thrown);
        }
    }

    /** A Double node's value as of the last update. A node that could not be calculated throws, with why. */
    public static double number(long graph, int node) {
        try {
            double held = (double) NUMBER.invokeExact(graph, node);
            if (!Double.isNaN(held)) {
                return held;
            }
        } catch (Throwable thrown) {
            throw impossible(thrown);
        }
        // NaN is also how the engine says it could not answer that simply.
        return switch (value(graph, node)) {
            case Double number -> number;
            case Failed failed -> throw new WatershedException(name(graph, node) + " could not be calculated: " + failed.why());
            case Object other -> throw new WatershedException(name(graph, node) + " holds " + other + ", not a number");
        };
    }

    /** A node's value as of the last update, or a {@link Failed} if it could not be calculated. */
    public static Object value(long graph, int node) {
        MemorySegment out = OUT.get();
        try {
            return read((int) VALUE.invokeExact(graph, node, out), out);
        } catch (Throwable thrown) {
            throw impossible(thrown);
        }
    }

    /** Applies every recorded change and recalculates what depends on them, and nothing else. */
    public static Report stabilize(long graph) {
        MemorySegment out = OUT.get();
        try {
            done((int) STABILIZE.invokeExact(graph, out));
        } catch (Throwable thrown) {
            throw impossible(thrown);
        }
        return new Report(out.getAtIndex(JAVA_LONG, 0), out.getAtIndex(JAVA_LONG, 1),
                out.getAtIndex(JAVA_LONG, 2), out.getAtIndex(JAVA_LONG, 3));
    }

    public static String explain(long graph, int node) {
        try {
            return text((long) EXPLAIN.invokeExact(graph, node));
        } catch (Throwable thrown) {
            throw impossible(thrown);
        }
    }

    public static String name(long graph, int node) {
        try {
            return text((long) NAME.invokeExact(graph, node));
        } catch (Throwable thrown) {
            throw impossible(thrown);
        }
    }

    public static List<String> lastRecalculated(long graph) {
        try {
            if ((long) LAST_RECALCULATED.invokeExact(graph) < 0) {
                throw refused();
            }
        } catch (Throwable thrown) {
            throw impossible(thrown);
        }
        return texts();
    }

    public static long size(long graph) {
        try {
            return (long) SIZE.invokeExact(graph);
        } catch (Throwable thrown) {
            throw impossible(thrown);
        }
    }

    public static void remove(long graph, int node) {
        try {
            done((int) REMOVE.invokeExact(graph, node));
        } catch (Throwable thrown) {
            throw impossible(thrown);
        }
    }

    public static void watch(long graph, int node, boolean on) {
        try {
            done((int) WATCH.invokeExact(graph, node, on ? 1 : 0));
        } catch (Throwable thrown) {
            throw impossible(thrown);
        }
    }

    public static void calculateOnlyWatched(long graph, boolean on) {
        try {
            done((int) ONLY_WATCHED.invokeExact(graph, on ? 1 : 0));
        } catch (Throwable thrown) {
            throw impossible(thrown);
        }
    }

    public static void checkConsistency(long graph) {
        try {
            done((int) CHECK.invokeExact(graph));
        } catch (Throwable thrown) {
            throw impossible(thrown);
        }
    }

    // ------------------------------------------------------------ collection

    /** A new collection in a graph, freed once nothing can reach {@code owner}, which must keep the graph's owner reachable. */
    public static long collection(Object owner, long graph, String name) {
        try (Arena arena = Arena.ofConfined()) {
            byte[] named = utf8(name);
            long address = (long) COLLECTION_NEW.invokeExact(graph, bytes(arena, named), (long) named.length);
            if (address == 0) {
                throw refused();
            }
            freeWith(owner, COLLECTION_FREE, address);
            return address;
        } catch (Throwable thrown) {
            throw impossible(thrown);
        }
    }

    public static void field(long collection, String name, byte kind) {
        try (Arena arena = Arena.ofConfined()) {
            byte[] named = utf8(name);
            done((int) FIELD.invokeExact(collection, bytes(arena, named), (long) named.length, (int) kind));
        } catch (Throwable thrown) {
            throw impossible(thrown);
        }
    }

    public static void column(long collection, String name, byte[] expression) {
        try (Arena arena = Arena.ofConfined()) {
            byte[] named = utf8(name);
            done((int) COLUMN.invokeExact(collection, bytes(arena, named), (long) named.length,
                    bytes(arena, expression), (long) expression.length));
        } catch (Throwable thrown) {
            throw impossible(thrown);
        }
    }

    /** A total ({@link Wire#SUM} and the others) of a field or column, or of rows when {@code of} is null. */
    public static int total(long collection, String name, int op, String of) {
        try (Arena arena = Arena.ofConfined()) {
            byte[] named = utf8(name);
            byte[] part = of == null ? new byte[0] : utf8(of);
            return node((int) TOTAL.invokeExact(collection, bytes(arena, named), (long) named.length, op,
                    bytes(arena, part), of == null ? -1L : (long) part.length));
        } catch (Throwable thrown) {
            throw impossible(thrown);
        }
    }

    /** Adds or changes many rows in one call: for each, its key, how many fields, and each field's name and value. */
    public static void upsert(long collection, byte[] rows, int count) {
        try (Arena arena = Arena.ofConfined()) {
            done((int) UPSERT.invokeExact(collection, bytes(arena, rows), (long) rows.length, (long) count));
        } catch (Throwable thrown) {
            throw impossible(thrown);
        }
    }

    public static boolean removeRow(long collection, String key) {
        try (Arena arena = Arena.ofConfined()) {
            byte[] keyed = utf8(key);
            int removed = (int) ROW_REMOVE.invokeExact(collection, bytes(arena, keyed), (long) keyed.length);
            if (removed < 0) {
                throw refused();
            }
            return removed == 1;
        } catch (Throwable thrown) {
            throw impossible(thrown);
        }
    }

    /** A field or column of a row, as {@link #value} gives a node's. */
    public static Object rowValue(long collection, String key, String part) {
        MemorySegment out = OUT.get();
        try (Arena arena = Arena.ofConfined()) {
            byte[] keyed = utf8(key);
            byte[] named = utf8(part);
            return read((int) ROW_VALUE.invokeExact(collection, bytes(arena, keyed), (long) keyed.length,
                    bytes(arena, named), (long) named.length, out), out);
        } catch (Throwable thrown) {
            throw impossible(thrown);
        }
    }

    public static long rows(long collection) {
        try {
            return (long) ROWS.invokeExact(collection);
        } catch (Throwable thrown) {
            throw impossible(thrown);
        }
    }

    // ----------------------------------------------------------------- plans

    private static MemorySegment ints(Arena arena, int[] values) {
        return values.length == 0 ? MemorySegment.NULL : arena.allocateFrom(JAVA_INT, values);
    }

    private static MemorySegment doubles(Arena arena, double[] values) {
        return values.length == 0 ? MemorySegment.NULL : arena.allocateFrom(JAVA_DOUBLE, values);
    }

    private static long address(long address) {
        if (address == 0) {
            throw refused();
        }
        return address;
    }

    /**
     * A plan of the graph as it stands, freed once nothing can reach {@code owner}: the inputs to vary in every
     * scenario, those shared by all of a run, and the outputs to read.
     */
    public static long plan(Object owner, long graph, int[] vary, int[] shared, int[] outputs) {
        try (Arena arena = Arena.ofConfined()) {
            long address = address((long) PLAN_NEW.invokeExact(graph, ints(arena, vary), (long) vary.length,
                    ints(arena, shared), (long) shared.length, ints(arena, outputs), (long) outputs.length));
            freeWith(owner, PLAN_FREE, address);
            return address;
        } catch (Throwable thrown) {
            throw impossible(thrown);
        }
    }

    /** A plan read from a file written by {@link #planSave}, freed once nothing can reach {@code owner}. */
    public static long planLoad(Object owner, String path) {
        try (Arena arena = Arena.ofConfined()) {
            byte[] at = utf8(path);
            long address = address((long) PLAN_LOAD.invokeExact(bytes(arena, at), (long) at.length));
            freeWith(owner, PLAN_FREE, address);
            return address;
        } catch (Throwable thrown) {
            throw impossible(thrown);
        }
    }

    /** Writes a plan to a file. */
    public static void planSave(long plan, String path) {
        try (Arena arena = Arena.ofConfined()) {
            byte[] at = utf8(path);
            done((int) PLAN_SAVE.invokeExact(plan, bytes(arena, at), (long) at.length));
        } catch (Throwable thrown) {
            throw impossible(thrown);
        }
    }

    /** How many varied inputs, shared inputs and outputs a plan has, and how many nodes it works out for a scenario. */
    public static long[] planSizes(long plan) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(JAVA_LONG, 4);
            done((int) PLAN_SIZES.invokeExact(plan, out));
            return out.toArray(JAVA_LONG);
        } catch (Throwable thrown) {
            throw impossible(thrown);
        }
    }

    /** Gives a plan's shared inputs new values, for every run from now on. */
    public static void planSetShared(long plan, double[] values) {
        try (Arena arena = Arena.ofConfined()) {
            done((int) PLAN_SET_SHARED.invokeExact(plan, doubles(arena, values), (long) values.length));
        } catch (Throwable thrown) {
            throw impossible(thrown);
        }
    }

    /**
     * Runs scenarios on the CPU, on all cores. {@code inputs} holds one scenario after another, and the result
     * one scenario's outputs after another: {@code results} values in all.
     */
    public static double[] planRunCpu(long plan, double[] inputs, long results) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(JAVA_DOUBLE, Math.max(results, 1));
            done((int) PLAN_RUN_CPU.invokeExact(plan, doubles(arena, inputs), (long) inputs.length, out, results));
            return out.asSlice(0, results * Double.BYTES).toArray(JAVA_DOUBLE);
        } catch (Throwable thrown) {
            throw impossible(thrown);
        }
    }

    /** An NVIDIA GPU to run plans on, freed once nothing can reach {@code owner}. Throws if there is none. */
    public static long gpu(Object owner) {
        try {
            long address = address((long) GPU_NEW.invokeExact());
            freeWith(owner, GPU_FREE, address);
            return address;
        } catch (Throwable thrown) {
            throw impossible(thrown);
        }
    }

    /** The bytes of a GPU's memory that are free. */
    public static long gpuFreeMemory(long gpu) {
        try {
            long free = (long) GPU_FREE_MEMORY.invokeExact(gpu);
            if (free < 0) {
                throw refused();
            }
            return free;
        } catch (Throwable thrown) {
            throw impossible(thrown);
        }
    }

    /** Runs scenarios on a GPU, as {@link #planRunCpu} does on the CPU, within {@code allowed} bytes of its memory. */
    public static double[] gpuRun(long gpu, long plan, double[] inputs, long results, long allowed) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(JAVA_DOUBLE, Math.max(results, 1));
            done((int) GPU_RUN.invokeExact(gpu, plan, doubles(arena, inputs), (long) inputs.length, out, results,
                    allowed));
            return out.asSlice(0, results * Double.BYTES).toArray(JAVA_DOUBLE);
        } catch (Throwable thrown) {
            throw impossible(thrown);
        }
    }

    // ------------------------------------------------ keeping a run up to date

    /**
     * What to keep of a graph's plan so that a run can be brought up to date after a change, freed once nothing can
     * reach {@code owner}: the plan's varied and shared inputs and its outputs, and the nodes whose values to keep.
     */
    public static long kept(Object owner, long graph, int[] vary, int[] shared, int[] outputs, int[] keep) {
        try (Arena arena = Arena.ofConfined()) {
            long address = address((long) KEPT_NEW.invokeExact(graph, ints(arena, vary), (long) vary.length,
                    ints(arena, shared), (long) shared.length, ints(arena, outputs), (long) outputs.length,
                    ints(arena, keep), (long) keep.length));
            freeWith(owner, KEPT_FREE, address);
            return address;
        } catch (Throwable thrown) {
            throw impossible(thrown);
        }
    }

    /** The plan whose outputs are every kept value of a scenario, freed once nothing can reach {@code owner}. */
    public static long keptPlan(Object owner, long kept) {
        try {
            long address = address((long) KEPT_PLAN.invokeExact(kept));
            freeWith(owner, PLAN_FREE, address);
            return address;
        } catch (Throwable thrown) {
            throw impossible(thrown);
        }
    }

    /** Where each output is among a scenario's kept values. */
    public static int[] keptColumns(long kept) {
        try {
            if ((long) KEPT_COLUMNS.invokeExact(kept) < 0) {
                throw refused();
            }
            ByteBuffer bytes = ByteBuffer.wrap(kept(RESULT)).order(ByteOrder.LITTLE_ENDIAN);
            int[] columns = new int[bytes.remaining() / Integer.BYTES];
            bytes.asIntBuffer().get(columns);
            return columns;
        } catch (Throwable thrown) {
            throw impossible(thrown);
        }
    }

    /**
     * What to run now that the inputs {@code changed} have new values on the graph: a patch's address, or 0 if nothing
     * kept uses them. Give a patch to {@link #ownPatch} at once, so that it is freed.
     */
    public static long keptPatch(long kept, long graph, int[] changed) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(JAVA_LONG);
            done((int) KEPT_PATCH.invokeExact(kept, graph, ints(arena, changed), (long) changed.length, out));
            return out.get(JAVA_LONG, 0);
        } catch (Throwable thrown) {
            throw impossible(thrown);
        }
    }

    /** Has the patch at {@code address} freed once nothing can reach {@code owner}, and returns the address. */
    public static long ownPatch(Object owner, long address) {
        freeWith(owner, PATCH_FREE, address);
        return address;
    }

    /** A patch read from a file written by {@link #patchSave}, freed once nothing can reach {@code owner}. */
    public static long patchLoad(Object owner, String path) {
        try (Arena arena = Arena.ofConfined()) {
            byte[] at = utf8(path);
            return ownPatch(owner, address((long) PATCH_LOAD.invokeExact(bytes(arena, at), (long) at.length)));
        } catch (Throwable thrown) {
            throw impossible(thrown);
        }
    }

    /** Writes a patch to a file. */
    public static void patchSave(long patch, String path) {
        try (Arena arena = Arena.ofConfined()) {
            byte[] at = utf8(path);
            done((int) PATCH_SAVE.invokeExact(patch, bytes(arena, at), (long) at.length));
        } catch (Throwable thrown) {
            throw impossible(thrown);
        }
    }

    /** How many values are kept for a scenario, how many of them a patch works out again, and on how many nodes. */
    public static long[] patchSizes(long patch) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(JAVA_LONG, 3);
            done((int) PATCH_SIZES.invokeExact(patch, out));
            return out.toArray(JAVA_LONG);
        } catch (Throwable thrown) {
            throw impossible(thrown);
        }
    }

    /**
     * Some scenarios' inputs ({@code vary} to a scenario) and the kept values a plan gave for them, copied into the
     * engine and freed once nothing can reach {@code owner}.
     */
    public static long held(Object owner, int vary, double[] inputs, double[] values) {
        try (Arena arena = Arena.ofConfined()) {
            long address = address((long) HELD_NEW.invokeExact((long) vary, doubles(arena, inputs),
                    (long) inputs.length, doubles(arena, values), (long) values.length));
            freeWith(owner, HELD_FREE, address);
            return address;
        } catch (Throwable thrown) {
            throw impossible(thrown);
        }
    }

    /** For each scenario held in turn, its kept values in the places {@code columns}: {@code results} values in all. */
    public static double[] heldColumns(long held, int[] columns, long results) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(JAVA_DOUBLE, Math.max(results, 1));
            done((int) HELD_COLUMNS.invokeExact(held, ints(arena, columns), (long) columns.length, out, results));
            return out.asSlice(0, results * Double.BYTES).toArray(JAVA_DOUBLE);
        } catch (Throwable thrown) {
            throw impossible(thrown);
        }
    }

    /** Brings held values up to date with a patch, on the GPU within {@code allowed} bytes, or on the CPU if {@code gpu} is 0. */
    public static void heldRefresh(long held, long patch, long gpu, long allowed) {
        try {
            done((int) HELD_REFRESH.invokeExact(held, patch, gpu, allowed));
        } catch (Throwable thrown) {
            throw impossible(thrown);
        }
    }
}
