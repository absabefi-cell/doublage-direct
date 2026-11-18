package com.mouad.doublage

/**
 * Contenu des sous-titres de contrôle (calculs purs, testés avant chaque APK) :
 * ligne 1 = ce que l'app a entendu dans la langue originale, ligne 2 = la traduction et ce qui lui est arrivé.
 * Pour comparer avec ce qu'on entend dans la vidéo et repérer les anomalies.
 */
object SousTitre {

    enum class Etat {
        TRADUCTION,     // en cours de traduction
        A_DIRE,         // traduite, attend son tour
        DITE,           // dite à voix haute
        FILTREE,        // mal entendue : pas traduite
        RETARD,         // sautée : trop de retard sur la vidéo
        FILE,           // sautée : trop de phrases en attente
        NOUVELLE,       // sautée : nouvelle vidéo ou « Recommencer »
        ERREUR,         // la traduction a échoué
        MEME_LANGUE     // la vidéo est déjà dans la langue choisie
    }

    /** Une phrase entendue, avec son numéro (pour relier l'original et sa traduction). */
    class Entree(val numero: Int, val original: String, val confiance: Float) {
        @Volatile var traduction: String = ""
        @Volatile var etat: Etat = Etat.TRADUCTION
    }

    /** Étiquette courte de la langue : EN, FR, AR... */
    fun etiquette(code: String): String = code.uppercase()

    fun ligneOriginal(langue: String, e: Entree): String {
        val c = if (e.confiance >= 0f) "  (${Math.round(e.confiance * 100)} %)" else ""
        return "${etiquette(langue)} : ${e.original}$c"
    }

    fun ligneTraduction(langue: String, e: Entree): String {
        val t = e.traduction
        val note = when (e.etat) {
            Etat.TRADUCTION -> return "${etiquette(langue)} : …"
            Etat.A_DIRE, Etat.DITE -> ""
            Etat.FILTREE -> return "${etiquette(langue)} : ✗ mal entendu, pas traduit (filtre)"
            Etat.ERREUR -> return "${etiquette(langue)} : ✗ erreur de traduction"
            Etat.MEME_LANGUE -> return "${etiquette(langue)} : (déjà dans cette langue)"
            Etat.RETARD -> "  ⏭ pas dite : trop de retard"
            Etat.FILE -> "  ⏭ pas dite : trop de phrases en attente"
            Etat.NOUVELLE -> "  ⏭ pas dite : nouvelle vidéo"
        }
        return "${etiquette(langue)} : $t$note"
    }

    /** Une anomalie se voit en rouge : phrase entendue mais pas dite. */
    fun anomalie(e: Entree): Boolean =
        e.etat in setOf(Etat.FILTREE, Etat.ERREUR, Etat.RETARD, Etat.FILE, Etat.NOUVELLE)
}
