package com.mouad.doublage

/** Calculs purs (sans Android) : testés automatiquement avant chaque APK. */
object Analyse {

    const val HOMME = 0
    const val FEMME = 1
    const val GARCON = 2
    const val FILLE = 3

    /** Réglage des enfants. */
    const val ENFANTS_NON = 0
    const val ENFANTS_AUTO = 1
    const val ENFANTS_GARCON = 2
    const val ENFANTS_FILLE = 3

    /** Voix de base (homme ou femme) à utiliser pour chaque personnage. */
    fun base(g: Int): Int = if (g == FEMME || g == FILLE) FEMME else HOMME

    /** Hauteur supplémentaire de la voix : garçon = voix d'homme montée, fille = voix de femme montée. */
    fun montee(g: Int): Float = when (g) { GARCON -> 1.45f; FILLE -> 1.3f; else -> 1f }

    /** Fréquence fondamentale (Hz) d'un bloc PCM 16 bits, par autocorrélation. null si pas de voix nette. */
    fun hauteur(buf: ByteArray, n: Int, taux: Int): Float? {
        val nb = minOf(n / 2, 1024)
        if (nb < 512) return null
        val x = FloatArray(nb)
        var energie = 0.0
        for (i in 0 until nb) {
            val v = ((buf[2 * i].toInt() and 0xFF) or (buf[2 * i + 1].toInt() shl 8)).toShort().toFloat()
            x[i] = v; energie += v * v
        }
        if (energie / nb < 300.0 * 300.0) return null
        val lmin = taux / 450   // 450 Hz max
        val lmax = taux / 75    // 75 Hz min
        val r = DoubleArray(lmax + 2)
        for (l in lmin..lmax) {
            var c = 0.0; var e1 = 0.0; var e2 = 0.0
            var i = 0
            while (i + l < nb) {
                c += x[i] * x[i + l]; e1 += x[i] * x[i]; e2 += x[i + l] * x[i + l]
                i++
            }
            r[l] = c / (Math.sqrt(e1 * e2) + 1e-9)
        }
        var meilleur = 0.0
        for (l in lmin..lmax) if (r[l] > meilleur) meilleur = r[l]
        if (meilleur < 0.6) return null
        // Premier pic proche du maximum : évite de prendre le double de la période (octave trop basse)
        for (l in lmin..lmax) {
            val pic = r[l] >= r[l - 1] && r[l] >= r[l + 1]
            if (pic && r[l] >= 0.9 * meilleur) return taux.toFloat() / l
        }
        return null
    }

    fun mediane(l: List<Float>): Float =
        if (l.size < 3) 0f else l.sorted()[l.size / 2]

    /**
     * Choix de la voix selon la hauteur f0 (0 = inconnue).
     * Au-dessus de seuilEnfant : enfant. En mode auto, au-dessus de seuilFille : fille, sinon garçon.
     * (Avant la puberté, garçons et filles ont presque la même hauteur : le mode auto reste une estimation.)
     */
    fun genre(f0: Float, seuilFemme: Int, seuilEnfant: Int, enfants: Int, seuilFille: Int, defaut: Int): Int = when {
        f0 <= 0f -> defaut.coerceIn(HOMME, FILLE)
        f0 < seuilFemme -> HOMME
        f0 < seuilEnfant -> FEMME
        enfants == ENFANTS_GARCON -> GARCON
        enfants == ENFANTS_FILLE -> FILLE
        enfants == ENFANTS_AUTO -> if (f0 >= seuilFille) FILLE else GARCON
        else -> FEMME
    }

    /** Classement d'une voix de synthèse d'après sa hauteur moyenne. null = pas mesurée. */
    fun genreVoix(f0: Float?): Int? = when {
        f0 == null || f0 <= 0f -> null
        f0 < 170f -> HOMME
        else -> FEMME
    }

