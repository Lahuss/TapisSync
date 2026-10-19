package fr.tapissync.run

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import fr.tapissync.App
import fr.tapissync.AppLog
import fr.tapissync.ble.Ftms
import fr.tapissync.ble.TreadmillManager
import fr.tapissync.workout.Format
import fr.tapissync.workout.PlannedStep
import fr.tapissync.workout.SpeedPlanner
import fr.tapissync.workout.Workout
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.abs

/**
 * Une séance en cours : fait avancer le moteur, envoie les vitesses au tapis,
 * suit les événements du tapis et déclenche les annonces. Tout sur le fil principal.
 */
class RunSession(
    private val app: App,
    val workout: Workout,
    steps: List<PlannedStep>,
) : TreadmillManager.Listener {

    fun interface Listener {
        fun onUpdate()
    }

    private val tm = app.treadmill
    private val prefs = app.prefs
    private val main = Handler(Looper.getMainLooper())
    private val speaker = Speaker(app).apply {
        voiceEnabled = prefs.voiceEnabled
        beepEnabled = prefs.beepEnabled
    }
    private val settings = prefs.speedSettings

    val engine = WorkoutEngine(
        steps = steps,
        normalize = { SpeedPlanner.clampAndRound(it, settings, tm.speedRange) },
        clock = { SystemClock.elapsedRealtime() },
    )
    val listeners = CopyOnWriteArraySet<Listener>()

    /** Message ponctuel à afficher (tapis déconnecté, vitesse modifiée…). */
    var notice: String? = null
        private set
    private var noticeUntil = 0L

    var lastTreadmillSpeed: Double? = null
        private set
    private var lastCommandMs = 0L
    private var lastRawDistance: Int? = null
    private var lastDataMs = 0L
    private var released = false

    private val tick = object : Runnable {
        override fun run() {
            if (released) return
            handle(engine.tick())
            if (notice != null && SystemClock.elapsedRealtime() > noticeUntil) notice = null
            listeners.forEach { it.onUpdate() }
            main.postDelayed(this, 250)
        }
    }

    // Déclaré après « tick » : les propriétés sont initialisées dans l'ordre.
    init {
        tm.listeners.add(this)
        main.post(tick)
    }

    // ---------- Actions ----------

    fun start() {
        if (engine.phase != WorkoutEngine.Phase.READY) return
        AppLog.add("Séance", "Départ : ${workout.name} (${engine.steps.size} étapes)")
        tm.requestControl()
        val incline = prefs.startInclinePct
        if (!incline.isNaN()) tm.setIncline(incline)
        tm.start()
        handle(engine.start())
    }

    fun pause() {
        if (engine.phase != WorkoutEngine.Phase.RUNNING) return
        engine.pause()
        tm.pause()
        speaker.say("Pause.")
        update()
    }

    fun resume() {
        if (engine.phase != WorkoutEngine.Phase.PAUSED) return
        engine.resume()
        tm.start()
        sendTarget()
        speaker.say("Reprise, ${WorkoutEngine.spokenNumber(engine.targetKmh())} kilomètres heure.")
        update()
    }

    fun skip() = handle(engine.skip())

    fun back() = handle(engine.back())

    fun adjust(delta: Double) {
        if (engine.phase != WorkoutEngine.Phase.RUNNING && engine.phase != WorkoutEngine.Phase.PAUSED) return
        engine.adjust(delta)
        sendTarget()
        update()
    }

    /** Arrêt avant la fin : on arrête la bande. */
    fun stopEarly() {
        if (engine.phase == WorkoutEngine.Phase.FINISHED) return
        engine.finish()
        tm.stopBelt()
        speaker.say("Séance arrêtée.")
        AppLog.add("Séance", "Arrêtée par l'utilisateur")
        update()
    }

    fun release() {
        if (released) return
        released = true
        tm.listeners.remove(this)
        main.removeCallbacksAndMessages(null)
        speaker.shutdown()
        listeners.clear()
    }

    // ---------- Interne ----------

    private fun handle(events: List<WorkoutEngine.Event>) {
        for (e in events) when (e) {
            is WorkoutEngine.Event.StepStarted -> {
                sendTarget()
                val p = engine.steps[e.index]
                AppLog.add("Séance", "Étape ${e.index + 1} : ${p.title} → ${Format.kmh(engine.targetKmh())} km/h")
                speaker.beep(long = true)
                speaker.say(WorkoutEngine.announce(p, e.index, engine.steps.size, engine.targetKmh()))
            }
            is WorkoutEngine.Event.Countdown -> speaker.beep()
            is WorkoutEngine.Event.AlmostDone -> speaker.beep()
            is WorkoutEngine.Event.Finished -> if (e.completed) {
                val end = prefs.endSpeedKmh
                if (end > 0.05) {
                    tm.setSpeed(SpeedPlanner.clampAndRound(end, settings, tm.speedRange))
                    speaker.say("Séance terminée. Bravo ! Marche à ${WorkoutEngine.spokenNumber(end)} kilomètres heure.")
                } else {
                    tm.stopBelt()
                    speaker.say("Séance terminée. Bravo !")
                }
                AppLog.add("Séance", "Terminée")
            }
        }
        if (events.isNotEmpty()) update()
    }

    private var checkToken = 0
    private var retriedToken = -1

    /**
     * Envoie la vitesse cible, puis vérifie 5 s et 11 s plus tard que le tapis l'a bien prise
     * (le temps d'accélérer). Sinon on la renvoie, et on relance la bande si elle est arrêtée.
     */
    private fun sendTarget() {
        if (engine.phase != WorkoutEngine.Phase.RUNNING) return
        tm.setSpeed(engine.targetKmh())
        lastCommandMs = SystemClock.elapsedRealtime()
        val token = ++checkToken
        main.postDelayed({ verifySpeed(token, last = false) }, 5000)
        main.postDelayed({ verifySpeed(token, last = true) }, 11000)
    }

    private fun verifySpeed(token: Int, last: Boolean) {
        if (released || token != checkToken || engine.phase != WorkoutEngine.Phase.RUNNING) return
        val actual = lastTreadmillSpeed ?: return
        val target = engine.targetKmh()
        if (abs(actual - target) <= 0.4) return
        AppLog.add("Séance", "Tapis à ${Format.kmh(actual)} km/h au lieu de ${Format.kmh(target)} : renvoi de la consigne")
        if (actual < 0.5) tm.start()
        tm.setSpeed(target)
        lastCommandMs = SystemClock.elapsedRealtime()
        if (last && actual < 0.5) {
            showNotice("Le tapis ne démarre pas : appuie sur Start sur la console.", 10000)
        }
    }

    private fun update() = listeners.forEach { it.onUpdate() }

    private fun showNotice(text: String, ms: Long = 6000) {
        notice = text
        noticeUntil = SystemClock.elapsedRealtime() + ms
        update()
    }

    // ---------- Événements du tapis ----------

    override fun onData(data: Ftms.TreadmillData) {
        val now = SystemClock.elapsedRealtime()
        data.speedKmh?.let { lastTreadmillSpeed = it }
        val raw = data.distanceM
        if (raw != null) {
            val prev = lastRawDistance
            lastRawDistance = raw
            if (prev != null) {
                val delta = raw - prev
                if (delta in 1..99) engine.addDistance(delta.toDouble())
            }
        } else if (lastDataMs > 0) {
            // Pas de distance fournie : on l'intègre à partir de la vitesse.
            val dt = (now - lastDataMs) / 1000.0
            val v = (data.speedKmh ?: 0.0) / 3.6
            if (dt in 0.0..5.0) engine.addDistance(v * dt)
        }
        lastDataMs = now
    }

    override fun onResponse(response: Ftms.ControlResponse) {
        // Consigne de vitesse refusée (bande pas encore lancée, compte à rebours…) : un nouvel essai.
        if (response.requestOp == Ftms.OP_SET_SPEED && !response.ok &&
            engine.phase == WorkoutEngine.Phase.RUNNING && retriedToken != checkToken
        ) {
            retriedToken = checkToken
            main.postDelayed({
                if (!released && engine.phase == WorkoutEngine.Phase.RUNNING) {
                    if ((lastTreadmillSpeed ?: 0.0) < 0.5) tm.start()
                    tm.setSpeed(engine.targetKmh())
                    lastCommandMs = SystemClock.elapsedRealtime()
                }
            }, 1500)
        }
    }

    override fun onStatus(event: Ftms.StatusEvent) {
        when (event) {
            is Ftms.StatusEvent.StoppedByUser, Ftms.StatusEvent.SafetyKey -> {
                if (engine.phase == WorkoutEngine.Phase.RUNNING) {
                    engine.pause()
                    speaker.say("Pause.")
                    showNotice(
                        if (event == Ftms.StatusEvent.SafetyKey) "Clé de sécurité retirée : séance en pause."
                        else "Tapis arrêté depuis la console : séance en pause.",
                    )
                }
            }
            Ftms.StatusEvent.StartedByUser -> {
                if (engine.phase == WorkoutEngine.Phase.PAUSED) {
                    engine.resume()
                    sendTarget()
                    showNotice("Reprise depuis la console.")
                }
            }
            is Ftms.StatusEvent.TargetSpeedChanged -> {
                val sinceCommand = SystemClock.elapsedRealtime() - lastCommandMs
                if (engine.phase == WorkoutEngine.Phase.RUNNING && sinceCommand > 2500 &&
                    abs(event.kmh - engine.targetKmh()) > 0.05
                ) {
                    // Changement fait sur la console : on le respecte jusqu'à la fin de l'étape.
                    engine.acceptManualTarget(event.kmh)
                    showNotice("Vitesse changée sur le tapis : ${Format.kmh(event.kmh)} km/h pour cette étape.")
                }
            }
            else -> Unit
        }
    }

    override fun onState(state: TreadmillManager.State, message: String?) {
        when (state) {
            TreadmillManager.State.DISCONNECTED ->
                if (engine.phase == WorkoutEngine.Phase.RUNNING || engine.phase == WorkoutEngine.Phase.PAUSED) {
                    showNotice("Tapis déconnecté, reconnexion en cours…", 15000)
                    lastRawDistance = null
                }
            TreadmillManager.State.READY ->
                if (engine.phase == WorkoutEngine.Phase.RUNNING) {
                    AppLog.add("Séance", "Tapis reconnecté : renvoi de la vitesse")
                    showNotice("Tapis reconnecté.")
                    sendTarget()
                }
            else -> Unit
        }
    }
}
