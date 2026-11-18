package com.mouad.doublage

/**
 * Sous-titres YouTube : lecture des fichiers, regroupement en phrases, choix de la bonne piste
 * et de la réplique à dire selon la position de la vidéo. Calculs purs, testés avant chaque APK.
 */
object Pistes {

    /** Une réplique : début et fin en millisecondes dans la vidéo. */
    data class Replique(val debut: Long, val fin: Long, val texte: String)

    /** Une piste proposée par YouTube. auto = sous-titres générés automatiquement. */
    data class Piste(val langue: String, val auto: Boolean, val url: String)

    /** Code court de la langue : « en-US » → « en », « pt-BR » → « pt ». */
    fun court(tag: String): String = tag.substringBefore('-').substringBefore('_').lowercase()

    // ---------- Lecture des fichiers ----------

    private val reTexte = Regex("""<text\s+([^>]*)>(.*?)</text>""", RegexOption.DOT_MATCHES_ALL)
    private val reStart = Regex("""start="([\d.]+)"""")
    private val reDur = Regex("""dur="([\d.]+)"""")
    private val reBalise = Regex("<[^>]*>")
    private val reNum = Regex("&#(x?)([0-9a-fA-F]+);")

    /** Décode les « &amp; &#39; ... » (YouTube les met parfois deux fois). */
    fun decoder(s: String): String {
        var t = s
        repeat(2) {
            t = t.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&apos;", "'").replace("&nbsp;", " ")
            t = reNum.replace(t) { m ->
                val v = m.groupValues[2].toIntOrNull(if (m.groupValues[1].isEmpty()) 10 else 16)
                if (v == null) m.value else String(Character.toChars(v))
            }
            t = t.replace("&amp;", "&")
        }
        return t
    }

    private fun nettoyer(s: String): String =
        decoder(reBalise.replace(decoder(s), " "))
            .replace(Regex("""\[[^\]]*\]|♪+"""), " ")   // [Musique], [Applaudissements], ♪
            .replace(Regex("\\s+"), " ").trim()

    /** Format « srv1 » de YouTube : <text start="1.5" dur="2.3">Bonjour</text> */
    fun lireSrv1(xml: String): List<Replique> {
        val l = ArrayList<Replique>()
        for (m in reTexte.findAll(xml)) {
            val attrs = m.groupValues[1]
            val debut = reStart.find(attrs)?.groupValues?.get(1)?.toDoubleOrNull() ?: continue
            val dur = reDur.find(attrs)?.groupValues?.get(1)?.toDoubleOrNull() ?: 2.0
            val t = nettoyer(m.groupValues[2])
            if (t.isEmpty()) continue
            l.add(Replique((debut * 1000).toLong(), ((debut + dur) * 1000).toLong(), t))
        }
        return l.sortedBy { it.debut }
    }

    private val reTemps = Regex("""(\d+):(\d{2}):(\d{2})[.,](\d{3})|(\d{2}):(\d{2})[.,](\d{3})""")

    private fun ms(m: MatchResult): Long {
        val g = m.groupValues
        return if (g[1].isNotEmpty())
            g[1].toLong() * 3600000 + g[2].toLong() * 60000 + g[3].toLong() * 1000 + g[4].toLong()
        else g[5].toLong() * 60000 + g[6].toLong() * 1000 + g[7].toLong()
    }

    /** Format WebVTT (au cas où). Les lignes répétées des sous-titres automatiques sont enlevées. */
    fun lireVtt(vtt: String): List<Replique> {
        val l = ArrayList<Replique>()
        val blocs = vtt.replace("\r", "").split(Regex("\n\\s*\n"))
        var derniere = ""
        for (b in blocs) {
            val lignes = b.lines()
            val i = lignes.indexOfFirst { it.contains("-->") }
            if (i < 0) continue
            val temps = reTemps.findAll(lignes[i]).toList()
            if (temps.size < 2) continue
            val textes = lignes.drop(i + 1).map { nettoyer(it) }.filter { it.isNotEmpty() && it != derniere }
            if (textes.isEmpty()) continue
            derniere = textes.last()
            l.add(Replique(ms(temps[0]), ms(temps[1]), textes.joinToString(" ")))
        }
        return l.sortedBy { it.debut }
    }

    fun lire(contenu: String): List<Replique> =
        if (contenu.contains("<text")) lireSrv1(contenu) else lireVtt(contenu)

    // ---------- Phrases ----------

    private fun finDePhrase(t: String) = t.trimEnd().lastOrNull()?.let { it in ".!?…؟。" } ?: false

    /**
     * Les sous-titres automatiques coupent les phrases n'importe où : on recolle les morceaux
     * qui se suivent (sans pause), pour que la voix dise des phrases entières.
     */
    fun regrouper(l: List<Replique>, maxMs: Long = 6000, maxMots: Int = 16, pauseMs: Long = 600): List<Replique> {
        val r = ArrayList<Replique>()
        for (x in l) {
            val p = r.lastOrNull()
            val mots = (p?.texte?.split(" ")?.size ?: 0) + x.texte.split(" ").size
            if (p != null && !finDePhrase(p.texte) && x.debut - p.fin <= pauseMs &&
                x.fin - p.debut <= maxMs && mots <= maxMots) {
                r[r.size - 1] = Replique(p.debut, maxOf(p.fin, x.fin), p.texte + " " + x.texte)
            } else r.add(x)
        }
        // Les sous-titres auto se chevauchent : chaque réplique finit au plus tard au début de la suivante
        for (i in 0 until r.size - 1) {
            if (r[i].fin > r[i + 1].debut) r[i] = r[i].copy(fin = maxOf(r[i].debut + 300, r[i + 1].debut))
        }
        return r
    }

    // ---------- Choix de la piste ----------

    /**
     * Piste de la vidéo originale : celle de la langue choisie si on la connaît, sinon la piste automatique
     * (YouTube la crée dans la langue parlée), sinon la première. Les pistes écrites à la main passent avant.
     */
    fun pisteOriginale(pistes: List<Piste>, source: String?): Piste? {
        val s = source?.takeIf { it.isNotBlank() && it != Langues.AUTO }
        if (s != null) {
            pistes.firstOrNull { court(it.langue) == s && !it.auto }?.let { return it }
            pistes.firstOrNull { court(it.langue) == s }?.let { return it }
        }
        return pistes.firstOrNull { it.auto } ?: pistes.firstOrNull()
    }

    /** Piste déjà dans la langue de la traduction, écrite à la main (la meilleure traduction possible). */
    fun pisteCible(pistes: List<Piste>, cible: String): Piste? =
        pistes.firstOrNull { court(it.langue) == cible && !it.auto }

    /** Adresse de la traduction automatique de YouTube vers une langue. */
    fun urlTraduite(url: String, cible: String): String =
        url.replace(Regex("&tlang=[^&]*"), "") + "&tlang=" + cible

    // ---------- Position dans la vidéo ----------

    /** Première réplique qui n'est pas encore finie à cette position (après un saut dans la vidéo). */
    fun indexA(l: List<Replique>, pos: Long): Int {
        var lo = 0; var hi = l.size
        while (lo < hi) { val m = (lo + hi) / 2; if (l[m].fin <= pos) lo = m + 1 else hi = m }
        return lo
    }

    /**
     * Répliques à dire maintenant : celles qui commencent avant pos + avance, à partir de « prochaine ».
     * Une réplique déjà commencée depuis plus de « tropTard » est sautée.
     * Retourne (index des répliques à dire, nouvelle valeur de « prochaine »).
     */
    fun aDire(l: List<Replique>, prochaine: Int, pos: Long, avance: Long = 150, tropTard: Long = 2500): Pair<List<Int>, Int> {
        var i = prochaine
        val dire = ArrayList<Int>()
        while (i < l.size && l[i].debut <= pos + avance) {
            if (pos - l[i].debut <= tropTard) dire.add(i)
            i++
        }
        return dire to i
    }

    /** Vitesse minimale de la voix pour que la traduction tienne dans le temps de la réplique. */
    fun vitesseMin(texte: String, dureeMs: Long, lettresParSeconde: Float = 14f): Float {
        if (dureeMs <= 0) return 1f
        val besoin = texte.length / lettresParSeconde / (dureeMs / 1000f)
        return besoin.coerceIn(0.5f, 2.0f)
    }

    // ---------- Retrouver la vidéo dans la recherche YouTube ----------

    fun normaliser(s: String): String =
        java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "").replace('ı', 'i')
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    /**
     * Note d'un résultat de recherche (plus haut = meilleur, < 0 = ce n'est pas la bonne vidéo).
     * dureeS = durée du résultat en secondes, dureeMs = durée donnée par le lecteur (0 si inconnue).
     */
    fun note(titre: String, chaine: String, dureeMs: Long, rTitre: String, rChaine: String, dureeS: Long): Int {
        var n = 0
        val a = normaliser(titre); val b = normaliser(rTitre)
        if (a == b) n += 50 else if (a.isNotEmpty() && (b.contains(a) || a.contains(b))) n += 25 else {
            val ma = a.split(" ").toSet(); val mb = b.split(" ").toSet()
            n += (ma.intersect(mb).size * 30 / maxOf(1, ma.size))
        }
        if (chaine.isNotBlank() && normaliser(chaine) == normaliser(rChaine)) n += 30
        if (dureeMs > 0 && dureeS > 0) {
            val ecart = kotlin.math.abs(dureeMs / 1000 - dureeS)
            n += if (ecart <= 2) 40 else if (ecart <= 5) 10 else -100
        }
        return if (n < 30) -1 else n
    }
}
