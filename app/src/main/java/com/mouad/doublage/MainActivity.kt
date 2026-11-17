package com.mouad.doublage

import android.Manifest
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.Spinner
import java.util.Locale
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
    private lateinit var spHomme: Spinner
    private lateinit var spFemme: Spinner
    private var essai: TextToSpeech? = null
    private var volEssai: Volume? = null
    private var voixDispo: List<Voice> = emptyList()
    private val prefs by lazy { getSharedPreferences(DoublageService.PREFS, MODE_PRIVATE) }
    private fun langue() = if (::rbArabe.isInitialized && rbArabe.isChecked) "ar" else "fr"

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
        groupe.check(if (prefs.getString("langue", "fr") == "ar") rbArabe.id else rbFr.id)
        groupe.setOnCheckedChangeListener { _, _ ->
            prefs.edit().putString("langue", langue()).apply()
            remplirVoix()
        }
        col.addView(groupe)

        cbBaisser = CheckBox(this).apply {
            text = "Baisser le son de YouTube pendant la traduction"
            isChecked = true
        }
        col.addView(cbBaisser)

        // ---------- Réglage des voix ----------
        col.addView(TextView(this).apply {
            text = "Voix"; textSize = 18f; setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(18), 0, dp(4))
        })
        col.addView(TextView(this).apply { text = "Voix homme"; textSize = 14f })
        spHomme = Spinner(this); col.addView(spHomme)
        col.addView(TextView(this).apply { text = "Voix femme"; textSize = 14f; setPadding(0, dp(8), 0, 0) })
        spFemme = Spinner(this); col.addView(spFemme)

        col.addView(CheckBox(this).apply {
            text = "Choisir homme / femme automatiquement selon la voix du film"
            isChecked = prefs.getBoolean("auto", true)
            setOnCheckedChangeListener { _, b -> prefs.edit().putBoolean("auto", b).apply() }
        })
        col.addView(CheckBox(this).apply {
            text = "Voix d'enfant (voix femme plus aiguë) pour les voix très aiguës"
            isChecked = prefs.getBoolean("enfant", true)
            setOnCheckedChangeListener { _, b -> prefs.edit().putBoolean("enfant", b).apply() }
        })

        fun curseur(titre: String, cle: String, lo: Int, hi: Int, defaut: Int, affiche: (Int) -> String,
                    sauver: (Int) -> Unit) {
            val lbl = TextView(this).apply { textSize = 14f; setPadding(0, dp(10), 0, 0) }
            col.addView(lbl)
            val sb = SeekBar(this).apply { max = hi - lo }
            val depart = when (cle) {
                "vitesse" -> (prefs.getFloat(cle, defaut / 100f) * 100).toInt()
                "hauteur" -> (prefs.getFloat(cle, defaut / 100f) * 100).toInt()
                else -> prefs.getInt(cle, defaut)
            }
            sb.progress = depart - lo
            lbl.text = "$titre : ${affiche(depart)}"
            sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) {
                    lbl.text = "$titre : ${affiche(p + lo)}"; sauver(p + lo)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
            col.addView(sb)
        }
        curseur("Vitesse de la voix", "vitesse", 60, 250, 120, { "x%.2f".format(it / 100f) }) {
            prefs.edit().putFloat("vitesse", it / 100f).apply()
        }
        curseur("Hauteur de la voix", "hauteur", 60, 180, 100, { "x%.2f".format(it / 100f) }) {
            prefs.edit().putFloat("hauteur", it / 100f).apply()
        }
        curseur("Volume de la traduction", "volume", 20, 300, 100,
            { "$it %" + if (it > 100) " (amplifiée)" else "" }) {
            prefs.edit().putInt("volume", it).apply()
        }
        col.addView(TextView(this).apply {
            text = "Le son de YouTube se règle avec les boutons de volume du téléphone. La traduction suit ce volume, plus ce curseur."
            textSize = 12f
        })
        curseur("Réactivité (mots max avant de traduire)", "mots_max", 4, 16, 8,
            { "$it mots" + if (it <= 6) " (très rapide)" else if (it >= 12) " (phrases plus complètes)" else "" }) {
            prefs.edit().putInt("mots_max", it).apply()
        }

        col.addView(Button(this).apply {
            text = "Tester les voix"
            setOnClickListener { tester() }
        })

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

        essai = TextToSpeech(this) { st -> if (st == TextToSpeech.SUCCESS) remplirVoix() }
    }

    override fun onDestroy() {
        essai?.shutdown()
        volEssai?.liberer()
        super.onDestroy()
    }

    private fun nomAffiche(v: Voice, i: Int): String {
        val pays = v.locale.displayCountry.ifBlank { v.locale.country }
        val net = if (v.isNetworkConnectionRequired) " (Internet)" else ""
        return "Voix ${i + 1} - $pays - ${v.name}$net"
    }

    private fun remplirVoix() {
        val t = essai ?: return
        val l = langue()
        voixDispo = (t.voices ?: emptySet())
            .filter { it.locale.language == l && it.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true }
            .sortedWith(compareBy({ it.isNetworkConnectionRequired }, { it.name }))
        val noms = listOf("Par défaut") + voixDispo.mapIndexed { i, v -> nomAffiche(v, i) }
        fun lier(sp: Spinner, cle: String) {
            sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, noms)
            val sel = voixDispo.indexOfFirst { it.name == prefs.getString(cle, null) }
            sp.setSelection(if (sel >= 0) sel + 1 else 0)
            sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    prefs.edit().putString(cle, if (pos == 0) null else voixDispo[pos - 1].name).apply()
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        lier(spHomme, "voix_homme_$l")
        lier(spFemme, "voix_femme_$l")
        if (voixDispo.isEmpty()) statut.text =
            "Aucune voix ${if (l == "ar") "arabe" else "française"} installée : Paramètres > Synthèse vocale > Google > Installer les données vocales."
    }

    private fun tester() {
        val t = essai ?: return
        val l = langue()
        t.language = if (l == "ar") Locale("ar") else Locale.FRENCH
        t.setSpeechRate(prefs.getFloat("vitesse", 1.2f))
        t.setPitch(prefs.getFloat("hauteur", 1.0f))
        val phrases = if (l == "ar") listOf("مرحبا، هذا صوت الرجل.", "وهذا صوت المرأة.")
                      else listOf("Bonjour, voici la voix d'homme.", "Et voici la voix de femme.")
        listOf("voix_homme_$l", "voix_femme_$l").forEachIndexed { i, cle ->
            val v = voixDispo.firstOrNull { it.name == prefs.getString(cle, null) }
            if (v != null) t.voice = v else t.language = if (l == "ar") Locale("ar") else Locale.FRENCH
            val vol = (volEssai ?: Volume(this).also { volEssai = it }).params(prefs.getInt("volume", 100))
            t.speak(phrases[i], if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, vol, "t$i")
        }
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
