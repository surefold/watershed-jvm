import dev.surefold.assert.shouldBeRight
import dev.surefold.files.InMemoryFiles
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.withTempDirectory
import dev.surefold.watershed.cluster.Store
import kotlin.io.path.pathString
import kotlin.io.path.writeBytes

object StoreSuite : Suite() {
    init {
        // A big file would not reach a bucket in the time one call on it is allowed, so it goes a part at a time.
        testEach("A file of this machine is in the bucket as it was", listOf(1_000, 5_000_000), named = { "$it numbers" }) { count ->
            withTempDirectory("watershed-store") { folder ->
                val store = Store(InMemoryFiles())
                val file = folder.resolve("numbers").also { it.writeBytes(ByteArray(count * 8) { (it % 251).toByte() }) }
                store.upload(file.pathString, "book/numbers").shouldBeRight()
                store.count("book/numbers").shouldBeRight() shouldBe count.toLong()
                val back = store.read("book/numbers").shouldBeRight()
                listOf(back.first().toRawBits(), back.last().toRawBits()) shouldBe listOf(0, count - 1).map { bits(it) }
            }
        }

        test("A part of a file put there in pieces is read from the pieces it is in") {
            withTempDirectory("watershed-store") { folder ->
                val store = Store(InMemoryFiles())
                val file = folder.resolve("numbers").also { it.writeBytes(ByteArray(1_000 * 8) { (it % 251).toByte() }) }
                // Pieces of 96 numbers: the last is shorter, and a part can lie across several.
                store.upload(file.pathString, "book/numbers", 96 * 8).shouldBeRight()
                store.pieced("book/numbers").shouldBeRight() shouldBe true
                store.count("book/numbers").shouldBeRight() shouldBe 1_000L
                listOf(0 to 10, 90 to 20, 95 to 300, 960 to 40, 999 to 1, 500 to 0).forEach { (from, count) ->
                    val part = store.read("book/numbers", from.toLong(), count).shouldBeRight()
                    part.map { it.toRawBits() } shouldBe (from until from + count).map { bits(it) }
                }
                store.read("book/numbers", 990, 11).isLeft() shouldBe true
                // A file there whole is not in pieces.
                store.upload(file.pathString, "book/whole").shouldBeRight()
                store.pieced("book/whole").shouldBeRight() shouldBe false
            }
        }
    }
}

// The number the bytes written at a place make: eight of them, least significant first.
private fun bits(at: Int): Long = (0 until 8).fold(0L) { all, byte -> all or (((at * 8 + byte) % 251).toLong() shl (8 * byte)) }
