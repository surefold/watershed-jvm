package dev.surefold.watershed

import dev.surefold.fail.Either
import dev.surefold.fail.Fail
import dev.surefold.fail.attempt
import dev.surefold.fail.bind
import dev.surefold.fail.ensure
import dev.surefold.fail.ensureNotNull
import dev.surefold.fail.fold
import dev.surefold.fail.mapLeft
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_DOUBLE
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.ref.Cleaner
import java.math.BigDecimal
import kotlin.io.path.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.pathString

/**
 * The engine's C functions (see `src/ffi.rs`), called through the JDK's foreign function API. A call costs tens of
 * nanoseconds. The JDK is the only way to reach a C library from the JVM, so this file is where its types are used.
 *
 * A graph or collection is a `Long`: its address in the engine. The typed Kotlin and Scala layers hold those and are
 * what a program uses. This is not for calling directly: an address that is wrong, or used after it is freed,
 * crashes the JVM.
 *
 * A call the engine refuses fails its scope with [Refused]; nothing here throws for one. A call that makes something
 * answers its address, and `own…` then has it freed once nothing can reach its owner.
 *
 * The library is found through the system property `watershed.library` (the file) or `watershed.library.path` (a
 * folder holding it), or else on `java.library.path`.
 */
object Native {
    /** A node that could not be calculated, and why. Answered by [value] in place of a value. */
    @JvmRecord
    data class Failed(val why: String)

    private val cleaner = Cleaner.create()

    // The engine's library, opened once; or why it could not be. Nothing is called before this has been asked.
    private val opened: Either<Refused, SymbolLookup> = attempt {
        val name = System.mapLibraryName("watershed_jvm")
        val folders = System.getProperty("watershed.library.path", "") + System.getProperty("path.separator") + System.getProperty("java.library.path", "")
        val file = System.getProperty("watershed.library")
            ?: folders.split(System.getProperty("path.separator")).filter { it.isNotEmpty() }.map { Path(it, name) }.firstOrNull { it.isRegularFile() }?.pathString
        ensureNotNull(file) {
            Refused("cannot find $name: set -Dwatershed.library.path to the folder it was built into (target/release)")
        }
        Either.catch { SymbolLookup.libraryLookup(file, Arena.global()) }.mapLeft { Refused("cannot open $file: $it") }.bind()
    }

    /** Refused unless the engine's library was found and opened. Everything that makes a graph, a plan or a GPU asks. */
    context(_: Fail<Refused>)
    private fun ready() {
        opened.bind()
        ensure(Calls.missing.isEmpty()) {
            Refused("the engine's library has no ${Calls.missing.joinToString()}: it was built from another version of the engine")
        }
    }

    // The engine's functions. They are an object of their own so that they are looked up on first use, which is
    // after [ready] has answered, and are constants to the JIT from then on.
    private object Calls {
        private val library: SymbolLookup = opened.fold({ SymbolLookup.loaderLookup() }, { it })

        // The functions the library lacks, noted as each is looked up, for [ready] to refuse with.
        @JvmField val missing: MutableList<String> = mutableListOf()

        // A function the library lacks is stood in for by one that does nothing: [ready] refuses before it is called.
        private fun function(name: String, result: MemoryLayout?, vararg arguments: MemoryLayout): MethodHandle {
            val shape = if (result == null) FunctionDescriptor.ofVoid(*arguments) else FunctionDescriptor.of(result, *arguments)
            return library.find(name)
                .map { Linker.nativeLinker().downcallHandle(it, shape) }
                .orElseGet { MethodHandles.empty(shape.toMethodType()).also { missing += name } }
        }

