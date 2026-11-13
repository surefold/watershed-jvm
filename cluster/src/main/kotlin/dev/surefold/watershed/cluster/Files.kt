package dev.surefold.watershed.cluster

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
import java.nio.file.StandardOpenOption.WRITE

// Numbers in files are eight bytes each, least significant first, one after another: what the engine's own
// examples write.

/** [count] numbers of the file at [path], from the [first]. */
fun readNumbers(path: Path, first: Long, count: Int): DoubleArray {
    val bytes = ByteBuffer.allocate(Math.multiplyExact(count, 8)).order(ByteOrder.LITTLE_ENDIAN)
    FileChannel.open(path, READ).use { file ->
        var at = first * 8
        while (bytes.hasRemaining()) {
            val read = file.read(bytes, at)
            require(read > 0) { "$path is too short: it has no number ${first + count - 1}" }
            at += read
        }
    }
    return DoubleArray(count).also { bytes.flip().asDoubleBuffer().get(it) }
}

/** How many numbers the file at [path] holds. */
fun countNumbers(path: Path): Long = Files.size(path) / 8

/**
 * Writes [numbers] to [path]. The file appears whole or not at all, so a reader never finds half of one, and a
 * lot that two workers ran is whichever finished last: the same numbers either way.
 */
fun writeNumbers(path: Path, numbers: DoubleArray) {
    val bytes = ByteBuffer.allocate(Math.multiplyExact(numbers.size, 8)).order(ByteOrder.LITTLE_ENDIAN)
    bytes.asDoubleBuffer().put(numbers)
    val part = path.resolveSibling("${path.fileName}.${ProcessHandle.current().pid()}.${System.nanoTime()}.part")
    FileChannel.open(part, CREATE, WRITE, TRUNCATE_EXISTING).use { file ->
        while (bytes.hasRemaining()) file.write(bytes)
        file.force(false)
    }
    Files.move(part, path, ATOMIC_MOVE, REPLACE_EXISTING)
}
