package com.whitelistcalls

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.telephony.TelephonyManager
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

class WhitelistEntryDetailsActivity : Activity() {
    private lateinit var store: WhitelistStore
    private lateinit var nameInput: EditText
    private lateinit var phoneInput: EditText
    private lateinit var originalNumber: String
    private var isFormattingPhone = false
    private var editingWhatsAppCustomEntry = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        originalNumber = intent.getStringExtra(EXTRA_NUMBER) ?: run {
            finish()
            return
        }
        editingWhatsAppCustomEntry = intent.getBooleanExtra(EXTRA_WHATSAPP_CUSTOM, false)
        store = WhitelistStore(this)
        setContentView(buildUi())
    }

    override fun onPause() {
        if (::nameInput.isInitialized && ::phoneInput.isInitialized) {
            dismissWhitelistInputFocus(listOf(nameInput, phoneInput))
        }
        super.onPause()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (::nameInput.isInitialized && ::phoneInput.isInitialized) {
            dismissFocusedInputOnOutsideTap(event, listOf(nameInput, phoneInput))
        }
        return super.dispatchTouchEvent(event)
    }

    private fun buildUi(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(C_BG) }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(52), dp(24), dp(32))
        }

        content.addView(label("← Back", 15f, C_ACCENT).apply {
            isClickable = true
            isFocusable = true
            setOnClickListener { finish() }
        })
        content.addView(space(24))
        content.addView(label(
            if (editingWhatsAppCustomEntry) "Edit WhatsApp whitelist entry" else "Edit whitelist entry",
            28f,
            C_WHITE,
        ).apply {
            setTypeface(typeface, Typeface.BOLD)
        })
        content.addView(space(6))
        content.addView(label("Change the display name or phone number.", 15f, C_DIM))
        content.addView(space(28))

        content.addView(sectionLabel("NAME"))
        content.addView(space(8))
        nameInput = EditText(this).apply {
            hint = "Contact name (optional)"
            setHintTextColor(C_HINT)
            setTextColor(C_WHITE)
            textSize = 16f
            isSingleLine = true
            setText(intent.getStringExtra(EXTRA_NAME).orEmpty())
            background = inputBackground()
            setPadding(dp(14), dp(13), dp(14), dp(13))
        }
        content.addView(nameInput, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))

        content.addView(space(20))
        content.addView(sectionLabel("PHONE NUMBER"))
        content.addView(space(8))
        phoneInput = EditText(this).apply {
            hint = "+1 234 567 8900"
            setHintTextColor(C_HINT)
            setTextColor(C_WHITE)
            textSize = 16f
            inputType = InputType.TYPE_CLASS_PHONE
            setText(PhoneNumberDisplayFormatter.format(originalNumber, defaultCountryIso()))
            setSelection(text.length)
            background = inputBackground()
            setPadding(dp(14), dp(13), dp(14), dp(13))
        }
        phoneInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(editable: Editable?) {
                if (isFormattingPhone) return
                phoneInput.error = null
                val formatted = PhoneNumberDisplayFormatter.formatValid(
                    editable?.toString().orEmpty(),
                    defaultCountryIso(),
                ) ?: return
                if (formatted == editable.toString()) return

                isFormattingPhone = true
                phoneInput.setText(formatted)
                phoneInput.setSelection(formatted.length)
                isFormattingPhone = false
            }
        })
        content.addView(phoneInput, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))

        content.addView(space(28))
        content.addView(label("Save changes", 16f, C_WHITE).apply {
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            background = rounded(C_ACCENT, 12f)
            setPadding(dp(18), dp(14), dp(18), dp(14))
            setOnClickListener { saveChanges() }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))

        scroll.addView(content)
        root.addView(scroll, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))
        return root
    }

    private fun saveChanges() {
        val formatted = PhoneNumberDisplayFormatter.formatValid(phoneInput.text.toString(), defaultCountryIso())
        if (formatted == null) {
            phoneInput.error = "Enter a valid phone number"
            phoneInput.requestFocus()
            return
        }

        val updateResult = if (editingWhatsAppCustomEntry) {
            WhatsAppSettingsStore(this).updateCustomEntry(
                originalNumber,
                formatted,
                nameInput.text.toString(),
            )
        } else {
            store.update(originalNumber, formatted, nameInput.text.toString())
        }
        when (updateResult) {
            WhitelistUpdateResult.UPDATED -> finish()
            WhitelistUpdateResult.INVALID_NUMBER -> {
                phoneInput.error = "Enter a valid phone number"
                phoneInput.requestFocus()
            }
            WhitelistUpdateResult.DUPLICATE_NUMBER -> {
                phoneInput.error = "Number already exists"
                phoneInput.requestFocus()
            }
            WhitelistUpdateResult.NOT_FOUND -> {
                Toast.makeText(this, "This whitelist entry no longer exists", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun defaultCountryIso(): String {
        val simCountry = getSystemService(TelephonyManager::class.java)
            ?.simCountryIso
            ?.uppercase()
        return simCountry?.takeIf { it.isNotBlank() } ?: Locale.getDefault().country.uppercase()
    }

    private fun sectionLabel(text: String): TextView = label(text, 13f, C_DIM).apply {
        letterSpacing = 0.08f
    }

    private fun label(text: String, size: Float, color: Int): TextView = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
    }

    private fun inputBackground(): GradientDrawable = rounded(C_SURF, 10f).apply {
        setStroke(dp(1), C_BORDER)
    }

    private fun rounded(fill: Int, radiusDp: Float): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radiusDp * resources.displayMetrics.density
        setColor(fill)
    }

    private fun space(heightDp: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(heightDp),
        )
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val EXTRA_NUMBER = "number"
        private const val EXTRA_NAME = "contact_name"
        private const val EXTRA_WHATSAPP_CUSTOM = "whatsapp_custom"

        private val C_BG = Color.parseColor("#0E0E1A")
        private val C_SURF = Color.parseColor("#1A1A2E")
        private val C_WHITE = Color.WHITE
        private val C_DIM = Color.parseColor("#7777AA")
        private val C_ACCENT = Color.parseColor("#4D80FF")
        private val C_HINT = Color.parseColor("#444466")
        private val C_BORDER = Color.parseColor("#2A2A44")

        fun intentFor(context: Context, entry: WhitelistEntry): Intent = Intent(
            context,
            WhitelistEntryDetailsActivity::class.java,
        ).putExtra(EXTRA_NUMBER, entry.number)
            .putExtra(EXTRA_NAME, entry.contactName)

        fun intentForWhatsAppCustom(context: Context, entry: WhitelistEntry): Intent = Intent(
            context,
            WhitelistEntryDetailsActivity::class.java,
        ).putExtra(EXTRA_NUMBER, entry.number)
            .putExtra(EXTRA_NAME, entry.contactName)
            .putExtra(EXTRA_WHATSAPP_CUSTOM, true)
    }
}
