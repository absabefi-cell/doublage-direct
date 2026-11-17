package com.mouad.doublage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class AnalyseTest {

    private val taux = 16000

    /** Voix synthétique : fondamentale + harmoniques, comme une vraie voix. */
    private fun voix(f0: Double, amp: Double = 8000.0): ByteArray {
        val n = 2048
        val b = ByteArray(n * 2)
        for (i in 0 until n) {
            val t = i.toDouble() / taux
            val v = amp * (sin(2 * PI * f0 * t) + 0.5 * sin(4 * PI * f0 * t) + 0.25 * sin(6 * PI * f0 * t)) / 1.75
            val s = v.toInt().toShort().toInt()
            b[2 * i] = (s and 0xFF).toByte()
            b[2 * i + 1] = (s shr 8).toByte()
        }
        return b
    }

    private fun proche(attendu: Double, mesure: Float?) {
        assertTrue("hauteur non détectée pour $attendu Hz", mesure != null)
        assertTrue("attendu ~$attendu Hz, mesuré $mesure", abs(mesure!! - attendu) / attendu < 0.06)
    }

    @Test fun hauteurHomme() = proche(110.0, Analyse.hauteur(voix(110.0), 4096, taux))
    @Test fun hauteurFemme() = proche(220.0, Analyse.hauteur(voix(220.0), 4096, taux))
    @Test fun hauteurEnfant() = proche(330.0, Analyse.hauteur(voix(330.0), 4096, taux))

    @Test fun silenceIgnore() = assertNull(Analyse.hauteur(ByteArray(4096), 4096, taux))
    @Test fun sonTropFaibleIgnore() = assertNull(Analyse.hauteur(voix(150.0, 100.0), 4096, taux))

    @Test fun bruitIgnore() {
        val r = java.util.Random(1)
        val b = ByteArray(4096).also { r.nextBytes(it) }
        assertNull(Analyse.hauteur(b, 4096, taux))
    }

    @Test fun genres() {
        assertEquals(Analyse.HOMME, Analyse.genre(110f, 165, 290, true, 0))
        assertEquals(Analyse.FEMME, Analyse.genre(220f, 165, 290, true, 0))
        assertEquals(Analyse.ENFANT, Analyse.genre(330f, 165, 290, true, 0))
        assertEquals(Analyse.FEMME, Analyse.genre(330f, 165, 290, false, 0))
        assertEquals(Analyse.HOMME, Analyse.genre(0f, 165, 290, true, 0))
        assertEquals(Analyse.FEMME, Analyse.genre(0f, 165, 290, true, 1))
    }

    @Test fun chaineComplete() {
        // Une phrase d'homme puis une de femme : la médiane choisit la bonne voix
        val h = List(10) { Analyse.hauteur(voix(120.0), 4096, taux)!! }
        val f = List(10) { Analyse.hauteur(voix(230.0), 4096, taux)!! }
        assertEquals(Analyse.HOMME, Analyse.genre(Analyse.mediane(h), 165, 290, true, 0))
        assertEquals(Analyse.FEMME, Analyse.genre(Analyse.mediane(f), 165, 290, true, 0))
        assertEquals(0f, Analyse.mediane(listOf(100f)))
    }

    @Test fun coupure() {
        assertTrue(Analyse.couper(8, 500, 8))
        assertTrue(!Analyse.couper(5, 1000, 8))
        assertTrue(Analyse.couper(4, 4000, 8))
        assertTrue(!Analyse.couper(2, 9000, 8))
    }
}
