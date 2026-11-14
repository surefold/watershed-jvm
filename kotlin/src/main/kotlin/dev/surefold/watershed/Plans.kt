package dev.surefold.watershed

import dev.surefold.fail.Fail
import dev.surefold.fail.ensure

/**
 * EXPERIMENTAL. A graph's calculation, laid out to run for many scenarios at once: made by [Graph.plan], or read
 * from a file by [Plan.load]. It holds no graph, so it can be saved, sent to another machine and run there.
 */
class Plan internal constructor(internal val address: Long, private val sizes: LongArray) {
    init {
        // Freed in the engine when this object can no longer be reached.
        Native.ownPlan(this, address)
    }

    /** How many inputs each scenario gives a value to. */
    val inputs: Int get() = sizes[0].toInt()

    /** How many inputs are the same in every scenario of a run. */
    val sharedInputs: Int get() = sizes[1].toInt()

    /** How many outputs each scenario answers. */
    val outputs: Int get() = sizes[2].toInt()

    /** How many nodes are worked out for a scenario. */
    val nodes: Long get() = sizes[3]

    /** How many scenarios [inputs] holds; refused unless it is a whole number of them. */
    context(_: Fail<Refused>)
    internal fun scenarios(inputs: DoubleArray): Int {
        ensure(inputs.size % this.inputs == 0) {
            Refused("${inputs.size} input values is not a whole number of scenarios of ${this.inputs} inputs each")
        }
        return inputs.size / this.inputs
    }

    /** Gives the shared inputs new values, for every run from now on. */
    context(_: Fail<Refused>)
    fun setShared(values: DoubleArray) = Native.planSetShared(address, values)

    /**
     * Runs scenarios on the CPU, on all cores. [inputs] holds one scenario's inputs after another, and the answer
     * one scenario's outputs after another.
     */
    context(_: Fail<Refused>)
    fun runCpu(inputs: DoubleArray): DoubleArray = Native.planRunCpu(address, inputs, scenarios(inputs).toLong() * outputs)

    /** Writes the plan to the file at a path, for [load] to read, here or on another machine. */
    context(_: Fail<Refused>)
    fun save(to: String) = Native.planSave(address, to)

    /** The plan as bytes, for [from] to read back: to keep it somewhere that is not a file of this machine. */
    context(_: Fail<Refused>)
    fun saved(): Saved = Saved.of(Native.planSaved(address))

    companion object {
        /** A plan read from the file at a path, which [save] wrote. */
        context(_: Fail<Refused>)
        fun load(from: String): Plan = of(Native.planLoad(from))

        /** A plan from the bytes [saved] gave. */
        context(_: Fail<Refused>)
        fun from(saved: Saved): Plan = of(Native.planOfSaved(saved.address))

        context(_: Fail<Refused>)
        internal fun of(address: Long): Plan = Plan(address, Native.planSizes(address))
    }
}

/** EXPERIMENTAL. An NVIDIA GPU to run plans on, opened with [Gpu.open]. The engine must be built with CUDA. */
class Gpu private constructor(internal val address: Long) {
    init {
        Native.ownGpu(this, address)
    }

    /** The bytes of the GPU's memory that are free. */
    context(_: Fail<Refused>)
    fun freeMemory(): Long = Native.gpuFreeMemory(address)

    /** Runs scenarios as [Plan.runCpu] does, on the GPU, within [allowed] bytes of its memory: four fifths of what is free unless told. */
    context(_: Fail<Refused>)
    fun run(plan: Plan, inputs: DoubleArray, allowed: Long = freeMemory() / 5 * 4): DoubleArray =
        Native.gpuRun(address, plan.address, inputs, plan.scenarios(inputs).toLong() * plan.outputs, allowed)

    companion object {
        /** The machine's GPU; refused if it has none, or the engine was built without CUDA. */
        context(_: Fail<Refused>)
        fun open(): Gpu = Gpu(Native.gpu())
    }
}

/**
 * EXPERIMENTAL. What [Graph.kept] keeps of a plan so that a run can be brought up to date after a change. Its
 * [plan] answers every kept value of a scenario; [outputColumns] says where the outputs are among them. After
 * inputs change on the graph, [patch] is what to run on values held from that plan.
 */
