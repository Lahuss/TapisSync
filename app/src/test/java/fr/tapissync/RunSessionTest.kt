package fr.tapissync

import android.os.Looper
import fr.tapissync.ble.Ftms
import fr.tapissync.run.RunSession
import fr.tapissync.run.WorkoutEngine.Phase
import fr.tapissync.workout.EndCondition
import fr.tapissync.workout.PlannedStep
import fr.tapissync.workout.StepKind
import fr.tapissync.workout.Target
import fr.tapissync.workout.Workout
import fr.tapissync.workout.WorkoutStep
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** Réactions de la séance aux événements du tapis (démarrage, arrêt depuis la console). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RunSessionTest {

    private var session: RunSession? = null

    private fun newSession(): RunSession {
        val step = WorkoutStep(StepKind.ACTIVE, EndCondition.Time(1800.0), Target.None)
        val w = Workout(1, "Test", "running", null, listOf(step))
        val s = RunSession(App.instance, w, listOf(PlannedStep(0, step, 10.0, "", false)))
        session = s
        return s
    }

    private fun wait(seconds: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(seconds))

    @After
    fun tearDown() {
        session?.release()
    }

    @Test
    fun stopSignalDuringStartupIsIgnored() {
        val s = newSession()
        s.start()
        wait(1)
        // Le tapis signale un arrêt transitoire pendant son compte à rebours.
        s.onStatus(Ftms.StatusEvent.StoppedByUser(paused = false))
        assertEquals(Phase.RUNNING, s.engine.phase)
        wait(3)
        s.onStatus(Ftms.StatusEvent.StoppedByUser(paused = true))
        assertEquals(Phase.RUNNING, s.engine.phase)
    }

    @Test
    fun consoleStopLaterPausesThenBeltRestartResumes() {
        val s = newSession()
        s.start()
        wait(30)
        s.onStatus(Ftms.StatusEvent.StoppedByUser(paused = true))
        assertEquals(Phase.PAUSED, s.engine.phase)

        // Bande relancée depuis la console, sans message « démarré » du tapis.
        repeat(3) {
            wait(1)
            s.onData(Ftms.TreadmillData(speedKmh = 3.0))
        }
        assertEquals(Phase.RUNNING, s.engine.phase)
    }

    @Test
    fun appPauseIsNotUndoneByBeltData() {
        val s = newSession()
        s.start()
        wait(30)
        s.pause()
        repeat(4) {
            wait(1)
            // La bande ralentit encore : ça ne doit pas relancer la séance.
            s.onData(Ftms.TreadmillData(speedKmh = 2.0))
        }
        assertEquals(Phase.PAUSED, s.engine.phase)
    }

    @Test
    fun safetyKeyAlwaysPauses() {
        val s = newSession()
        s.start()
        wait(1)
        s.onStatus(Ftms.StatusEvent.SafetyKey)
        assertEquals(Phase.PAUSED, s.engine.phase)
    }
}
