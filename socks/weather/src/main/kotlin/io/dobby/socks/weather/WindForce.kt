package io.dobby.socks.weather

import io.dobby.core.sock.Lang

/**
 * A wind speed as a word, because a number on its own does not answer "wie stark ist der Wind".
 *
 * "Zwölf Kilometer pro Stunde" means something to a sailor and nothing to somebody deciding
 * whether to put the parasol up, so every wind answer says both — the word first, because that
 * is the answer, and the number after it, because that is the evidence.
 *
 * The bands are the Beaufort scale collapsed to the six distinctions German actually makes in
 * a kitchen. Beaufort itself has thirteen, which is eleven more than anybody needs to know
 * before hanging the washing out.
 */
enum class WindForce(private val upTo: Double, private val de: String, private val en: String) {
    CALM(WindThresholds.CALM, "windstill", "calm"),
    LIGHT(WindThresholds.LIGHT, "schwacher Wind", "a light breeze"),
    MODERATE(WindThresholds.MODERATE, "mäßiger Wind", "a moderate breeze"),
    FRESH(WindThresholds.FRESH, "frischer Wind", "a fresh breeze"),
    STRONG(WindThresholds.STRONG, "starker Wind", "a strong wind"),
    STORM(Double.MAX_VALUE, "Sturm", "a gale"),
    ;

    fun text(lang: Lang): String = if (lang == Lang.EN) en else de

    /** True where the honest answer is "put things away", which is the one worth flagging. */
    val isNotable: Boolean get() = this >= STRONG

    companion object {
        /** km/h in, a word out. */
        fun of(kilometresPerHour: Double): WindForce =
            entries.first { kilometresPerHour < it.upTo }
    }
}

/** The band edges in km/h, out of the enum so the constructor reads as a table. */
private object WindThresholds {
    const val CALM = 6.0
    const val LIGHT = 20.0
    const val MODERATE = 39.0
    const val FRESH = 62.0
    const val STRONG = 89.0
}
