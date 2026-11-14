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
import dev.surefold.files.FileMeta
import dev.surefold.files.FilePage
import dev.surefold.files.Files
import com.github.luben.zstd.ZstdInputStream
import com.github.luben.zstd.ZstdOutputStream
import dev.surefold.watershed.Plan
import dev.surefold.watershed.Refused
import dev.surefold.watershed.Saved
import kotlin.io.path.Path
import kotlin.io.path.createParentDirectories
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.inputStream
import kotlin.io.path.moveTo
import kotlin.io.path.outputStream
import kotlin.io.path.pathString

/**
 * Where a job's files are: a bucket every node reaches, through Surefold's [Files] (any S3-compatible store, or
 * `InMemoryFiles` in a test). A file is named by its key there. Nothing is kept on a node's own disk: a plan or a
 * patch goes between the bucket and the engine as bytes, a part at a time.
 *
 * Numbers in a file are eight bytes each, least significant first, one after another: what the engine's own
 * examples write.
 */
class Store(
    private val files: Files,
    /** A folder of this machine to look in for a plan before the bucket: the `get` command puts one there, for every worker of the machine to read. */
    private val local: String? = null,
) {
    /** How many numbers the file at [key] holds, whether it is there whole or in pieces. */
    fun count(key: String): Either<JobFailure.File, Long> = attempt {
        val pieces = pieces(key).bind()
        val bytes = if (pieces.isEmpty()) bucket(key) { files.head(it).bind().size }.bind() else pieces.sumOf { it.size }
        bytes / Double.SIZE_BYTES
    }

    /** Whether the file at [key] was put there in pieces: a part of it can then be read without the rest. */
    fun pieced(key: String): Either<JobFailure.File, Boolean> = pieces(key).map { it.isNotEmpty() }

    /** [count] numbers of the file in pieces at [key], from the one at [from] on: only the pieces they are in are fetched. */
    fun read(key: String, from: Long, count: Int): Either<JobFailure.File, DoubleArray> = attempt {
        val pieces = pieces(key).bind()
        ensure(pieces.isNotEmpty()) { JobFailure.File(key, "it is not in pieces") }
        val (first, bytes) = from * Double.SIZE_BYTES to count.toLong() * Double.SIZE_BYTES
        val each = pieces.first().size
        ensure(from >= 0 && count >= 0 && first + bytes <= pieces.sumOf { it.size } && bytes <= Int.MAX_VALUE) {
            JobFailure.File(key, "it has no numbers $from to ${from + count}")
        }
        if (bytes == 0L) {
            DoubleArray(0)
        } else {
            val wanted = first / each..(first + bytes - 1) / each
            val fetched = wanted.map { n -> whole(pieces[n.toInt()].key.value).bind() }.fold(ByteArray(0)) { all, piece -> all + piece }
            val start = (first - wanted.first * each).toInt()
            fetched.copyOfRange(start, start + bytes.toInt()).numbers()
        }
    }

    /** Writes [numbers] at [key], in place of anything there. A lot two workers ran is whichever finished last: the same numbers. */
    fun write(key: String, numbers: DoubleArray): Either<JobFailure.File, Unit> = bucket(key) { at ->
        val bytes = numbers.bytes()
        files.put(at, bytes.inputStream(), bytes.size.toLong(), BYTES).bind()
    }

    /** Every number of the file at [key]. */
    fun read(key: String): Either<JobFailure.File, DoubleArray> = whole(key).map { it.numbers() }

    private fun whole(key: String): Either<JobFailure.File, ByteArray> =
        bucket(key) { at -> files.get(at).bind().use { it.stream.readAllBytes() } }

    // The pieces of the file at [key], in order; none if it is there whole. A piece is the key, a slash and its number.
    private fun pieces(key: String): Either<JobFailure.File, List<FileMeta>> = attempt { listed(key, null, emptyList()) }

    context(_: Fail<JobFailure.File>)
    private tailrec fun listed(key: String, after: String?, before: List<FileMeta>): List<FileMeta> {
        val page = attempt<FileError, FilePage> { files.list("$key/", after).bind() }.mapLeft { JobFailure.File(key, it.toString()) }.bind()
        val all = before + page.files
        return if (page.next == null) all else listed(key, page.next, all)
    }

    /** Puts a plan or a patch the engine [saved] at [key], a part at a time. */
    fun put(key: String, saved: Saved): Either<JobFailure.File, Unit> = attempt {
        sent(key, saved.size) { from, count -> attempt { engine(key) { saved.read(from, count) } } }
    }

    /** The plan or patch at [key], as bytes in the engine, for `Plan.from` or `Patch.from` to read. */
    fun fetch(key: String): Either<JobFailure.File, Saved> = attempt {
        val size = bucket(key) { files.head(it).bind().size }.bind()
        val content = bucket(key) { files.get(it).bind() }.bind()
        content.use {
            val start = disk(key) { it.stream.readNBytes(HEAD) }.bind()
            val packed = unpacked(start)
            if (packed == null) {
                // As it is: what was read to find that out is its beginning.
                val saved = engine(key) { Saved.sized(size) }
                ensure(start.size <= size) { JobFailure.File(key, "it is longer than the store said: $size bytes") }
                engine(key) { saved.write(0, start) }
                filled(key, saved, it.stream::readNBytes, start.size.toLong())
                saved
            } else {
                val saved = engine(key) { Saved.sized(packed) }
                val plain = disk(key) { ZstdInputStream(it.stream) }.bind()
                filled(key, saved, plain::readNBytes, 0)
                saved
            }
        }
    }

    /** The plan at [key]: from this machine's folder if it is there, and from the bucket if not. */
    fun plan(key: String): Either<JobFailure.File, Plan> = attempt {
        val here = local?.let { Path(it, key) }?.takeIf { disk(key) { it.exists() }.bind() }
        if (here == null) fetch(key).bind().let { saved -> engine(key) { Plan.from(saved) } } else engine(key) { Plan.load(here.pathString) }
    }

    /** Copies the file at [key] to the path [to] on this machine, as it was before it was packed. */
    fun download(key: String, to: String): Either<JobFailure.File, Unit> = attempt {
        val content = bucket(key) { files.get(it).bind() }.bind()
        // Under another name until it is whole, so that nothing reads half of it.
        val part = Path("$to.part")
        disk(key) {
            part.createParentDirectories()
            content.use {
                val start = it.stream.readNBytes(HEAD)
                part.outputStream().use { out ->
                    if (unpacked(start) == null) out.write(start).also { _ -> it.stream.copyTo(out) } else ZstdInputStream(it.stream).copyTo(out)
                }
            }
            part.moveTo(Path(to), overwrite = true)
        }.bind()
    }

    /** Puts the file at the path [from], on this machine, into the bucket at [key]: how what the engine's examples wrote gets there. */
    fun upload(from: String, key: String): Either<JobFailure.File, Unit> = upload(from, key, 0)

    /**
     * As [upload], with the file packed (zstd) on the way: a plan is a quarter of its size so, and [fetch], [plan]
     * and [download] give it back as it was. Not for a file read by anything else.
     */
    fun uploadPacked(from: String, key: String): Either<JobFailure.File, Unit> = attempt {
        val size = disk(key) { Path(from).fileSize() }.bind()
        val packed = disk(key) { createTempFile("watershed-packed") }.bind()
        disk(key) {
            packed.outputStream().use { out ->
                out.write(MARK + size.bytes())
                ZstdOutputStream(out, LEVEL).also { it.setWorkers(WORKERS) }.use { zstd -> Path(from).inputStream().use { it.copyTo(zstd) } }
            }
        }.bind()
        val sent = upload(packed.pathString, key)
        disk(key) { packed.deleteIfExists() }.bind()
        sent.bind()
    }

    /**
     * As [upload], in pieces of [piece] bytes if that is more than none, each a file of its own: for the numbers
     * of many scenarios, so that a worker fetches the pieces its lot is in and not the whole file.
     */
    fun upload(from: String, key: String, piece: Long): Either<JobFailure.File, Unit> = attempt {
        ensure(piece >= 0 && piece % Double.SIZE_BYTES == 0L) { JobFailure.File(key, "a piece is a whole number of numbers, not $piece bytes") }
        val size = disk(key) { Path(from).fileSize() }.bind()
        val stream = disk(key) { Path(from).inputStream() }.bind()
        // Read in order, so each part is what comes next in the file.
        stream.use { content ->
            if (piece == 0L) {
                sent(key, size) { _, count -> disk(key) { content.readNBytes(count) } }
            } else {
                (0 until size step piece).forEachIndexed { number, at ->
                    val name = "$key/${number.toString().padStart(PIECE_DIGITS, '0')}"
                    sent(name, minOf(piece, size - at)) { _, count -> disk(name) { content.readNBytes(count) } }
                }
            }
        }
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

        // What a packed file begins with: this, then how many bytes it is unpacked, then those bytes as zstd.
        val MARK = "WSPACKED".encodeToByteArray()
        val HEAD = MARK.size + Long.SIZE_BYTES
        const val LEVEL = 3
        const val WORKERS = 8

        // How many bytes the file that begins with [start] is once unpacked, if it is packed.
        fun unpacked(start: ByteArray): Long? =
            if (start.size == HEAD && start.copyOf(MARK.size).contentEquals(MARK)) {
                (0 until Long.SIZE_BYTES).fold(0L) { all, byte -> all or ((start[MARK.size + byte].toLong() and 0xff) shl (8 * byte)) }
            } else {
                null
            }

        fun Long.bytes(): ByteArray = ByteArray(Long.SIZE_BYTES) { byte -> (this ushr (8 * byte)).toByte() }

        // Pieces are numbered with this many digits, so that their keys are in order as the store lists them.
        const val PIECE_DIGITS = 6

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
