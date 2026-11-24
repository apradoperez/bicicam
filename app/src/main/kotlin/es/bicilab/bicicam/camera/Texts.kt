package es.bicilab.bicicam.camera

import java.util.Locale

/** Idiomas de la app. */
enum class Language(val code: String, val label: String) {
    SPANISH("es", "Español"),
    ENGLISH("en", "English"),
    ;

    companion object {
        /** El del Karoo si es español; para cualquier otro, inglés. */
        fun ofDevice(): Language = if (Locale.getDefault().language == "es") SPANISH else ENGLISH

        fun fromCode(code: String?): Language? = entries.firstOrNull { it.code == code }
    }
}

/**
 * El texto en el idioma elegido en los ajustes. Todos los textos que ve el usuario pasan por aquí, con sus
 * dos versiones juntas en el sitio donde se usan.
 */
fun t(es: String, en: String): String = if (AppSettings.language.value == Language.ENGLISH) en else es
