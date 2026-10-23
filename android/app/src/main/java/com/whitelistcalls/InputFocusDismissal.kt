package com.whitelistcalls

import android.app.Activity
import android.graphics.Rect
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import android.widget.EditText

internal fun shouldDismissInputFocus(
    inputLeft: Int,
    inputTop: Int,
    inputRight: Int,
    inputBottom: Int,
    tapX: Int,
    tapY: Int,
): Boolean = tapX !in inputLeft..inputRight || tapY !in inputTop..inputBottom

internal fun Activity.dismissFocusedInputOnOutsideTap(
    event: MotionEvent,
    inputs: Collection<EditText>,
) {
    if (event.actionMasked != MotionEvent.ACTION_DOWN) return
    val focusedInput = inputs.firstOrNull(EditText::hasFocus) ?: return
    val bounds = Rect()
    focusedInput.getGlobalVisibleRect(bounds)
    if (!shouldDismissInputFocus(
            bounds.left,
            bounds.top,
            bounds.right,
            bounds.bottom,
            event.rawX.toInt(),
            event.rawY.toInt(),
        )
    ) {
        return
    }
    hideKeyboardAndClearFocus(focusedInput)
}

internal fun Activity.dismissWhitelistInputFocus(inputs: Collection<EditText>) {
    inputs.firstOrNull(EditText::hasFocus)?.let(::hideKeyboardAndClearFocus)
}

private fun Activity.hideKeyboardAndClearFocus(input: EditText) {
    input.clearFocus()
    getSystemService(InputMethodManager::class.java)
        ?.hideSoftInputFromWindow(input.windowToken, 0)
}
