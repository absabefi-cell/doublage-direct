package com.mouad.doublage

/** Calculs purs (sans Android) : testés automatiquement avant chaque APK. */
object Analyse {

    const val HOMME = 0
    const val FEMME = 1
    const val ENFANT = 2

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

    /** Choix de la voix selon la hauteur f0 (0 = inconnue). */
    fun genre(f0: Float, seuilFemme: Int, seuilEnfant: Int, enfant: Boolean, defaut: Int): Int = when {
        f0 <= 0f -> if (defaut == FEMME) FEMME else HOMME
        f0 < seuilFemme -> HOMME
        f0 < seuilEnfant -> FEMME
        else -> if (enfant) ENFANT else FEMME
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
        mots >= maxMots || (mots >= 3 && dureeMs > 3500)
}
