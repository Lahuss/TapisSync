package fr.tapissync.workout

import kotlin.math.abs
import kotlin.math.roundToLong

/** Réglages qui décident de la vitesse envoyée au tapis. */
data class SpeedSettings(
    /** Position dans la plage d'allure : 0 = bout lent, 0.5 = milieu, 1 = bout rapide. */
    val rangePosition: Double = 0.5,
    /** Correction en % appliquée aux vitesses calculées (ex. +2 si le tapis est un peu lent). */
    val correctionPct: Double = 0.0,
    /** Vitesses par défaut (km/h) quand l'étape n'a pas de cible d'allure. */
    val warmupKmh: Double = 9.0,
    val activeKmh: Double = 10.0,
    val recoveryKmh: Double = 7.5,
    val restKmh: Double = 4.0,
    val cooldownKmh: Double = 8.0,
    /** Bornes de sécurité (km/h). */
    val minKmh: Double = 1.0,
    val maxKmh: Double = 18.0,
)

/** Plage acceptée par le tapis (lue en Bluetooth), en km/h. */
data class MachineSpeedRange(val minKmh: Double, val maxKmh: Double, val incrementKmh: Double)

data class PlannedStep(
    val index: Int,
    val step: WorkoutStep,
    /** Vitesse envoyée au tapis, en km/h. */
    val speedKmh: Double,
    /** Explication lisible de la conversion, affichée dans l'aperçu. */
    val explanation: String,
    /** Vrai si la vitesse vient d'une cible Garmin, faux si c'est une vitesse par défaut. */
    val fromTarget: Boolean,
) {
    val title: String
        get() = buildString {
            append(step.kind.label)
            step.repeatLabel?.let { append(" ($it)") }
        }
}

object SpeedPlanner {

    fun plan(
        workout: Workout,
        settings: SpeedSettings,
        machine: MachineSpeedRange? = null,
    ): List<PlannedStep> = workout.steps.mapIndexed { i, step ->
        planStep(i, step, settings, machine)
    }

    fun planStep(
        index: Int,
        step: WorkoutStep,
        s: SpeedSettings,
        machine: MachineSpeedRange?,
    ): PlannedStep {
        val target = step.target
        val raw: Double
        val why: String
        val fromTarget: Boolean
        when (target) {
            is Target.SpeedRange -> {
                raw = speedInRange(target.slowMps, target.fastMps, s.rangePosition)
                fromTarget = true
                why = if (target.fromPace) {
                    val slow = Format.pace(target.slowMps * 3.6)
                    val fast = Format.pace(target.fastMps * 3.6)
                    if (slow == fast) "Allure $slow /km" else "Allure $fast–$slow /km → ${positionName(s.rangePosition)}"
                } else {
                    val a = Format.kmh(target.slowMps * 3.6)
                    val b = Format.kmh(target.fastMps * 3.6)
                    if (a == b) "Vitesse $a km/h" else "Vitesse $a–$b km/h → ${positionName(s.rangePosition)}"
                }
            }
            else -> {
                raw = defaultFor(step.kind, s)
                fromTarget = false
                why = when (target) {
                    is Target.PaceZone -> "Zone d'allure ${target.zone} sans valeurs : vitesse par défaut"
                    is Target.HeartRate -> "Cible cardio : vitesse par défaut"
                    is Target.Other -> "Cible « ${target.key} » : vitesse par défaut"
                    else -> "Sans cible : vitesse par défaut"
                }
            }
        }
        // La correction ne s'applique qu'aux vitesses issues d'une cible Garmin :
        // les vitesses par défaut sont déjà des valeurs « tapis ».
        val corrected = if (fromTarget) raw * (1 + s.correctionPct / 100.0) else raw
        val speed = clampAndRound(corrected, s, machine)
        val note = buildString {
            append(why)
            if (fromTarget && abs(s.correctionPct) > 0.001) {
                append(", correction ${if (s.correctionPct > 0) "+" else ""}${Format.num1(s.correctionPct)} %")
            }
        }
        return PlannedStep(index, step, speed, note, fromTarget)
    }