        @JvmField val ERROR = function("ws_error", JAVA_LONG, ADDRESS, JAVA_LONG)
        @JvmField val RESULT = function("ws_result", JAVA_LONG, ADDRESS, JAVA_LONG)
        @JvmField val GRAPH_NEW = function("ws_graph_new", JAVA_LONG)
        @JvmField val GRAPH_FREE = function("ws_graph_free", null, JAVA_LONG)
        @JvmField val INPUT = function("ws_input", JAVA_INT, JAVA_LONG, ADDRESS, JAVA_LONG, JAVA_INT, JAVA_DOUBLE, JAVA_LONG, JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG)
        @JvmField val DEFINE = function("ws_define", JAVA_INT, JAVA_LONG, ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG)
        @JvmField val DEFINE_MANY = function("ws_define_many", JAVA_INT, JAVA_LONG, ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG)
        @JvmField val SET = function("ws_set", JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_DOUBLE, JAVA_LONG, JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG)
        @JvmField val SET_NUMBER = function("ws_set_number", JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_DOUBLE)
        @JvmField val SET_NUMBERS = function("ws_set_numbers", JAVA_INT, JAVA_LONG, ADDRESS, ADDRESS, JAVA_LONG)
        @JvmField val NUMBER = function("ws_number", JAVA_DOUBLE, JAVA_LONG, JAVA_INT)
        @JvmField val VALUE = function("ws_value", JAVA_INT, JAVA_LONG, JAVA_INT, ADDRESS)
        @JvmField val STABILIZE = function("ws_stabilize", JAVA_INT, JAVA_LONG, ADDRESS)
        @JvmField val EXPLAIN = function("ws_explain", JAVA_LONG, JAVA_LONG, JAVA_INT)
        @JvmField val NAME = function("ws_name", JAVA_LONG, JAVA_LONG, JAVA_INT)
        @JvmField val LAST_RECALCULATED = function("ws_last_recalculated", JAVA_LONG, JAVA_LONG)
        @JvmField val SIZE = function("ws_size", JAVA_LONG, JAVA_LONG)
        @JvmField val REMOVE = function("ws_remove", JAVA_INT, JAVA_LONG, JAVA_INT)
        @JvmField val WATCH = function("ws_watch", JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_INT)
        @JvmField val ONLY_WATCHED = function("ws_calculate_only_watched", JAVA_INT, JAVA_LONG, JAVA_INT)
        @JvmField val CHECK = function("ws_check_consistency", JAVA_INT, JAVA_LONG)
        @JvmField val COLLECTION_NEW = function("ws_collection_new", JAVA_LONG, JAVA_LONG, ADDRESS, JAVA_LONG)
        @JvmField val COLLECTION_FREE = function("ws_collection_free", null, JAVA_LONG)
        @JvmField val FIELD = function("ws_field", JAVA_INT, JAVA_LONG, ADDRESS, JAVA_LONG, JAVA_INT)
        @JvmField val COLUMN = function("ws_column", JAVA_INT, JAVA_LONG, ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG)
        @JvmField val TOTAL = function("ws_total", JAVA_INT, JAVA_LONG, ADDRESS, JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG)
        @JvmField val UPSERT = function("ws_upsert", JAVA_INT, JAVA_LONG, ADDRESS, JAVA_LONG, JAVA_LONG)
        @JvmField val ROW_REMOVE = function("ws_row_remove", JAVA_INT, JAVA_LONG, ADDRESS, JAVA_LONG)
        @JvmField val ROW_VALUE = function("ws_row_value", JAVA_INT, JAVA_LONG, ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG, ADDRESS)
        @JvmField val ROWS = function("ws_rows", JAVA_LONG, JAVA_LONG)
        @JvmField val PLAN_NEW = function("ws_plan_new", JAVA_LONG, JAVA_LONG, ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG)
        @JvmField val PLAN_FREE = function("ws_plan_free", null, JAVA_LONG)
        @JvmField val PLAN_SAVE = function("ws_plan_save", JAVA_INT, JAVA_LONG, ADDRESS, JAVA_LONG)
        @JvmField val PLAN_LOAD = function("ws_plan_load", JAVA_LONG, ADDRESS, JAVA_LONG)
        @JvmField val PLAN_SIZES = function("ws_plan_sizes", JAVA_INT, JAVA_LONG, ADDRESS)
        @JvmField val PLAN_SET_SHARED = function("ws_plan_set_shared", JAVA_INT, JAVA_LONG, ADDRESS, JAVA_LONG)
        @JvmField val PLAN_RUN_CPU = function("ws_plan_run_cpu", JAVA_INT, JAVA_LONG, ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG)
        @JvmField val GPU_NEW = function("ws_gpu_new", JAVA_LONG)
        @JvmField val GPU_FREE = function("ws_gpu_free", null, JAVA_LONG)
        @JvmField val GPU_FREE_MEMORY = function("ws_gpu_free_memory", JAVA_LONG, JAVA_LONG)
        @JvmField val GPU_RUN = function("ws_gpu_run", JAVA_INT, JAVA_LONG, JAVA_LONG, ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG, JAVA_LONG)
        @JvmField val KEPT_NEW = function("ws_kept_new", JAVA_LONG, JAVA_LONG, ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG)
        @JvmField val KEPT_FREE = function("ws_kept_free", null, JAVA_LONG)
        @JvmField val KEPT_PLAN = function("ws_kept_plan", JAVA_LONG, JAVA_LONG)
        @JvmField val KEPT_COLUMNS = function("ws_kept_columns", JAVA_LONG, JAVA_LONG)
        @JvmField val KEPT_PATCH = function("ws_kept_patch", JAVA_INT, JAVA_LONG, JAVA_LONG, ADDRESS, JAVA_LONG, ADDRESS)
        @JvmField val PATCH_FREE = function("ws_patch_free", null, JAVA_LONG)
        @JvmField val PATCH_SAVE = function("ws_patch_save", JAVA_INT, JAVA_LONG, ADDRESS, JAVA_LONG)
        @JvmField val PATCH_LOAD = function("ws_patch_load", JAVA_LONG, ADDRESS, JAVA_LONG)
        @JvmField val PATCH_SIZES = function("ws_patch_sizes", JAVA_INT, JAVA_LONG, ADDRESS)
        @JvmField val HELD_NEW = function("ws_held_new", JAVA_LONG, JAVA_LONG, ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG)
        @JvmField val HELD_FREE = function("ws_held_free", null, JAVA_LONG)
        @JvmField val HELD_COLUMNS = function("ws_held_columns", JAVA_INT, JAVA_LONG, ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG)
        @JvmField val HELD_REFRESH = function("ws_held_refresh", JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG)

