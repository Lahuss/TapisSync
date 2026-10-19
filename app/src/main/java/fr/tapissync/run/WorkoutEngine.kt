package fr.tapissync.run

import fr.tapissync.workout.EndCondition
import fr.tapissync.workout.Format
import fr.tapissync.workout.PlannedStep
import kotlin.math.ceil

/**
 * Déroulé de la séance : étape courante, temps, distance, passage à l'étape suivante.
 * Aucune dépendance Android : l'horloge est injectée, ce qui permet de le tester.
 */
class WorkoutEngine(
    val steps: List<PlannedStep>,
    /** Arrondit et borne une vitesse (pas du tapis, min/max). */
    private val normalize: (Double) -> Double,
    private val clock: () -> Long,
) {
    enum class Phase { READY, RUNNING, PAUSED, FINISHED }

    sealed class Event {
        data class StepStarted(val index: Int) : Event()
        data class Countdown(val secondsLeft: Int) : Event()
        object AlmostDone : Event()
        data class Finished(val completed: Boolean) : Event()
    }

    var phase = Phase.READY
        private set
    var index = 0
        private set
    /** Ajustement manuel de la vitesse pour l'étape en cours (km/h). */
    var adjustKmh = 0.0
        private set
    var totalDistanceM = 0.0
        private set

    private var workoutStartMs = 0L
    private var totalPausedMs = 0L
    private var stepStartMs = 0L
    private var stepPausedMs = 0L
    private var pauseStartedMs = 0L
    private var finishedAtMs = 0L
    private var stepStartDistanceM = 0.0
    private var lastCountdown = -1
    private var almostDoneSignaled = false

    val current: PlannedStep? get() = steps.getOrNull(index)
    val next: PlannedStep? get() = steps.getOrNull(index + 1)

    fun targetKmh(): Double {
        val step = current ?: return 0.0
        return normalize(step.speedKmh + adjustKmh)
    }

    fun start(): List<Event> {
        if (phase != Phase.READY || steps.isEmpty()) return emptyList()
        workoutStartMs = clock()
        phase = Phase.RUNNING
        beginStep(0, workoutStartMs)
        return listOf(Event.StepStarted(0))
    }

    fun pause() {
        if (phase != Phase.RUNNING) return
        pauseStartedMs = clock()
        phase = Phase.PAUSED
    }

    fun resume() {
        if (phase != Phase.PAUSED) return
        val d = clock() - pauseStartedMs
        stepPausedMs += d
        totalPausedMs += d
        phase = Phase.RUNNING
    }

    /** Passe à l'étape suivante (bouton « Suivant »). */
    fun skip(): List<Event> {
        if (phase != Phase.RUNNING && phase != Phase.PAUSED) return emptyList()
        return advance(clock())
    }

    /** Recommence l'étape précédente. */
    fun back(): List<Event> {
        if (phase != Phase.RUNNING && phase != Phase.PAUSED) return emptyList()
        val target = if (stepElapsedMs() > 5000 || index == 0) index else index - 1
        beginStep(target, clock())
        return listOf(Event.StepStarted(index))
    }

    fun finish(): List<Event> {
        if (phase == Phase.FINISHED) return emptyList()
        if (phase == Phase.PAUSED) resume()
        finishedAtMs = clock()
        phase = Phase.FINISHED
        return listOf(Event.Finished(completed = false))
    }

    fun adjust(deltaKmh: Double) {
        val step = current ?: return
        val newTarget = normalize(step.speedKmh + adjustKmh + deltaKmh)
        adjustKmh = newTarget - step.speedKmh
    }

    /** La vitesse a été changée sur la console du tapis : on la garde pour cette étape. */
    fun acceptManualTarget(kmh: Double) {
        val step = current ?: return
        adjustKmh = kmh - step.speedKmh
    }

    fun addDistance(meters: Double) {
        if (phase == Phase.RUNNING && meters > 0 && meters < 100) totalDistanceM += meters
    }

    fun stepElapsedMs(): Long = when (phase) {
        Phase.RUNNING -> clock() - stepStartMs - stepPausedMs
        Phase.PAUSED -> pauseStartedMs - stepStartMs - stepPausedMs
        else -> 0L
    }.coerceAtLeast(0L)

    fun totalElapsedMs(): Long = when (phase) {
        Phase.READY -> 0L
        Phase.RUNNING -> clock() - workoutStartMs - totalPausedMs
        Phase.PAUSED -> pauseStartedMs - workoutStartMs - totalPausedMs
        Phase.FINISHED -> finishedAtMs - workoutStartMs - totalPausedMs
    }.coerceAtLeast(0L)

    fun stepDistanceM(): Double = (totalDistanceM - stepStartDistanceM).coerceAtLeast(0.0)

    /** Temps restant (s) pour une étape au temps, sinon null. */
    fun stepRemainingS(): Double? = (current?.step?.end as? EndCondition.Time)?.let {
        (it.seconds - stepElapsedMs() / 1000.0).coerceAtLeast(0.0)
    }

    /** Distance restante (m) pour une étape à la distance, sinon null. */
    fun stepRemainingM(): Double? = (current?.step?.end as? EndCondition.Distance)?.let {
        (it.meters - stepDistanceM()).coerceAtLeast(0.0)
    }

    /** Avancement de l'étape entre 0 et 1, ou null pour une étape manuelle. */
    fun stepProgress(): Double? = when (val e = current?.step?.end) {
        is EndCondition.Time -> (stepElapsedMs() / 1000.0 / e.seconds).coerceIn(0.0, 1.0)
        is EndCondition.Distance -> (stepDistanceM() / e.meters).coerceIn(0.0, 1.0)
        else -> null
    }

    fun tick(): List<Event> {
        if (phase != Phase.RUNNING) return emptyList()
        val step = current ?: return finish()
        when (val e = step.step.end) {
            is EndCondition.Time -> {
                val durMs = (e.seconds * 1000).toLong()
                val elapsed = stepElapsedMs()
                if (elapsed >= durMs) {
                    // Début exact de l'étape suivante, sans cumuler le retard du minuteur.
                    val boundary = stepStartMs + stepPausedMs + durMs
                    return advance(boundary)
                }
                val left = ceil((durMs - elapsed) / 1000.0).toInt()
                if (left in 1..3 && left != lastCountdown) {
                    lastCountdown = left
                    return listOf(Event.Countdown(left))
                }
            }
            is EndCondition.Distance -> {
                val left = e.meters - stepDistanceM()
                if (left <= 0) return advance(clock())
                if (left <= 25 && !almostDoneSignaled) {
                    almostDoneSignaled = true
                    return listOf(Event.AlmostDone)
                }
            }
            is EndCondition.Manual -> Unit
        }
        return emptyList()
    }

    private fun advance(atMs: Long): List<Event> {
        if (index + 1 >= steps.size) {
            finishedAtMs = clock()
            if (phase == Phase.PAUSED) totalPausedMs += finishedAtMs - pauseStartedMs
            phase = Phase.FINISHED
            return listOf(Event.Finished(completed = true))
        }
        beginStep(index + 1, atMs)
        return listOf(Event.StepStarted(index))
    }

    private fun beginStep(i: Int, atMs: Long) {
        index = i
        stepStartMs = atMs
        stepPausedMs = 0L
        if (phase == Phase.PAUSED) {
            // La pause continue sur la nouvelle étape : on solde la partie déjà écoulée.
            val now = clock()
            totalPausedMs += now - pauseStartedMs
            pauseStartedMs = now
        }
        stepStartDistanceM = totalDistanceM
        adjustKmh = 0.0
        lastCountdown = -1
        almostDoneSignaled = false
    }

    companion object {
        /** Phrase annoncée au début d'une étape. */
        fun announce(p: PlannedStep, index: Int, count: Int, speedKmh: Double): String {
            val what = p.step.kind.label.lowercase()
            val speed = spokenNumber(speedKmh)
            val how = when (val e = p.step.end) {
                is EndCondition.Time -> "pendant ${spokenDuration(e.seconds)}"
                is EndCondition.Distance -> "sur ${spokenDistance(e.meters)}"
                is EndCondition.Manual -> "jusqu'à ce que tu appuies sur Suivant"
            }
            return "Étape ${index + 1} sur $count. ${what.replaceFirstChar { it.uppercase() }}, $speed kilomètres heure, $how."
        }

        fun spokenNumber(v: Double): String {
            val r = Math.round(v * 10) / 10.0
            return if (r == Math.floor(r)) r.toLong().toString() else Format.num1(r)
        }

        fun spokenDuration(seconds: Double): String {
            val s = Math.round(seconds)
            val m = s / 60
            val r = s % 60
            return when {
                m == 0L -> "$r secondes"
                r == 0L -> if (m == 1L) "1 minute" else "$m minutes"
                else -> "${if (m == 1L) "1 minute" else "$m minutes"} $r"
            }
        }

        fun spokenDistance(meters: Double): String = when {
            meters >= 1000 && Math.round(meters) % 1000 == 0L -> {
                val km = Math.round(meters) / 1000
                if (km == 1L) "1 kilomètre" else "$km kilomètres"
            }
            meters >= 1000 -> "${spokenNumber(meters / 1000.0)} kilomètres"
            else -> "${Math.round(meters)} mètres"
        }
    }
}
