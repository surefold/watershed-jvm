import dev.surefold.junit.Suite
import dev.surefold.junit.expect
import dev.surefold.junit.shouldBe
import dev.surefold.watershed.*
import java.math.BigDecimal
import java.math.RoundingMode

object WatershedSuite : Suite() {
    init {
        test("Changing an input recalculates only what depends on it") {
            engine {
                val g = Graph()
                val qty = g.input("qty", 10.0)
                val price = g.input("price", 2.5)
                val total: Node<Double> = g.define("total", qty * price)
                g.define("elsewhere", qty + 1)
                g[total] shouldBe 25.0

                g[price] = 3.0
                val report = g.stabilize()
                report.recalculated shouldBe 1L
                g.lastRecalculated() shouldBe listOf("total")
                g[total] shouldBe 30.0
                expect(g.explain(total).startsWith("total = 30  ← qty * price"))
                g.checkConsistency()
            }
        }

        test("Expressions of every type work out as written") {
            engine {
                val g = Graph()
                val a = g.input("a", 6.0)
                val b = g.input("b", 4.0)
                val n = g.input("n", 7L)
                val on = g.input("on", true)
                val side = g.input("side", "Buy")

                g[g.define("d1", -a + b)] shouldBe -2.0
                val d2 = g.define("d2", a + b * 2)
                g[d2] shouldBe 14.0
                g[g.define("d3", a / b / 2.0)] shouldBe 0.75
                g[g.define("d4", (a greatest b) - (a least b))] shouldBe 2.0
                g[g.define("d5", abs(b - a))] shouldBe 2.0
                g[g.define("w1", n * 2 + 1)] shouldBe 15L
                g[g.define("w2", 10L - n)] shouldBe 3L
                g[g.define("f1", (a gt b) and on)] shouldBe true
                g[g.define("f2", !on or (a eq b))] shouldBe false
                g[g.define("i1", whenever(side eq "Buy", 1.0, -1.0) * a)] shouldBe 6.0
                g[g.define("t1", side + " " + text(n))] shouldBe "Buy 7"
                g[g.define("t2", texts(side, value("x")).join("-"))] shouldBe "Buy-x"
                g[g.define("l1", texts(side) + texts(side))] shouldBe listOf("Buy", "Buy")
                g[g.define("c1", choose(n - 6, a, b))] shouldBe 4.0

                // Each one explains itself as the engine sees it.
                g.explain(d2).lines().first() shouldBe "d2 = 14  ← a + (b * 2)"
                g.checkConsistency()
            }
        }

        test("Decimals are exact and round as asked") {
            engine {
                val g = Graph()
                val a = g.input("a", BigDecimal("0.1"))
                val b = g.input("b", BigDecimal("0.2"))
                val sum = g.define("sum", a + b)
                g[sum] shouldBe BigDecimal("0.3")

                // 1.50 keeps its two places through a calculation.
                val price = g.input("price", BigDecimal("1.50"))
                val line = g.define("line", price * 3)
                g[line].toPlainString() shouldBe "4.50"

                val x = g.input("x", BigDecimal("2.345"))
                g[g.define("even", x.round(2))] shouldBe BigDecimal("2.34")
                g[g.define("up", x.round(2, RoundingMode.HALF_UP))] shouldBe BigDecimal("2.35")
                g[g.define("third", 1 / (x - x + 3))].toPlainString() shouldBe "0.3333333333333333333333333333"
                g[g.define("more", x gt BigDecimal("2.3"))] shouldBe true

                val count = g.input("count", 3L)
                val total = g.define("total", count.toDecimal() * price)
                g[total] shouldBe BigDecimal("4.50")
                g[g.define("rough", total.toDouble() * 2.0)] shouldBe 9.0

                g[price] = BigDecimal("2.00")
                g.stabilize()
                g[total] shouldBe BigDecimal("6.00")

                val orders = g.collection("orders")
                val net = orders.decimal("net")
                val gross = orders.column("gross", (net * BigDecimal("1.20")).round(2))
                val revenue = orders.sum("revenue", gross)
                repeat(1000) { orders.upsert("o$it", net(BigDecimal("0.10"))) }
                g.stabilize()
                g[revenue].toPlainString() shouldBe "120.00"

                // More digits than the engine holds is refused, not rounded.
                refusal { g.input("huge", BigDecimal("1e40")) }
                g.checkConsistency()
            }
        }

        test("A collection's totals follow its rows") {
            engine {
                val g = Graph()
                val orders = g.collection("orders")
                val qty = orders.number("qty")
                val region = orders.text("region")
                val double = orders.column("double", qty * 2)
                val total = orders.sum("total", double)
                val rows = orders.count("rows")
                orders.upsert("a", qty(1.0), region("EU"))
                orders.upsert("b", qty(4), region("US"))
                g.stabilize()
                g[total] shouldBe 10.0
                g[rows] shouldBe 2L

                orders.upsert("a", qty(3.0))
                g.stabilize().recalculated shouldBe 2L
                g[total] shouldBe 14.0
                orders["a", region] shouldBe "EU"
                orders["a", double] shouldBe 6.0

                expect(orders.remove("b"))
                g.stabilize()
                g[total] shouldBe 6.0
                orders.size() shouldBe 1L
                g.checkConsistency()
            }
        }

        test("What the engine refuses comes with a reason") {
            engine {
                val g = Graph()
                val a = g.input("a", 1.0)
                val again = refusal { g.input("a", 2.0) }
                again.why shouldBe "a node named \"a\" already exists"

                // A field can only be used in its own collection's columns.
                val qty = g.collection("orders").number("qty")
                refusal { g.define("stray", qty + a) }

                // A calculation that fails is a value the node holds, not a crash.
                val zero = g.input("zero", 0L)
                val ratio = g.define("ratio", zero / zero)
                refusal { g[ratio] }
                expect(refusal { g[ratio] }.why.contains("ratio"))
            }
        }

        test("Many nodes and many prices can go in one call each") {
            engine {
                val g = Graph()
                val prices = List(100) { g.input("p$it", it.toDouble()) }
                val doubles = g.defineAll(prices.mapIndexed { i, p -> "d$i" to p * 2 })
                val total = g.define("total", doubles.reduce<Expr<Double>, Node<Double>> { sum, d -> sum + d })
                g[total] shouldBe 9900.0

                g.setAll(mapOf(prices[0] to 10.0, prices[1] to 11.0))
                g.stabilize().inputsChanged.toInt() - 1 shouldBe 1
                g[total] shouldBe 9940.0
                g.checkConsistency()
            }
        }

        test("A failure or a node of another graph is told apart from a value") {
            engine {
                val g = Graph()
                val a = g.input("a", 1.0)
                val b = g.input("b", 0L)
                val half = g.define("half", a / 2.0)
                val broken = g.define("broken", (1L / b).toDecimal())
                val nan = g.define("nan", a - a + Double.NaN)

                // A Double is set, updated and read directly.
                g[a] = 5.0
                val report = g.stabilize()
                report.inputsChanged shouldBe 1L
                report.recalculated shouldBe 2L
                g[half] shouldBe 2.5

                // A node that really holds NaN is read as NaN, and one that
                // failed says why.
                expect(g[nan].isNaN())
                expect(refusal { g[broken] }.why.contains("division by zero"))

                // A node of another graph is refused rather than read as this one's.
                val other = Graph()
                val theirs = other.input("theirs", 9.0)
                refusal { g[theirs] }
                refusal { g[theirs] = 1.0 }
                g.checkConsistency()
            }
        }

        test("Every type of value is set and read back") {
            engine {
                val g = Graph()
                val number = g.input("number", 1.5)
                val whole = g.input("whole", 7L)
                val decimal = g.input("decimal", BigDecimal("19.99"))
                val flag = g.input("flag", true)
                val text = g.input("text", "héllo, wörld")
                val list = g.define("list", texts(text, text))
                g[number] shouldBe 1.5
                g[whole] shouldBe 7L
                g[decimal] shouldBe BigDecimal("19.99")
                g[flag] shouldBe true
                g[text] shouldBe "héllo, wörld"
                g[list] shouldBe listOf("héllo, wörld", "héllo, wörld")

                g[number] = -0.25
                g[whole] = Long.MIN_VALUE
                g[decimal] = BigDecimal("-79228162514264337593543950335")
                g[flag] = false
                g[text] = ""
                g.stabilize().inputsChanged shouldBe 5L
                g[number] shouldBe -0.25
                g[whole] shouldBe Long.MIN_VALUE
                g[decimal] shouldBe BigDecimal("-79228162514264337593543950335")
                g[flag] shouldBe false
                g[text] shouldBe ""
                g[list] shouldBe listOf("", "")

                // The smallest step a decimal has, and a long text.
                g[decimal] = BigDecimal("0.0000000000000000000000000001")
                g[text] = "x".repeat(10_000)
                g.stabilize()
                g[decimal].toPlainString() shouldBe "0.0000000000000000000000000001"
                g[text].length shouldBe 10_000
                g.lastRecalculated() shouldBe listOf("list")
                g.checkConsistency()
            }
        }

        test("Many rows go in one call") {
            engine {
                val g = Graph()
                val orders = g.collection("orders")
                val qty = orders.whole("qty")
                val price = orders.decimal("price")
                val note = orders.text("note")
                val line = orders.column("line", qty.toDecimal() * price)
                val total = orders.sum("total", line)
                val count = orders.count("count")
                orders.upsertAll(List(1000) { row("o$it", qty(it), price(BigDecimal("0.10")), note("row $it")) })
                g.stabilize()
                g[count] shouldBe 1000L
                g[total].toPlainString() shouldBe "49950.00"
                orders["o7", note] shouldBe "row 7"

                // Changing some rows changes only those.
                orders.upsertAll(listOf(row("o1", qty(1001)), row("o2", qty(1002))))
                g.stabilize().recalculated shouldBe 3L
                g[total].toPlainString() shouldBe "50150.00"

                // A row that is refused says why, and the rows before it stay.
                val refused = refusal {
                    orders.upsertAll(listOf(row("new", qty(1), price(BigDecimal("1")), note("ok")), row("bad", qty(1))))
                }
                expect(refused.why.contains("needs every field")) { refused.why }
                g.stabilize()
                g[count] shouldBe 1001L
                g.checkConsistency()
            }
        }

        test("In pull mode only what is watched is calculated") {
            engine {
                val g = Graph()
                val a = g.input("a", 1.0)
                val watched = g.define("watched", a + 1)
                val other = g.define("other", a + 2)
                g.watch(watched)
                g.calculateOnlyWatched(true)
                g[a] = 5
                g.stabilize().recalculated shouldBe 1L
                g[watched] shouldBe 6.0
                refusal { g[other] }

                g.calculateOnlyWatched(false)
                g.stabilize()
                g[other] shouldBe 7.0
            }
        }
    }
}
