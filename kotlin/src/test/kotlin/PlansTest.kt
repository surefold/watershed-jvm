import dev.surefold.watershed.*
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PlansTest {
    private class Model {
        val g = Graph()
        val rate = g.input("rate", 0.01)
        val shock = g.input("shock", 0.0)
        val fee = g.input("fee", 0.002)
        val worth: List<Node<Double>> = (0 until 8).map { n ->
            var value = g.define("v${n}_0", (rate + 1.0) * (100.0 + n))
            for (t in 1 until 5) value = g.define("v${n}_$t", value * (rate - fee + 1.0) - shock)
            value
        }
        val total = g.define("total", worth.drop(1).fold(worth[0] as Expr<Double>) { all, x -> all + x })

        init {
            g.stabilize()
        }

        fun plan() = g.plan(vary = listOf(rate, shock), outputs = listOf(total, worth[3]), shared = listOf(fee))
    }

    private val scenarios = DoubleArray(400) { if (it % 2 == 0) 0.0005 * it - 0.05 else 0.3 * (it % 7) }

    @Test
    fun `a plan gives what the graph gives when its inputs are set`() {
        val m = Model()
        val plan = m.plan()
        assertEquals(listOf(2, 1, 2), listOf(plan.inputs, plan.sharedInputs, plan.outputs))
        val answers = plan.runCpu(scenarios)
        assertEquals(400, answers.size)
        for (s in listOf(0, 57, 199)) {
            m.g[m.rate] = scenarios[2 * s]
            m.g[m.shock] = scenarios[2 * s + 1]
            m.g.stabilize()
            assertEquals(m.g[m.total].toBits(), answers[2 * s].toBits())
            assertEquals(m.g[m.worth[3]].toBits(), answers[2 * s + 1].toBits())
        }
    }

    @Test
    fun `a plan read back from a file gives the same answers`() {
        val plan = Model().plan()
        plan.setShared(doubleArrayOf(0.004))
        val file = Files.createTempFile("watershed", ".plan")
        try {
            plan.save(file)
            val back = Plan.load(file)
            assertEquals(plan.nodes, back.nodes)
            assertContentEquals(plan.runCpu(scenarios), back.runCpu(scenarios))
            // A lot of the scenarios, run on its own, is that part of the whole.
            val whole = plan.runCpu(scenarios)
            val lot = back.runCpu(scenarios.copyOfRange(100, 300))
            assertContentEquals(whole.copyOfRange(100, 300), lot)
        } finally {
            Files.delete(file)
        }
    }

    @Test
    fun `what cannot be run is refused with the reason`() {
        val plan = Model().plan()
        assertTrue(assertFailsWith<WatershedException> { plan.runCpu(DoubleArray(3)) }.message!!.contains("whole number"))
        assertFailsWith<WatershedException> { plan.setShared(doubleArrayOf(1.0, 2.0)) }
        val file = Files.createTempFile("watershed", ".plan")
        try {
            Files.write(file, "not a plan".toByteArray())
            assertFailsWith<WatershedException> { Plan.load(file) }
        } finally {
            Files.delete(file)
        }
    }

    @Test
    fun `a GPU, where there is one, gives what the CPU gives`() {
        val gpu = try {
            Gpu()
        } catch (none: WatershedException) {
            // Built for a GPU (WATERSHED_CUDA), there must be one.
            if (System.getenv("WATERSHED_CUDA") != null) throw none
            return
        }
        val plan = Model().plan()
        assertContentEquals(plan.runCpu(scenarios), gpu.run(plan, scenarios))
    }
}
