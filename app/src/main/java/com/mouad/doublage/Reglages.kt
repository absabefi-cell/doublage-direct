package com.mouad.doublage

import android.content.Context
import android.content.SharedPreferences
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/**
 * Curseurs de réglage, partagés entre l'écran principal et le panneau ⋮ par-dessus YouTube.
 * Chaque changement est enregistré tout de suite : le doublage l'applique dès la phrase suivante.
 */
object Reglages {

    fun ajouter(ctx: Context, col: LinearLayout, prefs: SharedPreferences, complet: Boolean, couleurTexte: Int? = null,
                essai: Boolean = false) {
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        fun curseur(titre: String, cle: String, lo: Int, hi: Int, defaut: Int, flottant: Boolean,
                    affiche: (Int) -> String) {
            val lbl = TextView(ctx).apply {
                textSize = 14f; setPadding(0, dp(8), 0, 0)
                couleurTexte?.let { setTextColor(it) }
            }
            col.addView(lbl)
            val sb = SeekBar(ctx).apply { max = hi - lo }
            val depart = if (flottant) (prefs.getFloat(cle, defaut / 100f) * 100).toInt() else prefs.getInt(cle, defaut)
            sb.progress = (depart - lo).coerceIn(0, hi - lo)
            lbl.text = "$titre : ${affiche(depart)}"
            sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) {
                    val v = p + lo
                    lbl.text = "$titre : ${affiche(v)}"
                    if (flottant) prefs.edit().putFloat(cle, v / 100f).apply()
                    else prefs.edit().putInt(cle, v).apply()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {
                    // Pendant le doublage : on redit la dernière phrase pour entendre le changement tout de suite
                    if (essai && cle != "mots_max" && cle != "filtre") prefs.edit().putLong("essai", System.currentTimeMillis()).apply()
                }
            })
            col.addView(sb)
        }

        curseur("Volume de la traduction", "volume", 20, 300, 100, false) {
            "$it %" + if (it > 100) " (amplifiée)" else ""
        }
        curseur("Volume de YouTube pendant la traduction", "volume_original", 10, 100, 40, false) {
            "$it %" + if (it >= 100) " (pas baissé)" else ""
        }
        curseur("Vitesse de la voix", "vitesse", 60, 250, 120, true) { "x%.2f".format(it / 100f) }
        curseur("Hauteur de la voix", "hauteur", 60, 180, 100, true) { "x%.2f".format(it / 100f) }
        curseur("Force des émotions", "emotions", 0, 100, 60, false) {
            "$it %" + if (it == 0) " (voix neutre)" else ""
        }
        if (complet) {
            curseur("Réactivité (mots max avant de traduire)", "mots_max", 4, 16, 10, false) {
                "$it mots" + if (it <= 6) " (très rapide, moins de sens)" else if (it >= 12) " (phrases plus complètes)" else ""
            }
            curseur("Filtre des phrases mal entendues", "filtre", 0, 90, 45, false) {
                if (it == 0) "0 % (tout traduire)" else "$it %" + if (it >= 65) " (strict)" else ""
            }
        }
    }
}
