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

    private fun advance(seconds: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(seconds))

    @After
    fun tearDown() {
        session?.release()
    }

    @Test
    fun stopSignalDuringStartupIsIgnored() {
        val s = newSession()
        s.start()
        advance(1)
        // Le tapis signale un arrêt transitoire pendant son compte à rebours.
        s.onStatus(Ftms.StatusEvent.StoppedByUser(paused = false))
        assertEquals(Phase.RUNNING, s.engine.phase)
        advance(3)
        s.onStatus(Ftms.StatusEvent.StoppedByUser(paused = true))
        assertEquals(Phase.RUNNING, s.engine.phase)
    }

    @Test
    fun consoleStopLaterPausesThenBeltRestartResumes() {
        val s = newSession()
        s.start()
        advance(30)
        s.onStatus(Ftms.StatusEvent.StoppedByUser(paused = true))
        assertEquals(Phase.PAUSED, s.engine.phase)

        // Bande relancée depuis la console, sans message « démarré » du tapis.
        repeat(3) {
            advance(1)
            s.onData(Ftms.TreadmillData(speedKmh = 3.0))
        }
        assertEquals(Phase.RUNNING, s.engine.phase)
    }

    @Test
    fun appPauseIsNotUndoneByBeltData() {
        val s = newSession()
        s.start()
        advance(30)
        s.pause()
        repeat(4) {
            advance(1)
            // La bande ralentit encore : ça ne doit pas relancer la séance.
            s.onData(Ftms.TreadmillData(speedKmh = 2.0))
        }
        assertEquals(Phase.PAUSED, s.engine.phase)
    }

    @Test
    fun treadmillStartSpeedIsNotTakenAsManualChange() {
        val s = newSession()
        s.start()
        advance(4)
        // Fin du compte à rebours : le tapis annonce sa vitesse de départ, 1 km/h.
        s.onStatus(Ftms.StatusEvent.TargetSpeedChanged(1.0))
        assertEquals(10.0, s.engine.targetKmh(), 1e-9)
        assertEquals(0.0, s.engine.adjustKmh, 1e-9)
    }

    @Test
    fun consoleSpeedChangeLaterIsKeptForTheStep() {
        App.instance.prefs.lockSpeed = false
        val s = newSession()
        s.start()
        advance(30)
        s.onStatus(Ftms.StatusEvent.TargetSpeedChanged(11.0))
        assertEquals(11.0, s.engine.targetKmh(), 1e-9)
    }

    @Test
    fun lockedSpeedIgnoresChangesFromOtherApps() {
        App.instance.prefs.lockSpeed = true
        val s = newSession()
        s.start()
        advance(30)
        // Kinomap (ou la console) change la vitesse : TapisSync garde celle de l'étape.
        s.onStatus(Ftms.StatusEvent.TargetSpeedChanged(6.0))
        advance(2)
        assertEquals(10.0, s.engine.targetKmh(), 1e-9)
        assertEquals(0.0, s.engine.adjustKmh, 1e-9)
        // Les boutons ± de TapisSync restent la façon d'ajuster.
        s.adjust(0.3)
        assertEquals(10.3, s.engine.targetKmh(), 1e-9)
    }

    @Test
    fun safetyKeyAlwaysPauses() {
        val s = newSession()
        s.start()
        advance(1)
        s.onStatus(Ftms.StatusEvent.SafetyKey)
        assertEquals(Phase.PAUSED, s.engine.phase)
    }
}
