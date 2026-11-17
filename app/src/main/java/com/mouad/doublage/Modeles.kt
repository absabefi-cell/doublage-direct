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

    /** À appeler hors du fil principal. progres(pourcent) pendant le téléchargement. */
    fun telecharger(ctx: Context, l: Langue, progres: (Int) -> Unit): File {
        pret(ctx, l)?.let { return it }
        val nom = l.vosk ?: throw IllegalArgumentException("Pas de modèle d'écoute pour ${l.nom}")
        val d = dossier(ctx, l)
        d.deleteRecursively(); d.mkdirs()
        val zip = File(ctx.cacheDir, "$nom.zip")
        val c = URL("https://alphacephei.com/vosk/models/$nom.zip").openConnection() as HttpURLConnection
        c.connectTimeout = 20000; c.readTimeout = 30000
        try {
            val total = c.contentLengthLong
            var lu = 0L; var dernier = -1
            c.inputStream.use { ins ->
                zip.outputStream().use { out ->
                    val b = ByteArray(64 * 1024)
                    while (true) {
                        val n = ins.read(b); if (n < 0) break
                        out.write(b, 0, n); lu += n
                        if (total > 0) {
                            val p = (lu * 100 / total).toInt()
                            if (p != dernier && p % 5 == 0) { dernier = p; progres(p) }
                        }
                    }
                }
            }
        } finally { c.disconnect() }

        val base = d.canonicalPath + File.separator
        ZipInputStream(zip.inputStream().buffered()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val f = File(d, e.name)
                if (!f.canonicalPath.startsWith(base)) continue   // sécurité : rien hors du dossier
                if (e.isDirectory) f.mkdirs() else {
                    f.parentFile?.mkdirs()
                    f.outputStream().use { z.copyTo(it) }
                }
            }
        }
        zip.delete()
        File(d, "ok").writeText(nom)
        return trouverRacine(d) ?: throw IllegalStateException("Modèle ${l.nom} incomplet")
    }
}
