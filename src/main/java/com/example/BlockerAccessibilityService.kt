package com.example

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.TextView
import com.example.custom_noscroll.R
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Service d'accessibilité NoScroll.
 * Détecte l'utilisation d'Instagram et de TikTok, compte le temps passé (10 min max),
 * et affiche une fenêtre d'overlay via WindowManager pour bloquer l'accès pendant 40 minutes (cooldown).
 */
class BlockerAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "NoScrollService"
        private const val PREFS_NAME = "NoScrollPrefs"
        private const val KEY_COOLDOWN_END = "cooldown_end_timestamp"
        private const val KEY_USAGE_ACCUMULATED = "usage_accumulated_seconds"
        private const val KEY_LAST_USAGE_TIME = "last_usage_time"

        // Configuration des durées (10 min d'utilisation / 40 min de cooldown)
        const val MAX_USAGE_SECONDS = 10 * 60L // 600 secondes = 10 minutes
        const val COOLDOWN_DURATION_MS = 40 * 60 * 1000L // 40 minutes

        // Packages ciblés
        val TARGET_PACKAGES = setOf(
            "com.instagram.android",
            "com.zhiliaoapp.musically",
            "com.ss.android.ugc.trill"
        )

        var isServiceRunning = false
            private set

        fun getCooldownRemainingMs(context: Context): Long {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val cooldownEnd = prefs.getLong(KEY_COOLDOWN_END, 0L)
            val now = System.currentTimeMillis()
            return if (cooldownEnd > now) cooldownEnd - now else 0L
        }

        fun resetCooldown(context: Context) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .remove(KEY_COOLDOWN_END)
                .putLong(KEY_USAGE_ACCUMULATED, 0L)
                .apply()
        }
    }

    private lateinit var windowManager: WindowManager
    private lateinit var prefs: SharedPreferences

    private var overlayView: View? = null
    private var isOverlayShowing = false

    private var currentForegroundPackage: String? = null
    private var isTargetAppInForeground = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private var usageSeconds = 0L

    // Ticker pour le décompte d'utilisation de 10 min
    private val usageTicker = object : Runnable {
        override fun run() {
            if (isTargetAppInForeground) {
                // Vérifier d'abord si on est en période de cooldown
                if (isInCooldown()) {
                    showBlockingOverlay()
                    return
                }

                usageSeconds++
                saveUsage(usageSeconds)
                Log.d(TAG, "Utilisation active : $usageSeconds / $MAX_USAGE_SECONDS s")

                if (usageSeconds >= MAX_USAGE_SECONDS) {
                    triggerCooldownAndBlock()
                } else {
                    mainHandler.postDelayed(this, 1000)
                }
            }
        }
    }

    // Ticker pour la mise à jour visuelle du temps restant dans l'overlay
    private val overlayCountdownTicker = object : Runnable {
        override fun run() {
            if (isOverlayShowing && overlayView != null) {
                val remainingMs = getRemainingCooldownMs()
                if (remainingMs > 0) {
                    updateOverlayCountdownText(remainingMs)
                    mainHandler.postDelayed(this, 1000)
                } else {
                    // Cooldown terminé !
                    hideOverlay()
                    resetUsage()
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isServiceRunning = true
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        usageSeconds = prefs.getLong(KEY_USAGE_ACCUMULATED, 0L)
        Log.i(TAG, "BlockerAccessibilityService connecté et opérationnel.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val pkgName = event.packageName?.toString() ?: return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                handleWindowStateChanged(pkgName)
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (TARGET_PACKAGES.contains(pkgName)) {
                    if (isInCooldown()) {
                        showBlockingOverlay()
                    }
                }
            }
        }
    }

    private fun handleWindowStateChanged(packageName: String) {
        currentForegroundPackage = packageName

        if (TARGET_PACKAGES.contains(packageName)) {
            if (!isTargetAppInForeground) {
                isTargetAppInForeground = true
                Log.d(TAG, "Application cible au premier plan : $packageName")

                // Si déjà en cooldown, bloquer immédiatement
                if (isInCooldown()) {
                    showBlockingOverlay()
                } else {
                    mainHandler.removeCallbacks(usageTicker)
                    mainHandler.post(usageTicker)
                }
            }
        } else {
            if (isTargetAppInForeground) {
                isTargetAppInForeground = false
                Log.d(TAG, "Sortie de l'application cible.")
                mainHandler.removeCallbacks(usageTicker)
                // Si on a quitté l'appli cible, cacher l'overlay
                if (isOverlayShowing) {
                    hideOverlay()
                }
            }
        }
    }

    private fun isInCooldown(): Boolean {
        val cooldownEnd = prefs.getLong(KEY_COOLDOWN_END, 0L)
        return System.currentTimeMillis() < cooldownEnd
    }

    private fun getRemainingCooldownMs(): Long {
        val cooldownEnd = prefs.getLong(KEY_COOLDOWN_END, 0L)
        val now = System.currentTimeMillis()
        return if (cooldownEnd > now) cooldownEnd - now else 0L
    }

    private fun triggerCooldownAndBlock() {
        val cooldownEndTime = System.currentTimeMillis() + COOLDOWN_DURATION_MS
        prefs.edit()
            .putLong(KEY_COOLDOWN_END, cooldownEndTime)
            .putLong(KEY_USAGE_ACCUMULATED, 0L)
            .apply()

        usageSeconds = 0L
        mainHandler.removeCallbacks(usageTicker)
        showBlockingOverlay()
    }

    private fun saveUsage(seconds: Long) {
        prefs.edit().putLong(KEY_USAGE_ACCUMULATED, seconds).apply()
    }

    private fun resetUsage() {
        usageSeconds = 0L
        prefs.edit().putLong(KEY_USAGE_ACCUMULATED, 0L).apply()
    }

    /**
     * Affiche l'overlay plein écran par-dessus l'application cible avec WindowManager.
     */
    fun showBlockingOverlay() {
        if (isOverlayShowing) return

        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "Permission SYSTEM_ALERT_WINDOW manquante !")
            return
        }

        mainHandler.post {
            try {
                val inflater = LayoutInflater.from(this)
                val view = inflater.inflate(R.layout.overlay_blocker, null)

                val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }

                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    layoutType,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                            WindowManager.LayoutParams.FLAG_FULLSCREEN,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.CENTER
                }

                val btnClose = view.findViewById<Button>(R.id.btn_overlay_close)
                val tvCountdown = view.findViewById<TextView>(R.id.tv_overlay_countdown)

                btnClose.setOnClickListener {
                    // Ramener l'utilisateur à l'écran d'accueil du téléphone
                    performGlobalAction(GLOBAL_ACTION_HOME)
                    hideOverlay()
                }

                val remainingMs = getRemainingCooldownMs()
                val displayMs = if (remainingMs > 0) remainingMs else COOLDOWN_DURATION_MS
                val minutes = TimeUnit.MILLISECONDS.toMinutes(displayMs)
                val seconds = TimeUnit.MILLISECONDS.toSeconds(displayMs) % 60
                tvCountdown.text = String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)

                windowManager.addView(view, params)
                overlayView = view
                isOverlayShowing = true

                // Démarrer la mise à jour du timer
                mainHandler.removeCallbacks(overlayCountdownTicker)
                mainHandler.post(overlayCountdownTicker)

                Log.i(TAG, "Overlay de blocage affiché avec succès.")
            } catch (e: Exception) {
                Log.e(TAG, "Erreur lors de l'affichage de l'overlay : ${e.message}", e)
            }
        }
    }

    /**
     * Supprime l'overlay de l'écran.
     */
    fun hideOverlay() {
        mainHandler.post {
            if (isOverlayShowing && overlayView != null) {
                try {
                    mainHandler.removeCallbacks(overlayCountdownTicker)
                    windowManager.removeView(overlayView)
                } catch (e: Exception) {
                    Log.e(TAG, "Erreur lors de la suppression de l'overlay: ${e.message}")
                } finally {
                    overlayView = null
                    isOverlayShowing = false
                }
            }
        }
    }

    private fun updateOverlayCountdownText(remainingMs: Long) {
        val tvCountdown = overlayView?.findViewById<TextView>(R.id.tv_overlay_countdown) ?: return
        val minutes = TimeUnit.MILLISECONDS.toMinutes(remainingMs)
        val seconds = TimeUnit.MILLISECONDS.toSeconds(remainingMs) % 60
        tvCountdown.text = String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }

    override fun onInterrupt() {
        Log.w(TAG, "Service d'accessibilité interrompu.")
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        hideOverlay()
        mainHandler.removeCallbacks(usageTicker)
        mainHandler.removeCallbacks(overlayCountdownTicker)
    }
}
