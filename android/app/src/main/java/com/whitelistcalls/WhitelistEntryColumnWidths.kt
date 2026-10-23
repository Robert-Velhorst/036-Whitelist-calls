package com.whitelistcalls

internal data class WhitelistEntryColumnWidths(
    val name: Int,
    val phone: Int,
)

/** Allocates a dynamic name column while reserving at least 40% for the phone. */
internal fun whitelistEntryColumnWidths(
    availableWidth: Int,
    nameMeasuredWidth: Int,
): WhitelistEntryColumnWidths {
    val usableWidth = availableWidth.coerceAtLeast(0)
    val maximumNameWidth = (usableWidth * MAXIMUM_NAME_FRACTION).toInt()
    val nameWidth = nameMeasuredWidth.coerceAtLeast(0).coerceAtMost(maximumNameWidth)
    return WhitelistEntryColumnWidths(
        name = nameWidth,
        phone = usableWidth - nameWidth,
    )
}

private const val MAXIMUM_NAME_FRACTION = 0.60f