        @JvmField val SAVED_NEW = function("ws_saved_new", JAVA_LONG, JAVA_LONG)
        @JvmField val SAVED_FREE = function("ws_saved_free", null, JAVA_LONG)
        @JvmField val SAVED_SIZE = function("ws_saved_size", JAVA_LONG, JAVA_LONG)
        @JvmField val SAVED_READ = function("ws_saved_read", JAVA_INT, JAVA_LONG, JAVA_LONG, ADDRESS, JAVA_LONG)
        @JvmField val SAVED_WRITE = function("ws_saved_write", JAVA_INT, JAVA_LONG, JAVA_LONG, ADDRESS, JAVA_LONG)
        @JvmField val PLAN_SAVED = function("ws_plan_saved", JAVA_LONG, JAVA_LONG)
        @JvmField val PLAN_OF_SAVED = function("ws_plan_of_saved", JAVA_LONG, JAVA_LONG)
        @JvmField val PATCH_SAVED = function("ws_patch_saved", JAVA_LONG, JAVA_LONG)
        @JvmField val PATCH_OF_SAVED = function("ws_patch_of_saved", JAVA_LONG, JAVA_LONG)
    }

    private const val DONE = 0

    // --------------------------------------------------------------- helpers

    private fun bytes(arena: Arena, bytes: ByteArray): MemorySegment =
        if (bytes.isEmpty()) MemorySegment.NULL else arena.allocateFrom(JAVA_BYTE, *bytes)

    private fun ints(arena: Arena, values: IntArray): MemorySegment =
        if (values.isEmpty()) MemorySegment.NULL else arena.allocateFrom(JAVA_INT, *values)

    private fun doubles(arena: Arena, values: DoubleArray): MemorySegment =
        if (values.isEmpty()) MemorySegment.NULL else arena.allocateFrom(JAVA_DOUBLE, *values)

    /** What the engine kept for this thread: the last error's reason, or the last text or list. */
    private fun kept(which: MethodHandle): ByteArray = Arena.ofConfined().use { arena ->
        val room = 256L
        val first = arena.allocate(room)
        val length = which.invokeExact(first, room) as Long
        if (length <= room) {
            first.asSlice(0, length).toArray(JAVA_BYTE)
        } else {
            val all = arena.allocate(length)
            all.asSlice(0, which.invokeExact(all, length) as Long).toArray(JAVA_BYTE)
        }
    }

    private fun refused(): Refused = Refused(String(kept(Calls.ERROR)))

    context(fail: Fail<Refused>)
    private fun done(status: Int) {
        if (status != DONE) fail.fail(refused())
    }

    context(fail: Fail<Refused>)
    private fun node(node: Int): Int = if (node < 0) fail.fail(refused()) else node

    context(fail: Fail<Refused>)
    private fun address(address: Long): Long = if (address == 0L) fail.fail(refused()) else address

    context(fail: Fail<Refused>)
    private fun text(length: Long): String = if (length < 0) fail.fail(refused()) else String(kept(Calls.RESULT))

    // A whole number as the engine writes it: four bytes, least significant first.
    private fun ByteArray.int(at: Int): Int = (0 until 4).fold(0) { whole, byte -> whole or ((this[at + byte].toInt() and 0xff) shl (8 * byte)) }

    // The engine's list of texts: how many, then each as its length and its bytes.
    private fun texts(): List<String> {
        val bytes = kept(Calls.RESULT)
        return (0 until bytes.int(0)).runningFold(4 to "") { (at, _), _ ->
            val length = bytes.int(at)
            (at + 4 + length) to bytes.decodeToString(at + 4, at + 4 + length)
        }.drop(1).map { it.second }
    }

    /** A value the engine wrote to [out]: a Double, Long, BigDecimal, Boolean, String, list of Strings, or [Failed]. */
    context(fail: Fail<Refused>)
    private fun read(kind: Int, out: MemorySegment): Any = when (kind.toByte()) {
        Wire.NUMBER -> Double.fromBits(out.getAtIndex(JAVA_LONG, 0))
        Wire.WHOLE -> out.getAtIndex(JAVA_LONG, 0)
        Wire.DECIMAL -> Wire.decimal(out.getAtIndex(JAVA_LONG, 0), out.getAtIndex(JAVA_LONG, 1), out.getAtIndex(JAVA_LONG, 2).toInt())
        Wire.FLAG -> out.getAtIndex(JAVA_LONG, 0) != 0L
        Wire.TEXT -> String(kept(Calls.RESULT))
        Wire.TEXTS -> texts()
        Wire.FAILED -> Failed(String(kept(Calls.RESULT)))
        else -> fail.fail(refused())
    }

    /** Frees what is at [address] once nothing can reach [owner]. */
    private fun freeWith(owner: Any, free: MethodHandle, address: Long) {
        cleaner.register(owner) { free.invokeExact(address) }
    }

