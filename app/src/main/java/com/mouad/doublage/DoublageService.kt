package com.mouad.doublage

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService
import android.content.SharedPreferences
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class DoublageService : Service() {

    companion object {
        const val CANAL = "doublage"
        const val TAUX = 16000
        @Volatile var enMarche = false
        @Volatile var dStatut = ""
        @Volatile var dOriginal = ""
        @Volatile var dTraduction = ""
        var ecouteur: (() -> Unit)? = null
        const val PREFS = "reglages"
    }

    private val principal = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var record: AudioRecord? = null
    private var tts: TextToSpeech? = null
    private var volume: Volume? = null
    private var fil: Thread? = null
    @Volatile private var actif = false
    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }
    private lateinit var audio: AudioManager
    private var baisser = true
    private var choixSource = "en"          // ce que l'utilisateur a choisi (peut être « auto »)
    @Volatile private var source = "en"     // langue réellement écoutée
    private var cible = "fr"
    private var bulle: Bulle? = null
    private var sousTitres: SousTitres? = null
    private var compteur = 0
    private var affichee: SousTitre.Entree? = null

    // Modèles d'écoute chargés (un seul, ou plusieurs en détection automatique)
    private val modeles = ConcurrentHashMap<String, Model>()
    // Traducteurs prêts, par langue de départ
    private val traducteurs = ConcurrentHashMap<String, Translator>()
    @Volatile private var traducteur: Translator? = null

    // Chaque nouvelle vidéo (ou « Recommencer ») change de génération : les vieilles phrases sont jetées
    private val generation = AtomicInteger(0)
    @Volatile private var aRemettre = false
    @Volatile private var aDetecter = false

    // File des phrases à dire, gérée par nous (et non par la synthèse) :
    // les réglages sont appliqués au moment de parler, et les phrases trop vieilles sont sautées.
    private class Phrase(val texte: String, val f0: Float, val energie: Float, val debit: Float,
                         val t0: Long, val gen: Int, val entree: SousTitre.Entree? = null,
                         val vitesseMin: Float = 0f)
    private val file = ArrayDeque<Phrase>()
    private var parle = false
    private var idEnCours: String? = null

    // ----- Sous-titres YouTube (V9) -----
    private var suivi: SuiviLecteur? = null
    @Volatile private var repliques: List<Pistes.Replique>? = null
    @Volatile private var trads: List<String> = emptyList()
    @Volatile private var origines: List<String>? = null
    private var prochaine = 0
    private var dernierePos = -1L
    private var dernierTic = 0L
    private val recherche = AtomicInteger(0)
    // Hauteurs de voix récentes (moment, hauteur, énergie) : pour choisir la voix au début de chaque réplique
    private val recentes = ArrayDeque<Triple<Long, Float, Float>>()

    // Voix normale de chaque personnage (homme, femme, garçon, fille) : pour mesurer les émotions
    private val f0Base = FloatArray(4)
    private val energieBase = FloatArray(4)
    private val debitBase = FloatArray(4)

    private val changementReglage = SharedPreferences.OnSharedPreferenceChangeListener { _, cle ->
        when (cle) {
            "volume" -> volume?.params(prefs.getInt("volume", 100), compensationMb)
            "volume_original" -> if (indexAvant >= 0) { rendreFocus(); if (parle) prendreFocus() }
            "essai" -> essaiReglage()
            "recommencer" -> recommencer(redetecter = true)
            "sous_titres" -> majSousTitres()
            "st_taille", "st_position" -> sousTitres?.reglages()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == "STOP") {
            stopSelf()
            return START_NOT_STICKY
        }
        if (enMarche) return START_NOT_STICKY

        audio = getSystemService(AudioManager::class.java)
        creerCanal()
        startForeground(1, notif("Préparation..."), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        val code = intent.getIntExtra("code", 0)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra("data", Intent::class.java)
        else
            @Suppress("DEPRECATION") intent.getParcelableExtra("data")
        choixSource = intent.getStringExtra("source") ?: "en"
        source = if (choixSource == Langues.AUTO) "" else choixSource
        cible = intent.getStringExtra("cible") ?: "fr"
        baisser = intent.getBooleanExtra("baisser", true)

        if (data == null) { stopSelf(); return START_NOT_STICKY }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        projection = mpm.getMediaProjection(code, data)
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, principal)

        enMarche = true
        dOriginal = ""
        dTraduction = ""
        prefs.registerOnSharedPreferenceChangeListener(changementReglage)
        preparer()
        return START_NOT_STICKY
    }

    // ---------- Préparation : voix, traducteur, reconnaissance ----------

    private fun preparer() {
        tts = TextToSpeech(this) { s ->
            if (s != TextToSpeech.SUCCESS) {
                statut("Synthèse vocale indisponible sur ce téléphone.")
                return@TextToSpeech
            }
            val r = tts?.setLanguage(locale())
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                statut("Voix ${Langues.trouver(cible).nom} absente : installe-la dans Paramètres > Synthèse vocale (Google).")
            }
            tts?.setAudioAttributes(attributsParole())
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) { finParole(id) }
                override fun onStop(id: String?, interrupted: Boolean) { finParole(id) }
                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) { finParole(id) }
            })
        }

        demarrerSuivi()
        if (choixSource == Langues.AUTO) {
            chargerModeles(Langues.choixAuto(prefs.getStringSet("auto_langues", null)))
        } else {
            // V9 : on capte le son tout de suite. Avec des sous-titres YouTube, pas besoin d'attendre
            // le modèle d'écoute ni le traducteur (ils se préparent en parallèle pour le cas sans sous-titres).
            demarrerCapture()
            preparerTraducteur(source) {}
            chargerModeles(listOf(Langues.trouver(source)))
        }
    }

    private fun locale(): Locale = Locale.forLanguageTag(Langues.codeVoix(cible))

    /** Traducteur source → cible (téléchargé la première fois). ok() appelé quand il est prêt. */
    private fun preparerTraducteur(de: String, ok: () -> Unit) {
        if (de == cible) { traducteur = null; ok(); return }
        traducteurs[de]?.let { traducteur = it; ok(); return }
        val a = TranslateLanguage.fromLanguageTag(de)
        val b = TranslateLanguage.fromLanguageTag(cible)
        if (a == null || b == null) { statut("Traduction ${Langues.trouver(de).nom} → ${Langues.trouver(cible).nom} non prise en charge."); return }
        statut("Préparation du traducteur ${Langues.trouver(de).nom} → ${Langues.trouver(cible).nom} (la première fois, il faut Internet)...")
        val t = Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(a).setTargetLanguage(b).build())
        t.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener { traducteurs[de] = t; traducteur = t; ok() }
            .addOnFailureListener { statut("Erreur traducteur : ${it.message}. Vérifie Internet et réessaie.") }
    }

    /** Charge (et télécharge au besoin) les modèles d'écoute, puis démarre la capture. */
    private fun chargerModeles(langues: List<Langue>) {
        Thread {
            for (l in langues) {
                if (!enMarche) return@Thread
                if (modeles.containsKey(l.code)) continue
                try {
                    val dossier = if (l.code == Langues.VOSK_INCLUS) {
                        statut("Chargement de l'écoute ${l.nom}...")
                        java.io.File(StorageService.sync(this, "model-en-us", "model"))
                    } else {
                        Modeles.telecharger(this, l) { p ->
                            statut("Téléchargement de l'écoute ${l.nom} : $p % (une seule fois, garde Internet)")
                        }
                    }
                    statut("Chargement de l'écoute ${l.nom}...")
                    modeles[l.code] = Model(dossier.absolutePath)
                    if (l.code == source) aRemettre = true
                } catch (e: Exception) {
                    statut("Erreur modèle ${l.nom} : ${e.message}. Vérifie Internet et réessaie.")
                }
            }
            if (choixSource != Langues.AUTO) {
                if (modeles.isNotEmpty() && repliques == null) statut("J'écoute (${Langues.trouver(source).nom}). Lance ta vidéo YouTube.")
                return@Thread
            }
            if (modeles.isEmpty()) return@Thread
            if (choixSource == Langues.AUTO) {
                if (modeles.size == 1) {
                    val seule = modeles.keys.first()
                    principal.post { preparerTraducteur(seule) { source = seule; principal.post { demarrerCapture() } } }
                    return@Thread
                }
                aDetecter = true
            }
            principal.post { if (enMarche) demarrerCapture() }
        }.start()
    }

    // ---------- Capture du son de YouTube ----------

    @SuppressLint("MissingPermission")
    private fun demarrerCapture() {
        if (record != null) return
        val p = projection ?: return
        val config = AudioPlaybackCaptureConfiguration.Builder(p)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(TAUX)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val min = AudioRecord.getMinBufferSize(TAUX, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(min, 8192) * 2)
                .setAudioPlaybackCaptureConfig(config)
                .build()
        } catch (e: Exception) {
            statut("Impossible de capter le son : ${e.message}")
            return
        }
        record = rec
        rec.startRecording()
        actif = true
        statut(if (aDetecter) "J'écoute pour trouver la langue de la vidéo (${modeles.keys.joinToString { Langues.trouver(it).nom }})..."
               else if (modeles.containsKey(source)) "J'écoute (${Langues.trouver(source).nom}). Lance ta vidéo YouTube."
               else dStatut.ifBlank { "Préparation de l'écoute ${Langues.trouver(source).nom}..." })
        if (prefs.getBoolean("bulle", true)) {
            bulle = Bulle(this, prefs) { stopSelf() }.also { it.montrer() }
        }
        majSousTitres()
        fil = Thread { boucle(rec) }.apply { start() }
    }

    private fun nouveauReco(m: Model) = Recognizer(m, TAUX.toFloat()).apply { setWords(true) }

    /** Boucle d'écoute (fil séparé). */
    private fun boucle(rec: AudioRecord) {
        val buf = ByteArray(4096)
        var reco: Recognizer? = if (!aDetecter) modeles[source]?.let { nouveauReco(it) } else null
        val detection = HashMap<String, Recognizer>()
        val scores = HashMap<String, Analyse.Score>()
        var debutDetection = 0L
        var dernierSon = System.currentTimeMillis()
        var averti = false
        var debutPhrase = 0L
        val hauteurs = ArrayList<Float>()
        val energies = ArrayList<Float>()
        var debutVoix = 0L
        var finVoix = System.currentTimeMillis()

        fun vider() { hauteurs.clear(); energies.clear(); debutPhrase = 0L; debutVoix = 0L }
        fun fin(json: String) {
            val (t, conf) = lire(json)
            val duree = if (debutVoix > 0) System.currentTimeMillis() - debutVoix else 0L
            traiter(t, conf, Analyse.mediane(hauteurs), Analyse.mediane(energies), duree)
            vider()
        }
        fun finirDetection(code: String) {
            detection.values.forEach { it.close() }; detection.clear(); scores.clear()
            aDetecter = false
            statut("Langue de la vidéo : ${Langues.trouver(code).nom}. Préparation...")
            principal.post {
                preparerTraducteur(code) {
                    source = code
                    aRemettre = true
                    statut(if (code == cible) "La vidéo est déjà en ${Langues.trouver(cible).nom} : rien à traduire."
                           else "Langue détectée : ${Langues.trouver(code).nom}. J'écoute.")
                }
            }
        }

        while (actif) {
            val n = rec.read(buf, 0, buf.size)
            if (n <= 0) continue
            val maintenant = System.currentTimeMillis()

            // Nouvelle vidéo ou « Recommencer » : on oublie tout ce qui était en cours
            if (aRemettre) {
                aRemettre = false
                reco?.close(); reco = null
                if (!aDetecter) modeles[source]?.let { reco = nouveauReco(it) }
                vider()
            }
            if (aDetecter && detection.isEmpty()) {
                reco?.close(); reco = null
                modeles.forEach { (c, m) -> detection[c] = nouveauReco(m); scores[c] = Analyse.Score() }
                debutDetection = 0L
            }

            val son = aDuSon(buf, n)
            if (son) {
                // Long silence puis du son : probablement une autre vidéo.
                // Un film a des pauses : avant, une pause de 4 s vidait tout et relançait la recherche
                // de la langue (jusqu'à 15 s sans traduction). Maintenant : 8 s pour vider, 15 s pour la langue.
                val apres = Analyse.apresSilence(maintenant - dernierSon, prefs.getInt("silence_nouvelle", 8),
                    choixSource == Langues.AUTO && prefs.getBoolean("auto_chaque_video", true))
                if (apres != Analyse.SILENCE_RIEN) nouvelleVideo(redetecter = apres == Analyse.SILENCE_CHERCHER_LANGUE)
                dernierSon = maintenant
                if (averti) { averti = false; statut("J'écoute.") }
            } else if (!averti && maintenant - dernierSon > 20000) {
                averti = true
                statut("Aucun son capté depuis 20 s. Vidéo en pause, ou l'app bloque la capture.")
            }

            // ----- Détection de la langue : toutes les écoutes en parallèle -----
            if (aDetecter) {
                if (detection.isEmpty()) continue
                if (son && debutDetection == 0L) debutDetection = maintenant
                for ((c, r) in detection) {
                    if (r.acceptWaveForm(buf, n)) scores[c]?.ajouter(confiances(r.result))
                }
                val delai = debutDetection > 0 && maintenant - debutDetection > 15000
                Analyse.choisirLangue(scores, delai)?.let { finirDetection(it) }
                continue
            }

            // Hauteur de la voix (pour choisir voix homme / femme / enfant)
            Analyse.hauteur(buf, n, TAUX)?.let {
                val en = Analyse.energie(buf, n)
                if (hauteurs.size < 400) { hauteurs.add(it); energies.add(en) }
                if (debutVoix == 0L) debutVoix = maintenant
                finVoix = maintenant
                synchronized(recentes) {
                    recentes.addLast(Triple(maintenant, it, en))
                    while (recentes.isNotEmpty() && (recentes.size > 300 || maintenant - recentes.first().first > 4000)) recentes.removeFirst()
                }
            }

            val r = reco ?: continue

            if (r.acceptWaveForm(buf, n)) {
                fin(r.result)
            } else {
                // On coupe vite pour coller à la vidéo : trop de mots ou trop longtemps
                val partiel = texte(r.partialResult, "partial")
                if (partiel.isEmpty()) continue
                if (debutPhrase == 0L) debutPhrase = maintenant
                val mots = partiel.split(" ").size
                val maxMots = prefs.getInt("mots_max", 10)
                if (Analyse.couper(mots, maintenant - debutPhrase, maxMots)) {
                    fin(r.finalResult)
                    r.reset()
                }
            }
        }
        reco?.close()
        detection.values.forEach { it.close() }
    }

    /** Texte et confiance moyenne d'un résultat Vosk. */
    private fun lire(json: String): Pair<String, Float> = try {
        val o = JSONObject(json)
        o.optString("text").trim() to Analyse.confianceMoyenne(confiances(json))
    } catch (e: Exception) { "" to -1f }

    private fun confiances(json: String): List<Float> = try {
        val a = JSONObject(json).optJSONArray("result")
        if (a == null) emptyList() else (0 until a.length()).map { a.getJSONObject(it).optDouble("conf", 0.0).toFloat() }
    } catch (e: Exception) { emptyList() }

    private fun aDuSon(buf: ByteArray, n: Int): Boolean {
        var i = 0
        while (i + 1 < n) {
            val v = (buf[i].toInt() and 0xFF) or (buf[i + 1].toInt() shl 8)
            if (v > 200 || v < -200) return true
            i += 2
        }
        return false
    }

    private fun texte(json: String, cle: String): String =
        try { JSONObject(json).optString(cle).trim() } catch (e: Exception) { "" }

    // ---------- Nouvelle vidéo / Recommencer ----------

    /** Appelé du fil d'écoute quand le son revient après un long silence. */
    private fun nouvelleVideo(redetecter: Boolean) {
        generation.incrementAndGet()
        aRemettre = true
        if (redetecter && modeles.size > 1) aDetecter = true
        principal.post { viderParole() }
    }

    /** Bouton « Recommencer » du panneau ⋮ : on jette tout et on repart à zéro. */
    private fun recommencer(redetecter: Boolean) {
        generation.incrementAndGet()
        aRemettre = true
        if (redetecter && choixSource == Langues.AUTO && modeles.size > 1) {
            aDetecter = true
            statut("Je cherche la langue de la nouvelle vidéo...")
        } else statut("Recommencé. J'écoute.")
        dOriginal = ""; dTraduction = ""
        viderParole()
        f0Base.fill(0f); energieBase.fill(0f); debitBase.fill(0f)
    }

    private fun viderParole() {
        file.forEach { p -> p.entree?.let { etat(it, SousTitre.Etat.NOUVELLE) } }
        file.clear()
        if (parle) tts?.stop()
        parle = false
        idEnCours = null
        rendreFocus()
    }

    // ---------- Traduction et parole ----------

    private fun traiter(t: String, conf: Float, f0: Float, energie: Float, dureeMs: Long) {
        if (Langues.parasite(source, t)) return
        if (sousTitresCales()) return
        val entree = SousTitre.Entree(++compteur, t, conf)
        // Phrase mal entendue (musique, bruit) : la traduire donnerait n'importe quoi
        if (Analyse.malEntendue(conf, prefs.getInt("filtre", 30))) {
            dOriginal = "($t)"
            signaler()
            etat(entree, SousTitre.Etat.FILTREE)
            return
        }
        dOriginal = t
        signaler()
        val tr = traducteur
        if (tr == null) { etat(entree, if (source == cible) SousTitre.Etat.MEME_LANGUE else SousTitre.Etat.ERREUR); return }
        etat(entree, SousTitre.Etat.TRADUCTION)
        val mots = t.split(" ").count { it.isNotBlank() }
        val debit = if (dureeMs > 300) mots * 1000f / dureeMs else 0f
        val gen = generation.get()
        val t0 = System.currentTimeMillis()
        principal.post {
            tr.translate(t).addOnSuccessListener { trad ->
                entree.traduction = trad
                if (trad.isBlank()) { etat(entree, SousTitre.Etat.ERREUR); return@addOnSuccessListener }
                if (gen != generation.get()) { etat(entree, SousTitre.Etat.NOUVELLE); return@addOnSuccessListener }
                dTraduction = trad
                notifier(trad)
                signaler()
                etat(entree, SousTitre.Etat.A_DIRE)
                file.addLast(Phrase(trad, f0, energie, debit, t0, gen, entree))
                // Trop de retard : on garde seulement les 2 dernières phrases
                while (file.size > 2) file.removeFirst().entree?.let { etat(it, SousTitre.Etat.FILE) }
                if (!parle) suivante()
            }.addOnFailureListener { etat(entree, SousTitre.Etat.ERREUR) }
        }
    }

    /** Dit la phrase suivante de la file, avec les réglages actuels. */
    private fun suivante() {
        val retardMax = prefs.getInt("retard_max", 10) * 1000L
        while (file.isNotEmpty()) {
            val p = file.removeFirst()
            if (p.gen != generation.get()) { p.entree?.let { etat(it, SousTitre.Etat.NOUVELLE) }; continue }
            if (Analyse.perimee(System.currentTimeMillis() - p.t0, retardMax)) { p.entree?.let { etat(it, SousTitre.Etat.RETARD) }; continue }
            if (dire(p.texte, p.f0, p.energie, p.debit, enRetard = file.size, vitesseMin = p.vitesseMin)) { p.entree?.let { etat(it, SousTitre.Etat.DITE) }; return }
            p.entree?.let { etat(it, SousTitre.Etat.ERREUR) }
        }
        parle = false
        rendreFocus()
    }

    private fun genre(f0: Float): Int = Analyse.genre(
        f0, prefs.getInt("seuil_femme", 165), prefs.getInt("seuil_enfant", 290),
        prefs.getInt("enfants", Analyse.ENFANTS_AUTO), prefs.getInt("seuil_fille", 330),
        prefs.getInt("defaut", Analyse.HOMME)
    )

    /** Parle tout de suite (la file est gérée par suivante()). */
    private fun dire(t: String, f0: Float, energie: Float, debit: Float, enRetard: Int, vitesseMin: Float = 0f): Boolean {
        val tt = tts ?: return false
        val g = if (prefs.getBoolean("auto", true)) genre(f0) else prefs.getInt("defaut", Analyse.HOMME)
        val nomVoix = prefs.getString(if (Analyse.base(g) == Analyse.HOMME) "voix_homme_$cible" else "voix_femme_$cible", null)
        val v = tt.voices?.firstOrNull { it.name == nomVoix }
        if (v != null) tt.voice = v else tt.language = locale()

        // Émotions : on compare la phrase à la voix normale de ce personnage
        val e = Analyse.emotion(f0, f0Base[g], energie, energieBase[g], debit, debitBase[g],
            prefs.getInt("emotions", 60) / 100f)
        f0Base[g] = Analyse.moyenne(f0Base[g], f0)
        energieBase[g] = Analyse.moyenne(energieBase[g], energie)
        debitBase[g] = Analyse.moyenne(debitBase[g], debit)

        // Vitesse : réglage de base x émotion + accélération si on prend du retard
        val base = prefs.getFloat("vitesse", 1.2f)
        tt.setSpeechRate(minOf(maxOf(base * e[2], vitesseMin) * (1f + 0.25f * enRetard), 2.5f))
        tt.setPitch(prefs.getFloat("hauteur", 1.0f) * Analyse.montee(g) * e[1])

        val id = "u" + System.nanoTime()
        if (baisser) prendreFocus()
        val pct = (prefs.getInt("volume", 100) * e[0]).toInt().coerceIn(10, 300)
        val vol = (volume ?: Volume(this).also { volume = it }).params(pct, compensationMb)
        idEnCours = id
        parle = true
        val ok = tt.speak(t, TextToSpeech.QUEUE_FLUSH, vol, id) == TextToSpeech.SUCCESS
        if (!ok) { idEnCours = null; parle = false; return false }
        // Sécurité : si la synthèse ne signale jamais la fin, on ne reste pas muet (et YouTube pas baissé) pour toujours
        principal.postDelayed({ if (idEnCours == id) { idEnCours = null; parle = false; suivante() } },
            Analyse.dureeMaxParole(t.length, base))
        return true
    }

    /** Un curseur du panneau ⋮ vient d'être lâché : on redit la dernière phrase avec le nouveau réglage. */
    private fun essaiReglage() {
        val t = dTraduction.ifBlank { if (cible == "ar") "تجربة الصوت" else if (cible == "fr") "Essai de la voix" else "1 2 3" }
        file.clear()
        generation.get().let { file.addLast(Phrase(t, 0f, 0f, 0f, System.currentTimeMillis(), it)) }
        suivante()
    }

    private fun finParole(id: String?) {
        principal.post {
            if (id != null && id == idEnCours) { idEnCours = null; parle = false; suivante() }
        }
    }

    private fun attributsParole() = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    // ---------- Volume de YouTube pendant la traduction ----------
    // On baisse le volume média du téléphone (YouTube) et on amplifie la traduction d'autant,
    // pour que seule la vidéo baisse. Volume remis comme avant quand la traduction se tait.
    private var indexAvant = -1
    private var compensationMb = 0

    private fun prendreFocus() {
        if (indexAvant >= 0) return
        // Sans amplification possible, baisser le volume média baisserait aussi la traduction
        if (volume?.amplificationOk == false) return
        val pct = prefs.getInt("volume_original", 40)
        if (pct >= 100) return
        val m = AudioManager.STREAM_MUSIC
        val base = audio.getStreamVolume(m)
        if (base <= 0) return
        val dbs = (0..base).map { i -> niveauDb(i, audio.getStreamMaxVolume(m)) }
        val cible = Analyse.indexCible(dbs, pct)
        if (cible >= base) return
        compensationMb = Analyse.compensationMb(dbs[base], dbs[cible])
        indexAvant = base
        try { audio.setStreamVolume(m, cible, 0) } catch (_: Exception) { indexAvant = -1; compensationMb = 0 }
    }

    private fun niveauDb(i: Int, max: Int): Float {
        val sortie = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }
        val type = listOf(
            android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET,
            android.media.AudioDeviceInfo.TYPE_USB_HEADSET
        ).firstOrNull { it in sortie } ?: android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        val db = try { audio.getStreamVolumeDb(AudioManager.STREAM_MUSIC, i, type) } catch (_: Exception) { Float.NaN }
        return if (db.isNaN()) Analyse.dbApprox(i, max) else db
    }

    private fun rendreFocus() {
        if (indexAvant >= 0) {
            try { audio.setStreamVolume(AudioManager.STREAM_MUSIC, indexAvant, 0) } catch (_: Exception) {}
        }
        indexAvant = -1
        compensationMb = 0
    }

    // ---------- Sous-titres YouTube : la traduction calée sur la position de la vidéo ----------

    private fun demarrerSuivi() {
        if (!prefs.getBoolean("yt_sous_titres", true)) return
        val s = SuiviLecteur(this, principal) { titre, chaine, duree -> videoChangee(titre, chaine, duree) }
        if (s.demarrer()) { suivi = s; principal.post(tic) }
    }

    /** Sous-titres chargés et position de la vidéo connue : c'est eux qui pilotent la traduction. */
    private fun sousTitresCales(): Boolean = repliques != null && suivi?.position() != null

    private fun videoChangee(titre: String, chaine: String, dureeMs: Long) {
        val n = recherche.incrementAndGet()
        repliques = null; origines = null; trads = emptyList()
        principal.post { viderParole() }
        statut("Recherche des sous-titres de « $titre »...")
        Thread {
            val res = try {
                val url = YouTubeSousTitres.trouverVideo(titre, chaine, dureeMs)
                if (url == null) null else YouTubeSousTitres.charger(url, source.ifBlank { null }, cible)
            } catch (e: Exception) { null }
            if (n != recherche.get()) return@Thread
            if (res == null) {
                statut("Pas de sous-titres pour cette vidéo : j'écoute et je traduis (${Langues.trouver(source.ifBlank { "en" }).nom}).")
                return@Thread
            }
            var lignes = res.repliques.map { it.texte }
            var orig: List<String>? = null
            if (res.langue != cible) {
                statut("Sous-titres trouvés : ${res.description}. Traduction de ${lignes.size} répliques...")
                val t = traduireTout(res.langue, lignes)
                if (t == null) {
                    statut("Sous-titres trouvés mais la traduction a échoué : j'écoute et je traduis.")
                    return@Thread
                }
                orig = lignes; lignes = t
            }
            if (n != recherche.get()) return@Thread
            principal.post {
                trads = lignes; origines = orig
                repliques = res.repliques
                prochaine = Pistes.indexA(res.repliques, suivi?.position() ?: 0L)
                dernierePos = -1L
                statut("Sous-titres : ${res.description}. Traduction calée sur la vidéo.")
            }
        }.start()
    }

    /** Traduit toutes les répliques d'un coup (hors du fil principal). null si impossible. */
    private fun traduireTout(de: String, lignes: List<String>): List<String>? {
        val a = TranslateLanguage.fromLanguageTag(de) ?: return null
        val b = TranslateLanguage.fromLanguageTag(cible) ?: return null
        val t = Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(a).setTargetLanguage(b).build())
        return try {
            com.google.android.gms.tasks.Tasks.await(t.downloadModelIfNeeded(DownloadConditions.Builder().build()))
            lignes.map { com.google.android.gms.tasks.Tasks.await(t.translate(it)) }
        } catch (_: Exception) { null } finally { t.close() }
    }

    /** Hauteur et énergie de la voix depuis un moment donné (ou les 1,5 dernières secondes). */
    private fun voixDepuis(t: Long): Pair<Float, Float> {
        val l = synchronized(recentes) { recentes.toList() }
        val maintenant = System.currentTimeMillis()
        val choix = l.filter { it.first >= t }.takeIf { it.size >= 3 } ?: l.filter { maintenant - it.first <= 1500 }
        return Analyse.mediane(choix.map { it.second }) to Analyse.mediane(choix.map { it.third })
    }

    /** Toutes les 100 ms : quelle réplique faut-il dire maintenant ? */
    private val tic: Runnable = object : Runnable {
        override fun run() {
            if (!enMarche) return
            principal.postDelayed(this, 100)
            val l = repliques ?: return
            val s = suivi ?: return
            val pos = s.position() ?: return
            val maintenant = System.currentTimeMillis()
            if (!s.enLecture()) {
                // Pause : on se tait tout de suite
                if (parle || file.isNotEmpty()) viderParole()
                dernierePos = pos; dernierTic = maintenant
                return
            }
            // Saut dans la vidéo (avance, recul) : on repart de la bonne réplique
            if (dernierePos >= 0 && kotlin.math.abs(pos - (dernierePos + (maintenant - dernierTic))) > 2500) {
                prochaine = Pistes.indexA(l, pos)
                viderParole()
            }
            dernierePos = pos; dernierTic = maintenant
            // On attend 250 ms après le début de la réplique pour entendre la voix (homme, femme, enfant)
            val (dire, suite) = Pistes.aDire(l, prochaine, pos, avance = -250)
            prochaine = suite
            for (i in dire) direReplique(l, i, pos, maintenant)
        }
    }

    private fun direReplique(l: List<Pistes.Replique>, i: Int, pos: Long, maintenant: Long) {
        val trad = trads.getOrNull(i)?.takeIf { it.isNotBlank() } ?: return
        val r = l[i]
        val (f0, energie) = voixDepuis(maintenant - (pos - r.debut))
        val orig = origines?.getOrNull(i) ?: "(sous-titres YouTube)"
        val entree = SousTitre.Entree(++compteur, orig, -1f).also { it.traduction = trad }
        dOriginal = orig; dTraduction = trad
        signaler()
        etat(entree, SousTitre.Etat.A_DIRE)
        file.addLast(Phrase(trad, f0, energie, 0f, maintenant, generation.get(), entree,
            Pistes.vitesseMin(trad, r.fin - r.debut)))
        while (file.size > 2) file.removeFirst().entree?.let { etat(it, SousTitre.Etat.FILE) }
        if (!parle) suivante()
    }

    // ---------- Sous-titres de contrôle ----------

    private fun majSousTitres() {
        val voulu = prefs.getBoolean("sous_titres", true) && actif
        if (voulu && sousTitres == null) sousTitres = SousTitres(this, prefs).also { it.montrer() }
        if (!voulu) { sousTitres?.cacher(); sousTitres = null }
    }

    /** Change l'état d'une phrase et met les sous-titres à jour (jamais une phrase plus vieille que celle affichée). */
    private fun etat(e: SousTitre.Entree, nouvel: SousTitre.Etat) {
        e.etat = nouvel
        principal.post {
            val st = sousTitres ?: return@post
            val a = affichee
            if (a != null && e.numero < a.numero) return@post
            affichee = e
            st.afficher(if (source.isBlank()) "?" else source, cible, e)
        }
    }

    // ---------- Notification et état ----------

    private fun statut(s: String) {
        dStatut = s
        notifier(s)
        signaler()
    }

    private fun signaler() {
        principal.post { ecouteur?.invoke(); bulle?.maj(dStatut, dOriginal, dTraduction) }
    }

    private fun creerCanal() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CANAL, "Doublage", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun notif(texte: String): Notification {
        val stop = PendingIntent.getService(
            this, 1, Intent(this, DoublageService::class.java).setAction("STOP"),
            PendingIntent.FLAG_IMMUTABLE
        )
        val ouvrir = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CANAL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Doublage Direct")
            .setContentText(texte)
            .setStyle(Notification.BigTextStyle().bigText(texte))
            .setContentIntent(ouvrir)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_media_pause),
                    "Arrêter", stop
                ).build()
            )
            .build()
    }

    private fun notifier(t: String) {
        if (!enMarche) return
        getSystemService(NotificationManager::class.java).notify(1, notif(t))
    }

    override fun onDestroy() {
        prefs.unregisterOnSharedPreferenceChangeListener(changementReglage)
        principal.removeCallbacks(tic)
        suivi?.arreter()
        suivi = null
        actif = false
        enMarche = false
        bulle?.cacher()
        bulle = null
        sousTitres?.cacher()
        sousTitres = null
        try { record?.stop() } catch (_: Exception) {}
        try { fil?.join(800) } catch (_: Exception) {}
        record?.release()
        record = null
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        tts?.stop()
        tts?.shutdown()
        volume?.liberer()
        traducteurs.values.forEach { it.close() }
        modeles.values.forEach { it.close() }
        if (::audio.isInitialized) rendreFocus()
        dStatut = "Arrêté."
        signaler()
        super.onDestroy()
    }
}
