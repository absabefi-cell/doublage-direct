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

    private fun g(f0: Float, enfants: Int = Analyse.ENFANTS_AUTO, defaut: Int = Analyse.HOMME) =
        Analyse.genre(f0, 165, 290, enfants, 330, defaut)

    @Test fun genres() {
        assertEquals(Analyse.HOMME, g(110f))
        assertEquals(Analyse.FEMME, g(220f))
        assertEquals(Analyse.GARCON, g(300f))
        assertEquals(Analyse.FILLE, g(360f))
        assertEquals(Analyse.GARCON, g(360f, Analyse.ENFANTS_GARCON))
        assertEquals(Analyse.FILLE, g(300f, Analyse.ENFANTS_FILLE))
        assertEquals(Analyse.FEMME, g(360f, Analyse.ENFANTS_NON))
        assertEquals(Analyse.HOMME, g(0f))
        assertEquals(Analyse.FEMME, g(0f, defaut = Analyse.FEMME))
    }

    @Test fun voixDeBase() {
        // Homme -> voix d'homme, femme -> voix de femme, garçon -> voix d'homme montée, fille -> voix de femme montée
        assertEquals(Analyse.HOMME, Analyse.base(Analyse.HOMME))
        assertEquals(Analyse.FEMME, Analyse.base(Analyse.FEMME))
        assertEquals(Analyse.HOMME, Analyse.base(Analyse.GARCON))
        assertEquals(Analyse.FEMME, Analyse.base(Analyse.FILLE))
        assertTrue(Analyse.montee(Analyse.GARCON) > 1.2f)
        assertTrue(Analyse.montee(Analyse.FILLE) > 1.1f)
        assertEquals(1f, Analyse.montee(Analyse.HOMME))
        assertEquals(1f, Analyse.montee(Analyse.FEMME))
    }

    @Test fun chaineComplete() {
        // Une phrase d'homme puis une de femme : la médiane choisit la bonne voix
        val h = List(10) { Analyse.hauteur(voix(120.0), 4096, taux)!! }
        val f = List(10) { Analyse.hauteur(voix(230.0), 4096, taux)!! }
        assertEquals(Analyse.HOMME, g(Analyse.mediane(h)))
        assertEquals(Analyse.FEMME, g(Analyse.mediane(f)))
        assertEquals(0f, Analyse.mediane(listOf(100f)))
    }

    /** Fichier WAV comme celui que la synthèse vocale écrit. */
    private fun wav(f0: Double, tauxWav: Int = 24000, secondes: Double = 1.0): ByteArray {
        val n = (tauxWav * secondes).toInt()
        val data = java.io.ByteArrayOutputStream()
        for (i in 0 until n) {
            val t = i.toDouble() / tauxWav
            val v = 8000 * (sin(2 * PI * f0 * t) + 0.5 * sin(4 * PI * f0 * t)) / 1.5
            val s = v.toInt()
            data.write(s and 0xFF); data.write((s shr 8) and 0xFF)
        }
        val d = data.toByteArray()
        val b = java.nio.ByteBuffer.allocate(44 + d.size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + d.size).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(tauxWav).putInt(tauxWav * 2)
            .putShort(2).putShort(16)
        b.put("data".toByteArray()).putInt(d.size).put(d)
        return b.array()
    }

    @Test fun triDesVoix() {
        assertEquals(Analyse.HOMME, Analyse.genreVoix(Analyse.hauteurWav(wav(115.0))))
        assertEquals(Analyse.FEMME, Analyse.genreVoix(Analyse.hauteurWav(wav(215.0))))
        assertEquals(Analyse.HOMME, Analyse.genreVoix(Analyse.hauteurWav(wav(130.0, 22050))))
        assertNull(Analyse.genreVoix(null))
        assertNull(Analyse.hauteurWav(ByteArray(10)))
        assertNull(Analyse.genreVoix(Analyse.hauteurWav(wav(200.0, secondes = 0.0))))
    }

    @Test fun emotions() {
        // Voix normale : aucun changement
        val n = Analyse.emotion(200f, 200f, 3000f, 3000f, 2.5f, 2.5f, 1f)
        n.forEach { assertEquals(1f, it, 0.001f) }
        // Cri : plus fort, plus aigu, plus rapide
        val cri = Analyse.emotion(260f, 200f, 12000f, 3000f, 3.5f, 2.5f, 1f)
        assertTrue(cri[0] > 1.5f); assertTrue(cri[1] > 1.2f); assertTrue(cri[2] > 1.3f)
        // Tristesse / chuchotement : plus doux, plus lent
        val triste = Analyse.emotion(190f, 200f, 800f, 3000f, 1.5f, 2.5f, 1f)
        assertTrue(triste[0] < 0.7f); assertTrue(triste[2] < 0.85f)
        // Force à 0 : voix neutre
        Analyse.emotion(260f, 200f, 12000f, 3000f, 3.5f, 2.5f, 0f).forEach { assertEquals(1f, it, 0.001f) }
        // Voix normale pas encore connue : neutre
        Analyse.emotion(260f, 0f, 12000f, 0f, 3.5f, 0f, 1f).forEach { assertEquals(1f, it, 0.001f) }
        // Limites
        val fort = Analyse.emotion(1000f, 100f, 1e6f, 1f, 50f, 1f, 1f)
        assertEquals(1.7f, fort[0], 0.001f); assertEquals(1.25f, fort[1], 0.001f); assertEquals(1.35f, fort[2], 0.001f)
        assertEquals(200f, Analyse.moyenne(0f, 200f)); assertEquals(200f, Analyse.moyenne(200f, 0f))
        assertEquals(215f, Analyse.moyenne(200f, 300f), 0.01f)
        assertTrue(Analyse.energie(voix(150.0), 4096) > 3000f)
        assertEquals(0f, Analyse.energie(ByteArray(4096), 4096))
    }

    @Test fun langues() {
        // Toutes les langues dans les deux sens, dont celles demandées
        listOf("fr", "en", "de", "es", "ko", "ar").forEach { c ->
            assertTrue("$c manque (vidéo)", Langues.sources.any { it.code == c })
            assertTrue("$c manque (traduction)", Langues.toutes.any { it.code == c })
        }
        assertEquals(Langues.toutes.size, Langues.toutes.map { it.code }.toSet().size)
        assertTrue(Langues.toutes.size >= 50)
        assertEquals("zh-CN", Langues.codeVoix("zh"))
        assertTrue(Langues.parasite("en", "the")); assertTrue(!Langues.parasite("fr", "the bon"))
        Langues.sources.forEach { assertTrue(it.vosk!!.startsWith("vosk-model")) }
    }

    @Test fun volume() {
        assertEquals(0.5f, Analyse.volumeTts(50))
        assertEquals(1f, Analyse.volumeTts(250))
        assertEquals(0, Analyse.gainMb(80))
        assertEquals(0, Analyse.gainMb(100))
        assertEquals(602, Analyse.gainMb(200))
        assertEquals(954, Analyse.gainMb(300))
        assertEquals(954, Analyse.gainMb(500))
    }

    @Test fun volumeOriginal() {
        // 15 crans, environ 3 dB par cran
        val dbs = (0..15).map { if (it == 0) -96f else -3f * (15 - it) }
        assertEquals(15, Analyse.indexCible(dbs, 100))
        val i50 = Analyse.indexCible(dbs, 50)       // -6 dB
        assertEquals(13, i50)
        val i25 = Analyse.indexCible(dbs, 25)       // -12 dB
        assertEquals(11, i25)
        assertTrue(Analyse.indexCible(dbs, 1) >= 9) // baisse limitée à 18 dB, jamais 0
        assertEquals(600, Analyse.compensationMb(dbs[15], dbs[i50]))
        assertEquals(1800, Analyse.compensationMb(0f, -40f))
        assertEquals(1, Analyse.indexCible(listOf(-96f, -20f), 10)) // volume déjà au minimum : on ne touche pas
        assertEquals(-96f, Analyse.dbApprox(0, 15))
        assertEquals(0f, Analyse.dbApprox(15, 15))
    }

    @Test fun coupure() {
        assertTrue(Analyse.couper(8, 500, 8))
        assertTrue(!Analyse.couper(5, 1000, 8))
        assertTrue(!Analyse.couper(4, 4000, 8))   // v7 : on attend 4,5 s pour des phrases plus complètes
        assertTrue(Analyse.couper(4, 5000, 8))
        assertTrue(!Analyse.couper(2, 9000, 8))
    }

    // ---------- v7 : phrases mal entendues, phrases périmées, détection de la langue ----------

    @Test fun filtreMalEntendu() {
        assertTrue(Analyse.malEntendue(0.30f, 45))
        assertTrue(!Analyse.malEntendue(0.80f, 45))
        assertTrue(!Analyse.malEntendue(0.30f, 0))      // filtre coupé : tout passe
        assertTrue(!Analyse.malEntendue(-1f, 45))       // confiance inconnue : on garde
        assertEquals(0.5f, Analyse.confianceMoyenne(listOf(0.4f, 0.6f)), 1e-4f)
        assertEquals(-1f, Analyse.confianceMoyenne(emptyList()), 0f)
    }

    @Test fun phrasePerimee() {
        assertTrue(Analyse.perimee(7000, 6000))
        assertTrue(!Analyse.perimee(2000, 6000))
    }

    private fun score(vararg c: Float) = Analyse.Score().apply { ajouter(c.toList()) }

    @Test fun detectionLangueClaire() {
        val s = mapOf(
            "en" to score(0.45f, 0.5f, 0.4f, 0.5f, 0.42f, 0.38f),
            "fr" to score(0.95f, 0.9f, 0.97f, 0.88f, 0.93f, 1f, 0.91f)
        )
        assertEquals("fr", Analyse.choisirLangue(s, fin = false))
    }

    @Test fun detectionPasAssezDeMots() {
        val s = mapOf("en" to score(0.9f, 0.8f), "fr" to score(0.5f))
        assertNull(Analyse.choisirLangue(s, fin = false))
        assertEquals("en", Analyse.choisirLangue(s, fin = true))
    }

    @Test fun detectionTropProcheAttend() {
        val s = mapOf(
            "en" to score(0.80f, 0.80f, 0.80f, 0.80f, 0.80f, 0.80f),
            "es" to score(0.78f, 0.78f, 0.78f, 0.78f, 0.78f, 0.78f)
        )
        assertNull(Analyse.choisirLangue(s, fin = false))
        assertEquals("en", Analyse.choisirLangue(s, fin = true))
    }

    @Test fun languesAuto() {
        assertEquals(listOf("fr", "en"), Langues.choixAuto(null).map { it.code })
        assertEquals(listOf("en"), Langues.choixAuto(setOf("zz")).map { it.code })
        assertTrue(Langues.detectables.none { it.code == "ar" })   // arabe trop lourd pour écouter en parallèle
    }
}
