import dev.surefold.app.validate
import dev.surefold.fail.fold
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.watershed.cluster.Put
import dev.surefold.watershed.cluster.Submit
import dev.surefold.watershed.cluster.Worker

object WiringSuite : Suite() {
    init {
        // Each node of the cluster is a Surefold application; its graph is checked without starting it.
        testEach("An application's graph has everything it asks for", listOf(Worker, Submit, Put), named = { it::class.simpleName.orEmpty() }) { app ->
            app.module.validate().fold({ it.toList().map { fault -> fault.toString() } }, { emptyList() }) shouldBe emptyList()
        }
    }
}