    // ----------------------------------------------------------------- graph

    /** A new graph; refused if the engine's library cannot be found. Give it to [ownGraph]. */
    context(_: Fail<Refused>)
    @JvmStatic
    fun graph(): Long {
        ready()
        return Calls.GRAPH_NEW.invokeExact() as Long
    }

    /** Has the graph freed once nothing can reach [owner]. */
    @JvmStatic
    fun ownGraph(owner: Any, address: Long) = freeWith(owner, Calls.GRAPH_FREE, address)

    /** Adds an input holding a Double, Long, BigDecimal, Boolean or String. */
    context(fail: Fail<Refused>)
    @JvmStatic
    fun input(graph: Long, name: String, value: Any?): Int = Arena.ofConfined().use { arena ->
        val named = name.toByteArray()
        val at = bytes(arena, named)
        val size = named.size.toLong()
        val none = MemorySegment.NULL
        node(
            when (value) {
                is Double -> Calls.INPUT.invokeExact(graph, at, size, Wire.NUMBER.toInt(), value, 0L, 0L, 0, none, 0L) as Int
                is Long -> Calls.INPUT.invokeExact(graph, at, size, Wire.WHOLE.toInt(), 0.0, value, 0L, 0, none, 0L) as Int
                is BigDecimal -> {
                    val (low, high, scale) = Wire.parts(value).bind()
                    Calls.INPUT.invokeExact(graph, at, size, Wire.DECIMAL.toInt(), 0.0, low, high, scale.toInt(), none, 0L) as Int
                }

                is Boolean -> Calls.INPUT.invokeExact(graph, at, size, Wire.FLAG.toInt(), 0.0, if (value) 1L else 0L, 0L, 0, none, 0L) as Int
                is String -> {
                    val held = value.toByteArray()
                    Calls.INPUT.invokeExact(graph, at, size, Wire.TEXT.toInt(), 0.0, 0L, 0L, 0, bytes(arena, held), held.size.toLong()) as Int
                }

                else -> fail.fail(Refused("a value is a Double, a Long, a BigDecimal, a Boolean or a String, not $value"))
            },
        )
    }

    /** Adds a calculated node from an expression written by [Wire]. */
    context(_: Fail<Refused>)
    @JvmStatic
    fun define(graph: Long, name: String, expression: ByteArray): Int = Arena.ofConfined().use { arena ->
        val named = name.toByteArray()
        node(Calls.DEFINE.invokeExact(graph, bytes(arena, named), named.size.toLong(), bytes(arena, expression), expression.size.toLong()) as Int)
    }

    /** Adds many calculated nodes in one call, in order. */
    context(_: Fail<Refused>)
    @JvmStatic
    fun defineMany(graph: Long, names: List<String>, expressions: List<ByteArray>): IntArray {
        val written = names.indices
            .fold(Wire()) { data, i -> data.put(names[i]).put(expressions[i].size).put(expressions[i]) }
            .bytes()
        return Arena.ofConfined().use { arena ->
            val nodes = arena.allocate(JAVA_INT, maxOf(1, names.size).toLong())
            done(Calls.DEFINE_MANY.invokeExact(graph, bytes(arena, written), written.size.toLong(), nodes, names.size.toLong()) as Int)
            nodes.asSlice(0, 4L * names.size).toArray(JAVA_INT)
        }
    }

    /** Records a new value for a Double input. */
    context(_: Fail<Refused>)
    @JvmStatic
    fun setNumber(graph: Long, node: Int, value: Double) = done(Calls.SET_NUMBER.invokeExact(graph, node, value) as Int)

    /** Records a new value for a Long input. */
    context(_: Fail<Refused>)
    @JvmStatic
    fun setWhole(graph: Long, node: Int, value: Long) =
        done(Calls.SET.invokeExact(graph, node, Wire.WHOLE.toInt(), 0.0, value, 0L, 0, MemorySegment.NULL, 0L) as Int)

    /** Records a new value for a Boolean input. */
    context(_: Fail<Refused>)
    @JvmStatic
    fun setFlag(graph: Long, node: Int, value: Boolean) =
        done(Calls.SET.invokeExact(graph, node, Wire.FLAG.toInt(), 0.0, if (value) 1L else 0L, 0L, 0, MemorySegment.NULL, 0L) as Int)

    /** Records a new value for a BigDecimal input. */
    context(_: Fail<Refused>)
    @JvmStatic
    fun setDecimal(graph: Long, node: Int, value: BigDecimal) {
        val (low, high, scale) = Wire.parts(value).bind()
        done(Calls.SET.invokeExact(graph, node, Wire.DECIMAL.toInt(), 0.0, low, high, scale.toInt(), MemorySegment.NULL, 0L) as Int)
    }

    /** Records a new value for a String input. */
    context(_: Fail<Refused>)
    @JvmStatic
    fun setText(graph: Long, node: Int, value: String) = Arena.ofConfined().use { arena ->
        val held = value.toByteArray()
        done(Calls.SET.invokeExact(graph, node, Wire.TEXT.toInt(), 0.0, 0L, 0L, 0, bytes(arena, held), held.size.toLong()) as Int)
    }

