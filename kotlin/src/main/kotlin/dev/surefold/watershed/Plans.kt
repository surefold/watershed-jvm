package dev.surefold.watershed

import java.nio.file.Path

/**
 * EXPERIMENTAL. A graph prepared to run for many scenarios at once. Make one with [Graph.plan], or read one
 * that another process wrote with [load]: a plan read back gives the same answers, to the last bit.
 *
 * A plan is safe to use from several threads; its runs take turns.
 */
class Plan internal constructor(make: (Plan) -> Long) {
    // Where the plan is in the engine. It is freed when this object can no longer be reached.
    internal val address: Long = make(this)

    private val sizes: LongArray = Native.planSizes(address)

    /** How many values each scenario gives: one for every varied input, in the order they were listed. */
    val inputs: Int get() = sizes[0].toInt()

    /** How many inputs are shared by every scenario of a run. */
    val sharedInputs: Int get() = sizes[1].toInt()

    /** How many values each scenario's answer holds. */
    val outputs: Int get() = sizes[2].toInt()

    /** How many nodes are worked out for each scenario. */
    val nodes: Long get() = sizes[3]

    /** How many scenarios [inputs] holds. */
    internal fun scenarios(inputs: DoubleArray): Int {
        if (inputs.size % this.inputs != 0) {
            throw WatershedException(
                "${inputs.size} input values is not a whole number of scenarios of ${this.inputs} inputs each",
            )
        }
        return inputs.size / this.inputs
    }

    /** Gives the shared inputs new values, for every run from now on. */
    fun setShared(values: DoubleArray) = Native.planSetShared(address, values)

    /**
     * Runs every scenario on the CPU, on all cores. [inputs] holds one scenario after another; the result holds
     * one scenario's outputs after another.
     */
    fun runCpu(inputs: DoubleArray): DoubleArray =
        Native.planRunCpu(address, inputs, scenarios(inputs).toLong() * outputs)

    /** Writes the plan to a file, with its shared inputs as they are now. A plan of 20 million nodes is about 2 GB. */
    fun save(to: Path) = Native.planSave(address, to.toString())

    companion object {
        /** Reads a plan written by [save]. The file is checked for being whole, not for being well meant. */
        fun load(from: Path): Plan = Plan { owner -> Native.planLoad(owner, from.toString()) }
    }
}

/**
 * EXPERIMENTAL. An NVIDIA GPU to run plans on. Making one throws if there is no GPU, or if the native library
 * was built without the `cuda` feature.
 */
class Gpu {
    internal val address: Long = Native.gpu(this)

    /** The bytes of the GPU's memory that are free. */
    fun freeMemory(): Long = Native.gpuFreeMemory(address)

    /**
     * Runs every scenario on the GPU, as [Plan.runCpu] does on the CPU, in as many parts as fit in [allowed]
     * bytes of its memory (four fifths of what is free, unless told otherwise).
     */
    fun run(plan: Plan, inputs: DoubleArray, allowed: Long = freeMemory() / 5 * 4): DoubleArray =
        Native.gpuRun(address, plan.address, inputs, plan.scenarios(inputs).toLong() * plan.outputs, allowed)
}

/**
 * EXPERIMENTAL. What is kept of a plan so that a run of it can be brought up to date after a change. Make one
 * with [Graph.kept]. It holds no values itself: run [plan], whose outputs are every kept value of a scenario,
 * and give the answers to a [Held], or to several that each hold some of the scenarios.
 *
 * After inputs of the graph change (and `stabilize()`), [patch] gives what to run on the values held.
 */
class Kept internal constructor(make: (Kept) -> Long, private val patcher: (Long, List<Input<*>>) -> Long) {
    private val address: Long = make(this)

    /** The plan to run first: each scenario's answer is every kept value. */
    val plan: Plan = Plan { owner -> Native.keptPlan(owner, address) }

    /** Where each output is among a scenario's kept values. */
    val outputColumns: IntArray = Native.keptColumns(address)

    /** What to run now that [changed] have new values on the graph, or null if nothing kept uses them. */
    fun patch(changed: List<Input<*>>): Patch? {
        val patch = patcher(address, changed)
        return if (patch == 0L) null else Patch { owner -> Native.ownPatch(owner, patch) }
    }
}

/**
 * EXPERIMENTAL. A small plan and which kept values it reads and works out again: what brings held values up to
 * date after a change. It can be written to a file for whoever holds the values, in another process or on
 * another machine.
 */
class Patch internal constructor(make: (Patch) -> Long) {
    internal val address: Long = make(this)

    private val sizes: LongArray = Native.patchSizes(address)

    /** How many values are kept for a scenario. */
    val width: Int get() = sizes[0].toInt()

    /** How many of them the patch works out again. */
    val stale: Int get() = sizes[1].toInt()

    /** How many nodes it works out for each scenario to do so. */
    val nodes: Long get() = sizes[2]

    fun save(to: Path) = Native.patchSave(address, to.toString())

    companion object {
        /** Reads a patch written by [save]. */
        fun load(from: Path): Patch = Patch { owner -> Native.patchLoad(owner, from.toString()) }
    }
}

/**
 * EXPERIMENTAL. Some scenarios' [inputs] ([vary] numbers to a scenario) and the kept [values] a [Kept.plan] gave
 * for them, held in the engine to be brought up to date.
 */
class Held(vary: Int, inputs: DoubleArray, values: DoubleArray) {
    private val address: Long = Native.held(this, vary, inputs, values)

    /** How many scenarios are held. */
    val scenarios: Int = inputs.size / vary

    /** For each scenario in turn, its kept values in the places [columns], such as [Kept.outputColumns]. */
    fun values(columns: IntArray): DoubleArray = Native.heldColumns(address, columns, scenarios.toLong() * columns.size)

    /**
     * Brings the values up to date with [patch], on [gpu] or, without one, on the CPU. Use what the first run
     * used: where a model uses `exp`, `ln` or `pow`, a GPU and a CPU differ in the last digits.
     */
    fun refresh(patch: Patch, gpu: Gpu? = null, allowed: Long? = null) =
        Native.heldRefresh(address, patch.address, gpu?.address ?: 0L, allowed ?: gpu?.let { it.freeMemory() / 5 * 4 } ?: 0L)
}
