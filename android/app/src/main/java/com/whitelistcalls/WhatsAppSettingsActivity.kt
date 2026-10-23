package com.whitelistcalls

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

internal data class WhatsAppFilteringToggleState(
    val checked: Boolean,
    val enabled: Boolean,
)

internal fun whatsAppFilteringToggleState(
    masterEnabled: Boolean,
    savedEnabled: Boolean,
): WhatsAppFilteringToggleState = WhatsAppFilteringToggleState(
    checked = savedEnabled,
    enabled = masterEnabled,
)

class WhatsAppSettingsActivity : Activity() {
    private lateinit var settingsStore: WhatsAppSettingsStore
    private lateinit var whitelistStore: WhitelistStore
    private var customNumberInput: EditText? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsStore = WhatsAppSettingsStore(this)
        whitelistStore = WhitelistStore(this)
    }

    override fun onResume() {
        super.onResume()
        setContentView(buildUi())
    }

    override fun onPause() {
        customNumberInput?.let { dismissWhitelistInputFocus(listOf(it)) }
        super.onPause()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        customNumberInput?.let { dismissFocusedInputOnOutsideTap(event, listOf(it)) }
        return super.dispatchTouchEvent(event)
    }

    @Deprecated("Deprecated in Android")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            REQUEST_EXPORT_DIAGNOSTICS -> {
                val destination = data?.data ?: return
                runCatching {
                    contentResolver.openOutputStream(destination)?.bufferedWriter().use { writer ->
                        requireNotNull(writer) { "The selected destination could not be opened." }
                        writer.write(WhatsAppCallNotificationListener.redactedDiagnosticsExport())
                    }
                }.onSuccess {
                    Toast.makeText(this, "Redacted diagnostics exported.", Toast.LENGTH_SHORT).show()
                }.onFailure {
                    Toast.makeText(this, "Could not export diagnostics.", Toast.LENGTH_LONG).show()
                }
            }
            REQUEST_PICK_CUSTOM_CONTACT -> addCustomContact(data)
        }
    }

    private fun buildUi(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(C_BG) }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(52), dp(24), dp(32))
        }
        val notificationAccessGranted = hasNotificationListenerAccess()
        val masterEnabled = whitelistStore.isProtectionEnabled()
        val filteringToggleState = whatsAppFilteringToggleState(
            masterEnabled = masterEnabled,
            savedEnabled = settingsStore.isFilteringEnabled(),
        )
        val status = WhatsAppSetupStatus.from(
            filteringEnabled = isWhatsAppFilteringEffective(
                masterEnabled = masterEnabled,
                whatsappEnabled = filteringToggleState.checked,
            ),
            notificationAccessGranted = notificationAccessGranted,
            directDeclineUnavailable = settingsStore.isDirectDeclineUnavailable(),
        )
        val whitelistMode = settingsStore.whitelistMode()

        content.addView(tv("← Back", 15f, C_ACCENT).apply {
            isClickable = true
            isFocusable = true
            setOnClickListener { finish() }
        })
        content.addView(space(24))
        content.addView(tv("WhatsApp", 28f, C_WHITE).apply { setTypeface(typeface, Typeface.BOLD) })
        content.addView(space(6))
        content.addView(tv("Call filtering setup", 15f, C_DIM))
        content.addView(space(24))

        content.addView(statusCard(status))
        content.addView(space(24))
        content.addView(tv("FILTERING", 13f, C_DIM).apply { letterSpacing = 0.08f })
        content.addView(space(12))
        content.addView(
            switchCard(
                title = "Filter WhatsApp calls",
                description = "Allow only callers whose phone number is on the selected whitelist.",
                checked = filteringToggleState.checked,
                enabled = filteringToggleState.enabled,
            ) { enabled ->
                settingsStore.setFilteringEnabled(enabled)
                setContentView(buildUi())
            },
        )
        content.addView(space(10))
        content.addView(
            infoCard(
                title = "Master protection required",
                description = if (masterEnabled) {
                    "Main call protection is on, so the saved WhatsApp filtering choice can take effect."
                } else {
                    "Turn on main call protection to use WhatsApp filtering. Your saved WhatsApp choice remains unchanged."
                },
            ),
        )
        content.addView(space(10))
        content.addView(
            actionCard(
                title = "Notification access",
                description = if (notificationAccessGranted) {
                    "Granted. Incoming WhatsApp call notifications can be detected."
                } else {
                    "Required to detect incoming WhatsApp call notifications."
                },
                actionLabel = if (notificationAccessGranted) "Review access" else "Grant notification access",
            ) {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            },
        )
        content.addView(space(10))
        content.addView(
            modeCard(
                title = "Use shared whitelist",
                description = "Use the same phone-number whitelist as regular calls.",
                selected = whitelistMode == WhatsAppWhitelistMode.SHARED,
            ) {
                settingsStore.setWhitelistMode(WhatsAppWhitelistMode.SHARED)
                setContentView(buildUi())
            },
        )
        content.addView(space(10))
        content.addView(
            modeCard(
                title = "Use custom WhatsApp whitelist",
                description = "Use a separate list only for WhatsApp calls.",
                selected = whitelistMode == WhatsAppWhitelistMode.CUSTOM,
            ) {
                settingsStore.setWhitelistMode(WhatsAppWhitelistMode.CUSTOM)
                setContentView(buildUi())
            },
        )

        if (whitelistMode == WhatsAppWhitelistMode.CUSTOM) {
            addCustomWhitelistControls(content)
        }

        content.addView(space(24))
        content.addView(tv("DIAGNOSTICS", 13f, C_DIM).apply { letterSpacing = 0.08f })
        content.addView(space(12))
        content.addView(
            switchCard(
                title = "Store local diagnostics",
                description = "Opt in to a bounded local log with hashed keys and redacted phone numbers.",
                checked = settingsStore.isDiagnosticsEnabled(),
            ) { enabled ->
                settingsStore.setDiagnosticsEnabled(enabled)
                setContentView(buildUi())
            },
        )
        content.addView(space(10))
        content.addView(
            actionCard(
                title = "Export diagnostics",
                description = "Save only the redacted local diagnostics as a text file.",
                actionLabel = "Export redacted diagnostics",
                onClick = ::exportDiagnostics,
            ),
        )

        content.addView(space(24))
        content.addView(
            tv(
                "Requires Android 12 or newer. Direct rejection depends on the decline action supplied by WhatsApp and Android.",
                13f,
                C_DIM,
            ),
        )

        scroll.addView(content)
        root.addView(
            scroll,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        return root
    }

    private fun hasNotificationListenerAccess(): Boolean =
        getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(
            ComponentName(this, WhatsAppCallNotificationListener::class.java),
        )

    private fun exportDiagnostics() {
        val exportIntent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, "whatsapp-call-diagnostics.txt")
        }
        @Suppress("DEPRECATION")
        startActivityForResult(exportIntent, REQUEST_EXPORT_DIAGNOSTICS)
    }

    private fun statusCard(status: WhatsAppSetupStatus): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = cardBackground()
        setPadding(dp(18), dp(18), dp(18), dp(18))

        val statusColor = if (status.isReady) C_GREEN else C_RED
        val header = LinearLayout(this@WhatsAppSettingsActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(View(this@WhatsAppSettingsActivity).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(statusColor)
            }
            layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply { rightMargin = dp(10) }
        })
        header.addView(
            tv("Setup status", 15f, C_WHITE),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        header.addView(tv(status.label, 13f, statusColor))
        addView(header)
        addView(space(10))
        addView(tv(status.detail, 13f, C_DIM))
    }

    private fun switchCard(
        title: String,
        description: String,
        checked: Boolean,
        enabled: Boolean = true,
        onCheckedChanged: (Boolean) -> Unit,
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = cardBackground()
        setPadding(dp(18), dp(16), dp(12), dp(16))

        addView(
            LinearLayout(this@WhatsAppSettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(tv(title, 15f, if (enabled) C_WHITE else C_DIM))
                addView(space(5))
                addView(tv(description, 13f, C_DIM))
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        @Suppress("DEPRECATION")
        addView(Switch(this@WhatsAppSettingsActivity).apply {
            isChecked = checked
            isEnabled = enabled
            contentDescription = title
            setOnCheckedChangeListener { _, enabled -> onCheckedChanged(enabled) }
        })
    }

    private fun modeCard(
        title: String,
        description: String,
        selected: Boolean,
        onSelect: () -> Unit,
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = cardBackground()
        setPadding(dp(18), dp(16), dp(12), dp(16))
        isClickable = true
        isFocusable = true
        setOnClickListener { onSelect() }

        addView(
            LinearLayout(this@WhatsAppSettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(tv(title, 15f, C_WHITE))
                addView(space(5))
                addView(tv(description, 13f, C_DIM))
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        addView(RadioButton(this@WhatsAppSettingsActivity).apply {
            isChecked = selected
            isClickable = false
            contentDescription = title
        })
    }

    private fun addCustomWhitelistControls(content: LinearLayout) {
        content.addView(space(24))
        content.addView(tv("CUSTOM WHATSAPP WHITELIST", 13f, C_DIM).apply { letterSpacing = 0.08f })
        content.addView(space(12))
        content.addView(
            actionCard(
                title = "Copy shared whitelist",
                description = "Replace the custom WhatsApp list with a snapshot of the shared whitelist.",
                actionLabel = "Copy shared whitelist",
            ) {
                settingsStore.replaceCustomEntries(whitelistStore.allowedEntries())
                setContentView(buildUi())
            },
        )
        content.addView(space(10))
        content.addView(
            actionCard(
                title = "Add new shared entries",
                description = "Add missing shared numbers while preserving custom-only numbers.",
                actionLabel = "Add new shared entries",
            ) {
                settingsStore.mergeSharedEntries(whitelistStore.allowedEntries())
                setContentView(buildUi())
            },
        )
        content.addView(space(10))
        content.addView(customNumberInputCard())
        content.addView(space(10))
        content.addView(
            actionCard(
                title = "Add from Contacts",
                description = "Add a contact name and phone number to the custom WhatsApp whitelist.",
                actionLabel = "Pick from Contacts",
                onClick = ::pickCustomContact,
            ),
        )

        settingsStore.customEntries().forEach { entry ->
            content.addView(space(10))
            content.addView(customNumberRow(entry))
        }
    }

    private fun customNumberInputCard(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = cardBackground()
        setPadding(dp(18), dp(16), dp(18), dp(16))
        addView(tv("Add custom number", 15f, C_WHITE))
        addView(space(10))

        val inputRow = LinearLayout(this@WhatsAppSettingsActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val numberInput = EditText(this@WhatsAppSettingsActivity).apply {
            hint = "+1 234 567 8900"
            setHintTextColor(Color.parseColor("#444466"))
            setTextColor(C_WHITE)
            textSize = 15f
            inputType = InputType.TYPE_CLASS_PHONE
            background = inputBackground()
            setPadding(dp(14), dp(12), dp(14), dp(12))
            contentDescription = "Custom WhatsApp phone number"
        }
        customNumberInput = numberInput
        var isFormatting = false
        numberInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(editable: Editable?) {
                if (isFormatting) return
                numberInput.error = null
                val formatted = PhoneNumberDisplayFormatter.formatValid(
                    editable?.toString().orEmpty(),
                    defaultCountryIso(),
                ) ?: return
                if (formatted == editable.toString()) return

                isFormatting = true
                numberInput.setText(formatted)
                numberInput.setSelection(formatted.length)
                isFormatting = false
            }
        })
        inputRow.addView(numberInput, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        inputRow.addView(space(8), LinearLayout.LayoutParams(dp(8), 1))
        inputRow.addView(tv("Add", 14f, C_WHITE).apply {
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = buttonBackground()
            setPadding(dp(18), dp(12), dp(18), dp(12))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (settingsStore.addCustomNumber(numberInput.text.toString())) {
                    setContentView(buildUi())
                } else {
                    numberInput.error = "Enter a valid number that is not already added."
                }
            }
        })
        addView(inputRow)
    }

    private fun customNumberRow(entry: WhitelistEntry): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = cardBackground()
        setPadding(dp(18), dp(14), dp(12), dp(14))
        isClickable = true
        isFocusable = true
        setOnClickListener {
            startActivity(WhitelistEntryDetailsActivity.intentForWhatsAppCustom(this@WhatsAppSettingsActivity, entry))
        }

        val formattedNumber = PhoneNumberDisplayFormatter.format(entry.number, defaultCountryIso())
        var nameViewForSizing: TextView? = null
        var phoneViewForSizing: TextView? = null
        var nameParamsForSizing: LinearLayout.LayoutParams? = null
        var phoneParamsForSizing: LinearLayout.LayoutParams? = null
        if (entry.contactName != null) {
            val nameView = marqueeText(entry.contactName, 15f, C_WHITE)
            val phoneView = marqueeText(formattedNumber, 14f, C_DIM)
            val nameParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.60f).apply {
                marginEnd = dp(10)
            }
            val phoneParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.40f).apply {
                marginEnd = dp(8)
            }
            addView(nameView, nameParams)
            addView(phoneView, phoneParams)
            nameViewForSizing = nameView
            phoneViewForSizing = phoneView
            nameParamsForSizing = nameParams
            phoneParamsForSizing = phoneParams
        } else {
            addView(
                tv(formattedNumber, 15f, C_WHITE),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
        }
        addView(tv("Remove", 13f, C_RED).apply {
            isClickable = true
            isFocusable = true
            contentDescription = "Remove $formattedNumber"
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener {
                settingsStore.removeCustomNumber(entry.number)
                setContentView(buildUi())
            }
        })

        val removeView = getChildAt(childCount - 1)
        val nameView = nameViewForSizing
        val phoneView = phoneViewForSizing
        val nameParams = nameParamsForSizing
        val phoneParams = phoneParamsForSizing
        if (nameView != null && phoneView != null && nameParams != null && phoneParams != null) {
            post {
                val textAreaWidth = width - paddingLeft - paddingRight - removeView.width -
                    nameParams.marginEnd - phoneParams.marginEnd
                val widths = whitelistEntryColumnWidths(
                    availableWidth = textAreaWidth,
                    nameMeasuredWidth = nameView.paint.measureText(entry.contactName).toInt(),
                )
                nameView.layoutParams = nameParams.apply {
                    width = widths.name
                    weight = 0f
                }
                phoneView.layoutParams = phoneParams.apply {
                    width = widths.phone
                    weight = 0f
                }
            }
        }
    }

    private fun pickCustomContact() {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Please grant contacts permission on the main screen first", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
        if (intent.resolveActivity(packageManager) == null) {
            Toast.makeText(this, "No contacts app available on this device", Toast.LENGTH_LONG).show()
            return
        }
        startActivityForResult(intent, REQUEST_PICK_CUSTOM_CONTACT)
    }

    private fun addCustomContact(data: Intent?) {
        val uri = data?.data ?: return
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return
            val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            if (numberIndex < 0) return
            val number = cursor.getString(numberIndex)
            val nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val contactName = if (nameIndex >= 0) cursor.getString(nameIndex) else null
            val formatted = PhoneNumberDisplayFormatter.formatValid(number, defaultCountryIso())
            if (formatted == null) {
                Toast.makeText(this, "Contact has an invalid phone number", Toast.LENGTH_SHORT).show()
            } else if (settingsStore.addCustomNumber(formatted, contactName)) {
                setContentView(buildUi())
            } else {
                Toast.makeText(this, "Number already exists", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun actionCard(
        title: String,
        description: String,
        actionLabel: String,
        onClick: () -> Unit,
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = cardBackground()
        setPadding(dp(18), dp(16), dp(18), dp(16))
        addView(tv(title, 15f, C_WHITE))
        addView(space(5))
        addView(tv(description, 13f, C_DIM))
        addView(space(12))
        addView(tv(actionLabel, 13f, C_ACCENT).apply {
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        })
    }

    private fun infoCard(title: String, description: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = cardBackground()
        setPadding(dp(18), dp(16), dp(18), dp(16))
        addView(tv(title, 15f, C_WHITE))
        addView(space(5))
        addView(tv(description, 13f, C_DIM))
    }

    private fun cardBackground() = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(14).toFloat()
        setColor(C_SURFACE)
        setStroke(dp(1), Color.parseColor("#2A2A44"))
    }

    private fun inputBackground() = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(10).toFloat()
        setColor(C_BG)
        setStroke(dp(1), Color.parseColor("#2A2A44"))
    }

    private fun buttonBackground() = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(10).toFloat()
        setColor(C_ACCENT)
    }

    private fun defaultCountryIso(): String = Locale.getDefault().country.uppercase()

    private fun tv(text: String, sizeSp: Float, color: Int) = TextView(this).apply {
        this.text = text
        textSize = sizeSp
        setTextColor(color)
    }

    private fun marqueeText(text: String, sizeSp: Float, color: Int): TextView = tv(text, sizeSp, color).apply {
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.MARQUEE
        marqueeRepeatLimit = -1
        isSelected = true
    }

    private fun space(heightDp: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(heightDp))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val REQUEST_EXPORT_DIAGNOSTICS = 201
        const val REQUEST_PICK_CUSTOM_CONTACT = 202
        val C_BG = Color.parseColor("#0E0E1A")
        val C_SURFACE = Color.parseColor("#1A1A2E")
        val C_WHITE = Color.WHITE
        val C_DIM = Color.parseColor("#7777AA")
        val C_ACCENT = Color.parseColor("#4D80FF")
        val C_GREEN = Color.parseColor("#4DD16A")
        val C_RED = Color.parseColor("#EF5350")
    }
}
