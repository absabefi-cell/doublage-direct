package com.mouad.doublage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PistesTest {

    private val srv1 = """<?xml version="1.0" encoding="utf-8" ?><transcript>
        <text start="1.5" dur="2.0">Merhaba &amp;#39;dostum&amp;#39;</text>
        <text start="3.6" dur="1.2">nasılsın</text>
        <text start="10" dur="2">[Müzik]</text>
        <text start="12.25" dur="1.5">&lt;i&gt;İyiyim.&lt;/i&gt;</text>
        </transcript>"""

    @Test fun lireSrv1() {
        val l = Pistes.lireSrv1(srv1)
        assertEquals(3, l.size)                                  // [Müzik] est enlevé
        assertEquals(Pistes.Replique(1500, 3500, "Merhaba 'dostum'"), l[0])
        assertEquals("nasılsın", l[1].texte)
        assertEquals(12250L, l[2].debut)
        assertEquals("İyiyim.", l[2].texte)                      // balises <i> enlevées
    }

    @Test fun arabe() {
        val l = Pistes.lireSrv1("""<transcript><text start="0.5" dur="2">مرحبا يا صديقي&#x061F;</text></transcript>""")
        assertEquals("مرحبا يا صديقي؟", l[0].texte)
    }

    @Test fun lireVttSansRepetitions() {
        val vtt = "WEBVTT\n\n00:00:01.000 --> 00:00:03.000\nhello there\n\n" +
            "00:00:03.000 --> 00:00:05.000\nhello there\nhow are you\n\n01:02.500 --> 01:04.000\nfine"
        val l = Pistes.lireVtt(vtt)
        assertEquals(listOf("hello there", "how are you", "fine"), l.map { it.texte })
        assertEquals(62500L, l[2].debut)
    }

    @Test fun regrouperLesMorceaux() {
        val l = listOf(
            Pistes.Replique(0, 1000, "bugün"), Pistes.Replique(1100, 2000, "çok güzel"),
            Pistes.Replique(2100, 3000, "bir gün."), Pistes.Replique(3100, 4000, "Evet"),
            Pistes.Replique(9000, 10000, "sonra")
        )
        val r = Pistes.regrouper(l)
        assertEquals(listOf("bugün çok güzel bir gün.", "Evet", "sonra"), r.map { it.texte })
        assertEquals(0L, r[0].debut); assertEquals(3000L, r[0].fin)
    }

    @Test fun chevauchementsCoupes() {
        val r = Pistes.regrouper(listOf(Pistes.Replique(0, 5000, "Bonjour."), Pistes.Replique(2000, 4000, "Salut.")))
        assertEquals(2000L, r[0].fin)
    }

    @Test fun choixDesPistes() {
        val p = listOf(
            Pistes.Piste("tr", true, "u-tr-auto"), Pistes.Piste("en", false, "u-en"),
            Pistes.Piste("ar", false, "u-ar")
        )
        assertEquals("u-ar", Pistes.pisteCible(p, "ar")?.url)
        assertNull(Pistes.pisteCible(p, "fr"))
        assertNull(Pistes.pisteCible(listOf(Pistes.Piste("ar", true, "x")), "ar"))   // auto arabe : pas une vraie traduction
        assertEquals("u-tr-auto", Pistes.pisteOriginale(p, null)?.url)              // langue inconnue : piste auto
        assertEquals("u-tr-auto", Pistes.pisteOriginale(p, Langues.AUTO)?.url)
        assertEquals("u-en", Pistes.pisteOriginale(p, "en")?.url)
        assertEquals("u-en", Pistes.pisteOriginale(listOf(Pistes.Piste("en-US", false, "u-en")), "en")?.url)
        assertNull(Pistes.pisteOriginale(emptyList(), "tr"))
    }

    @Test fun urlTraduite() {
        assertEquals("https://x/api?v=1&fmt=srv1&tlang=ar", Pistes.urlTraduite("https://x/api?v=1&fmt=srv1", "ar"))
        assertEquals("https://x/api?v=1&tlang=fr", Pistes.urlTraduite("https://x/api?v=1&tlang=en", "fr"))
    }

    private val film = listOf(
        Pistes.Replique(1000, 2000, "a"), Pistes.Replique(3000, 4000, "b"),
        Pistes.Replique(5000, 6000, "c"), Pistes.Replique(20000, 21000, "d")
    )

    @Test fun repliquesAuBonMoment() {
        // Avant le début : rien
        assertEquals(emptyList<Int>() to 0, Pistes.aDire(film, 0, 500, avance = -250))
        // 250 ms après le début de « a »
        assertEquals(listOf(0) to 1, Pistes.aDire(film, 0, 1250, avance = -250))
        // Déjà dite : on ne la redit pas
        assertEquals(emptyList<Int>() to 1, Pistes.aDire(film, 1, 1500, avance = -250))
        // Trop en retard (saut) : sautée, mais la suivante est dite
        assertEquals(listOf(2) to 3, Pistes.aDire(film, 0, 5300, avance = -250, tropTard = 2000))
    }

    @Test fun positionApresUnSaut() {
        assertEquals(0, Pistes.indexA(film, 0))
        assertEquals(1, Pistes.indexA(film, 2500))
        assertEquals(2, Pistes.indexA(film, 5500))     // en plein dans « c »
        assertEquals(3, Pistes.indexA(film, 15000))
        assertEquals(4, Pistes.indexA(film, 99000))    // fin du film
    }

    @Test fun vitesse() {
        assertEquals(0.5f, Pistes.vitesseMin("ok", 5000), 0.001f)
        assertEquals(2.0f, Pistes.vitesseMin("x".repeat(200), 1000), 0.001f)
        assertEquals(1.0f, Pistes.vitesseMin("x".repeat(28), 2000), 0.001f)
    }

    @Test fun retrouverLaVideo() {
        val bonne = Pistes.note("Kuruluş Osman 1. Bölüm", "atv", 7_200_000, "Kuruluş Osman 1. Bölüm", "atv", 7200)
        val autre = Pistes.note("Kuruluş Osman 1. Bölüm", "atv", 7_200_000, "Kuruluş Osman 2. Bölüm", "atv", 7100)
        val fausseDuree = Pistes.note("Kurulus Osman 1. Bolum", "atv", 7_200_000, "Kuruluş Osman 1. Bölüm", "atv", 300)
        assertTrue(bonne > 0)
        assertTrue(autre < bonne)
        assertTrue(fausseDuree < 0)                       // mauvaise durée : ce n'est pas la vidéo
        assertTrue(Pistes.note("Extra English episode 1", "", 0, "Extra English Episode 1", "BBC", 1500) > 0)
        assertTrue(Pistes.note("Recette tajine", "", 0, "Football highlights", "ESPN", 600) < 0)
    }

    @Test fun normaliser() {
        assertEquals("kurulus osman 1 bolum", Pistes.normaliser("Kuruluş Osman: 1. Bölüm"))
        assertEquals("iyiyim", Pistes.normaliser("İyiyim!"))
    }
}
