package dev.surefold.watershed.cluster

import dev.surefold.fail.Either
import dev.surefold.fail.Fail
import dev.surefold.fail.attempt
import dev.surefold.fail.bind
import dev.surefold.fail.ensure
import dev.surefold.fail.map
import dev.surefold.fail.mapLeft
import dev.surefold.files.FileError
import dev.surefold.files.FileKey
import dev.surefold.files.Files
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.READ
import java.nio.file.Files as LocalFiles

/**
 * Where a job's files are: a bucket every node reaches ([files], any S3-compatible store, or `InMemoryFiles` in a
 * test), and a folder on this machine, [local], for the copies the engine needs as files of its own.
 *
 * Numbers in a file are eight bytes each, least significant first, one after another: what the engine's own
 * examples write. A file is named by its key in the bucket, so nothing here depends on a shared disk.
 */
class Store(private val files: Files, private val local: Path) {
    /** How many numbers the file at [key] holds. */
    fun count(key: String): Either<JobFailure.File, Long> = bucket(key) { files.head(it).bind().size / Double.SIZE_BYTES }

    /** Writes [numbers] at [key], in place of anything there. A lot two workers ran is whichever finished last: the same numbers. */
    fun write(key: String, numbers: DoubleArray): Either<JobFailure.File, Unit> = bucket(key) { at ->
        val bytes = ByteBuffer.allocate(numbers.size * Double.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        bytes.asDoubleBuffer().put(numbers)
        files.put(at, ByteArrayInputStream(bytes.array()), bytes.capacity().toLong(), BYTES).bind()
    }

    /** Every number of the file at [key]: a lot's answers. */
    fun read(key: String): Either<JobFailure.File, DoubleArray> = attempt {
        val bytes = bucket(key) { at -> files.get(at).bind().use { it.stream.readAllBytes() } }.bind()
        DoubleArray(bytes.size / Double.SIZE_BYTES).also { ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asDoubleBuffer().get(it) }
    }

    /** [count] numbers of the file at [key], from the [first], read from this machine's copy of it. */
    fun read(key: String, first: Long, count: Long): Either<JobFailure.File, DoubleArray> = attempt {
        ensure(count in 0..Int.MAX_VALUE / Double.SIZE_BYTES) { JobFailure.File(key, "$count numbers are more than can be read at once") }
        val copy = fetched(key).bind()
        val bytes = ByteBuffer.allocate(count.toInt() * Double.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        val whole = disk(key) { FileChannel.open(copy, READ).use { it.fill(bytes, first * Double.SIZE_BYTES) } }.bind()
        ensure(whole) { JobFailure.File(key, "it is too short: it has no number ${first + count - 1}") }
        DoubleArray(count.toInt()).also { bytes.flip().asDoubleBuffer().get(it) }
    }

    /**
     * The file at [key] as a file on this machine, fetched once: the engine reads a plan or a patch from a path. A
     * copy is named for what the bucket says the file holds, so a file that changes is fetched again.
     */
    fun fetched(key: String): Either<JobFailure.File, Path> = attempt {
        val meta = bucket(key) { files.head(it).bind() }.bind()
        val copy = local.resolve("${meta.etag.filter(Char::isLetterOrDigit)}-${meta.key.name}")
        if (!disk(key) { LocalFiles.exists(copy) }.bind()) {
            val content = bucket(key) { files.get(it).bind() }.bind()
            disk(key) {
                LocalFiles.createDirectories(local)
                val part = LocalFiles.createTempFile(local, meta.key.name, ".part")
                content.use { LocalFiles.copy(it.stream, part, REPLACE_EXISTING) }
                LocalFiles.move(part, copy, ATOMIC_MOVE, REPLACE_EXISTING)
            }.bind()
        }
        copy
    }

    /** Puts the file at [from] on this machine into the bucket at [key]: how a plan or a patch the engine saved gets there. */
    fun put(from: Path, key: String): Either<JobFailure.File, Unit> = attempt {
        val size = disk(key) { LocalFiles.size(from) }.bind()
        val stream = disk(key) { LocalFiles.newInputStream(from) }.bind()
        bucket(key) { at -> stream.use { files.put(at, it, size, BYTES).bind() } }.bind()
    }

    // A call on the bucket: its key checked, and what the store refuses said as this module's failure.
    private fun <A> bucket(key: String, call: Fail<FileError>.(FileKey) -> A): Either<JobFailure.File, A> =
        attempt<FileError, A> { call(FileKey.of(key).bind()) }.mapLeft { JobFailure.File(key, it.toString()) }

    // java.nio throws for a local file it cannot read or write; that is the same failure.
    private inline fun <A> disk(key: String, block: () -> A): Either<JobFailure.File, A> =
        Either.catch(block).mapLeft { JobFailure.File(key, it.toString()) }

    // Reads until [bytes] is full; false if the file ends first.
    private tailrec fun FileChannel.fill(bytes: ByteBuffer, at: Long): Boolean {
        if (!bytes.hasRemaining()) return true
        val read = read(bytes, at)
        return if (read <= 0) false else fill(bytes, at + read)
    }

    private companion object {
        const val BYTES = "application/octet-stream"
    }
}