    /** Records new values for many Double inputs in one call. */
    context(_: Fail<Refused>)
    @JvmStatic
    fun setNumbers(graph: Long, nodes: IntArray, values: DoubleArray) {
        ensure(nodes.size == values.size) { Refused("${nodes.size} nodes were given ${values.size} values") }
        if (nodes.isNotEmpty()) {
            Arena.ofConfined().use { arena ->
                done(Calls.SET_NUMBERS.invokeExact(graph, ints(arena, nodes), doubles(arena, values), nodes.size.toLong()) as Int)
            }
        }
    }

    /** A Double node's value as of the last update. A node that could not be calculated is refused, with why. */
    context(fail: Fail<Refused>)
    @JvmStatic
    fun number(graph: Long, node: Int): Double {
        val held = Calls.NUMBER.invokeExact(graph, node) as Double
        // NaN is also how the engine says it could not answer that simply.
        return if (!held.isNaN()) held else slowNumber(graph, node)
    }

    context(fail: Fail<Refused>)
    private fun slowNumber(graph: Long, node: Int): Double = when (val held = value(graph, node)) {
        is Double -> held
        is Failed -> fail.fail(Refused("${name(graph, node)} could not be calculated: ${held.why}"))
        else -> fail.fail(Refused("${name(graph, node)} holds $held, not a number"))
    }

    /** A node's value as of the last update, or a [Failed] if it could not be calculated. */
    context(_: Fail<Refused>)
    @JvmStatic
    fun value(graph: Long, node: Int): Any = Arena.ofConfined().use { arena ->
        val out = arena.allocate(JAVA_LONG, 4)
        read(Calls.VALUE.invokeExact(graph, node, out) as Int, out)
    }

    /** Applies every recorded change and recalculates what depends on them, and nothing else. */
    context(_: Fail<Refused>)
    @JvmStatic
    fun stabilize(graph: Long): Report = Arena.ofConfined().use { arena ->
        val out = arena.allocate(JAVA_LONG, 4)
        done(Calls.STABILIZE.invokeExact(graph, out) as Int)
        Report(out.getAtIndex(JAVA_LONG, 0), out.getAtIndex(JAVA_LONG, 1), out.getAtIndex(JAVA_LONG, 2), out.getAtIndex(JAVA_LONG, 3))
    }

    context(_: Fail<Refused>)
    @JvmStatic
    fun explain(graph: Long, node: Int): String = text(Calls.EXPLAIN.invokeExact(graph, node) as Long)

    context(_: Fail<Refused>)
    @JvmStatic
    fun name(graph: Long, node: Int): String = text(Calls.NAME.invokeExact(graph, node) as Long)

    context(fail: Fail<Refused>)
    @JvmStatic
    fun lastRecalculated(graph: Long): List<String> =
        if ((Calls.LAST_RECALCULATED.invokeExact(graph) as Long) < 0) fail.fail(refused()) else texts()

    @JvmStatic
    fun size(graph: Long): Long = Calls.SIZE.invokeExact(graph) as Long

    context(_: Fail<Refused>)
    @JvmStatic
    fun remove(graph: Long, node: Int) = done(Calls.REMOVE.invokeExact(graph, node) as Int)

    context(_: Fail<Refused>)
    @JvmStatic
    fun watch(graph: Long, node: Int, on: Boolean) = done(Calls.WATCH.invokeExact(graph, node, if (on) 1 else 0) as Int)

    context(_: Fail<Refused>)
    @JvmStatic
    fun calculateOnlyWatched(graph: Long, on: Boolean) = done(Calls.ONLY_WATCHED.invokeExact(graph, if (on) 1 else 0) as Int)

    context(_: Fail<Refused>)
    @JvmStatic
    fun checkConsistency(graph: Long) = done(Calls.CHECK.invokeExact(graph) as Int)

    // ------------------------------------------------------------ collection

    /** A new collection in a graph. Give it to [ownCollection]. */
    context(_: Fail<Refused>)
    @JvmStatic
    fun collection(graph: Long, name: String): Long = Arena.ofConfined().use { arena ->
        val named = name.toByteArray()
        address(Calls.COLLECTION_NEW.invokeExact(graph, bytes(arena, named), named.size.toLong()) as Long)
    }

    /** Has the collection freed once nothing can reach [owner], which must keep the graph's owner reachable. */
    @JvmStatic
    fun ownCollection(owner: Any, address: Long) = freeWith(owner, Calls.COLLECTION_FREE, address)

    context(_: Fail<Refused>)
    @JvmStatic
    fun field(collection: Long, name: String, kind: Byte) = Arena.ofConfined().use { arena ->
        val named = name.toByteArray()
        done(Calls.FIELD.invokeExact(collection, bytes(arena, named), named.size.toLong(), kind.toInt()) as Int)
    }

