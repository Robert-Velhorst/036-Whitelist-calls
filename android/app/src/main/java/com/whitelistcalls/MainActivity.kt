package com.whitelistcalls

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var store: WhitelistStore
    private lateinit var circleOuter: View
    private lateinit var circleInner: FrameLayout
    private lateinit var statusLabel: TextView
    private lateinit var roleStatusDot: View
    private lateinit var roleStatusTv: TextView
    private lateinit var contactsStatusDot: View
    private lateinit var contactsStatusTv: TextView
    private lateinit var roleBtnTv: TextView
    private lateinit var contactsBtnTv: TextView
    private lateinit var whitelistContainer: LinearLayout
    private lateinit var numberInput: EditText
    private lateinit var simInfoContainer: LinearLayout
    private lateinit var whatsAppSettingsStore: WhatsAppSettingsStore
    private lateinit var whatsAppStatusDot: View
    private lateinit var weChatStatusDot: View
    private var isFormattingNumberInput = false

    companion object {
        private const val REQ_ROLE = 101
        private const val REQ_CONTACTS = 102
        private const val REQ_PHONE_NUMBERS = 103
        private const val REQ_PICK_CONTACT = 104

        private val C_BG      = Color.parseColor("#0E0E1A")
        private val C_SURF    = Color.parseColor("#1A1A2E")
        private val C_ON      = Color.parseColor("#4DD16A")
        private val C_OFF     = Color.parseColor("#383855")
        private val C_WHITE   = Color.WHITE
        private val C_DIM     = Color.parseColor("#7777AA")
        private val C_DIVIDER = Color.parseColor("#252540")
        private val C_ACCENT  = Color.parseColor("#4D80FF")
        private val C_RED     = Color.parseColor("#EF5350")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = WhitelistStore(this)
        whatsAppSettingsStore = WhatsAppSettingsStore(this)
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        // Request phone number permissions for SIM info display
        val permissionsNeeded = mutableListOf<String>()

        if (checkSelfPermission(Manifest.permission.READ_PHONE_NUMBERS) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.READ_PHONE_NUMBERS)
        }
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.READ_PHONE_STATE)
        }
        if (checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.READ_SMS)
        }

        if (permissionsNeeded.isNotEmpty()) {
            requestPermissions(permissionsNeeded.toTypedArray(), REQ_PHONE_NUMBERS)
        }

        refresh()
    }

    override fun onPause() {
        if (::numberInput.isInitialized) dismissWhitelistInputFocus(listOf(numberInput))
        super.onPause()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (::numberInput.isInitialized) {
            dismissFocusedInputOnOutsideTap(event, listOf(numberInput))
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    private fun buildUi(): View {
        // Root FrameLayout: circle anchored to screen center, bottom panel anchored to bottom
        val root = FrameLayout(this).apply {
            setBackgroundColor(C_BG)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        // SIM info: top-left overlay, wider coverage
        val simWrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(48), dp(24), 0)  // Add right padding for wider coverage
        }
        simInfoContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        simWrapper.addView(simInfoContainer)

        root.addView(simWrapper, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,  // MATCH_PARENT for full screen width coverage
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START
        ))

        // Circle + status: absolute screen center
        val circleContainerSize = dp(200)
        val innerSize = dp(156)
        val circleContainer = FrameLayout(this)

        circleOuter = View(this)
        circleContainer.addView(circleOuter, FrameLayout.LayoutParams(circleContainerSize, circleContainerSize))

        circleInner = FrameLayout(this).apply {
            isClickable = true
            isFocusable = true
            setOnClickListener { onToggle() }
        }
        val powerIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_power)
            imageTintList = android.content.res.ColorStateList.valueOf(C_WHITE)
        }
        circleInner.addView(
            powerIcon,
            FrameLayout.LayoutParams(dp(52), dp(52)).apply { gravity = Gravity.CENTER }
        )
        circleContainer.addView(
            circleInner,
            FrameLayout.LayoutParams(innerSize, innerSize).apply { gravity = Gravity.CENTER }
        )

        val centerCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        centerCol.addView(circleContainer, LinearLayout.LayoutParams(circleContainerSize, circleContainerSize))
        centerCol.addView(vspace(16))
        statusLabel = tv("", 22f, C_WHITE).apply {
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
        }
        centerCol.addView(statusLabel)

        root.addView(centerCol, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL
        ).apply {
            topMargin = dp(120)  // Fixed at upper 1/4 of screen
        })

        // Bottom panel: scrollable, anchored to screen bottom
        val bottomScroll = ScrollView(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(16), dp(24), dp(40))
        }

        // Permission status card
        val permCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundRect(C_SURF, 16f)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }

        val roleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        roleStatusDot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(C_RED)
            }
            layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply {
                rightMargin = dp(10)
            }
        }
        roleRow.addView(roleStatusDot)
        roleStatusTv = tv("Call Screening Role", 13f, C_WHITE).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        roleBtnTv = tv("Grant →", 13f, C_ACCENT).apply {
            isClickable = true
            isFocusable = true
            setOnClickListener { requestScreeningRole() }
        }
        roleRow.addView(roleStatusTv)
        roleRow.addView(roleBtnTv)
        permCard.addView(roleRow)

        permCard.addView(View(this).apply {
            setBackgroundColor(C_DIVIDER)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
                setMargins(0, dp(10), 0, dp(10))
            }
        })

        val contactsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        contactsStatusDot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(C_RED)
            }
            layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply {
                rightMargin = dp(10)
            }
        }
        contactsRow.addView(contactsStatusDot)
        contactsStatusTv = tv("Contacts Permission (for picker)", 13f, C_WHITE).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        contactsBtnTv = tv("Grant →", 13f, C_ACCENT).apply {
            isClickable = true
            isFocusable = true
            setOnClickListener { requestContactsPermission() }
        }
        contactsRow.addView(contactsStatusTv)
        contactsRow.addView(contactsBtnTv)
        permCard.addView(contactsRow)

        col.addView(permCard, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        col.addView(vspace(12))

        val whatsAppChip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundRect(C_SURF, 24f)
            setPadding(dp(18), dp(13), dp(18), dp(13))
            isClickable = true
            isFocusable = true
            setOnClickListener { startActivity(Intent(this@MainActivity, WhatsAppSettingsActivity::class.java)) }
        }
        whatsAppStatusDot = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply { rightMargin = dp(10) }
        }
        whatsAppChip.addView(whatsAppStatusDot)
        whatsAppChip.addView(tv("WhatsApp", 15f, C_WHITE).apply {
            setTypeface(typeface, Typeface.BOLD)
        })
        val socialProviderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
        }
        socialProviderRow.addView(
            whatsAppChip,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT),
        )
        val weChatChip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundRect(C_SURF, 24f)
            setPadding(dp(18), dp(13), dp(18), dp(13))
            isClickable = true
            isFocusable = true
            setOnClickListener { startActivity(Intent(this@MainActivity, WeChatSettingsActivity::class.java)) }
        }
        weChatStatusDot = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply { rightMargin = dp(10) }
        }
        weChatChip.addView(weChatStatusDot)
        weChatChip.addView(tv("WeChat", 15f, C_WHITE).apply { setTypeface(typeface, Typeface.BOLD) })
        socialProviderRow.addView(
            weChatChip,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { leftMargin = dp(10) },
        )
        col.addView(
            socialProviderRow,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
        )

        col.addView(vspace(40))

        col.addView(View(this).apply {
            setBackgroundColor(C_DIVIDER)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
        })

        col.addView(vspace(24))

        col.addView(tv("WHITELIST NUMBERS", 13f, C_DIM).apply { letterSpacing = 0.08f })

        col.addView(vspace(12))

        // Input + Add button
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        numberInput = EditText(this).apply {
            hint = "+1 234 567 8900"
            setHintTextColor(Color.parseColor("#444466"))
            setTextColor(C_WHITE)
            textSize = 15f
            inputType = android.text.InputType.TYPE_CLASS_PHONE
            background = roundRect(C_SURF, 10f)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        numberInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(editable: Editable?) {
                if (isFormattingNumberInput) return
                numberInput.error = null

                val formatted = PhoneNumberDisplayFormatter.formatValid(
                    editable?.toString().orEmpty(),
                    defaultCountryIso(),
                ) ?: return
                if (formatted == editable.toString()) return

                isFormattingNumberInput = true
                numberInput.setText(formatted)
                numberInput.setSelection(formatted.length)
                isFormattingNumberInput = false
            }
        })
        inputRow.addView(numberInput, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        inputRow.addView(View(this), LinearLayout.LayoutParams(dp(8), 1))
        val addBtn = tv("Add", 14f, C_WHITE).apply {
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = roundRect(C_ACCENT, 10f)
            setPadding(dp(18), dp(12), dp(18), dp(12))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                addNumberFromInput()
            }
        }
        inputRow.addView(addBtn)
        col.addView(inputRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        col.addView(vspace(12))

        // Pick from contacts button
        val pickContactBtn = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(C_SURF)
                setStroke(dp(1), Color.parseColor("#2A2A44"))
            }
            setPadding(dp(16), dp(14), dp(16), dp(14))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this@MainActivity, "Please grant contacts permission first", Toast.LENGTH_SHORT).show()
                    requestContactsPermission()
                } else {
                    pickContact()
                }
            }
        }
        val contactIcon = tv("📱", 18f, C_WHITE)
        pickContactBtn.addView(contactIcon)
        pickContactBtn.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(8), 1)
        })
        pickContactBtn.addView(tv("Pick from Contacts", 14f, C_ACCENT).apply {
            setTypeface(typeface, Typeface.NORMAL)
        })
        col.addView(pickContactBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        col.addView(vspace(20))

        // Whitelist container with nested ScrollView for independent scrolling
        val whitelistScrollWrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val whitelistScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(280)  // Max height, scrollable when exceeded
            )
        }
        whitelistContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        whitelistScroll.addView(whitelistContainer)
        whitelistScrollWrapper.addView(whitelistScroll)

        col.addView(whitelistScrollWrapper)

        col.addView(vspace(12))

        col.addView(tv("Hidden/unknown numbers are not managed by this service. Please block them in system settings.", 11f, Color.parseColor("#404060")).apply {
            gravity = Gravity.CENTER
        })

        bottomScroll.addView(col)
        root.addView(bottomScroll, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL
        ).apply {
            topMargin = dp(380)  // Start below button to avoid overlap
        })

        return root
    }

    private fun onToggle() {
        val enabled = !store.isProtectionEnabled()

        // Show permission prompt on first enable
        if (enabled && !store.hasShownFirstToggleDialog()) {
            AlertDialog.Builder(this)
                .setTitle("Permission Required")
                .setMessage("Whitelist protection requires:\n\n1. Call Screening Role - Block non-whitelist calls\n2. Contacts Permission - Pick numbers from contacts\n\nPlease grant permissions in the card below.")
                .setPositiveButton("OK") { _, _ ->
                    store.setFirstToggleDialogShown()
                    store.setProtectionEnabled(enabled)
                    updateCircle(enabled)
                    updateWhatsAppStatus()
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }

        store.setProtectionEnabled(enabled)
        if (!enabled) AllowedCallVibrationController.cancelActiveAlert()
        updateCircle(enabled)
        updateWhatsAppStatus()
    }

    private fun refresh() {
        updateCircle(store.isProtectionEnabled())
        updatePermStatus()
        updateWhatsAppStatus()
        renderWhitelist()
        updateWeChatStatus()
        renderSimInfo()
    }

    private fun updateWeChatStatus() {
        val access = getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(
            ComponentName(this, WhatsAppCallNotificationListener::class.java),
        )
        val status = WeChatSettingsStore(this).status(access)
        weChatStatusDot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (status.isReady) C_ON else C_RED)
        }
        weChatStatusDot.contentDescription = "WeChat: ${status.label}"
    }

    private fun updateWhatsAppStatus() {
        val filteringEffective = isWhatsAppFilteringEffective(
            masterEnabled = store.isProtectionEnabled(),
            whatsappEnabled = whatsAppSettingsStore.isFilteringEnabled(),
        )
        val notificationAccessGranted = getSystemService(NotificationManager::class.java)
            .isNotificationListenerAccessGranted(
                ComponentName(this, WhatsAppCallNotificationListener::class.java),
            )
        val color = if (filteringEffective && notificationAccessGranted) C_ON else C_RED
        whatsAppStatusDot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
    }

    private fun updateCircle(enabled: Boolean) {
        val color = if (enabled) C_ON else C_OFF
        circleOuter.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.TRANSPARENT)
            setStroke(dp(3), color)
        }
        circleInner.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
        statusLabel.text = if (enabled) "Protected" else "Paused"
        statusLabel.setTextColor(if (enabled) C_ON else C_DIM)
    }

    private fun updatePermStatus() {
        val roleManager = getSystemService(RoleManager::class.java)
        val roleOk = roleManager.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
        val contactsOk = checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

        // Update call screening role status
        roleStatusDot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (roleOk) C_ON else C_RED)
        }
        roleStatusTv.text = "Call Screening Role"
        roleStatusTv.setTextColor(C_WHITE)
        roleBtnTv.visibility = if (roleOk) View.GONE else View.VISIBLE

        // Update contacts permission status
        contactsStatusDot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (contactsOk) C_ON else C_RED)
        }
        contactsStatusTv.text = "Contacts Permission (for picker)"
        contactsStatusTv.setTextColor(C_WHITE)
        contactsBtnTv.visibility = if (contactsOk) View.GONE else View.VISIBLE
    }

    private fun pickContact() {
        // Try multiple intent methods for better device compatibility (Samsung, etc.)
        val intents = listOf(
            // Standard Android picker
            Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI),
            // Alternative for some devices
            Intent(Intent.ACTION_PICK).apply {
                type = ContactsContract.CommonDataKinds.Phone.CONTENT_TYPE
            },
            // Samsung and other custom ROMs
            Intent(Intent.ACTION_GET_CONTENT).apply {
                type = ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE
            }
        )

        // Try each intent until one works
        for (intent in intents) {
            if (intent.resolveActivity(packageManager) != null) {
                try {
                    startActivityForResult(intent, REQ_PICK_CONTACT)
                    return
                } catch (e: Exception) {
                    // Try next intent
                    continue
                }
            }
        }

        // None worked
        Toast.makeText(this, "No contacts app available on this device", Toast.LENGTH_LONG).show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK_CONTACT && resultCode == RESULT_OK && data != null) {
            val uri = data.data ?: return
            val cursor = contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val numberIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    if (numberIndex >= 0) {
                        val number = it.getString(numberIndex)
                        val nameIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                        val contactName = if (nameIndex >= 0) it.getString(nameIndex) else null
                        val formatted = PhoneNumberDisplayFormatter.formatValid(number, defaultCountryIso())
                        if (formatted == null) {
                            Toast.makeText(this, "Contact has an invalid phone number", Toast.LENGTH_SHORT).show()
                        } else if (store.add(formatted, contactName)) {
                            renderWhitelist()
                            Toast.makeText(this, "Added: $formatted", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this, "Number already exists", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

    private fun addNumberFromInput() {
        val formatted = PhoneNumberDisplayFormatter.formatValid(
            numberInput.text.toString(),
            defaultCountryIso(),
        )
        if (formatted == null) {
            numberInput.error = "Enter a valid phone number"
            numberInput.requestFocus()
            return
        }

        if (store.add(formatted)) {
            numberInput.text.clear()
            renderWhitelist()
        } else {
            numberInput.error = "Number already exists"
        }
    }

    private fun requestScreeningRole() {
        val roleManager = getSystemService(RoleManager::class.java)
        if (!roleManager.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)) {
            Toast.makeText(this, "Call screening not supported on this device", Toast.LENGTH_LONG).show()
            return
        }
        if (!roleManager.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)) {
            startActivityForResult(
                roleManager.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING),
                REQ_ROLE,
            )
        }
    }

    private fun requestContactsPermission() {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), REQ_CONTACTS)
        }
    }

    private fun renderWhitelist() {
        whitelistContainer.removeAllViews()
        val entries = store.allowedEntries()
        if (entries.isEmpty()) {
            val emptyView = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(0, dp(32), 0, dp(32))
            }
            emptyView.addView(tv("📝", 32f, C_DIM).apply { gravity = Gravity.CENTER })
            emptyView.addView(vspace(8))
            emptyView.addView(tv("No numbers added yet", 14f, C_DIM).apply { gravity = Gravity.CENTER })
            whitelistContainer.addView(emptyView)
            return
        }
        entries.forEachIndexed { index, entry ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(14).toFloat()
                    setColor(C_SURF)
                    setStroke(dp(1), Color.parseColor("#2A2A44"))
                }
                setPadding(dp(18), dp(16), dp(14), dp(16))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    startActivity(WhitelistEntryDetailsActivity.intentFor(this@MainActivity, entry))
                }
            }

            // Index badge
            val indexBadge = TextView(this).apply {
                text = "${index + 1}"
                textSize = 11f
                setTextColor(C_DIM)
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#252540"))
                }
                val sz = dp(24)
                layoutParams = LinearLayout.LayoutParams(sz, sz)
            }
            row.addView(indexBadge)

            row.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(12), 1)
            })

            val phoneText = formatPhoneWithCountry(entry.number, defaultCountryIso())
            var nameViewForSizing: TextView? = null
            var phoneViewForSizing: TextView? = null
            var nameParamsForSizing: LinearLayout.LayoutParams? = null
            var phoneParamsForSizing: LinearLayout.LayoutParams? = null
            if (entry.contactName != null) {
                val nameView = marqueeText(entry.contactName, 15f, C_WHITE).apply {
                    setTypeface(typeface, Typeface.NORMAL)
                }
                val phoneView = marqueeText(phoneText, 14f, C_DIM)
                val nameParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    0.60f,
                ).apply { marginEnd = dp(10) }
                val phoneParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    0.40f,
                ).apply { marginEnd = dp(8) }
                row.addView(nameView, nameParams)
                row.addView(phoneView, phoneParams)
                nameViewForSizing = nameView
                phoneViewForSizing = phoneView
                nameParamsForSizing = nameParams
                phoneParamsForSizing = phoneParams
            } else {
                row.addView(
                    tv(phoneText, 15f, C_WHITE).apply {
                        setTypeface(typeface, Typeface.NORMAL)
                    },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
                )
            }

            // 移除按钮
            val removeBtn = TextView(this).apply {
                text = "×"
                textSize = 24f
                setTextColor(C_RED)
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#3A1A1A"))
                }
                val sz = dp(32)
                layoutParams = LinearLayout.LayoutParams(sz, sz)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    store.remove(entry.number)
                    renderWhitelist()
                }
            }
            row.addView(removeBtn)

            val nameView = nameViewForSizing
            val phoneView = phoneViewForSizing
            val nameParams = nameParamsForSizing
            val phoneParams = phoneParamsForSizing
            if (nameView != null && phoneView != null && nameParams != null && phoneParams != null) {
                row.post {
                    val textAreaWidth = row.width - row.paddingLeft - row.paddingRight -
                        indexBadge.width - dp(12) - removeBtn.width - nameParams.marginEnd - phoneParams.marginEnd
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

            whitelistContainer.addView(
                row,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(10)
                }
            )
        }
    }

    private fun marqueeText(text: String, size: Float, color: Int): TextView = tv(text, size, color).apply {
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.MARQUEE
        marqueeRepeatLimit = -1
        isSelected = true
    }

    private fun renderSimInfo() {
        simInfoContainer.removeAllViews()

        val hasPhoneNumberPerm = checkSelfPermission(Manifest.permission.READ_PHONE_NUMBERS) == PackageManager.PERMISSION_GRANTED
        val hasPhoneStatePerm = checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        val hasSMSPerm = checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

        // Try SubscriptionManager first (best method)
        val sm = if (hasPhoneNumberPerm || hasPhoneStatePerm) getSystemService(SubscriptionManager::class.java) else null
        val allSubs = sm?.activeSubscriptionInfoList ?: emptyList()

        if (allSubs.isNotEmpty()) {
            allSubs.forEach { sub ->
                val slot = sub.simSlotIndex + 1
                val subId = sub.subscriptionId
                val number = sub.number
                val countryIso = sub.countryIso?.uppercase() ?: ""

                // Try to get number from TelephonyManager for this specific SIM if SubscriptionInfo has no number
                val displayNumber = if (!number.isNullOrBlank()) {
                    formatPhoneWithCountry(number, countryIso)
                } else {
                    // Try alternative method for this specific subscription
                    if (hasPhoneStatePerm) {
                        try {
                            val tm = getSystemService(TelephonyManager::class.java)
                            val tmForSub = tm?.createForSubscriptionId(subId)
                            val line1 = tmForSub?.line1Number
                            if (!line1.isNullOrBlank()) {
                                formatPhoneWithCountry(line1, countryIso)
                            } else {
                                "Unknown Number"
                            }
                        } catch (e: Exception) {
                            "Unknown Number"
                        }
                    } else {
                        "Unknown Number"
                    }
                }

                addSimInfoRow(slot.toString(), displayNumber)
            }
            return
        }

        // Fallback: Try TelephonyManager (Samsung devices may need this)
        if (hasPhoneStatePerm || hasSMSPerm) {
            try {
                val tm = getSystemService(TelephonyManager::class.java)
                val line1Number = tm?.line1Number
                if (!line1Number.isNullOrBlank()) {
                    val countryIso = tm?.simCountryIso?.uppercase() ?: ""
                    val displayNumber = formatPhoneWithCountry(line1Number, countryIso)
                    addSimInfoRow("SIM", displayNumber)
                    return
                }
            } catch (e: Exception) {
                // Ignore
            }
        }

        // No number found - show unknown
        addSimInfoRow("SIM", "Unknown Number")
    }

    private fun addSimInfoRow(slotLabel: String, displayNumber: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val badge = TextView(this).apply {
            text = slotLabel
            textSize = if (slotLabel.length == 1) 9f else 8f
            setTextColor(C_BG)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(C_DIM)
            }
            val sz = if (slotLabel.length == 1) dp(18) else dp(24)
            layoutParams = LinearLayout.LayoutParams(sz, sz).apply { rightMargin = dp(6) }
        }
        row.addView(badge)
        row.addView(tv(displayNumber, 16f, C_DIM))
        simInfoContainer.addView(row, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(4) })
    }

    private fun formatPhoneWithCountry(number: String, countryIso: String): String {
        return PhoneNumberDisplayFormatter.format(number, countryIso)
    }

    private fun defaultCountryIso(): String {
        val simCountry = getSystemService(TelephonyManager::class.java)
            ?.simCountryIso
            ?.uppercase()
        return simCountry?.takeIf { it.isNotBlank() } ?: Locale.getDefault().country.uppercase()
    }

    private fun tv(text: String, sizeSp: Float, color: Int) = TextView(this).apply {
        this.text = text
        textSize = sizeSp
        setTextColor(color)
    }

    private fun vspace(heightDp: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(heightDp))
    }

    private fun roundRect(fill: Int, radiusDp: Float) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radiusDp * resources.displayMetrics.density
        setColor(fill)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
