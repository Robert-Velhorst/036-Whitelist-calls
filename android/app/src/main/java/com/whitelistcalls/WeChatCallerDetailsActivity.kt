package com.whitelistcalls

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class WeChatCallerDetailsActivity : Activity() {
    private lateinit var settingsStore: WeChatSettingsStore
    private lateinit var labelInput: EditText
    private lateinit var digest: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsStore = WeChatSettingsStore(this)
        digest = intent.getStringExtra(EXTRA_DIGEST) ?: run { finish(); return }
        val binding = settingsStore.verifiedBindings().firstOrNull { it.idDigest == digest }
            ?: run { finish(); return }
        setContentView(buildUi(binding))
    }

    override fun onPause() {
        if (::labelInput.isInitialized) dismissWhitelistInputFocus(listOf(labelInput))
        super.onPause()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (::labelInput.isInitialized) dismissFocusedInputOnOutsideTap(event, listOf(labelInput))
        return super.dispatchTouchEvent(event)
    }

    private fun buildUi(binding: WeChatCallerBinding): View {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(52), dp(24), dp(32))
            isFocusableInTouchMode = true
        }
        content.addView(button("← Back", C_ACCENT) { finish() })
        content.addView(space(24))
        content.addView(tv("Edit WeChat caller", 28f, C_WHITE).apply { setTypeface(typeface, Typeface.BOLD) })
        content.addView(space(8))
        content.addView(tv("Verified identity", 15f, C_GREEN))
        content.addView(space(8))
        content.addView(tv("Change the local label for this caller. Their verified identity stays the same.", 15f, C_DIM))
        content.addView(space(28))
        content.addView(tv("LOCAL LABEL", 13f, C_DIM).apply { letterSpacing = 0.08f })
        content.addView(space(8))
        labelInput = EditText(this).apply {
            hint = "Local label"
            contentDescription = "Local label"
            setText(binding.localLabel)
            setTextColor(C_WHITE)
            setHintTextColor(C_DIM)
            textSize = 16f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            isSingleLine = true
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor("#1A1A2E"))
                setStroke(dp(1), Color.parseColor("#2A2A44"))
            }
            setPadding(dp(14), dp(13), dp(14), dp(13))
        }
        content.addView(labelInput, LinearLayout.LayoutParams(-1, -2))
        content.addView(space(24))
        content.addView(button("Save changes", C_ACCENT) { saveChanges() })
        content.addView(space(12))
        content.addView(button("Delete binding", C_RED) {
            AlertDialog.Builder(this)
                .setTitle("Delete binding?")
                .setMessage("This caller will be removed from your WeChat callers. Removing the last binding also turns off WeChat filtering.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete") { _, _ ->
                    settingsStore.removeBinding(digest)
                    finish()
                }
                .show()
        })
        return ScrollView(this).apply {
            setBackgroundColor(C_BG)
            addView(content)
        }
    }

    private fun saveChanges() {
        if (labelInput.text.toString().isBlank()) {
            labelInput.error = "Enter a local label"
            labelInput.requestFocus()
            return
        }
        if (!settingsStore.renameBinding(digest, labelInput.text.toString())) {
            Toast.makeText(this, "This caller binding no longer exists", Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    private fun button(text: String, color: Int, action: () -> Unit) = tv(text, 16f, color).apply {
        gravity = Gravity.CENTER_VERTICAL
        minHeight = dp(48)
        isClickable = true
        isFocusable = true
        setOnClickListener { action() }
    }
    private fun tv(text: String, size: Float, color: Int) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
    }
    private fun space(height: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(-1, dp(height)) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val EXTRA_DIGEST = "wechat_binding_digest"
        private val C_BG = Color.parseColor("#0E0E1A")
        private val C_WHITE = Color.WHITE
        private val C_DIM = Color.parseColor("#7777AA")
        private val C_ACCENT = Color.parseColor("#4D80FF")
        private val C_GREEN = Color.parseColor("#4DD16A")
        private val C_RED = Color.parseColor("#EF5350")

        fun intentFor(context: Context, binding: WeChatCallerBinding): Intent =
            Intent(context, WeChatCallerDetailsActivity::class.java).putExtra(EXTRA_DIGEST, binding.idDigest)
    }
}
