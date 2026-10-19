package fr.tapissync.workout

/** Type d'étape, d'après le champ stepType.stepTypeKey de Garmin. */
enum class StepKind(val label: String) {
    WARMUP("Échauffement"),
    ACTIVE("Course"),
    RECOVERY("Récupération"),
    REST("Repos"),
    COOLDOWN("Retour au calme"),
    OTHER("Étape");

    companion object {
        fun fromGarmin(key: String?): StepKind = when (key?.lowercase()) {
            "warmup" -> WARMUP
            "cooldown" -> COOLDOWN
            "interval", "main", "active" -> ACTIVE
            "recovery" -> RECOVERY
            "rest" -> REST
            else -> OTHER
        }
    }
}

/** Condition de fin d'une étape. */
sealed class EndCondition {
    data class Time(val seconds: Double) : EndCondition()
    data class Distance(val meters: Double) : EndCondition()

    /** Bouton « Lap » ou condition que l'appli ne sait pas mesurer : on passe à la main. */
    data class Manual(val reason: String) : EndCondition()
}

/** Cible d'intensité d'une étape. Les vitesses sont en m/s, comme chez Garmin. */
sealed class Target {
    object None : Target()

    /** Plage d'allure ou de vitesse. slowMps <= fastMps. */
    data class SpeedRange(val slowMps: Double, val fastMps: Double, val fromPace: Boolean) : Target()

    /** Zone d'allure référencée par son numéro, sans valeurs dans la séance. */
    data class PaceZone(val zone: Int) : Target()

    data class HeartRate(val low: Double?, val high: Double?, val zone: Int?) : Target()

    data class Other(val key: String) : Target()
}

data class WorkoutStep(
    val kind: StepKind,
    val end: EndCondition,
    val target: Target,
    val description: String? = null,
    /** Ex. « 2/5 » quand l'étape vient d'un bloc répété. */
    val repeatLabel: String? = null,
)

data class Workout(
    val id: Long?,
    val name: String,
    val sportKey: String,
    val description: String?,
    val steps: List<WorkoutStep>,
) {
    val isRunning: Boolean
        get() = sportKey.isBlank() || sportKey.contains("run", ignoreCase = true)
}
