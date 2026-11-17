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

    /** Faut-il traduire tout de suite la phrase en cours ? */
    fun couper(mots: Int, dureeMs: Long, maxMots: Int): Boolean =
        mots >= maxMots || (mots >= 3 && dureeMs > 3500)
}