    context(_: Fail<Refused>)
    @JvmStatic
    fun column(collection: Long, name: String, expression: ByteArray) = Arena.ofConfined().use { arena ->
        val named = name.toByteArray()
        done(Calls.COLUMN.invokeExact(collection, bytes(arena, named), named.size.toLong(), bytes(arena, expression), expression.size.toLong()) as Int)
    }

    /** A total ([Wire.SUM] and the others) of a field or column, or of rows when [of] is null. */
    context(_: Fail<Refused>)
    @JvmStatic
    fun total(collection: Long, name: String, op: Int, of: String?): Int = Arena.ofConfined().use { arena ->
        val named = name.toByteArray()
        val part = of?.toByteArray() ?: ByteArray(0)
        val length = if (of == null) -1L else part.size.toLong()
        node(Calls.TOTAL.invokeExact(collection, bytes(arena, named), named.size.toLong(), op, bytes(arena, part), length) as Int)
    }

    /** Adds or changes many rows in one call: for each, its key, how many fields, and each field's name and value. */
    context(_: Fail<Refused>)
    @JvmStatic
    fun upsert(collection: Long, rows: ByteArray, count: Int) = Arena.ofConfined().use { arena ->
        done(Calls.UPSERT.invokeExact(collection, bytes(arena, rows), rows.size.toLong(), count.toLong()) as Int)
    }

    context(fail: Fail<Refused>)
    @JvmStatic
    fun removeRow(collection: Long, key: String): Boolean = Arena.ofConfined().use { arena ->
        val keyed = key.toByteArray()
        val removed = Calls.ROW_REMOVE.invokeExact(collection, bytes(arena, keyed), keyed.size.toLong()) as Int
        if (removed < 0) fail.fail(refused()) else removed == 1
    }

    /** A field or column of a row, as [value] gives a node's. */
    context(_: Fail<Refused>)
    @JvmStatic
    fun rowValue(collection: Long, key: String, part: String): Any = Arena.ofConfined().use { arena ->
        val out = arena.allocate(JAVA_LONG, 4)
        val keyed = key.toByteArray()
        val named = part.toByteArray()
        read(Calls.ROW_VALUE.invokeExact(collection, bytes(arena, keyed), keyed.size.toLong(), bytes(arena, named), named.size.toLong(), out) as Int, out)
    }

    @JvmStatic
    fun rows(collection: Long): Long = Calls.ROWS.invokeExact(collection) as Long

    // ----------------------------------------------------------------- plans

    /**
     * A plan of the graph as it stands: the inputs to vary in every scenario, those shared by all of a run, and the
     * outputs to read. Give it to [ownPlan].
     */
    context(_: Fail<Refused>)
    @JvmStatic
    fun plan(graph: Long, vary: IntArray, shared: IntArray, outputs: IntArray): Long = Arena.ofConfined().use { arena ->
        address(
            Calls.PLAN_NEW.invokeExact(
                graph, ints(arena, vary), vary.size.toLong(), ints(arena, shared), shared.size.toLong(),
                ints(arena, outputs), outputs.size.toLong(),
            ) as Long,
        )
    }

    /** A plan read from a file written by [planSave]. Give it to [ownPlan]. */
    context(_: Fail<Refused>)
    @JvmStatic
    fun planLoad(path: String): Long = Arena.ofConfined().use { arena ->
        ready()
        val at = path.toByteArray()
        address(Calls.PLAN_LOAD.invokeExact(bytes(arena, at), at.size.toLong()) as Long)
    }

    /** Has the plan freed once nothing can reach [owner]. */
    @JvmStatic
    fun ownPlan(owner: Any, address: Long) = freeWith(owner, Calls.PLAN_FREE, address)

    /** Writes a plan to a file. */
    context(_: Fail<Refused>)
    @JvmStatic
    fun planSave(plan: Long, path: String) = Arena.ofConfined().use { arena ->
        val at = path.toByteArray()
        done(Calls.PLAN_SAVE.invokeExact(plan, bytes(arena, at), at.size.toLong()) as Int)
    }

    /** How many varied inputs, shared inputs and outputs a plan has, and how many nodes it works out for a scenario. */
    context(_: Fail<Refused>)
    @JvmStatic
    fun planSizes(plan: Long): LongArray = Arena.ofConfined().use { arena ->
        val out = arena.allocate(JAVA_LONG, 4)
        done(Calls.PLAN_SIZES.invokeExact(plan, out) as Int)
        out.toArray(JAVA_LONG)
    }

    /** Gives a plan's shared inputs new values, for every run from now on. */
    context(_: Fail<Refused>)
    @JvmStatic
    fun planSetShared(plan: Long, values: DoubleArray) = Arena.ofConfined().use { arena ->
        done(Calls.PLAN_SET_SHARED.invokeExact(plan, doubles(arena, values), values.size.toLong()) as Int)
    }

    /**
     * Runs scenarios on the CPU, on all cores. [inputs] holds one scenario after another, and the result one
     * scenario's outputs after another: [results] values in all.
     */
    context(_: Fail<Refused>)
    @JvmStatic
    fun planRunCpu(plan: Long, inputs: DoubleArray, results: Long): DoubleArray = Arena.ofConfined().use { arena ->
        val out = arena.allocate(JAVA_DOUBLE, maxOf(results, 1))
        done(Calls.PLAN_RUN_CPU.invokeExact(plan, doubles(arena, inputs), inputs.size.toLong(), out, results) as Int)
        out.asSlice(0, results * Double.SIZE_BYTES).toArray(JAVA_DOUBLE)
    }

