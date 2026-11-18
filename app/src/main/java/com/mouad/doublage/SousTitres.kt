package com.mouad.doublage

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Sous-titres de contrôle par-dessus YouTube, en bas de l'écran :
 * ligne 1 = langue originale (ce que l'app a entendu), ligne 2 = langue traduite.
 * On ne peut pas les toucher : les doigts passent à travers vers YouTube.
 */
class SousTitres(private val ctx: Context, private val prefs: SharedPreferences) {

    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val d = ctx.resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()
    private val principal = Handler(Looper.getMainLooper())
    private var cadre: LinearLayout? = null
    private var lp: WindowManager.LayoutParams? = null
    private var lOrig: TextView? = null
    private var lTrad: TextView? = null
    private val effacer = Runnable { cadre?.visibility = View.GONE }

    fun montrer() {
        if (cadre != null || !Settings.canDrawOverlays(ctx)) return
        val c = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(Color.parseColor("#B3000000")) }
            visibility = View.GONE
        }
        val o = TextView(ctx).apply { setTextColor(Color.WHITE); gravity = Gravity.CENTER }
        val t = TextView(ctx).apply { setTextColor(Color.parseColor("#FFE24A")); setTypeface(null, Typeface.BOLD); gravity = Gravity.CENTER }
        c.addView(o); c.addView(t)
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            horizontalMargin = 0.03f
            // Android 12+ : au-dessus de 0,8 d'opacité, les touchers ne passent plus vers YouTube
            alpha = 0.8f
        }
        try { wm.addView(c, p) } catch (_: Exception) { return }
        cadre = c; lp = p; lOrig = o; lTrad = t
        reglages()
    }

    /** Taille et hauteur à l'écran : appliquées tout de suite quand on bouge les curseurs. */
    fun reglages() {
        val taille = prefs.getInt("st_taille", 16).toFloat()
        lOrig?.textSize = taille * 0.9f
        lTrad?.textSize = taille
        val p = lp ?: return
        p.y = (ctx.resources.displayMetrics.heightPixels * prefs.getInt("st_position", 6) / 100f).toInt()
        cadre?.let { try { wm.updateViewLayout(it, p) } catch (_: Exception) {} }
    }

    /** Affiche une phrase : original en blanc, traduction en jaune (en rouge si elle n'a pas été dite). */
    fun afficher(source: String, cible: String, e: SousTitre.Entree) {
        val c = cadre ?: return
        lOrig?.text = SousTitre.ligneOriginal(source, e)
        lTrad?.text = SousTitre.ligneTraduction(cible, e)
        lTrad?.setTextColor(Color.parseColor(if (SousTitre.anomalie(e)) "#FF6B6B" else "#FFE24A"))
        c.visibility = View.VISIBLE
        principal.removeCallbacks(effacer)
        principal.postDelayed(effacer, 12000)
    }

    fun cacher() {
        principal.removeCallbacks(effacer)
        cadre?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        cadre = null; lp = null; lOrig = null; lTrad = null
    }
}
