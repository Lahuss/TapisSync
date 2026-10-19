package fr.tapissync.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import fr.tapissync.App
import fr.tapissync.ble.Ftms
import fr.tapissync.ble.TreadmillManager
import fr.tapissync.run.RunSession
import fr.tapissync.run.WorkoutService
import fr.tapissync.ui.Ui.addSpaced
import fr.tapissync.workout.EndCondition
import fr.tapissync.workout.Format
import fr.tapissync.workout.PlannedStep
import fr.tapissync.workout.SpeedPlanner
import fr.tapissync.workout.Workout

/** Aperçu de la séance : chaque étape avec la vitesse exacte qui sera envoyée au tapis. */
class PreviewActivity : Activity(), TreadmillManager.Listener {

    private val app get() = App.instance
    private lateinit var workout: Workout
    private lateinit var summary: TextView
    private lateinit var settingsLine: TextView
    private lateinit var tmLine: TextView
    private lateinit var stepsBox: LinearLayout
    private var plan: List<PlannedStep> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val w = app.selectedWorkout
        if (w == null) {
            finish()
            return
        }
        workout = w
        val root = Ui.page(this, w.name, w.description)

        if (!w.isRunning) {
            root.addSpaced(Ui.text(this, "Attention : cette séance n'est pas une séance de course (${w.sportKey}).", 14f, Ui.WARN), this, 8)
        }

        val head = Ui.card(this)
        summary = Ui.text(this, "", 16f, bold = true)
        head.addView(summary)
        settingsLine = Ui.text(this, "", 14f, Ui.MUTED)
        head.addSpaced(settingsLine, this, 4)
        head.addSpaced(Ui.button(this, "Régler les vitesses", primary = false) {
            startActivity(Intent(this, SettingsActivity::class.java))
        }, this, 10)
        root.addSpaced(head, this, 16)

        val tmCard = Ui.card(this)
        tmLine = Ui.text(this, "", 15f)
        tmCard.addView(tmLine)
        tmCard.addSpaced(Ui.button(this, "Tapis…", primary = false) {
            startActivity(Intent(this, TreadmillActivity::class.java))
        }, this, 8)
        root.addSpaced(tmCard, this, 12)

        root.addSpaced(Ui.button(this, "Démarrer la séance") { start() }, this, 16)
        root.addSpaced(
            Ui.text(this, "Garde la clé de sécurité attachée. Tu peux lancer la même séance sur ta Fenix 8 au même moment.", 13f, Ui.MUTED),
            this, 8,
        )

        root.addSpaced(Ui.text(this, "Étapes", 20f, bold = true), this, 24)
        stepsBox = Ui.vertical(this)
        root.addSpaced(stepsBox, this, 4)
    }

    override fun onResume() {
        super.onResume()
        if (!::workout.isInitialized) return
        app.treadmill.listeners.add(this)
        recompute()
        updateTreadmill()
    }

    override fun onPause() {
        app.treadmill.listeners.remove(this)
        super.onPause()
    }

    private fun recompute() {
        val s = app.prefs.speedSettings
        plan = SpeedPlanner.plan(workout, s, app.treadmill.speedRange)
        val dur = SpeedPlanner.estimatedSeconds(plan)
        val dist = SpeedPlanner.estimatedMeters(plan)
        val manual = plan.count { it.step.end is EndCondition.Manual }
        summary.text = buildString {
            append("${plan.size} étapes · environ ${Format.duration(dur)} · ${Format.distance(dist)}")
            if (manual > 0) append("\n$manual étape(s) à passer avec « Suivant »")
        }
        val pos = when {
            s.rangePosition <= 0.01 -> "bout lent de la plage"
            s.rangePosition >= 0.99 -> "bout rapide de la plage"
            kotlin.math.abs(s.rangePosition - 0.5) < 0.01 -> "milieu de la plage"
            else -> "${Math.round(s.rangePosition * 100)} % de la plage vers le rapide"
        }
        val corr = if (kotlin.math.abs(s.correctionPct) > 0.001) " · correction ${Format.num1(s.correctionPct)} %" else ""
        settingsLine.text = "Allure visée : $pos$corr"

        stepsBox.removeAllViews()
        plan.forEachIndexed { i, p ->
            val row = Ui.card(this)
            val top = Ui.horizontal(this)
            top.addView(Ui.text(this, "${i + 1}. ${p.title}", 16f, bold = true), Ui.lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            top.addView(Ui.text(this, "${Format.kmh(p.speedKmh)} km/h", 18f, if (p.fromTarget) Ui.ACCENT else Ui.TEXT, bold = true))
            row.addView(top)
            row.addView(Ui.text(this, "${Format.endCondition(p.step.end)} · ${Format.pace(p.speedKmh)} /km", 14f, Ui.MUTED))
            row.addView(Ui.text(this, p.explanation, 13f, Ui.MUTED))
            p.step.description?.let { row.addView(Ui.text(this, it, 13f, Ui.MUTED)) }
            stepsBox.addSpaced(row, this, 8)
        }
    }

    private fun updateTreadmill() {
        val tm = app.treadmill
        val ready = tm.state == TreadmillManager.State.READY && tm.hasControlPoint
        tmLine.text = when {
            ready -> "Tapis prêt : ${tm.deviceName ?: ""}"
            tm.state == TreadmillManager.State.READY -> "Tapis connecté mais non pilotable : voir Tapis…"
            app.prefs.treadmillAddress == null -> "Aucun tapis choisi."
            else -> "Tapis : ${tm.state.label.lowercase()}"
        }
        tmLine.setTextColor(if (ready) Ui.OK else Ui.WARN)
    }

    private fun start() {
        if (app.session != null) {
            AlertDialog.Builder(this)
                .setMessage("Une séance est déjà en cours. Termine-la avant d'en lancer une autre.")
                .setPositiveButton("Voir la séance") { _, _ -> startActivity(Intent(this, RunActivity::class.java)) }
                .setNegativeButton("Annuler", null)
                .show()
            return
        }
        if (plan.isEmpty()) return
        val tm = app.treadmill
        if (tm.state != TreadmillManager.State.READY || !tm.hasControlPoint) {
            AlertDialog.Builder(this)
                .setTitle("Tapis non prêt")
                .setMessage("Le tapis n'est pas connecté ou ne peut pas être piloté. Lancer quand même ? Tu devras régler la vitesse à la main.")
                .setPositiveButton("Lancer quand même") { _, _ -> launch() }
                .setNegativeButton("Annuler", null)
                .show()
            return
        }
        launch()
    }

    private fun launch() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 3)
        }
        app.session = RunSession(app, workout, plan)
        WorkoutService.start(this)
        startActivity(Intent(this, RunActivity::class.java))
        finish()
    }

    override fun onState(state: TreadmillManager.State, message: String?) {
        updateTreadmill()
        if (state == TreadmillManager.State.READY) recompute()
    }

    override fun onData(data: Ftms.TreadmillData) = Unit
}