    /** An NVIDIA GPU to run plans on; refused if there is none. Give it to [ownGpu]. */
    context(_: Fail<Refused>)
    @JvmStatic
    fun gpu(): Long {
        ready()
        return address(Calls.GPU_NEW.invokeExact() as Long)
    }

    /** Has the GPU freed once nothing can reach [owner]. */
    @JvmStatic
    fun ownGpu(owner: Any, address: Long) = freeWith(owner, Calls.GPU_FREE, address)

    /** The bytes of a GPU's memory that are free. */
    context(fail: Fail<Refused>)
    @JvmStatic
    fun gpuFreeMemory(gpu: Long): Long = (Calls.GPU_FREE_MEMORY.invokeExact(gpu) as Long).let { if (it < 0) fail.fail(refused()) else it }

    /** Runs scenarios on a GPU, as [planRunCpu] does on the CPU, within [allowed] bytes of its memory. */
    context(_: Fail<Refused>)
    @JvmStatic
    fun gpuRun(gpu: Long, plan: Long, inputs: DoubleArray, results: Long, allowed: Long): DoubleArray = Arena.ofConfined().use { arena ->
        val out = arena.allocate(JAVA_DOUBLE, maxOf(results, 1))
        done(Calls.GPU_RUN.invokeExact(gpu, plan, doubles(arena, inputs), inputs.size.toLong(), out, results, allowed) as Int)
        out.asSlice(0, results * Double.SIZE_BYTES).toArray(JAVA_DOUBLE)
    }

    // ------------------------------------------------ keeping a run up to date

    /**
     * What to keep of a graph's plan so that a run can be brought up to date after a change: the plan's varied and
     * shared inputs and its outputs, and the nodes whose values to keep. Give it to [ownKept].
     */
    context(_: Fail<Refused>)
    @JvmStatic
    fun kept(graph: Long, vary: IntArray, shared: IntArray, outputs: IntArray, keep: IntArray): Long = Arena.ofConfined().use { arena ->
        address(
            Calls.KEPT_NEW.invokeExact(
                graph, ints(arena, vary), vary.size.toLong(), ints(arena, shared), shared.size.toLong(),
                ints(arena, outputs), outputs.size.toLong(), ints(arena, keep), keep.size.toLong(),
            ) as Long,
        )
    }

    /** Has what is kept freed once nothing can reach [owner]. */
    @JvmStatic
    fun ownKept(owner: Any, address: Long) = freeWith(owner, Calls.KEPT_FREE, address)

    /** The plan whose outputs are every kept value of a scenario. Give it to [ownPlan]. */
    context(_: Fail<Refused>)
    @JvmStatic
    fun keptPlan(kept: Long): Long = address(Calls.KEPT_PLAN.invokeExact(kept) as Long)

    /** Where each output is among a scenario's kept values. */
    context(fail: Fail<Refused>)
    @JvmStatic
    fun keptColumns(kept: Long): IntArray {
        if ((Calls.KEPT_COLUMNS.invokeExact(kept) as Long) < 0) fail.fail(refused())
        val bytes = kept(Calls.RESULT)
        return IntArray(bytes.size / Int.SIZE_BYTES) { bytes.int(it * Int.SIZE_BYTES) }
    }

    /**
     * What to run now that the inputs [changed] have new values on the graph: a patch's address, or 0 if nothing kept
     * uses them. Give a patch to [ownPatch].
     */
    context(_: Fail<Refused>)
    @JvmStatic
    fun keptPatch(kept: Long, graph: Long, changed: IntArray): Long = Arena.ofConfined().use { arena ->
        val out = arena.allocate(JAVA_LONG)
        done(Calls.KEPT_PATCH.invokeExact(kept, graph, ints(arena, changed), changed.size.toLong(), out) as Int)
        out.get(JAVA_LONG, 0)
    }

    /** Has the patch freed once nothing can reach [owner]. */
    @JvmStatic
    fun ownPatch(owner: Any, address: Long) = freeWith(owner, Calls.PATCH_FREE, address)

    /** A patch read from a file written by [patchSave]. Give it to [ownPatch]. */
    context(_: Fail<Refused>)
    @JvmStatic
    fun patchLoad(path: String): Long = Arena.ofConfined().use { arena ->
        ready()
        val at = path.toByteArray()
        address(Calls.PATCH_LOAD.invokeExact(bytes(arena, at), at.size.toLong()) as Long)
    }

    /** Writes a patch to a file. */
    context(_: Fail<Refused>)
    @JvmStatic
    fun patchSave(patch: Long, path: String) = Arena.ofConfined().use { arena ->
        val at = path.toByteArray()
        done(Calls.PATCH_SAVE.invokeExact(patch, bytes(arena, at), at.size.toLong()) as Int)
    }

