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
    private val address: Long = Native.gpu(this)

    /** The bytes of the GPU's memory that are free. */
    fun freeMemory(): Long = Native.gpuFreeMemory(address)

    /**
     * Runs every scenario on the GPU, as [Plan.runCpu] does on the CPU, in as many parts as fit in [allowed]
     * bytes of its memory (four fifths of what is free, unless told otherwise).
     */
    fun run(plan: Plan, inputs: DoubleArray, allowed: Long = freeMemory() / 5 * 4): DoubleArray =
        Native.gpuRun(address, plan.address, inputs, plan.scenarios(inputs).toLong() * plan.outputs, allowed)
}