    /** Hauteur médiane d'un fichier WAV PCM 16 bits mono (sortie de la synthèse vocale). */
    fun hauteurWav(w: ByteArray): Float? {
        if (w.size < 44 || String(w, 0, 4) != "RIFF") return null
        fun int(o: Int) = (w[o].toInt() and 0xFF) or ((w[o + 1].toInt() and 0xFF) shl 8) or
            ((w[o + 2].toInt() and 0xFF) shl 16) or ((w[o + 3].toInt() and 0xFF) shl 24)
        var o = 12
        var taux = 0; var canaux = 1; var bits = 16
        while (o + 8 <= w.size) {
            val id = String(w, o, 4); val t = int(o + 4)
            if (id == "fmt ") {
                canaux = (w[o + 10].toInt() and 0xFF); taux = int(o + 12); bits = (w[o + 22].toInt() and 0xFF)
            }
            if (id == "data") { o += 8; break }
            o += 8 + t + (t and 1)
        }
        if (taux <= 0 || bits != 16 || canaux != 1 || o >= w.size) return null
        val l = ArrayList<Float>()
        val bloc = 2048
        var i = o
        while (i + bloc <= w.size) {
            hauteur(w.copyOfRange(i, i + bloc), bloc, taux)?.let { l.add(it) }
            i += bloc
        }
        val m = mediane(l)
        return if (m > 0f) m else null
    }

    /** Énergie (RMS) d'un bloc PCM 16 bits. */
    fun energie(buf: ByteArray, n: Int): Float {
        var e = 0.0; var c = 0
        var i = 0
        while (i + 1 < n) {
            val v = ((buf[i].toInt() and 0xFF) or (buf[i + 1].toInt() shl 8)).toShort().toDouble()
            e += v * v; c++; i += 2
        }
        return if (c == 0) 0f else Math.sqrt(e / c).toFloat()
    }

    /** Moyenne glissante de la voix normale d'un personnage (0 = pas encore connue). */
    fun moyenne(ancienne: Float, nouvelle: Float): Float = when {
        nouvelle <= 0f -> ancienne
        ancienne <= 0f -> nouvelle
        else -> 0.85f * ancienne + 0.15f * nouvelle
    }

    /**
     * Émotion : on compare la phrase à la voix normale du personnage.
     * Renvoie les facteurs (volume, hauteur, vitesse), adoucis par force (0..1).
     */
    fun emotion(f0: Float, f0Base: Float, energie: Float, energieBase: Float,
                debit: Float, debitBase: Float, force: Float): FloatArray {
        fun r(x: Float, b: Float, lo: Float, hi: Float, racine: Boolean = false): Float {
            if (x <= 0f || b <= 0f) return 1f
            val q = if (racine) Math.sqrt((x / b).toDouble()).toFloat() else x / b
            return q.coerceIn(lo, hi)
        }
        val f = force.coerceIn(0f, 1f)
        fun doux(v: Float) = 1f + f * (v - 1f)
        return floatArrayOf(
            doux(r(energie, energieBase, 0.6f, 1.7f, racine = true)),
            doux(r(f0, f0Base, 0.85f, 1.25f)),
            doux(r(debit, debitBase, 0.8f, 1.35f))
        )
    }

    /** Volume de la synthèse (0..1) : la partie sous 100 %. */
    fun volumeTts(pourcent: Int): Float = pourcent.coerceIn(0, 100) / 100f

    /** Amplification en millibels pour la partie au-dessus de 100 % (300 % = +9,5 dB). */
    fun gainMb(pourcent: Int): Int =
        if (pourcent <= 100) 0 else (2000 * Math.log10(pourcent.coerceAtMost(300) / 100.0)).toInt()

    /** Niveau approximatif (dB) d'un cran de volume si le téléphone ne le donne pas. */
    fun dbApprox(i: Int, max: Int): Float =
        if (i <= 0) -96f else (50.0 * (i - max) / max.coerceAtLeast(1)).toFloat()

    /**
     * Cran de volume média qui donne environ `pourcent` du volume actuel (dbs.last()).
     * Jamais 0 (sinon la traduction serait coupée aussi), baisse limitée à 18 dB.
     */
    fun indexCible(dbs: List<Float>, pourcent: Int): Int {
        val base = dbs.size - 1
        if (base <= 1 || pourcent >= 100) return base
        val baisse = maxOf(20 * Math.log10(pourcent.coerceAtLeast(1) / 100.0), -18.0)
        val vise = dbs[base] + baisse
        var meilleur = base
        for (i in 1..base) {
            val d = dbs[i]
            if (d.isInfinite() || d.isNaN()) continue
            if (d >= vise - 1.5 && Math.abs(d - vise) < Math.abs(dbs[meilleur] - vise)) meilleur = i
        }
        return meilleur
    }

