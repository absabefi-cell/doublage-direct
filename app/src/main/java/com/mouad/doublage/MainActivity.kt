package com.mouad.doublage

import android.Manifest
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : ComponentActivity() {

    private lateinit var statut: TextView
    private lateinit var original: TextView
    private lateinit var traduction: TextView
    private lateinit var rbArabe: RadioButton
    private lateinit var cbBaisser: CheckBox

    private val demandePermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
            if (res[Manifest.permission.RECORD_AUDIO] == true) demanderCapture()
            else statut.text = "Permission micro refusée. Elle est obligatoire pour capter le son de YouTube."
        }

    private val demandeCapture =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            if (r.resultCode == RESULT_OK && r.data != null) {
                val i = Intent(this, DoublageService::class.java).apply {
                    putExtra("code", r.resultCode)
                    putExtra("data", r.data)
                    putExtra("langue", if (rbArabe.isChecked) "ar" else "fr")
                    putExtra("baisser", cbBaisser.isChecked)
                }
                startForegroundService(i)
                statut.text = "Démarrage..."
            } else {
                statut.text = "Capture refusée."
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(20))
        }

        col.addView(TextView(this).apply {
            text = "Doublage Direct"
            textSize = 26f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#1B7F4B"))
        })
        col.addView(TextView(this).apply {
            text = "Anglais → traduction parlée, sans serveur"
            textSize = 14f
            setPadding(0, dp(2), 0, dp(18))
        })

        col.addView(TextView(this).apply { text = "Langue de la traduction"; textSize = 16f })
        val groupe = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val rbFr = RadioButton(this).apply { text = "Français"; id = View.generateViewId() }
        rbArabe = RadioButton(this).apply { text = "العربية"; id = View.generateViewId() }
        groupe.addView(rbFr); groupe.addView(rbArabe)
        groupe.check(rbFr.id)
        col.addView(groupe)

        cbBaisser = CheckBox(this).apply {
            text = "Baisser le son de YouTube pendant la traduction"
            isChecked = true
        }
        col.addView(cbBaisser)

        val boutons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(16))
        }
        val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(dp(4), 0, dp(4), 0)
        }
        boutons.addView(Button(this).apply {
            text = "Démarrer"
            setOnClickListener { demarrer() }
        }, lp)
        boutons.addView(Button(this).apply {
            text = "Arrêter"
            setOnClickListener { stopService(Intent(this@MainActivity, DoublageService::class.java)) }
        }, lp)
        col.addView(boutons)

        statut = TextView(this).apply { textSize = 15f; setTypeface(null, Typeface.ITALIC) }
        col.addView(statut)

        col.addView(TextView(this).apply {
            text = "Entendu (anglais)"; textSize = 13f; setPadding(0, dp(20), 0, dp(4))
        })
        original = TextView(this).apply { textSize = 16f }
        col.addView(original)

        col.addView(TextView(this).apply {
            text = "Traduction"; textSize = 13f; setPadding(0, dp(16), 0, dp(4))
        })
        traduction = TextView(this).apply {
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#1B7F4B"))
        }
        col.addView(traduction)

        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() {
        super.onResume()
        DoublageService.ecouteur = { rafraichir() }
        rafraichir()
    }

    override fun onPause() {
        DoublageService.ecouteur = null
        super.onPause()
    }

    private fun rafraichir() {
        statut.text = DoublageService.dStatut.ifBlank { "Choisis la langue, puis Démarrer." }
        original.text = DoublageService.dOriginal
        traduction.text = DoublageService.dTraduction
    }

    private fun demarrer() {
        if (DoublageService.enMarche) {
            statut.text = "Déjà en marche. Arrête d'abord pour changer de langue."
            return
        }
        demandePermissions.launch(
            arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        )
    }

    private fun demanderCapture() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        demandeCapture.launch(mpm.createScreenCaptureIntent())
    }
}
