package com.whitelistcalls

import android.app.Activity
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

internal data class WeChatFilteringToggleState(val checked: Boolean, val enabled: Boolean)

internal fun weChatFilteringToggleState(
    masterEnabled: Boolean,
    savedEnabled: Boolean,
    hasVerifiedBinding: Boolean,
) = WeChatFilteringToggleState(savedEnabled, masterEnabled && hasVerifiedBinding)

class WeChatSettingsActivity : Activity() {
    private lateinit var settingsStore: WeChatSettingsStore
    private var labelInput: EditText? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsStore = WeChatSettingsStore(this)
    }

    override fun onResume() {
        super.onResume()
        setContentView(buildUi())
    }

    override fun onPause() {
        labelInput?.let { dismissWhitelistInputFocus(listOf(it)) }
        super.onPause()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        labelInput?.let { dismissFocusedInputOnOutsideTap(event, listOf(it)) }
        return super.dispatchTouchEvent(event)
    }

    private fun buildUi(): View {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(52), dp(24), dp(32))
            isFocusableInTouchMode = true
        }
        val access = getSystemService(NotificationManager::class.java)
            .isNotificationListenerAccessGranted(ComponentName(this, WhatsAppCallNotificationListener::class.java))
        val status = settingsStore.status(access)
        val bindings = settingsStore.verifiedBindings()
        val masterEnabled = WhitelistStore(this).isProtectionEnabled()
        val toggleState = weChatFilteringToggleState(masterEnabled, settingsStore.isFilteringEnabled(), bindings.isNotEmpty())
        val candidate = settingsStore.recentCandidate()

        content.addView(action("← Back") { finish() })
        content.addView(space(24))
        content.addView(tv("WeChat", 28f, C_WHITE).apply { setTypeface(typeface, Typeface.BOLD) })
        content.addView(space(6))
        content.addView(tv("Call filtering setup", 15f, C_DIM))
        content.addView(space(24))
        content.addView(card().apply {
            val color = if (status.isReady) C_GREEN else C_RED
            addView(LinearLayout(this@WeChatSettingsActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(View(this@WeChatSettingsActivity).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(color)
                    }
                }, LinearLayout.LayoutParams(dp(10), dp(10)).apply { rightMargin = dp(10) })
                addView(tv("Setup status", 15f, C_WHITE), LinearLayout.LayoutParams(0, -2, 1f))
                addView(tv(status.label, 13f, color))
            })
            addView(space(10))
            addView(tv(status.detail, 13f, C_DIM))
        })
        content.addView(space(24))
        content.addView(section("FILTERING"))
        content.addView(space(12))
        content.addView(card().apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(LinearLayout(this@WeChatSettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(tv("Filter WeChat calls", 15f, if (toggleState.enabled) C_WHITE else C_DIM))
                addView(space(5))
                addView(tv("Allow only callers with a verified identity on this list.", 13f, C_DIM))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            @Suppress("DEPRECATION")
            addView(Switch(this@WeChatSettingsActivity).apply {
                contentDescription = "Filter WeChat calls"
                isChecked = toggleState.checked
                isEnabled = toggleState.enabled
                setOnCheckedChangeListener { _, enabled ->
                    settingsStore.setFilteringEnabled(enabled)
                    setContentView(buildUi())
                }
            })
        })
        content.addView(space(10))
        content.addView(card().apply {
            addView(tv("Notification access", 15f, C_WHITE))
            addView(space(5))
            addView(tv(if (access) "Granted. Incoming WeChat calls can be detected." else
                "Required to detect incoming WeChat call notifications.", 13f, C_DIM))
            addView(space(12))
            addView(action(if (access) "Review access" else "Grant notification access") {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            })
        })
        content.addView(space(10))
        content.addView(tv(if (masterEnabled) "Main call protection is on." else
            "Turn on main call protection to use WeChat filtering. Your saved choice remains unchanged.", 13f, C_DIM))

        content.addView(space(24))
        content.addView(section("WECHAT CALLERS"))
        content.addView(space(12))
        content.addView(card().apply {
            addView(tv("Bind recent caller", 15f, C_WHITE))
            addView(space(5))
            addView(tv("Have the same caller call twice, ending each call first. Then enter a local label and bind their verified identity. Names alone cannot be verified.", 13f, C_DIM))
            addView(space(12))
            labelInput = EditText(this@WeChatSettingsActivity).apply {
                hint = "Local label"
                contentDescription = "Local label"
                setHintTextColor(C_DIM)
                setTextColor(C_WHITE)
                textSize = 16f
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
                isSingleLine = true
                background = rounded(C_BG)
                setPadding(dp(14), dp(13), dp(14), dp(13))
            }
            addView(labelInput, LinearLayout.LayoutParams(-1, -2))
            addView(space(12))
            addView(tv(candidate?.let {
                "Recent identity: ${it.fingerprint} · ${it.observationCount} matching calls"
            } ?: "No supported recent identity. Receive two separate calls from the same caller first.", 13f, C_DIM))
            addView(action("Bind recent caller") {
                candidate?.takeIf { it.canBind }?.let { bindRecentCaller(it.digest) }
            }.apply {
                isEnabled = candidate?.canBind == true
                alpha = if (isEnabled) 1f else 0.4f
            })
        })
        if (bindings.isEmpty()) {
            content.addView(space(12))
            content.addView(tv("No verified callers yet. Filtering is unavailable until you bind one.", 13f, C_DIM))
        }
        bindings.forEach { binding ->
            content.addView(space(10))
            content.addView(card().apply {
                isClickable = true
                isFocusable = true
                addView(tv(binding.localLabel, 16f, C_WHITE))
                addView(space(5))
                addView(tv("Verified identity", 13f, C_GREEN))
                setOnClickListener {
                    startActivity(WeChatCallerDetailsActivity.intentFor(this@WeChatSettingsActivity, binding))
                }
            })
        }
        content.addView(space(24))
        content.addView(section("DIAGNOSTICS"))
        content.addView(space(12))
        content.addView(card().apply {
            val (title, detail) = when (settingsStore.diagnosticStatus()) {
                WeChatDiagnosticStatus.SUPPORTED -> "Identity available" to "A supported caller identity was observed. Two separate calls with the same identity are required to bind."
                WeChatDiagnosticStatus.NAME_ONLY -> "Name only" to "WeChat supplied a display name without a supported identity. This caller cannot be bound."
                WeChatDiagnosticStatus.MISSING -> "Identity missing" to "No supported caller identity is available from a recent call."
                WeChatDiagnosticStatus.UNSUPPORTED -> "Unsupported notification" to "This notification or Android version does not expose a supported call identity."
            }
            addView(tv(title, 15f, C_WHITE))
            addView(space(5))
            addView(tv(detail, 13f, C_DIM))
            candidate?.let {
                addView(space(8))
                addView(tv("Fingerprint: ${it.fingerprint}\nMatching calls: ${it.observationCount}\n" +
                    if (it.observationCount >= 2) "Stability: repeated identity" else "Stability: needs another matching call", 13f, C_DIM))
                if (!it.canBind && it.observationCount >= 2) {
                    addView(tv("This identity is already bound.", 13f, C_DIM))
                }
            }
            if (settingsStore.isDirectDeclineUnavailable()) {
                addView(space(12))
                addView(tv("Direct decline unavailable. Removing a notification may not end the call.", 13f, C_RED))
            }
            addView(space(12))
            addView(action("Refresh diagnostics") { setContentView(buildUi()) })
        })
        content.addView(space(24))
        content.addView(tv("Requires Android 12 or newer. Call detection and direct rejection depend on the notification supplied by WeChat. Recent observations are kept only in memory and reset when the app process stops.", 13f, C_DIM))
        return ScrollView(this).apply {
            setBackgroundColor(C_BG)
            addView(content)
        }
    }

    private fun bindRecentCaller(expectedDigest: String) {
        val input = labelInput ?: return
        if (input.text.toString().isBlank()) {
            input.error = "Enter a local label"
            input.requestFocus()
            return
        }
        if (settingsStore.bindRecentCaller(input.text.toString(), System.currentTimeMillis(), expectedDigest)) {
            dismissWhitelistInputFocus(listOf(input))
            setContentView(buildUi())
            Toast.makeText(this, "Verified caller added", Toast.LENGTH_SHORT).show()
        } else {
            setContentView(buildUi())
            Toast.makeText(this, "No new verified caller. Receive two separate calls from the same caller first.", Toast.LENGTH_LONG).show()
        }
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(C_SURFACE)
        setPadding(dp(18), dp(18), dp(18), dp(18))
    }

    private fun rounded(color: Int) = GradientDrawable().apply {
        cornerRadius = dp(14).toFloat()
        setColor(color)
        setStroke(dp(1), Color.parseColor("#2A2A44"))
    }

    private fun action(text: String, onClick: () -> Unit) = tv(text, 15f, C_ACCENT).apply {
        isClickable = true
        isFocusable = true
        minHeight = dp(48)
        gravity = Gravity.CENTER_VERTICAL
        setOnClickListener { onClick() }
    }

    private fun section(text: String) = tv(text, 13f, C_DIM).apply { letterSpacing = 0.08f }
    private fun tv(text: String, size: Float, color: Int) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
    }
    private fun space(height: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(-1, dp(height)) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        val C_BG = Color.parseColor("#0E0E1A")
        val C_SURFACE = Color.parseColor("#1A1A2E")
        val C_WHITE = Color.WHITE
        val C_DIM = Color.parseColor("#7777AA")
        val C_ACCENT = Color.parseColor("#4D80FF")
        val C_GREEN = Color.parseColor("#4DD16A")
        val C_RED = Color.parseColor("#EF5350")
    }
}
