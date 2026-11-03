package com.poolaim.android.vision

/** Canonical original-trainer IDs: cue=0, solids=1..7, eight=8, stripes=9..15. */
enum class BallKind { CUE, SOLID, EIGHT, STRIPE, UNKNOWN }

object BallIdentity {
    const val CUE = 0
    const val EIGHT = 8
    const val FIRST_SOLID = 1
    const val LAST_SOLID = 7
    const val FIRST_STRIPE = 9
    const val LAST_STRIPE = 15

    private val colors = mapOf(
        1 to intArrayOf(242, 193, 46),
        2 to intArrayOf(29, 79, 192),
        3 to intArrayOf(210, 56, 42),
        4 to intArrayOf(90, 42, 136),
        5 to intArrayOf(239, 122, 26),
        6 to intArrayOf(23, 137, 75),
        7 to intArrayOf(123, 30, 36)
    )

    fun isValid(id: Int): Boolean = id in CUE..LAST_STRIPE

    fun kindOf(id: Int?): BallKind {
        if (id == null) return BallKind.UNKNOWN
        return when {
            id == CUE -> BallKind.CUE
            id in FIRST_SOLID..LAST_SOLID -> BallKind.SOLID
            id == EIGHT -> BallKind.EIGHT
            id in FIRST_STRIPE..LAST_STRIPE -> BallKind.STRIPE
            else -> BallKind.UNKNOWN
        }
    }

    fun baseColorId(id: Int): Int = when {
        id in FIRST_STRIPE..LAST_STRIPE -> id - 8
        id in FIRST_SOLID..LAST_SOLID -> id
        else -> 0
    }

    fun rgbForBaseColor(id: Int): IntArray? = colors[id]?.copyOf()

    fun label(id: Int?): String {
        if (id == null) return "Unknown"
        return when {
            id == CUE -> "Cue"
            id == EIGHT -> "8"
            id in FIRST_SOLID..LAST_SOLID -> "Solid $id"
            id in FIRST_STRIPE..LAST_STRIPE -> "Stripe $id"
            else -> "Unknown"
        }
    }
}