    /** Amplification de la traduction pour compenser la baisse du volume média (max 18 dB). */
    fun compensationMb(dbAvant: Float, dbApres: Float): Int =
        (((dbAvant - dbApres).coerceIn(0f, 18f)) * 100).toInt()

    /** Faut-il traduire tout de suite la phrase en cours ? */
    fun couper(mots: Int, dureeMs: Long, maxMots: Int): Boolean =
        mots >= maxMots || (mots >= 3 && dureeMs > 4500)

    /** Confiance moyenne (0..1) des mots reconnus. -1 si aucun mot. */
    fun confianceMoyenne(confs: List<Float>): Float =
        if (confs.isEmpty()) -1f else confs.sum() / confs.size

    /**
     * Phrase probablement mal entendue (musique, bruit, mauvaise langue) : on ne la traduit pas.
     * seuil en % (0 = tout garder). Une phrase sans confiance connue (-1) est gardée.
     */
    fun malEntendue(confiance: Float, seuil: Int): Boolean =
        seuil > 0 && confiance >= 0f && confiance * 100f < seuil

    /** Phrase trop vieille pour être dite : la vidéo est déjà ailleurs (autre scène ou autre film). */
    fun perimee(ageMs: Long, retardMaxMs: Long): Boolean = ageMs > retardMaxMs

    const val SILENCE_RIEN = 0
    const val SILENCE_NOUVELLE = 1
    const val SILENCE_CHERCHER_LANGUE = 2

    /** Pause de moins de 15 s : jamais de nouvelle recherche de la langue (un film a des silences). */
    const val SILENCE_LANGUE_MS = 15000L

    /**
     * Le son revient après un silence de silenceMs. Un film a des pauses : on ne considère
     * « nouvelle vidéo » qu'après seuilS secondes, et on ne recherche la langue qu'après 15 s.
     */
    fun apresSilence(silenceMs: Long, seuilS: Int, redetecter: Boolean): Int = when {
        silenceMs <= seuilS * 1000L -> SILENCE_RIEN
        redetecter && silenceMs > maxOf(seuilS * 1000L, SILENCE_LANGUE_MS) -> SILENCE_CHERCHER_LANGUE
        else -> SILENCE_NOUVELLE
    }

    /**
     * Durée maximale normale d'une phrase dite (ms). Au-delà, la synthèse vocale ne nous a pas
     * prévenus de la fin : on passe à la suite pour ne pas rester muet.
     */
    fun dureeMaxParole(caracteres: Int, vitesse: Float): Long =
        (caracteres * 110L / vitesse.coerceAtLeast(0.3f)).toLong() + 5000L

    /** Score de détection d'une langue : mots reconnus et leur confiance totale. */
    class Score { var mots = 0; var conf = 0f
        fun ajouter(confs: List<Float>) { mots += confs.size; conf += confs.sum() }
        fun moyenne(): Float = if (mots == 0) 0f else conf / mots
    }

    /**
     * Langue de la vidéo d'après les écoutes en parallèle : celle dont les mots sont reconnus
     * avec la meilleure confiance. null = pas encore assez de paroles pour décider.
     * fin = true : on décide avec ce qu'on a (délai écoulé).
     */
    fun choisirLangue(scores: Map<String, Score>, fin: Boolean): String? {
        val valides = scores.filter { it.value.mots >= 3 }
        if (valides.isEmpty()) return if (fin) scores.maxByOrNull { it.value.mots }?.key else null
        val tries = valides.entries.sortedByDescending { it.value.moyenne() }
        val premier = tries[0]
        if (fin || tries.size == 1 && premier.value.mots >= 8) return premier.key
        if (premier.value.mots < 6) return null
        val ecart = premier.value.moyenne() - (tries.getOrNull(1)?.value?.moyenne() ?: 0f)
        return if (ecart >= 0.08f || premier.value.mots >= 15) premier.key else null
    }
}
