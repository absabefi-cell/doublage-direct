package com.mouad.doublage

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Bouton ⋮ flottant par-dessus YouTube : ouvre les réglages sans arrêter le doublage. */
class Bulle(service: Context, private val prefs: SharedPreferences, private val arreter: () -> Unit) {

    // Thème clair pour que les curseurs aient leur allure normale
    private val ctx: Context = android.view.ContextThemeWrapper(service, android.R.style.Theme_Material_Light_Dialog)
    private val wm = service.getSystemService(WindowManager::class.java)
    private val d = ctx.resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()
    private var bouton: TextView? = null
    private var panneau: View? = null
    private val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    fun permise() = Settings.canDrawOverlays(ctx)

    @SuppressLint("ClickableViewAccessibility")
    fun montrer() {
        if (bouton != null || !permise()) return
        val lp = WindowManager.LayoutParams(
            dp(46), dp(46), type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("bulle_x", dp(8)); y = prefs.getInt("bulle_y", dp(160))
        }
        val b = TextView(ctx).apply {
            text = "⋮"; textSize = 26f; gravity = Gravity.CENTER
            setTextColor(Color.WHITE); setTypeface(null, Typeface.BOLD)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#CC1B7F4B")) }
            contentDescription = "Réglages du doublage"
        }
        var x0 = 0; var y0 = 0; var tx = 0f; var ty = 0f; var bouge = false
        b.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { x0 = lp.x; y0 = lp.y; tx = e.rawX; ty = e.rawY; bouge = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - tx; val dy = e.rawY - ty
                    if (Math.abs(dx) > dp(6) || Math.abs(dy) > dp(6)) bouge = true
                    if (bouge) { lp.x = x0 + dx.toInt(); lp.y = y0 + dy.toInt(); wm.updateViewLayout(v, lp) }
                }
                MotionEvent.ACTION_UP -> {
                    if (bouge) prefs.edit().putInt("bulle_x", lp.x).putInt("bulle_y", lp.y).apply()
                    else basculer()
                }
            }
            true
        }
        try { wm.addView(b, lp); bouton = b } catch (_: Exception) {}
    }

    private var fermeA = 0L

    private fun basculer() {
        // Toucher ⋮ quand le panneau est ouvert le ferme déjà (toucher « à côté ») : on ne le rouvre pas.
        if (panneau != null) fermerPanneau()
        else if (System.currentTimeMillis() - fermeA > 400) ouvrirPanneau()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun ouvrirPanneau() {
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        col.addView(TextView(ctx).apply {
            text = "Réglages en direct"; textSize = 17f; setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#1B7F4B"))
        })
        Reglages.ajouter(ctx, col, prefs, complet = true, couleurTexte = Color.parseColor("#222222"))
        val ligne = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, 0) }
        val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        ligne.addView(Button(ctx).apply { text = "Fermer"; setOnClickListener { fermerPanneau() } }, lp)
        ligne.addView(Button(ctx).apply { text = "Arrêter"; setOnClickListener { fermerPanneau(); arreter() } }, lp)
        col.addView(ligne)

        val cadre = ScrollView(ctx).apply {
            addView(col)
            background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(Color.parseColor("#F5FFFFFF")) }
            setOnTouchListener { _, e ->
                if (e.action == MotionEvent.ACTION_OUTSIDE) { fermerPanneau(); true } else false
            }
        }
        val haut = (ctx.resources.displayMetrics.heightPixels * 0.6).toInt()
        val p = WindowManager.LayoutParams(
            dp(320), WindowManager.LayoutParams.WRAP_CONTENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }
        cadre.layoutParams = android.view.ViewGroup.LayoutParams(dp(320), android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        cadre.addOnLayoutChangeListener { v, _, t, _, b, _, _, _, _ ->
            if (b - t > haut) { p.height = haut; try { wm.updateViewLayout(v, p) } catch (_: Exception) {} }
        }
        try { wm.addView(cadre, p); panneau = cadre } catch (_: Exception) {}
    }

    private fun fermerPanneau() {
        panneau?.let { try { wm.removeView(it) } catch (_: Exception) {}; fermeA = System.currentTimeMillis() }
        panneau = null
    }

    fun cacher() {
        fermerPanneau()
        bouton?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        bouton = null
    }
}
