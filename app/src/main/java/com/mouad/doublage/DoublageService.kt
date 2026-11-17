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
import android.media.AudioFocusRequest
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
    }

    private val principal = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var record: AudioRecord? = null
    private var model: Model? = null
    private var traducteur: Translator? = null
    private var tts: TextToSpeech? = null
    private var fil: Thread? = null
    @Volatile private var actif = false
    private val enAttente = AtomicInteger(0)
    private lateinit var audio: AudioManager
    private var focus: AudioFocusRequest? = null
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
                override fun onDone(id: String?) { finParole() }
                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) { finParole() }
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

                if (reco.acceptWaveForm(buf, n)) {
                    traiter(texte(reco.result, "text"))
                } else {
                    // Phrase trop longue sans pause : on coupe pour ne pas prendre de retard
                    val partiel = texte(reco.partialResult, "partial")
                    if (partiel.split(" ").size >= 16) {
                        traiter(texte(reco.finalResult, "text"))
                        reco.reset()
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

    private fun traiter(t: String) {
        if (t.length < 3 || t == "the" || t == "huh") return
        dOriginal = t
        signaler()
        principal.post {
            traducteur?.translate(t)?.addOnSuccessListener { tr ->
                dTraduction = tr
                notifier(tr)
                signaler()
                parler(tr)
            }
        }
    }

    private fun parler(t: String) {
        val enRetard = enAttente.get() >= 2
        if (enRetard) enAttente.set(0)
        enAttente.incrementAndGet()
        if (baisser) prendreFocus()
        tts?.speak(t, if (enRetard) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "u" + System.nanoTime())
    }

    private fun finParole() {
        if (enAttente.decrementAndGet() <= 0) {
            enAttente.set(0)
            principal.post { rendreFocus() }
        }
    }

    private fun attributsParole() = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private fun prendreFocus() {
        if (focus != null) return
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributsParole())
            .build()
        audio.requestAudioFocus(focus!!)
    }

    private fun rendreFocus() {
        focus?.let { audio.abandonAudioFocusRequest(it) }
        focus = null
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
        traducteur?.close()
        model?.close()
        if (::audio.isInitialized) rendreFocus()
        dStatut = "Arrêté."
        signaler()
        super.onDestroy()
    }
}
