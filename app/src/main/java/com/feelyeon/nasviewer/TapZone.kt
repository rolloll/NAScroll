package com.feelyeon.nasviewer

// Shared by both readers' tap-to-turn-page handling. Ridibooks lets you pick ONE axis for
// page-turn taps, not both — mixing left/right and top/bottom edges makes two of the four
// screen corners ambiguous (e.g. the top edge is also the right edge, which could mean
// either "previous" or "next"). `vertical` selects which single axis is live; the other
// fraction is ignored entirely rather than being checked as a secondary/fallback zone.
object TapZone {
    private const val EDGE = 0.3f

    enum class Action { BACKWARD, FORWARD, TOGGLE }

    fun resolve(paging: Boolean, vertical: Boolean, xFraction: Float, yFraction: Float): Action {
        if (!paging) return Action.TOGGLE
        val fraction = if (vertical) yFraction else xFraction
        return when {
            fraction < EDGE -> Action.BACKWARD
            fraction > 1f - EDGE -> Action.FORWARD
            else -> Action.TOGGLE
        }
    }
}