    /** How many values are kept for a scenario, how many of them a patch works out again, and on how many nodes. */
    context(_: Fail<Refused>)
    @JvmStatic
    fun patchSizes(patch: Long): LongArray = Arena.ofConfined().use { arena ->
        val out = arena.allocate(JAVA_LONG, 3)
        done(Calls.PATCH_SIZES.invokeExact(patch, out) as Int)
        out.toArray(JAVA_LONG)
    }

    /**
     * Some scenarios' inputs ([vary] to a scenario) and the kept values a plan gave for them, copied into the engine.
     * Give it to [ownHeld].
     */
    context(_: Fail<Refused>)
    @JvmStatic
    fun held(vary: Int, inputs: DoubleArray, values: DoubleArray): Long = Arena.ofConfined().use { arena ->
        ready()
        address(Calls.HELD_NEW.invokeExact(vary.toLong(), doubles(arena, inputs), inputs.size.toLong(), doubles(arena, values), values.size.toLong()) as Long)
    }

    /** Has the held values freed once nothing can reach [owner]. */
    @JvmStatic
    fun ownHeld(owner: Any, address: Long) = freeWith(owner, Calls.HELD_FREE, address)

    /** For each scenario held in turn, its kept values in the places [columns]: [results] values in all. */
    context(_: Fail<Refused>)
    @JvmStatic
    fun heldColumns(held: Long, columns: IntArray, results: Long): DoubleArray = Arena.ofConfined().use { arena ->
        val out = arena.allocate(JAVA_DOUBLE, maxOf(results, 1))
        done(Calls.HELD_COLUMNS.invokeExact(held, ints(arena, columns), columns.size.toLong(), out, results) as Int)
        out.asSlice(0, results * Double.SIZE_BYTES).toArray(JAVA_DOUBLE)
    }

    /** Brings held values up to date with a patch, on the GPU within [allowed] bytes, or on the CPU if [gpu] is 0. */
    context(_: Fail<Refused>)
    @JvmStatic
    fun heldRefresh(held: Long, patch: Long, gpu: Long, allowed: Long) = done(Calls.HELD_REFRESH.invokeExact(held, patch, gpu, allowed) as Int)

    // ------------------------------------------- plans and patches as bytes

    /** Room in the engine for [size] bytes, to write a plan or a patch into a part at a time. Give it to [ownSaved]. */
    context(_: Fail<Refused>)
    @JvmStatic
    fun saved(size: Long): Long {
        ready()
        return address(Calls.SAVED_NEW.invokeExact(size) as Long)
    }

    /** Has the bytes freed once nothing can reach [owner]. */
    @JvmStatic
    fun ownSaved(owner: Any, address: Long) = freeWith(owner, Calls.SAVED_FREE, address)

    /** How many bytes there are. */
    context(fail: Fail<Refused>)
    @JvmStatic
    fun savedSize(saved: Long): Long = (Calls.SAVED_SIZE.invokeExact(saved) as Long).let { if (it < 0) fail.fail(refused()) else it }

    /** [count] of the bytes, from the one at [from]. */
    context(_: Fail<Refused>)
    @JvmStatic
    fun savedRead(saved: Long, from: Long, count: Int): ByteArray = Arena.ofConfined().use { arena ->
        val out = arena.allocate(maxOf(count, 1).toLong())
        done(Calls.SAVED_READ.invokeExact(saved, from, out, count.toLong()) as Int)
        out.asSlice(0, count.toLong()).toArray(JAVA_BYTE)
    }

    /** Puts the first [count] of [bytes] at the place [from]. */
    context(_: Fail<Refused>)
    @JvmStatic
    fun savedWrite(saved: Long, from: Long, bytes: ByteArray, count: Int) = Arena.ofConfined().use { arena ->
        val part = arena.allocate(maxOf(count, 1).toLong())
        MemorySegment.copy(bytes, 0, part, JAVA_BYTE, 0, count)
        done(Calls.SAVED_WRITE.invokeExact(saved, from, part, count.toLong()) as Int)
    }

    /** A plan as bytes. Give them to [ownSaved]. */
    context(_: Fail<Refused>)
    @JvmStatic
    fun planSaved(plan: Long): Long = address(Calls.PLAN_SAVED.invokeExact(plan) as Long)

    /** A plan from bytes. Give it to [ownPlan]. */
    context(_: Fail<Refused>)
    @JvmStatic
    fun planOfSaved(saved: Long): Long = address(Calls.PLAN_OF_SAVED.invokeExact(saved) as Long)

    /** A patch as bytes. Give them to [ownSaved]. */
    context(_: Fail<Refused>)
    @JvmStatic
    fun patchSaved(patch: Long): Long = address(Calls.PATCH_SAVED.invokeExact(patch) as Long)

    /** A patch from bytes. Give it to [ownPatch]. */
    context(_: Fail<Refused>)
    @JvmStatic
    fun patchOfSaved(saved: Long): Long = address(Calls.PATCH_OF_SAVED.invokeExact(saved) as Long)
}
