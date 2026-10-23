package com.whitelistcalls

import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

interface AllowedCallVibrator {
    fun start(notificationKey: String)
    fun stop(notificationKey: String)
    fun stopAll()
}

class AndroidAllowedCallVibrator(context: Context) : AllowedCallVibrator {
    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java).defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    override fun start(notificationKey: String) {
        val effect = VibrationEffect.createWaveform(INCOMING_CALL_WAVEFORM, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrator.vibrate(effect, ringtoneVibrationAttributes())
        } else {
            vibrator.vibrate(effect)
        }
    }

    override fun stop(notificationKey: String) {
        vibrator.cancel()
    }

    override fun stopAll() {
        vibrator.cancel()
    }

    private companion object {
        val INCOMING_CALL_WAVEFORM = longArrayOf(0L, 500L, 1_000L)
        @android.annotation.TargetApi(Build.VERSION_CODES.TIRAMISU)
        fun ringtoneVibrationAttributes() = VibrationAttributes.Builder()
            .setUsage(VibrationAttributes.USAGE_RINGTONE)
            .build()
    }
}
