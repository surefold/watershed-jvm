package dev.surefold.watershed.cluster

import dev.surefold.fail.Either
import dev.surefold.fail.attempt
import dev.surefold.fail.bind
import dev.surefold.fail.ensure
import dev.surefold.fail.mapLeft
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE

// Numbers in files are eight bytes each, least significant first, one after another: what the engine's own
// examples write. Surefold has no files of its own, so this is the one place that reaches for java.nio.

/** [count] numbers of the file at [path], from the [first]. */
fun readNumbers(path: Path, first: Long, count: Int): Either<JobFailure.File, DoubleArray> = attempt {
    val bytes = ByteBuffer.allocate(Math.multiplyExact(count, 8)).order(ByteOrder.LITTLE_ENDIAN)
    val whole = filed(path) { FileChannel.open(path, READ).use { it.fill(bytes, first * 8) } }.bind()
    ensure(whole) { JobFailure.File(path, "it is too short: it has no number ${first + count - 1}") }
    DoubleArray(count).also { bytes.flip().asDoubleBuffer().get(it) }
}

/** How many numbers the file at [path] holds. */
fun countNumbers(path: Path): Either<JobFailure.File, Long> = filed(path) { Files.size(path) / 8 }

/**
 * Writes [numbers] to [path]. The file appears whole or not at all, so a reader never finds half of one, and a
 * lot that two workers ran is whichever finished last: the same numbers either way.
 */
fun writeNumbers(path: Path, numbers: DoubleArray): Either<JobFailure.File, Unit> = filed(path) {
    val bytes = ByteBuffer.allocate(Math.multiplyExact(numbers.size, 8)).order(ByteOrder.LITTLE_ENDIAN)
    bytes.asDoubleBuffer().put(numbers)
    val part = Files.createTempFile(path.parent, "${path.fileName}.", ".part")
    FileChannel.open(part, WRITE).use { file ->
        while (bytes.hasRemaining()) file.write(bytes)
        file.force(false)
    }
    Files.move(part, path, ATOMIC_MOVE, REPLACE_EXISTING)
}

/** Makes the folder at [path], and any above it, if they are not there. */
fun makeFolder(path: Path): Either<JobFailure.File, Unit> = filed(path) { Files.createDirectories(path) }

// java.nio throws for a file it cannot read or write; that is this module's declared failure.
private inline fun <A> filed(path: Path, block: () -> A): Either<JobFailure.File, A> =
    Either.catch(block).mapLeft { JobFailure.File(path, it.toString()) }

// Reads until [bytes] is full; false if the file ends first.
private tailrec fun FileChannel.fill(bytes: ByteBuffer, at: Long): Boolean {
    if (!bytes.hasRemaining()) return true
    val read = read(bytes, at)
    return if (read <= 0) false else fill(bytes, at + read)
}