    /**
     * Vitesse (km/h) à une position donnée d'une plage. L'interpolation se fait sur l'allure
     * (min/km), parce que c'est ainsi que Garmin et le coureur lisent la plage :
     * le milieu de 5:00–5:20 /km est 5:10 /km.
     */
    fun speedInRange(slowMps: Double, fastMps: Double, position: Double): Double {
        val lo = minOf(slowMps, fastMps)
        val hi = maxOf(slowMps, fastMps)
        if (lo <= 0.0) return hi * 3.6
        val pos = position.coerceIn(0.0, 1.0)
        val paceSlow = 1000.0 / lo // s/km
        val paceFast = 1000.0 / hi
        val pace = paceSlow + (paceFast - paceSlow) * pos
        return 3600.0 / pace
    }

    fun defaultFor(kind: StepKind, s: SpeedSettings): Double = when (kind) {
        StepKind.WARMUP -> s.warmupKmh
        StepKind.COOLDOWN -> s.cooldownKmh
        StepKind.RECOVERY -> s.recoveryKmh
        StepKind.REST -> s.restKmh
        StepKind.ACTIVE, StepKind.OTHER -> s.activeKmh
    }

    fun clampAndRound(kmh: Double, s: SpeedSettings, machine: MachineSpeedRange?): Double {
        var lo = s.minKmh
        var hi = s.maxKmh
        var inc = 0.1
        if (machine != null) {
            if (machine.minKmh > 0) lo = maxOf(lo, machine.minKmh)
            if (machine.maxKmh > 0) hi = minOf(hi, machine.maxKmh)
            if (machine.incrementKmh in 0.01..1.0) inc = machine.incrementKmh
        }
        if (hi < lo) hi = lo
        val rounded = (kmh / inc).roundToLong() * inc
        // Arrondi propre à 2 décimales pour éviter 11.700000001.
        val clean = Math.round(rounded.coerceIn(lo, hi) * 100.0) / 100.0
        return clean
    }

    private fun positionName(p: Double): String = when {
        p <= 0.01 -> "bout lent"
        p >= 0.99 -> "bout rapide"
        abs(p - 0.5) < 0.01 -> "milieu"
        else -> "${Math.round(p * 100)} % vers le rapide"
    }

    /** Durée estimée de la séance, en secondes. */
    fun estimatedSeconds(steps: List<PlannedStep>): Double = steps.sumOf { p ->
        when (val e = p.step.end) {
            is EndCondition.Time -> e.seconds
            is EndCondition.Distance -> if (p.speedKmh > 0) e.meters / (p.speedKmh / 3.6) else 0.0
            is EndCondition.Manual -> 0.0
        }
    }

    /** Distance estimée, en mètres. */
    fun estimatedMeters(steps: List<PlannedStep>): Double = steps.sumOf { p ->
        when (val e = p.step.end) {
            is EndCondition.Time -> e.seconds * p.speedKmh / 3.6
            is EndCondition.Distance -> e.meters
            is EndCondition.Manual -> 0.0
        }
    }
}

object Format {
    /** Allure « m:ss » pour une vitesse en km/h. */
    fun pace(kmh: Double): String {
        if (kmh <= 0.05) return "--:--"
        val totalSec = Math.round(3600.0 / kmh)
        return "%d:%02d".format(totalSec / 60, totalSec % 60)
    }

    fun kmh(v: Double): String = num1(v)

    fun num1(v: Double): String = String.format(java.util.Locale.FRANCE, "%.1f", v)

    fun duration(seconds: Double): String {
        val s = Math.round(seconds).coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    fun distance(meters: Double): String =
        if (meters >= 1000) String.format(java.util.Locale.FRANCE, "%.2f km", meters / 1000.0)
        else "${Math.round(meters)} m"

    fun endCondition(e: EndCondition): String = when (e) {
        is EndCondition.Time -> duration(e.seconds)
        is EndCondition.Distance -> distance(e.meters)
        is EndCondition.Manual -> "Manuel (${e.reason})"
    }
}
