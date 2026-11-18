package com.mouad.doublage

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/** Téléchargement (une seule fois) des modèles d'écoute Vosk des autres langues. */
object Modeles {

    private fun dossier(ctx: Context, l: Langue) = File(ctx.filesDir, "vosk/${l.code}")

    /** Dossier du modèle prêt, ou null s'il faut le télécharger. */
    fun pret(ctx: Context, l: Langue): File? {
        val d = dossier(ctx, l)
        if (!File(d, "ok").exists()) return null
        return trouverRacine(d)
    }

    /** Le zip contient un dossier : on cherche celui qui a le sous-dossier « am » ou « conf ». */
    private fun trouverRacine(d: File): File? {
        if (File(d, "am").isDirectory || File(d, "conf").isDirectory) return d
        return d.listFiles()?.filter { it.isDirectory }?.firstNotNullOfOrNull { trouverRacine(it) }
    }

    private val verrous = java.util.concurrent.ConcurrentHashMap<String, Any>()

    /**
     * À appeler hors du fil principal. progres(pourcent) pendant le téléchargement.
     * V9 : si la connexion coupe, on reprend où on était (au lieu de recommencer à 0 %),
     * et l'écran principal et le doublage ne téléchargent jamais la même langue en même temps.
     */
    fun telecharger(ctx: Context, l: Langue, progres: (Int) -> Unit): File =
        synchronized(verrous.getOrPut(l.code) { Any() }) { telechargerSeul(ctx, l, progres) }

    private fun telechargerSeul(ctx: Context, l: Langue, progres: (Int) -> Unit): File {
        pret(ctx, l)?.let { return it }
        val nom = l.vosk ?: throw IllegalArgumentException("Pas de modèle d'écoute pour ${l.nom}")
        val d = dossier(ctx, l)
        val zip = File(ctx.filesDir, "vosk/$nom.zip.part")
        zip.parentFile?.mkdirs()
        var essais = 0
        while (true) {
            try {
                if (morceau(URL("https://alphacephei.com/vosk/models/$nom.zip"), zip, progres)) break
                if (++essais >= 8) throw java.io.IOException("téléchargement incomplet")
            } catch (e: java.io.IOException) {
                if (++essais >= 8) throw java.io.IOException("connexion coupée ($essais essais) : ${e.message}")
                Thread.sleep(2000L * essais)
            }
        }
        d.deleteRecursively(); d.mkdirs()
        val base = d.canonicalPath + File.separator
        try { ZipInputStream(zip.inputStream().buffered()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val f = File(d, e.name)
                if (!f.canonicalPath.startsWith(base)) continue   // sécurité : rien hors du dossier
                if (e.isDirectory) f.mkdirs() else {
                    f.parentFile?.mkdirs()
                    f.outputStream().use { z.copyTo(it) }
                }
            }
        } } catch (e: Exception) {
            zip.delete(); d.deleteRecursively()      // fichier abîmé : on recommencera proprement
            throw e
        }
        zip.delete()
        File(d, "ok").writeText(nom)
        return trouverRacine(d) ?: throw IllegalStateException("Modèle ${l.nom} incomplet")
    }

    /** Télécharge la suite du fichier. true = fichier complet. */
    private fun morceau(url: URL, zip: File, progres: (Int) -> Unit): Boolean {
        val deja = if (zip.exists()) zip.length() else 0L
        val c = url.openConnection() as HttpURLConnection
        c.connectTimeout = 20000; c.readTimeout = 30000
        if (deja > 0) c.setRequestProperty("Range", "bytes=$deja-")
        try {
            val code = c.responseCode
            if (code == 416) return true                         // déjà tout reçu
            if (code != 200 && code != 206) throw java.io.IOException("serveur : $code")
            val reprise = code == 206
            val total = c.contentLengthLong.let { if (it > 0) it + (if (reprise) deja else 0L) else -1L }
            var lu = if (reprise) deja else 0L
            var dernier = -1
            c.inputStream.use { ins ->
                java.io.FileOutputStream(zip, reprise).use { out ->
                    val b = ByteArray(64 * 1024)
                    while (true) {
                        val n = ins.read(b); if (n < 0) break
                        out.write(b, 0, n); lu += n
                        if (total > 0) {
                            val p = (lu * 100 / total).toInt()
                            if (p != dernier) { dernier = p; progres(p) }
                        }
                    }
                }
            }
            return total <= 0 || lu >= total
        } finally { c.disconnect() }
    }
}
