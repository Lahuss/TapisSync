package fr.tapissync

import fr.tapissync.run.WorkoutEngine
import fr.tapissync.run.WorkoutEngine.Event
import fr.tapissync.workout.EndCondition
import fr.tapissync.workout.PlannedStep
import fr.tapissync.workout.StepKind
import fr.tapissync.workout.Target
import fr.tapissync.workout.WorkoutStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineTest {

    private var now = 0L

    private fun step(kind: StepKind, end: EndCondition, kmh: Double, i: Int) =
        PlannedStep(i, WorkoutStep(kind, end, Target.None), kmh, "", false)

    private fun engine(): WorkoutEngine = WorkoutEngine(
        steps = listOf(
            step(StepKind.WARMUP, EndCondition.Time(60.0), 9.0, 0),
            step(StepKind.ACTIVE, EndCondition.Distance(400.0), 14.0, 1),
            step(StepKind.COOLDOWN, EndCondition.Manual("bouton Lap"), 8.0, 2),
        ),
        normalize = { Math.round(it.coerceIn(1.0, 18.0) * 10) / 10.0 },
        clock = { now },
    )

    @Test
    fun timeStepAdvancesExactlyWithCountdownAndPause() {
        val e = engine()
        assertEquals(listOf<Event>(Event.StepStarted(0)), e.start())
        assertEquals(9.0, e.targetKmh(), 1e-9)

        now = 30_000
        assertTrue(e.tick().isEmpty())
        e.pause()
        now = 50_000 // 20 s de pause
        assertTrue(e.tick().isEmpty())
        assertEquals(30.0, e.stepRemainingS()!!, 1e-6)
        e.resume()

        now = 77_500 // 57,5 s de course effective → 2,5 s restantes → bip « 3 »
        assertEquals(listOf<Event>(Event.Countdown(3)), e.tick())
        assertTrue(e.tick().isEmpty())

        now = 80_300 // 60,3 s effectives : passage à l'étape 2, commencée à 80 000 pile
        assertEquals(listOf<Event>(Event.StepStarted(1)), e.tick())
        assertEquals(300L, e.stepElapsedMs())
        assertEquals(14.0, e.targetKmh(), 1e-9)
        assertEquals(60_300L, e.totalElapsedMs())
    }

    @Test
    fun distanceStepUsesTreadmillDistanceAndAdjustResets() {
        val e = engine()
        e.start()
        now = 60_000
        e.tick()
        assertEquals(1, e.index)
        e.adjust(0.3)
        assertEquals(14.3, e.targetKmh(), 1e-9)
        e.addDistance(380.0 - 0.0) // trop grand d'un coup : ignoré (> 100 m)
        assertEquals(0.0, e.stepDistanceM(), 1e-9)
        repeat(38) { e.addDistance(10.0) }
        assertEquals(listOf<Event>(Event.AlmostDone), e.tick())
        e.addDistance(10.0)
        e.addDistance(10.0)
        assertEquals(listOf<Event>(Event.StepStarted(2)), e.tick())
        assertEquals(8.0, e.targetKmh(), 1e-9) // l'ajustement ne déborde pas sur l'étape suivante
    }

    @Test
    fun manualStepWaitsForSkipThenFinishes() {
        val e = engine()
        e.start()
        e.skip()
        e.skip()
        assertEquals(2, e.index)
        now = 1_000_000
        assertTrue(e.tick().isEmpty())
        assertEquals(listOf<Event>(Event.Finished(completed = true)), e.skip())
        assertEquals(WorkoutEngine.Phase.FINISHED, e.phase)
    }

    @Test
    fun skipWhilePausedKeepsPauseAccounting() {
        val e = engine()
        e.start()
        now = 10_000
        e.pause()
        now = 20_000
        e.skip()
        assertEquals(1, e.index)
        assertEquals(WorkoutEngine.Phase.PAUSED, e.phase)
        now = 30_000
        e.resume()
        now = 35_000
        assertEquals(5_000L, e.stepElapsedMs())
        assertEquals(15_000L, e.totalElapsedMs())
    }

    @Test
    fun spokenAnnouncements() {
        val p = step(StepKind.ACTIVE, EndCondition.Distance(1000.0), 12.6, 1)
        assertEquals(
            "Étape 2 sur 11. Course, 12,6 kilomètres heure, sur 1 kilomètre.",
            WorkoutEngine.announce(p, 1, 11, 12.6),
        )
        assertEquals("1 minute 30", WorkoutEngine.spokenDuration(90.0))
        assertEquals("45 secondes", WorkoutEngine.spokenDuration(45.0))
        assertEquals("400 mètres", WorkoutEngine.spokenDistance(400.0))
        assertEquals("9", WorkoutEngine.spokenNumber(9.0))
    }
}
