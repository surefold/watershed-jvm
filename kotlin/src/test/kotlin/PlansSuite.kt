import dev.surefold.assert.shouldNotBeNull
import dev.surefold.fail.Fail
import dev.surefold.fail.attempt
import dev.surefold.fail.fold
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldContain
import dev.surefold.junit.withTempDirectory
import dev.surefold.watershed.Expr
import dev.surefold.watershed.Gpu
import dev.surefold.watershed.Graph
import dev.surefold.watershed.Held
import dev.surefold.watershed.Input
import dev.surefold.watershed.Kept
import dev.surefold.watershed.Node
import dev.surefold.watershed.Patch
import dev.surefold.watershed.Plan
import dev.surefold.watershed.Refused
import dev.surefold.watershed.Saved
import dev.surefold.watershed.minus
import dev.surefold.watershed.plus
import dev.surefold.watershed.times
import kotlin.io.path.pathString
import kotlin.io.path.writeText

object PlansSuite : Suite() {
    init {
        test("A plan gives what the graph gives when its inputs are set") {
            engine {
                val m = model()
                val plan = m.plan()
                listOf(plan.inputs, plan.sharedInputs, plan.outputs) shouldBe listOf(2, 1, 2)
                val answers = plan.runCpu(scenarios)
                answers.size shouldBe 400
                listOf(0, 57, 199).forEach { s ->
                    m.g[m.rate] = scenarios[2 * s]
                    m.g[m.shock] = scenarios[2 * s + 1]
                    m.g.stabilize()
                    answers[2 * s].toBits() shouldBe m.g[m.total].toBits()
                    answers[2 * s + 1].toBits() shouldBe m.g[m.worth[3]].toBits()
                }
            }
        }

        test("A plan read back from a file gives the same answers") {
            withTempDirectory("watershed-plan") { folder ->
                engine {
                    val plan = model().plan()
                    plan.setShared(doubleArrayOf(0.004))
                    plan.save(folder.resolve("plan").pathString)
                    val back = Plan.load(folder.resolve("plan").pathString)
                    back.nodes shouldBe plan.nodes
                    val whole = plan.runCpu(scenarios)
                    back.runCpu(scenarios).asList() shouldBe whole.asList()
                    // A lot of the scenarios, run on its own, is that part of the whole.
                    back.runCpu(scenarios.copyOfRange(100, 300)).asList() shouldBe whole.copyOfRange(100, 300).asList()
                }
            }
        }

        test("A plan and a patch cross as bytes, a part at a time, and come back the same") {
            engine {
                val m = ledger()
                val kept = m.kept()
                val saved = kept.plan.saved()
                // Read in parts and written in parts, as they would be to and from a store.
                val copy = Saved.sized(saved.size)
                (0 until saved.size step 1000).forEach { from -> copy.write(from, saved.read(from, minOf(1000, saved.size - from).toInt())) }
                Plan.from(copy).runCpu(m.scenarios).asList() shouldBe kept.plan.runCpu(m.scenarios).asList()

                m.g[m.floors[3]] = 120.0
                m.g.stabilize()
                val patch = kept.patch(listOf(m.floors[3])).shouldNotBeNull()
                val back = Patch.from(patch.saved())
                listOf(back.width, back.stale) shouldBe listOf(patch.width, patch.stale)
                refusal { saved.read(saved.size - 1, 2) }
            }
        }

        test("What cannot be run is refused with the reason") {
            withTempDirectory("watershed-plan") { folder ->
                engine {
                    val plan = model().plan()
                    refusal { plan.runCpu(DoubleArray(3)) }.why shouldContain "whole number"
                    refusal { plan.setShared(doubleArrayOf(1.0, 2.0)) }
                    val file = folder.resolve("not-a-plan").also { it.writeText("not a plan") }
                    refusal { Plan.load(file.pathString) }
                    refusal { Plan.from(Saved.sized(10)) }
                }
            }
        }

        test("A GPU, where there is one, gives what the CPU gives") {
            engine {
                val plan = model().plan()
                attempt<Refused, Gpu> { Gpu.open() }.fold(
                    // Built for a GPU (WATERSHED_CUDA), there must be one; built without, there is nothing to compare.
                    { none -> withClue(none) { System.getenv("WATERSHED_CUDA") shouldBe null } },
                    { gpu -> gpu.run(plan, scenarios).asList() shouldBe plan.runCpu(scenarios).asList() },
                )
            }
        }

        test("Values held in two parts and patched from a file are what running it all again gives") {
            withTempDirectory("watershed-patch") { folder ->
                engine {
                    val m = ledger()
                    val kept = m.kept()
                    val first = kept.plan.runCpu(m.scenarios)
                    val width = kept.plan.outputs
                    val all = IntArray(width) { it }
                    val parts = listOf(0 until 20, 20 until 50).map { part ->
                        Held.of(
                            2,
                            m.scenarios.copyOfRange(part.first * 2, (part.last + 1) * 2),
                            first.copyOfRange(part.first * width, (part.last + 1) * width),
                        )
                    }
                    listOf(6 to 150.0, 19 to 20.0, 6 to 96.0).forEach { (n, to) ->
                        m.g[m.floors[n]] = to
                        m.g.stabilize()
                        val file = folder.resolve("patch").pathString
                        kept.patch(listOf(m.floors[n])).shouldNotBeNull().save(file)
                        val patch = Patch.load(file)
                        // One group and the total.
                        patch.stale shouldBe 2
                        parts.forEach { it.refresh(patch) }

                        val again = m.kept()
                        val whole = again.plan.runCpu(m.scenarios)
                        parts.flatMap { it.values(all).asList() } shouldBe whole.asList()
                        val totals = List(50) { whole[it * width + again.outputColumns[0]] }
                        parts.flatMap { it.values(kept.outputColumns).asList() } shouldBe totals
                    }
                    // An input nothing kept uses.
                    kept.patch(listOf(m.g.input("aside", 1.0))) shouldBe null
                }
            }
        }
    }
}