class Kept internal constructor(
    private val graph: Graph,
    private val address: Long,
    val plan: Plan,
    val outputColumns: IntArray,
) {
    init {
        Native.ownKept(this, address)
    }

    /** What brings held values up to date now that [changed] have new values, or null if nothing kept uses them. */
    context(_: Fail<Refused>)
    fun patch(changed: List<Input<*>>): Patch? =
        graph.patch(address, changed).takeIf { it != 0L }?.let { Patch.of(it) }
}

/** EXPERIMENTAL. What to run on held values to bring them up to date: made by [Kept.patch], or read by [Patch.load]. */
class Patch internal constructor(internal val address: Long, private val sizes: LongArray) {
    init {
        Native.ownPatch(this, address)
    }

    /** How many values are kept for a scenario. */
    val width: Int get() = sizes[0].toInt()

    /** How many of them the patch works out again. */
    val stale: Int get() = sizes[1].toInt()

    /** How many nodes it works out for a scenario. */
    val nodes: Long get() = sizes[2]

    /** Writes the patch to the file at a path, for [load] to read, here or on another machine. */
    context(_: Fail<Refused>)
    fun save(to: String) = Native.patchSave(address, to)

    /** The patch as bytes, for [from] to read back. */
    context(_: Fail<Refused>)
    fun saved(): Saved = Saved.of(Native.patchSaved(address))

    companion object {
        /** A patch read from the file at a path, which [save] wrote. */
        context(_: Fail<Refused>)
        fun load(from: String): Patch = of(Native.patchLoad(from))

        /** A patch from the bytes [saved] gave. */
        context(_: Fail<Refused>)
        fun from(saved: Saved): Patch = of(Native.patchOfSaved(saved.address))

        context(_: Fail<Refused>)
        internal fun of(address: Long): Patch = Patch(address, Native.patchSizes(address))
    }
}

/**
 * EXPERIMENTAL. Some scenarios' inputs and the kept values a [Kept]'s plan gave for them, held in the engine so
 * that a [Patch] can bring them up to date in place. Made by [Held.of].
 */
class Held private constructor(private val address: Long, val scenarios: Int) {
    init {
        Native.ownHeld(this, address)
    }

    /** For each scenario in turn, its kept values in the places [columns]. */
    context(_: Fail<Refused>)
    fun values(columns: IntArray): DoubleArray = Native.heldColumns(address, columns, scenarios.toLong() * columns.size)

    /** Brings the values up to date with [patch], on [gpu] within [allowed] bytes of its memory, or on the CPU with none. */
    context(_: Fail<Refused>)
    fun refresh(patch: Patch, gpu: Gpu? = null, allowed: Long? = null) =
        Native.heldRefresh(address, patch.address, gpu?.address ?: 0L, allowed ?: gpu?.let { it.freeMemory() / 5 * 4 } ?: 0L)

    companion object {
        /** Holds [inputs], [vary] of them to a scenario, and the [values] a kept plan gave for them. */
        context(_: Fail<Refused>)
        fun of(vary: Int, inputs: DoubleArray, values: DoubleArray): Held {
            ensure(vary > 0 && inputs.size % vary == 0) { Refused("${inputs.size} inputs is not a whole number of scenarios of $vary") }
            return Held(Native.held(vary, inputs, values), inputs.size / vary)
        }
    }
}

/**
 * EXPERIMENTAL. A plan or a patch as bytes, held in the engine and read or written a part at a time: a plan can be
 * bigger than one array. [Plan.saved] and [Patch.saved] give one to read; [Saved.sized] gives one to write into,
 * for [Plan.from] or [Patch.from] to read back.
 */
class Saved private constructor(internal val address: Long, val size: Long) {
    init {
        Native.ownSaved(this, address)
    }

    /** [count] of the bytes, from the one at [from]. */
    context(_: Fail<Refused>)
    fun read(from: Long, count: Int): ByteArray = Native.savedRead(address, from, count)

    /** Puts [bytes] at the place [from]. */
    context(_: Fail<Refused>)
    fun write(from: Long, bytes: ByteArray) = Native.savedWrite(address, from, bytes, bytes.size)

    companion object {
        /** Room for [size] bytes, all zero until written. */
        context(_: Fail<Refused>)
        fun sized(size: Long): Saved = Saved(Native.saved(size), size)

        context(_: Fail<Refused>)
        internal fun of(address: Long): Saved = Saved(address, Native.savedSize(address))
    }
}
