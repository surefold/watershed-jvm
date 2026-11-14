package dev.surefold.watershed.cluster

import dev.surefold.fail.Either
import dev.surefold.fail.Fail
import dev.surefold.fail.attempt
import dev.surefold.fail.bind
import dev.surefold.fail.ensure
import dev.surefold.fail.mapLeft
import dev.surefold.files.FileError
import dev.surefold.files.FileKey
import dev.surefold.files.Files
import dev.surefold.watershed.Refused
import dev.surefold.watershed.Saved
import kotlin.io.path.Path
import kotlin.io.path.fileSize
import kotlin.io.path.inputStream

/**
 * Where a job's files are: a bucket every node reaches, through Surefold's [Files] (any S3-compatible store, or
 * `InMemoryFiles` in a test). A file is named by its key there. Nothing is kept on a node's own disk: a plan or a
 * patch goes between the bucket and the engine as bytes, a part at a time.
 *
 * Numbers in a file are eight bytes each, least significant first, one after another: what the engine's own
 * examples write.
 */
class Store(private val files: Files) {
    /** How many numbers the file at [key] holds. */
    fun count(key: String): Either<JobFailure.File, Long> = bucket(key) { files.head(it).bind().size / Double.SIZE_BYTES }

    /** Writes [numbers] at [key], in place of anything there. A lot two workers ran is whichever finished last: the same numbers. */
    fun write(key: String, numbers: DoubleArray): Either<JobFailure.File, Unit> = bucket(key) { at ->
        val bytes = numbers.bytes()
        files.put(at, bytes.inputStream(), bytes.size.toLong(), BYTES).bind()
    }

    /** Every number of the file at [key]. */
    fun read(key: String): Either<JobFailure.File, DoubleArray> =
        bucket(key) { at -> files.get(at).bind().use { it.stream.readAllBytes() }.numbers() }

    /** Puts a plan or a patch the engine [saved] at [key], a part at a time. */
    fun put(key: String, saved: Saved): Either<JobFailure.File, Unit> = attempt {
        sent(key, saved.size) { from, count -> attempt { engine(key) { saved.read(from, count) } } }
    }

    /** The plan or patch at [key], as bytes in the engine, for `Plan.from` or `Patch.from` to read. */
    fun fetch(key: String): Either<JobFailure.File, Saved> = attempt {
        val size = bucket(key) { files.head(it).bind().size }.bind()
        val saved = engine(key) { Saved.sized(size) }
        val content = bucket(key) { files.get(it).bind() }.bind()
        content.use { filled(key, saved, it.stream::readNBytes, 0) }
        saved
    }

    /** Puts the file at the path [from], on this machine, into the bucket at [key]: how what the engine's examples wrote gets there. */
    fun upload(from: String, key: String): Either<JobFailure.File, Unit> = attempt {
        val size = disk(key) { Path(from).fileSize() }.bind()
        val stream = disk(key) { Path(from).inputStream() }.bind()
        // Read in order, so each part is what comes next in the file.
        stream.use { sent(key, size) { _, count -> disk(key) { it.readNBytes(count) } } }
    }

    // Puts [size] bytes at [key], [part] giving those from a place on: in one go if small, and a part at a time
    // otherwise, so that no one call on the bucket has more to send than its time allows.
    context(_: Fail<JobFailure.File>)
    private fun sent(key: String, size: Long, part: (Long, Int) -> Either<JobFailure.File, ByteArray>) {
        if (size <= PART) {
            val bytes = part(0, size.toInt()).bind()
            bucket(key) { at -> files.put(at, bytes.inputStream(), bytes.size.toLong(), BYTES).bind() }.bind()
        } else {
            val upload = bucket(key) { at -> files.multipart(at, BYTES).bind() }.bind()
            (0 until size step PART).forEachIndexed { number, from ->
                val bytes = part(from, minOf(PART, size - from).toInt()).bind()
                ensure(bytes.size.toLong() == minOf(PART, size - from)) { JobFailure.File(key, "it ended after ${from + bytes.size} of $size bytes") }
                bucket(key) { upload.part(number + 1, bytes.inputStream(), bytes.size.toLong()).bind() }.bind()
            }
            bucket(key) { upload.complete().bind() }.bind()
        }
    }

    // Reads what is left of a file into [saved], a part at a time; a file that ends early, or goes on, is not the one asked for.
    context(_: Fail<JobFailure.File>)
    private tailrec fun filled(key: String, saved: Saved, read: (Int) -> ByteArray, at: Long) {
        val bytes = disk(key) { read(PART.toInt()) }.bind()
        ensure(at + bytes.size <= saved.size) { JobFailure.File(key, "it is longer than the store said: ${saved.size} bytes") }
        if (bytes.isEmpty()) {
            ensure(at == saved.size) { JobFailure.File(key, "it ended after $at of ${saved.size} bytes") }
            return
        }
        engine(key) { saved.write(at, bytes) }
        filled(key, saved, read, at + bytes.size)
    }

    // A call on the bucket: its key checked, and what the store refuses said as this module's failure.
    private fun <A> bucket(key: String, call: Fail<FileError>.(FileKey) -> A): Either<JobFailure.File, A> =
        attempt<FileError, A> { call(FileKey.of(key).bind()) }.mapLeft { JobFailure.File(key, it.toString()) }

    // What the engine refuses of a file's bytes is the same failure.
    context(_: Fail<JobFailure.File>)
    private fun <A> engine(key: String, call: Fail<Refused>.() -> A): A =
        attempt(call).mapLeft { JobFailure.File(key, it.why) }.bind()

    // Reading a stream, or a file of this machine, throws when it cannot; that is the same failure too.
    private inline fun <A> disk(key: String, block: () -> A): Either<JobFailure.File, A> =
        Either.catch(block).mapLeft { JobFailure.File(key, it.toString()) }

    private companion object {
        const val BYTES = "application/octet-stream"

        // How much of a plan crosses at a time: more than the five megabytes S3 wants of every part but the last.
        const val PART = 32L * 1024 * 1024
    }
}

// Numbers as their bytes, eight to a number, least significant first; and back.
private fun DoubleArray.bytes(): ByteArray = ByteArray(size * Double.SIZE_BYTES).also { all ->
    forEachIndexed { at, number ->
        val bits = number.toRawBits()
        repeat(Double.SIZE_BYTES) { byte -> all[at * Double.SIZE_BYTES + byte] = (bits ushr (8 * byte)).toByte() }
    }
}

private fun ByteArray.numbers(): DoubleArray = DoubleArray(size / Double.SIZE_BYTES) { at ->
    Double.fromBits((0 until Double.SIZE_BYTES).fold(0L) { bits, byte -> bits or ((this[at * Double.SIZE_BYTES + byte].toLong() and 0xff) shl (8 * byte)) })
}
