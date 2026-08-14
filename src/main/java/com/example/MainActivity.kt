package com.example

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.example.custom_noscroll.R

/**
 * Activité principale NoScroll permettant à l'utilisateur :
 * 1. D'autoriser la permission d'overlay (SYSTEM_ALERT_WINDOW).
 * 2. D'ouvrir les réglages d'accessibilité pour activer BlockerAccessibilityService.
 * 3. De tester l'overlay et de réinitialiser le temps de pause.
 */
class MainActivity : ComponentActivity() {

    private lateinit var tvOverlayStatus: TextView
    private lateinit var tvAccessibilityStatus: TextView
    private lateinit var btnRequestOverlay: Button
    private lateinit var btnOpenAccessibility: Button
    private lateinit var btnTestOverlay: Button
    private lateinit var btnResetCooldown: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialisation des vues
        tvOverlayStatus = findViewById(R.id.tv_overlay_status)
        tvAccessibilityStatus = findViewById(R.id.tv_accessibility_status)
        btnRequestOverlay = findViewById(R.id.btn_request_overlay)
        btnOpenAccessibility = findViewById(R.id.btn_open_accessibility)
        btnTestOverlay = findViewById(R.id.btn_test_overlay)
        btnResetCooldown = findViewById(R.id.btn_reset_cooldown)

        // Bouton 1 : Demande de permission d'affichage en superposition (Overlay)
        btnRequestOverlay.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
                Toast.makeText(
                    this,
                    "Veuillez autoriser NoScroll à s'afficher par-dessus les autres applis.",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Toast.makeText(this, "Permission Overlay déjà accordée !", Toast.LENGTH_SHORT).show()
            }
        }

        // Bouton 2 : Ouverture des paramètres d'accessibilité
        btnOpenAccessibility.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
            Toast.makeText(
                this,
                "Cherchez 'NoScroll' dans la liste des services téléchargés et activez-le.",
                Toast.LENGTH_LONG
            ).show()
        }

        // Bouton Test Overlay
        btnTestOverlay.setOnClickListener {
            if (Settings.canDrawOverlays(this)) {
                // Déclencher un aperçu de l'overlay de test
                showDirectOverlayPreview()
            } else {
                Toast.makeText(
                    this,
                    "Veuillez d'abord autoriser la permission d'overlay (Étape 1)",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        // Bouton Réinitialiser Cooldown
        btnResetCooldown.setOnClickListener {
            BlockerAccessibilityService.resetCooldown(this)
            Toast.makeText(this, "Cooldown de 40 minutes réinitialisé à zéro !", Toast.LENGTH_SHORT).show()
            updateStatusIndicators()
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatusIndicators()
    }

    /**
     * Vérifie et met à jour l'état des autorisations dans l'interface.
     */
    private fun updateStatusIndicators() {
        // 1. Statut Overlay
        val hasOverlay = Settings.canDrawOverlays(this)
        if (hasOverlay) {
            tvOverlayStatus.text = "Granted ✓"
            tvOverlayStatus.setTextColor(Color.parseColor("#BAF3DB")) // Mint
            btnRequestOverlay.alpha = 0.6f
        } else {
            tvOverlayStatus.text = "Required ✕"
            tvOverlayStatus.setTextColor(Color.parseColor("#FFB4AB")) // Alert Rose
            btnRequestOverlay.alpha = 1.0f
        }

        // 2. Statut Service d'Accessibilité
        val isAccessibilityEnabled = isAccessibilityServiceEnabled(this, BlockerAccessibilityService::class.java)
        if (isAccessibilityEnabled) {
            tvAccessibilityStatus.text = "Active ✓"
            tvAccessibilityStatus.setTextColor(Color.parseColor("#BAF3DB")) // Mint
            btnOpenAccessibility.alpha = 0.6f
        } else {
            tvAccessibilityStatus.text = "Required ✕"
            tvAccessibilityStatus.setTextColor(Color.parseColor("#FFB4AB")) // Alert Rose
            btnOpenAccessibility.alpha = 1.0f
        }
    }

    /**
     * Vérifie si le service d'accessibilité est activé dans le système.
     */
    private fun isAccessibilityServiceEnabled(context: Context, serviceClass: Class<*>): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return false
        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_GENERIC)
        val expectedComponentName = "${context.packageName}/${serviceClass.name}"

        return enabledServices.any { serviceInfo ->
            serviceInfo.resolveInfo.serviceInfo.packageName == context.packageName &&
                    serviceInfo.resolveInfo.serviceInfo.name == serviceClass.name
        } || BlockerAccessibilityService.isServiceRunning
    }

    /**
     * Affiche un aperçu direct de l'overlay de blocage pour tester le rendu visuel.
     */
    private fun showDirectOverlayPreview() {
        try {
            val wm = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
            val inflater = android.view.LayoutInflater.from(this)
            val view = inflater.inflate(R.layout.overlay_blocker, null)

            val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                android.view.WindowManager.LayoutParams.TYPE_PHONE
            }

            val params = android.view.WindowManager.LayoutParams(
                android.view.WindowManager.LayoutParams.MATCH_PARENT,
                android.view.WindowManager.LayoutParams.MATCH_PARENT,
                layoutType,
                android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN,
                android.graphics.PixelFormat.TRANSLUCENT
            )

            val btnClose = view.findViewById<Button>(R.id.btn_overlay_close)
            btnClose.setOnClickListener {
                try {
                    wm.removeView(view)
                } catch (_: Exception) {}
            }

            wm.addView(view, params)
            Toast.makeText(this, "Aperçu de l'overlay affiché !", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Erreur overlay : ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
