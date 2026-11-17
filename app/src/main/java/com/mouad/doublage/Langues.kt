package com.mouad.doublage

/** Langues proposées. vosk = modèle d'écoute (null = la langue peut seulement être la traduction). */
data class Langue(val code: String, val nom: String, val vosk: String? = null, val tailleMo: Int = 0)

object Langues {

    /** Modèle anglais livré dans l'APK : pas de téléchargement. */
    const val VOSK_INCLUS = "en"

    /** Toutes les langues que le traducteur ML Kit connaît (traduction dans tous les sens). */
    val toutes = listOf(
        Langue("ar", "Arabe العربية", "vosk-model-ar-mgb2-0.4", 318),
        Langue("fr", "Français", "vosk-model-small-fr-0.22", 41),
        Langue("en", "Anglais", "vosk-model-small-en-us-0.15", 0),
        Langue("es", "Espagnol", "vosk-model-small-es-0.42", 39),
        Langue("de", "Allemand", "vosk-model-small-de-0.15", 45),
        Langue("ko", "Coréen", "vosk-model-small-ko-0.22", 82),
        Langue("it", "Italien", "vosk-model-small-it-0.22", 48),
        Langue("pt", "Portugais", "vosk-model-small-pt-0.3", 31),
        Langue("tr", "Turc", "vosk-model-small-tr-0.3", 35),
        Langue("ru", "Russe", "vosk-model-small-ru-0.22", 45),
        Langue("zh", "Chinois", "vosk-model-small-cn-0.22", 42),
        Langue("ja", "Japonais", "vosk-model-small-ja-0.22", 48),
        Langue("hi", "Hindi", "vosk-model-small-hi-0.22", 42),
        Langue("fa", "Persan", "vosk-model-small-fa-0.42", 53),
        Langue("nl", "Néerlandais", "vosk-model-small-nl-0.22", 39),
        Langue("pl", "Polonais", "vosk-model-small-pl-0.22", 50),
        Langue("uk", "Ukrainien", "vosk-model-small-uk-v3-nano", 73),
        Langue("vi", "Vietnamien", "vosk-model-small-vn-0.4", 32),
        Langue("ca", "Catalan", "vosk-model-small-ca-0.4", 42),
        Langue("cs", "Tchèque", "vosk-model-small-cs-0.4-rhasspy", 44),
        Langue("ka", "Géorgien", "vosk-model-small-ka-0.42", 45),
        Langue("gu", "Gujarati", "vosk-model-small-gu-0.42", 100),
        Langue("te", "Télougou", "vosk-model-small-te-0.42", 58),
        Langue("eo", "Espéranto", "vosk-model-small-eo-0.42", 42),
        Langue("sv", "Suédois"), Langue("da", "Danois"), Langue("no", "Norvégien"),
        Langue("fi", "Finnois"), Langue("el", "Grec"), Langue("ro", "Roumain"),
        Langue("hu", "Hongrois"), Langue("bg", "Bulgare"), Langue("hr", "Croate"),
        Langue("sk", "Slovaque"), Langue("sl", "Slovène"), Langue("lt", "Lituanien"),
        Langue("lv", "Letton"), Langue("et", "Estonien"), Langue("he", "Hébreu"),
        Langue("ur", "Ourdou"), Langue("bn", "Bengali"), Langue("ta", "Tamoul"),
        Langue("mr", "Marathi"), Langue("kn", "Kannada"), Langue("th", "Thaï"),
        Langue("id", "Indonésien"), Langue("ms", "Malais"), Langue("tl", "Tagalog"),
        Langue("sw", "Swahili"), Langue("af", "Afrikaans"), Langue("sq", "Albanais"),
        Langue("mk", "Macédonien"), Langue("be", "Biélorusse"), Langue("is", "Islandais"),
        Langue("ga", "Irlandais"), Langue("cy", "Gallois"), Langue("gl", "Galicien"),
        Langue("mt", "Maltais"), Langue("ht", "Créole haïtien")
    )

    /** Langues possibles pour la vidéo originale (il faut un modèle d'écoute). */
    val sources = toutes.filter { it.vosk != null }

    fun trouver(code: String?): Langue = toutes.firstOrNull { it.code == code } ?: toutes[1]

    /** Étiquette du menu, avec la taille à télécharger la première fois. */
    fun etiquetteSource(l: Langue): String =
        if (l.code == VOSK_INCLUS || l.tailleMo == 0) l.nom else "${l.nom} (${l.tailleMo} Mo la 1re fois)"

    /** Code de langue pour la synthèse vocale d'Android. */
    fun codeVoix(code: String): String = when (code) {
        "zh" -> "zh-CN"; "no" -> "nb"; "tl" -> "fil"; else -> code
    }

    /** Petits mots parasites que la reconnaissance entend dans le bruit. */
    fun parasite(code: String, t: String): Boolean =
        t.length < 3 || (code == "en" && (t == "the" || t == "huh"))
}
