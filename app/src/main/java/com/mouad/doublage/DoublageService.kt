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
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

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
    private var model: Model? = null
    private var traducteur: Translator? = null
    private var tts: TextToSpeech? = null
    private var volume: Volume? = null
    private var fil: Thread? = null
    @Volatile private var actif = false
    private val enCours = ConcurrentHashMap.newKeySet<String>()
    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }
    private lateinit var audio: AudioManager
    private var baisser = true
    private var langue = "fr"

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
        langue = intent.getStringExtra("langue") ?: "fr"
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
            val loc = if (langue == "ar") Locale("ar") else Locale.FRENCH
            val r = tts?.setLanguage(loc)
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                statut("Voix ${if (langue == "ar") "arabe" else "française"} absente : installe-la dans Paramètres > Synthèse vocale (Google).")
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

        statut("Téléchargement du traducteur (première fois seulement, il faut Internet)...")
        val opts = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(if (langue == "ar") TranslateLanguage.ARABIC else TranslateLanguage.FRENCH)
            .build()
        traducteur = Translation.getClient(opts).also { t ->
            t.downloadModelIfNeeded(DownloadConditions.Builder().build())
                .addOnSuccessListener { chargerVosk() }
                .addOnFailureListener { statut("Erreur traducteur : ${it.message}. Vérifie Internet et réessaie.") }
        }
    }

    private fun chargerVosk() {
        statut("Chargement de la reconnaissance vocale...")
        StorageService.unpack(this, "model-en-us", "model",
            { m -> model = m; demarrerCapture() },
            { e -> statut("Erreur modèle vocal : ${e.message}") })
    }

    // ---------- Capture du son de YouTube ----------

    @SuppressLint("MissingPermission")
    private fun demarrerCapture() {
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
        statut("J'écoute. Lance ta vidéo YouTube.")

        val m = model ?: return
        fil = Thread {
            val reco = Recognizer(m, TAUX.toFloat())
            val buf = ByteArray(4096)
            var dernierSon = System.currentTimeMillis()
            var averti = false
            var debutPhrase = 0L
            val hauteurs = ArrayList<Float>()
            while (actif) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue

                // Détection du silence total (app qui bloque la capture)
                if (aDuSon(buf, n)) {
                    dernierSon = System.currentTimeMillis()
                    if (averti) { averti = false; statut("J'écoute.") }
                } else if (!averti && System.currentTimeMillis() - dernierSon > 20000) {
                    averti = true
                    statut("Aucun son capté depuis 20 s. Vidéo en pause, ou l'app bloque la capture.")
                }

                // Hauteur de la voix (pour choisir voix homme / femme / enfant)
                Analyse.hauteur(buf, n, TAUX)?.let { if (hauteurs.size < 400) hauteurs.add(it) }

                if (reco.acceptWaveForm(buf, n)) {
                    traiter(texte(reco.result, "text"), Analyse.mediane(hauteurs))
                    hauteurs.clear(); debutPhrase = 0L
                } else {
                    // On coupe vite pour coller à la vidéo : trop de mots ou trop longtemps
                    val partiel = texte(reco.partialResult, "partial")
                    if (partiel.isEmpty()) continue
                    val maintenant = System.currentTimeMillis()
                    if (debutPhrase == 0L) debutPhrase = maintenant
                    val mots = partiel.split(" ").size
                    val maxMots = prefs.getInt("mots_max", 8)
                    if (Analyse.couper(mots, maintenant - debutPhrase, maxMots)) {
                        traiter(texte(reco.finalResult, "text"), Analyse.mediane(hauteurs))
                        reco.reset()
                        hauteurs.clear(); debutPhrase = 0L
                    }
                }
            }
            reco.close()
        }.apply { start() }
    }

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

    // ---------- Traduction et parole ----------

    private fun traiter(t: String, f0: Float) {
        if (t.length < 3 || t == "the" || t == "huh") return
        dOriginal = t
        signaler()
        principal.post {
            traducteur?.translate(t)?.addOnSuccessListener { tr ->
                dTraduction = tr
                notifier(tr)
                signaler()
                parler(tr, f0)
            }
        }
    }

    private fun genre(f0: Float): Int = Analyse.genre(
        f0, prefs.getInt("seuil_femme", 165), prefs.getInt("seuil_enfant", 290),
        prefs.getBoolean("enfant", true), prefs.getInt("defaut", 0)
    )

    private fun parler(t: String, f0: Float) {
        val tt = tts ?: return
        // Retard : s'il reste déjà 2 phrases à dire, on les saute pour rattraper la vidéo
        val enRetard = enCours.size >= 2
        if (enRetard) enCours.clear()

        val g = if (prefs.getBoolean("auto", true)) genre(f0) else prefs.getInt("defaut", 0)
        val nomVoix = prefs.getString(if (g == 0) "voix_homme_$langue" else "voix_femme_$langue", null)
        val v = tt.voices?.firstOrNull { it.name == nomVoix }
        if (v != null) tt.voice = v else tt.language = if (langue == "ar") Locale("ar") else Locale.FRENCH

        // Vitesse : réglage de base + accélération si on prend du retard
        val base = prefs.getFloat("vitesse", 1.2f)
        tt.setSpeechRate(minOf(base * (1f + 0.25f * enCours.size), 2.5f))
        val h = prefs.getFloat("hauteur", 1.0f)
        tt.setPitch(if (g == 2) h * 1.4f else h)

        val id = "u" + System.nanoTime()
        enCours.add(id)
        if (baisser) prendreFocus()
        val vol = (volume ?: Volume(this).also { volume = it }).params(prefs.getInt("volume", 100), compensationMb)
        tt.speak(t, if (enRetard) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, vol, id)
    }

    private fun finParole(id: String?) {
        if (id != null) enCours.remove(id)
        if (enCours.isEmpty()) principal.post { if (enCours.isEmpty()) rendreFocus() }
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

    // ---------- Notification et état ----------

    private fun statut(s: String) {
        dStatut = s
        notifier(s)
        signaler()
    }

    private fun signaler() {
        principal.post { ecouteur?.invoke() }
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
        actif = false
        enMarche = false
        try { record?.stop() } catch (_: Exception) {}
        try { fil?.join(800) } catch (_: Exception) {}
        record?.release()
        record = null
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        tts?.stop()
        tts?.shutdown()
        volume?.liberer()
        traducteur?.close()
        model?.close()
        if (::audio.isInitialized) rendreFocus()
        dStatut = "Arrêté."
        signaler()
        super.onDestroy()
    }
}
