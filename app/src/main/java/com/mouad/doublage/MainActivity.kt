package com.mouad.doublage

import android.Manifest
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.io.File
import java.util.Locale

class MainActivity : ComponentActivity() {

    private lateinit var statut: TextView
    private lateinit var original: TextView
    private lateinit var traduction: TextView
    private lateinit var cbBaisser: CheckBox
    private lateinit var spSource: Spinner
    private lateinit var spCible: Spinner
    private lateinit var spHomme: Spinner
    private lateinit var spFemme: Spinner
    private lateinit var triVoix: TextView
    private lateinit var btBulle: Button
    private lateinit var btAuto: Button
    private lateinit var etatLangues: TextView
    private lateinit var btPreparer: Button
    private lateinit var btVoix: Button
    private lateinit var btNotif: Button
    @Volatile private var preparation = false
    private var ttsPret = false
    private var essai: TextToSpeech? = null
    private var volEssai: Volume? = null
    private var voixToutes: List<Voice> = emptyList()
    private var voixHommes: List<Voice> = emptyList()
    private var voixFemmes: List<Voice> = emptyList()
    private val aTrier = ArrayDeque<Voice>()
    private var triEnCours = false
    private val prefs by lazy { getSharedPreferences(DoublageService.PREFS, MODE_PRIVATE) }