private fun withClue(clue: Any?, block: () -> Unit) = dev.surefold.junit.withClue(clue, block)

private val scenarios = DoubleArray(400) { if (it % 2 == 0) 0.0005 * it - 0.05 else 0.3 * (it % 7) }

// Eight accounts that grow at a rate less a fee and take a shock each period, and their total.
private class Model(
    val g: Graph,
    val rate: Input<Double>,
    val shock: Input<Double>,
    val fee: Input<Double>,
    val worth: List<Node<Double>>,
    val total: Node<Double>,
) {
    context(_: Fail<Refused>)
    fun plan(): Plan = g.also { it.stabilize() }.plan(vary = listOf(rate, shock), outputs = listOf(total, worth[3]), shared = listOf(fee))
}

context(_: Fail<Refused>)
private fun model(): Model {
    val g = Graph()
    val rate = g.input("rate", 0.01)
    val shock = g.input("shock", 0.0)
    val fee = g.input("fee", 0.002)
    val worth = (0 until 8).map { n ->
        (1 until 5).fold(g.define("v${n}_0", (rate + 1.0) * (100.0 + n))) { value, t ->
            g.define("v${n}_$t", value * (rate - fee + 1.0) - shock)
        }
    }
    val total = g.define("total", worth.drop(1).fold(worth[0] as Expr<Double>) { all, x -> all + x })
    return Model(g, rate, shock, fee, worth, total)
}

// Accounts in groups, a sum for each group and a sum of those.
private class Ledger(
    val g: Graph,
    val rate: Input<Double>,
    val shock: Input<Double>,
    val floors: List<Input<Double>>,
    val groups: List<Node<Double>>,
    val total: Node<Double>,
) {
    val scenarios = DoubleArray(100) { if (it % 2 == 0) 0.002 * it - 0.03 else 0.01 * (it % 7) }

    context(_: Fail<Refused>)
    fun kept(): Kept = g.also { it.stabilize() }.kept(vary = listOf(rate, shock), outputs = listOf(total), keep = groups)
}

context(_: Fail<Refused>)
private fun ledger(): Ledger {
    val g = Graph()
    val rate = g.input("rate", 0.01)
    val shock = g.input("shock", 0.0)
    val floors = (0 until 20).map { n -> g.input("floor$n", 90.0 + n) }
    val groups = (0 until 5).map { a ->
        val each = (0 until 4).map { b ->
            val n = a * 4 + b
            val value = (1 until 6).fold(g.define("v${n}_0", (rate + 1.0) * (100.0 + n))) { value, t ->
                g.define("v${n}_$t", value * (rate + 1.0) - shock)
            }
            g.define("worth$n", value + floors[n] * shock)
        }
        g.define("group$a", each.drop(1).fold(each[0] as Expr<Double>) { all, x -> all + x })
    }
    val total = g.define("total", groups.drop(1).fold(groups[0] as Expr<Double>) { all, x -> all + x })
    return Ledger(g, rate, shock, floors, groups, total)
}
