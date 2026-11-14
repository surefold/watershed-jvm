package dev.surefold.watershed

import dev.surefold.fail.Either
import dev.surefold.fail.attempt
import dev.surefold.fail.ensure
import dev.surefold.fail.left
import dev.surefold.fail.map
import dev.surefold.fail.right
import java.math.BigDecimal
import java.math.BigInteger

/**
 * Writes values, expressions and rows as the bytes the engine reads (see `src/wire.rs`): whole numbers
 * little-endian, text as its length and then UTF-8.
 *
 * Used by the typed Kotlin and Scala layers. Not for calling directly.
 */
class Wire private constructor(private val last: Part?, private val size: Int) {
    constructor() : this(null, 0)

    // What has been written, last first: each put adds one part and copies nothing.
    private class Part(val bytes: ByteArray, val before: Part?)

    fun put(value: Byte): Wire = put(byteArrayOf(value))

    fun put(value: Int): Wire = put(ByteArray(Int.SIZE_BYTES) { (value ushr (8 * it)).toByte() })

    fun put(value: Long): Wire = put(ByteArray(Long.SIZE_BYTES) { (value ushr (8 * it)).toByte() })

    fun put(more: ByteArray): Wire = Wire(Part(more, last), size + more.size)

    fun put(text: String): Wire = text.encodeToByteArray().let { put(it.size).put(it) }

    /** A value: a Double, a Long, a BigDecimal, a Boolean or a String. Anything else is refused. */
    fun value(value: Any?): Either<Refused, Wire> = when (value) {
        is Double -> put(NUMBER).put(value.toRawBits()).right()
        is Long -> put(WHOLE).put(value).right()
        is BigDecimal -> parts(value).map { (low, high, scale) -> put(DECIMAL).put(low).put(high).put(scale.toInt()) }
        is Boolean -> put(FLAG).put((if (value) 1 else 0).toByte()).right()
        is String -> put(TEXT).put(value).right()
        else -> Refused("a value is a Double, a Long, a BigDecimal, a Boolean or a String, not $value").left()
    }

    /** Everything written, in the order it was. */
    fun bytes(): ByteArray = ByteArray(size).also { all -> fill(all, last, size) }

    private tailrec fun fill(all: ByteArray, part: Part?, end: Int) {
        if (part == null) return
        part.bytes.copyInto(all, end - part.bytes.size)
        fill(all, part.before, end - part.bytes.size)
    }

    companion object {
        // The kinds of value, as the engine numbers them.
        const val NUMBER: Byte = 0
        const val WHOLE: Byte = 1
        const val DECIMAL: Byte = 2
        const val FLAG: Byte = 3
        const val TEXT: Byte = 4
        const val TEXTS: Byte = 5
        const val FAILED: Byte = 6

        // Operations on one value.
        const val NEGATE: Byte = 0
        const val NOT: Byte = 1
        const val ABSOLUTE: Byte = 2
        const val TO_TEXT: Byte = 3
        const val TO_DECIMAL: Byte = 4
        const val TO_NUMBER: Byte = 5

        // Operations on two values.
        const val ADD: Byte = 0
        const val SUBTRACT: Byte = 1
        const val MULTIPLY: Byte = 2
        const val DIVIDE: Byte = 3
        const val LEAST: Byte = 4
        const val GREATEST: Byte = 5
        const val LESS: Byte = 6
        const val LESS_OR_EQUAL: Byte = 7
        const val GREATER: Byte = 8
        const val GREATER_OR_EQUAL: Byte = 9
        const val EQUAL: Byte = 10
        const val NOT_EQUAL: Byte = 11
        const val AND: Byte = 12
        const val OR: Byte = 13
        const val CONCAT: Byte = 14
        const val JOIN: Byte = 15
        const val ROUND_HALF_EVEN: Byte = 16
        const val ROUND_HALF_UP: Byte = 17
        const val ROUND_DOWN: Byte = 18
        const val ROUND_UP: Byte = 19

        // Totals over a collection's rows.
        const val SUM: Int = 0
        const val SMALLEST: Int = 1
        const val LARGEST: Int = 2
        const val COUNT: Int = 3

        /** A decimal as the engine takes it: its digits as one whole number in two halves, and its scale. */
        @JvmStatic
        fun parts(decimal: BigDecimal): Either<Refused, Triple<Long, Long, Long>> = attempt {
            val scaled = if (decimal.scale() < 0) decimal.setScale(0) else decimal
            val digits = scaled.unscaledValue()
            ensure(digits.bitLength() <= 96 && scaled.scale() <= 28) {
                Refused("${decimal.toPlainString()} has more digits than the engine holds: it keeps 28 significant digits")
            }
            Triple(digits.toLong(), digits.shiftRight(64).toLong(), scaled.scale().toLong())
        }

        @JvmStatic
        fun decimal(low: Long, high: Long, scale: Int): BigDecimal =
            BigDecimal(BigInteger.valueOf(high).shiftLeft(64).or(BigInteger(low.toULong().toString())), scale)

        // An expression is the steps a calculator with a stack would take: `qty * price + 1` is qty, price,
        // multiply, 1, add. So an operation is its operands' steps, then one more.

        @JvmStatic
        fun node(node: Int): ByteArray = Wire().put(0.toByte()).put(node).bytes()

        @JvmStatic
        fun part(name: String): ByteArray = Wire().put(1.toByte()).put(name).bytes()

        @JvmStatic
        fun constant(value: Any?): Either<Refused, ByteArray> = Wire().put(2.toByte()).value(value).map { it.bytes() }

        @JvmStatic
        fun unary(op: Byte, a: ByteArray): ByteArray = Wire().put(a).put(3.toByte()).put(op).bytes()

        @JvmStatic
        fun binary(op: Byte, a: ByteArray, b: ByteArray): ByteArray = Wire().put(a).put(b).put(4.toByte()).put(op).bytes()

        @JvmStatic
        fun choice(condition: ByteArray, then: ByteArray, otherwise: ByteArray): ByteArray =
            Wire().put(condition).put(then).put(otherwise).put(5.toByte()).bytes()

        @JvmStatic
        fun choose(index: ByteArray, options: List<ByteArray>): ByteArray =
            options.fold(Wire().put(index)) { wire, option -> wire.put(option) }.put(6.toByte()).put(options.size).bytes()

        @JvmStatic
        fun texts(items: List<ByteArray>): ByteArray =
            items.fold(Wire()) { wire, item -> wire.put(item) }.put(7.toByte()).put(items.size).bytes()
    }
}