    private fun source() = prefs.getString("source", "en") ?: "en"
    private fun cible() = prefs.getString("cible", "fr") ?: "fr"
    private fun locale(): Locale = Locale.forLanguageTag(Langues.codeVoix(cible()))

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
                    putExtra("source", source())
                    putExtra("cible", cible())
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
        fun titre(t: String) = col.addView(TextView(this).apply {
            text = t; textSize = 18f; setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(18), 0, dp(4))
        })
        fun petit(t: String) = TextView(this).apply { text = t; textSize = 14f; setPadding(0, dp(6), 0, 0) }

        col.addView(TextView(this).apply {
            text = "Doublage Direct"
            textSize = 26f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#1B7F4B"))
        })
        col.addView(TextView(this).apply {
            text = "Toutes les langues → traduction parlée, sans serveur"
            textSize = 14f
            setPadding(0, dp(2), 0, dp(10))
        })

        // ---------- Langues ----------
        titre("Langues")
        col.addView(petit("Langue de la vidéo"))
        spSource = Spinner(this); col.addView(spSource)
        btAuto = Button(this).apply {
            setOnClickListener { choisirLanguesAuto() }
            visibility = if (source() == Langues.AUTO) View.VISIBLE else View.GONE
        }
        col.addView(btAuto)
        majBtAuto()
        col.addView(petit("Langue de la traduction"))
        spCible = Spinner(this); col.addView(spCible)
        lierLangues()

        // ---------- V9 : tout préparer d'avance (écoute, traduction, voix) ----------
        etatLangues = TextView(this).apply { textSize = 14f; setPadding(0, dp(8), 0, 0) }
        col.addView(etatLangues)
        btPreparer = Button(this).apply {
            text = "Tout préparer maintenant (une seule fois, garde Internet)"
            setOnClickListener { toutPreparer() }
        }
        col.addView(btPreparer)
        btVoix = Button(this).apply {
            setOnClickListener {
                try { startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)) }
                catch (_: Exception) { startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
            }
        }
        col.addView(btVoix)

        // ---------- V9 : sous-titres YouTube ----------
        titre("Sous-titres YouTube")
        col.addView(CheckBox(this).apply {
            text = "Utiliser les sous-titres de la vidéo quand elle en a (texte exact, traduction pile au début de chaque réplique)"
            isChecked = prefs.getBoolean("yt_sous_titres", true)
            setOnCheckedChangeListener { _, b -> prefs.edit().putBoolean("yt_sous_titres", b).apply(); majNotif() }
        })
        btNotif = Button(this).apply {
            text = "Autoriser l'accès aux notifications (une seule fois)"
            setOnClickListener { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        }
        col.addView(btNotif)
        col.addView(TextView(this).apply {
            text = "Cet accès sert seulement à lire le lecteur YouTube (titre, chaîne, position), comme les commandes média du téléphone. " +
                "Si Android affiche « Paramètre restreint » : Paramètres > Applications > Doublage Direct > ⋮ (en haut) > " +
                "Autoriser les paramètres restreints, puis réessaie."
            textSize = 12f
        })

        cbBaisser = CheckBox(this).apply {
            text = "Baisser le son de YouTube pendant la traduction"
            isChecked = prefs.getBoolean("baisser", true)
            setOnCheckedChangeListener { _, b -> prefs.edit().putBoolean("baisser", b).apply() }
        }
        col.addView(cbBaisser)

        // ---------- Voix ----------
        titre("Voix")
        col.addView(petit("Voix homme (garçon = même voix, plus aiguë)"))
        spHomme = Spinner(this); col.addView(spHomme)
        col.addView(Button(this).apply {
            text = "Cette voix est une femme ↓"
            setOnClickListener { corriger(spHomme, voixHommes, Analyse.FEMME) }
        })
        col.addView(petit("Voix femme (fille = même voix, plus aiguë)"))
        spFemme = Spinner(this); col.addView(spFemme)
        col.addView(Button(this).apply {
            text = "Cette voix est un homme ↑"
            setOnClickListener { corriger(spFemme, voixFemmes, Analyse.HOMME) }
        })
        triVoix = TextView(this).apply { textSize = 12f; setTypeface(null, Typeface.ITALIC) }
        col.addView(triVoix)

        col.addView(CheckBox(this).apply {
            text = "Choisir la voix automatiquement selon le personnage du film"
            isChecked = prefs.getBoolean("auto", true)
            setOnCheckedChangeListener { _, b -> prefs.edit().putBoolean("auto", b).apply() }
        })
        col.addView(petit("Voix des enfants"))
        val choixEnfants = listOf(
            "Pas de voix d'enfant", "Auto (garçon ou fille, estimation)",
            "Toujours garçon", "Toujours fille"
        )
        col.addView(Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, choixEnfants)
            setSelection(prefs.getInt("enfants", Analyse.ENFANTS_AUTO))
            onItemSelectedListener = choix { prefs.edit().putInt("enfants", it).apply() }
        })

        // ---------- Réglages (aussi dans le bouton ⋮ par-dessus YouTube) ----------
        titre("Réglages")
        Reglages.ajouter(this, col, prefs, complet = true)
        col.addView(TextView(this).apply {
            text = "Les boutons de volume du téléphone règlent tout. Les curseurs de volume règlent l'équilibre entre la vidéo et la traduction."
            textSize = 12f
        })

        col.addView(CheckBox(this).apply {
            text = "Bouton ⋮ par-dessus YouTube pour régler sans arrêter"
            isChecked = prefs.getBoolean("bulle", true)
            setOnCheckedChangeListener { _, b -> prefs.edit().putBoolean("bulle", b).apply(); majBulle() }
        })
        btBulle = Button(this).apply {
            text = "Autoriser le bouton ⋮ (afficher par-dessus les autres applis)"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        }
        col.addView(btBulle)

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
            text = "Entendu"; textSize = 13f; setPadding(0, dp(20), 0, dp(4))
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

        essai = TextToSpeech(this) { st ->
            if (st != TextToSpeech.SUCCESS) return@TextToSpeech
            essai?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) { finTri(id, true) }
                override fun onStop(id: String?, interrupted: Boolean) { finTri(id, false) }
                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) { finTri(id, false) }
            })
            runOnUiThread { ttsPret = true; remplirVoix() }
        }
    }

    private fun choix(f: (Int) -> Unit) = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = f(pos)
        override fun onNothingSelected(p: AdapterView<*>?) {}
    }

    private fun lierLangues() {
        // Première ligne : détection automatique, puis les langues
        val src = listOf(Langues.trouver(Langues.AUTO)) + Langues.sources
        val noms = listOf("Détection automatique") + Langues.sources.map { Langues.etiquetteSource(it) }
        spSource.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, noms)
        spSource.setSelection(src.indexOfFirst { it.code == source() }.coerceAtLeast(0))
        spSource.onItemSelectedListener = choix {
            prefs.edit().putString("source", src[it].code).apply()
            btAuto.visibility = if (src[it].code == Langues.AUTO) View.VISIBLE else View.GONE
            majPreparation()
        }

        val tous = Langues.toutes
        spCible.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, tous.map { it.nom })
        spCible.setSelection(tous.indexOfFirst { it.code == cible() }.coerceAtLeast(0))
        spCible.onItemSelectedListener = choix {
            if (tous[it].code != cible()) {
                prefs.edit().putString("cible", tous[it].code).apply()
                remplirVoix()
                majPreparation()
            }
        }
    }

    private fun languesAuto() = Langues.choixAuto(prefs.getStringSet("auto_langues", null))

    private fun majBtAuto() {
        btAuto.text = "Langues à reconnaître : " + languesAuto().joinToString { it.nom } + " (changer)"
    }

    /** Détection automatique : l'utilisateur choisit les langues possibles de ses vidéos (4 au plus). */
    private fun choisirLanguesAuto() {
        val l = Langues.detectables
        val coche = BooleanArray(l.size) { i -> languesAuto().any { it.code == l[i].code } }
        android.app.AlertDialog.Builder(this)
            .setTitle("Langues possibles des vidéos (4 au plus)")
            .setMultiChoiceItems(l.map { Langues.etiquetteSource(it) }.toTypedArray(), coche) { d, i, oui ->
                if (oui && coche.count { it } > 4) {
                    coche[i] = false
                    (d as android.app.AlertDialog).listView.setItemChecked(i, false)
                    statut.text = "4 langues au plus : chaque langue en plus fait travailler le téléphone."
                } else coche[i] = oui
            }
            .setPositiveButton("OK") { _, _ ->
                val choix = l.filterIndexed { i, _ -> coche[i] }.map { it.code }.toSet()
                prefs.edit().putStringSet("auto_langues", choix.ifEmpty { setOf("en") }).apply()
                majBtAuto()
                majPreparation()
                statut.text = "Les langues pas encore téléchargées le seront au prochain Démarrer (garde Internet)."
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    override fun onDestroy() {
        essai?.shutdown()
        volEssai?.liberer()
        super.onDestroy()
    }

    // ---------- Listes de voix : hommes à part, femmes à part ----------

    /** Genre d'une voix : correction à la main, sinon mesure de sa hauteur, sinon null (pas encore triée). */
    private fun genreVoix(v: Voice): Int? {
        val corr = prefs.getInt("gv_${v.name}", -1)
        if (corr >= 0) return corr
        val f0 = prefs.getFloat("f0v_${v.name}", 0f)
        return Analyse.genreVoix(if (f0 > 0f) f0 else null)
    }

    private fun nomAffiche(v: Voice, i: Int): String {
        val pays = v.locale.displayCountry.ifBlank { v.locale.country }
        val net = if (v.isNetworkConnectionRequired) " (Internet)" else ""
        val doute = if (genreVoix(v) == null) " (?)" else ""
        return "Voix ${i + 1} - $pays - ${v.name}$net$doute"
    }

    private fun remplirVoix() {
        val t = essai ?: return
        val l = cible()
        val lang = locale().language
        voixToutes = (t.voices ?: emptySet())
            .filter { it.locale.language == lang && it.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true }
            .sortedWith(compareBy({ it.isNetworkConnectionRequired }, { it.name }))
        // Une voix pas encore triée apparaît dans les deux listes
        voixHommes = voixToutes.filter { genreVoix(it) != Analyse.FEMME }
        voixFemmes = voixToutes.filter { genreVoix(it) != Analyse.HOMME }

        fun lier(sp: Spinner, liste: List<Voice>, cle: String) {
            val noms = listOf("Par défaut") + liste.mapIndexed { i, v -> nomAffiche(v, i) }
            sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, noms)
            val sel = liste.indexOfFirst { it.name == prefs.getString(cle, null) }
            sp.setSelection(if (sel >= 0) sel + 1 else 0)
            sp.onItemSelectedListener = choix { pos ->
                prefs.edit().putString(cle, if (pos == 0) null else liste[pos - 1].name).apply()
            }
        }
        lier(spHomme, voixHommes, "voix_homme_$l")
        lier(spFemme, voixFemmes, "voix_femme_$l")
        if (voixToutes.isEmpty()) statut.text =
            "Aucune voix ${Langues.trouver(l).nom} installée : Paramètres > Synthèse vocale > Google > Installer les données vocales."
        lancerTri()
        majPreparation()
    }

    /** La voix choisie est mal classée : on la déplace dans l'autre liste. */
    private fun corriger(sp: Spinner, liste: List<Voice>, nouveau: Int) {
        val pos = sp.selectedItemPosition
        if (pos <= 0) { statut.text = "Choisis d'abord une voix dans la liste."; return }
        val v = liste[pos - 1]
        val l = cible()
        val cleAvant = if (nouveau == Analyse.FEMME) "voix_homme_$l" else "voix_femme_$l"
        prefs.edit().putInt("gv_${v.name}", nouveau).remove(cleAvant).apply()
        statut.text = "${v.name} déplacée dans les voix ${if (nouveau == Analyse.FEMME) "femme" else "homme"}."
        remplirVoix()
    }

    /** Chaque voix parle une fois en silence (dans un fichier) : on mesure si elle est grave ou aiguë. */
    private fun lancerTri() {
        aTrier.clear()
        aTrier.addAll(voixToutes.filter { prefs.getFloat("f0v_${it.name}", 0f) == 0f && prefs.getInt("gv_${it.name}", -1) < 0 })
        if (!triEnCours) trierSuivante()
    }

    private fun fichierTri() = File(cacheDir, "tri_voix.wav")

    private fun trierSuivante() {
        val t = essai ?: return
        val v = aTrier.removeFirstOrNull()
        if (v == null) {
            if (triEnCours) { triEnCours = false; triVoix.text = "Voix triées : hommes et femmes séparés."; remplirVoix() }
            return
        }
        triEnCours = true
        triVoix.text = "Tri des voix homme / femme... (${aTrier.size + 1} restantes)"
        t.voice = v
        t.setPitch(1f); t.setSpeechRate(1f)
        val r = t.synthesizeToFile("1 2 3 4 5 6 7 8 9 10", Bundle(), fichierTri(), "tri:${v.name}")
        if (r != TextToSpeech.SUCCESS) { prefs.edit().putFloat("f0v_${v.name}", -1f).apply(); trierSuivante() }
    }

    private fun finTri(id: String?, ok: Boolean) {
        if (id == null || !id.startsWith("tri:")) return
        val nom = id.removePrefix("tri:")
        val f0 = if (ok) try { Analyse.hauteurWav(fichierTri().readBytes()) } catch (_: Exception) { null } else null
        prefs.edit().putFloat("f0v_$nom", f0 ?: -1f).apply()
        runOnUiThread { trierSuivante() }
    }

    private fun tester() {
        val t = essai ?: return
        val l = cible()
        t.setSpeechRate(prefs.getFloat("vitesse", 1.2f))
        val phrases = when (l) {
            "ar" -> listOf("مرحبا، هذا صوت الرجل.", "وهذا صوت المرأة.", "وهذا صوت الولد.", "وهذا صوت البنت.")
            "fr" -> listOf("Bonjour, voici la voix d'homme.", "Et voici la voix de femme.", "Voici le garçon.", "Et voici la fille.")
            else -> listOf("1 2 3", "4 5 6", "7 8 9", "10")
        }
        val h = prefs.getFloat("hauteur", 1.0f)
        listOf(Analyse.HOMME, Analyse.FEMME, Analyse.GARCON, Analyse.FILLE).forEachIndexed { i, g ->
            val cle = if (Analyse.base(g) == Analyse.HOMME) "voix_homme_$l" else "voix_femme_$l"
            val v = voixToutes.firstOrNull { it.name == prefs.getString(cle, null) }
            if (v != null) t.voice = v else t.language = locale()
            t.setPitch(h * Analyse.montee(g))
            val vol = (volEssai ?: Volume(this).also { volEssai = it }).params(prefs.getInt("volume", 100))
            t.speak(phrases[i], if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, vol, "t$i")
        }
    }

    // ---------- V9 : préparation des langues ----------

    /** Langues à écouter : celle choisie, ou celles de la détection automatique. */
    private fun languesEcoute(): List<Langue> =
        if (source() == Langues.AUTO) languesAuto() else listOf(Langues.trouver(source()))

    private fun ecoutePrete(l: Langue) = l.code == Langues.VOSK_INCLUS || Modeles.pret(this, l) != null

    private fun voixPrete() = voixToutes.isNotEmpty()

    private fun majNotif() {
        if (!::btNotif.isInitialized) return
        btNotif.visibility = if (prefs.getBoolean("yt_sous_titres", true) && !SuiviLecteur.autorise(this)) View.VISIBLE else View.GONE
    }

    /** Affiche ce qui est prêt et ce qui manque (sans rien télécharger). */
    private fun majPreparation() {
        if (!::etatLangues.isInitialized || preparation) return
        val c = cible()
        val lignes = ArrayList<String>()
        for (l in languesEcoute()) lignes.add(if (ecoutePrete(l)) "✓ Écoute ${l.nom}" else "✗ Écoute ${l.nom} (${l.tailleMo} Mo à télécharger)")
        lignes.add(if (voixPrete()) "✓ Voix ${Langues.nom(c)}" else "✗ Voix ${Langues.nom(c)} pas installée")
        val sources = languesEcoute().map { it.code }.filter { it != c }
        etatLangues.text = lignes.joinToString("\n") + "\n… Traduction : vérification"
        btVoix.text = "Installer la voix ${Langues.nom(c)}"
        btVoix.visibility = if (voixPrete()) View.GONE else View.VISIBLE
        com.google.mlkit.common.model.RemoteModelManager.getInstance()
            .getDownloadedModels(com.google.mlkit.nl.translate.TranslateRemoteModel::class.java)
            .addOnSuccessListener { modeles ->
                if (preparation) return@addOnSuccessListener
                val la = modeles.map { it.language }.toSet() + "en"
                val manque = (sources + c).distinct().filter { it !in la }
                val tr = if (sources.isEmpty()) "✓ Traduction : rien à traduire"
                    else if (manque.isEmpty()) "✓ Traduction ${sources.joinToString { Langues.nom(it) }} → ${Langues.nom(c)}"
                    else "✗ Traduction ${sources.joinToString { Langues.nom(it) }} → ${Langues.nom(c)} (environ ${manque.size * 30} Mo)"
                etatLangues.text = lignes.joinToString("\n") + "\n" + tr
                val tout = languesEcoute().all { ecoutePrete(it) } && manque.isEmpty()
                btPreparer.visibility = if (tout) View.GONE else View.VISIBLE
            }
    }

    /** Télécharge d'avance l'écoute et la traduction : au moment de regarder la vidéo, tout part tout de suite. */
    private fun toutPreparer() {
        if (preparation) return
        preparation = true
        btPreparer.isEnabled = false
        val c = cible()
        val langues = languesEcoute()
        fun dire(t: String) = runOnUiThread { etatLangues.text = t }
        Thread {
            var erreur: String? = null
            for (l in langues) {
                if (ecoutePrete(l)) continue
                try {
                    Modeles.telecharger(this, l) { p -> dire("Écoute ${l.nom} : $p % (${l.tailleMo} Mo)") }
                } catch (e: Exception) { erreur = "Écoute ${l.nom} : ${e.message}" }
            }
            for (de in langues.map { it.code }.filter { it != c }) {
                val a = com.google.mlkit.nl.translate.TranslateLanguage.fromLanguageTag(de) ?: continue
                val b = com.google.mlkit.nl.translate.TranslateLanguage.fromLanguageTag(c) ?: continue
                dire("Traduction ${Langues.nom(de)} → ${Langues.nom(c)} : téléchargement...")
                val t = com.google.mlkit.nl.translate.Translation.getClient(
                    com.google.mlkit.nl.translate.TranslatorOptions.Builder().setSourceLanguage(a).setTargetLanguage(b).build())
                try {
                    com.google.android.gms.tasks.Tasks.await(
                        t.downloadModelIfNeeded(com.google.mlkit.common.model.DownloadConditions.Builder().build()))
                } catch (e: Exception) { erreur = "Traduction : ${e.message}" } finally { t.close() }
            }
            runOnUiThread {
                preparation = false
                btPreparer.isEnabled = true
                majPreparation()
                statut.text = erreur?.let { "Préparation incomplète ($it). Vérifie Internet et réessaie." }
                    ?: if (voixPrete()) "Tout est prêt. Lance ta vidéo et appuie sur Démarrer."
                       else "Écoute et traduction prêtes. Il reste à installer la voix ${Langues.nom(c)}."
            }
        }.start()
    }

    // ---------- Bouton ⋮ ----------

    private fun majBulle() {
        btBulle.visibility = if (prefs.getBoolean("bulle", true) && !Settings.canDrawOverlays(this)) View.VISIBLE else View.GONE
    }

    override fun onResume() {
        super.onResume()
        DoublageService.ecouteur = { rafraichir() }
        rafraichir()
        majBulle()
        majNotif()
        if (ttsPret) remplirVoix() else majPreparation()
    }

    override fun onPause() {
        DoublageService.ecouteur = null
        super.onPause()
    }

    private fun rafraichir() {
        statut.text = DoublageService.dStatut.ifBlank { "Choisis les langues, puis Démarrer." }
        original.text = DoublageService.dOriginal
        traduction.text = DoublageService.dTraduction
    }

    private fun demarrer() {
        if (DoublageService.enMarche) {
            statut.text = "Déjà en marche. Arrête d'abord pour changer de langue."
            return
        }
        if (source() == cible()) {
            statut.text = "Choisis une langue de traduction différente de celle de la vidéo."
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
