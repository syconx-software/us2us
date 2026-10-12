package com.syconx.us2us.domain

/**
 * Immutable bytes with value equality (Swift's `Data` in the models): two values are equal when
 * their contents are. The array is copied in and out, so a model holding [Bytes] can never change
 * underneath a screen that drew it.
 */
class Bytes private constructor(private val array: ByteArray) {
    val size: Int get() = array.size

    fun isEmpty(): Boolean = array.isEmpty()

    operator fun get(index: Int): Byte = array[index]

    /** A copy of the bytes. */
    fun toByteArray(): ByteArray = array.copyOf()

    /** The bytes as unsigned values 0…255. */
    fun toUnsignedList(): List<Int> = array.map { it.toInt() and 0xFF }

    /** Lower-case hex. */
    fun toHex(): String = buildString(array.size * 2) {
        for (byte in array) {
            val value = byte.toInt() and 0xFF
            append(HEX[value ushr 4])
            append(HEX[value and 0x0F])
        }
    }

    /** UTF-8 text (for payloads that are text, such as a fixture's stand-in file). */
    fun decodeToString(): String = array.decodeToString()

    override fun equals(other: Any?): Boolean = other is Bytes && array.contentEquals(other.array)

    override fun hashCode(): Int = array.contentHashCode()

    override fun toString(): String = "Bytes(${array.size})"

    companion object {
        private const val HEX = "0123456789abcdef"

        val Empty = Bytes(ByteArray(0))

        /** Copies [array]. */
        fun of(array: ByteArray): Bytes = Bytes(array.copyOf())

        /** Takes [array] without copying: only for arrays nobody else holds. */
        fun wrap(array: ByteArray): Bytes = Bytes(array)

        fun utf8(text: String): Bytes = Bytes(text.encodeToByteArray())

        /** Values 0…255. */
        fun ofUnsigned(values: List<Int>): Bytes = Bytes(ByteArray(values.size) { values[it].toByte() })
    }
}
