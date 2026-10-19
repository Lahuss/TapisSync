package fr.tapissync.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
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
import kotlin.math.abs

/** Aperçu de la séance : profil, chiffres clés et vitesse exacte de chaque étape. */
class PreviewActivity : Activity(), TreadmillManager.Listener {

    private val app get() = App.instance
    private lateinit var workout: Workout
    private lateinit var profile: LedProfileView
    private lateinit var stats: LinearLayout
    private lateinit var manualNote: TextView
    private lateinit var settingsLine: TextView
    private lateinit var tmLine: Ui.StatusLine
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
        val wide = Ui.isWide(this)
        val pad = Ui.dp(this, if (wide) 32 else 22)

        val left: LinearLayout
        val right: LinearLayout
        if (wide) {
            val split = Ui.horizontal(this).apply {
                setBackgroundColor(Ui.BG)
                gravity = Gravity.TOP
            }
            left = Ui.vertical(this).apply { setPadding(pad, pad, pad / 2, pad) }
            right = Ui.vertical(this).apply { setPadding(pad / 2, pad, pad, pad) }
            split.addView(scroll(left), Ui.lp(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.1f))
            split.addView(scroll(right), Ui.lp(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            setContentView(split)
        } else {
            val col = Ui.vertical(this).apply { setPadding(pad, pad, pad, pad * 2) }
            setContentView(scroll(col))
            left = col
            right = col
        }

        left.addView(Ui.header(this, w.name, 40f))
        w.description?.let { left.addSpaced(Ui.text(this, it, 16f, Ui.MUTED), this, 6) }
        if (!w.isRunning) {
            left.addSpaced(Ui.text(this, "Cette séance n'est pas une séance de course (${w.sportKey}).", 15f, Ui.WARN), this, 8)
        }

        profile = LedProfileView(this, rows = 10)
        left.addSpaced(profile, this, 22)

        stats = Ui.horizontal(this).apply { gravity = Gravity.TOP }
        left.addSpaced(stats, this, 20)
        manualNote = Ui.text(this, "", 15f, Ui.MUTED)
        left.addSpaced(manualNote, this, 8)

        val settings = Ui.card(this)
        settingsLine = Ui.text(this, "", 16f)
        settings.addView(settingsLine)
        settings.addSpaced(Ui.button(this, "Régler les vitesses", primary = false) {
            startActivity(Intent(this, SettingsActivity::class.java))
        }, this, 12)
        left.addSpaced(settings, this, 18)

        val tmCard = Ui.card(this)
        tmLine = Ui.status(this)
        tmCard.addView(tmLine)
        tmCard.addSpaced(Ui.button(this, "Choisir ou tester le tapis", primary = false) {
            startActivity(Intent(this, TreadmillActivity::class.java))
        }, this, 12)
        left.addSpaced(tmCard, this, 12)

        left.addSpaced(Ui.button(this, "Démarrer la séance") { start() }.apply {
            minHeight = Ui.dp(this@PreviewActivity, 68)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
        }, this, 22)
        left.addSpaced(
            Ui.text(this, "Garde la clé de sécurité attachée. Tu peux lancer la même séance sur ta montre au même moment.", 15f, Ui.MUTED),
            this, 10,
        )

        right.addSpaced(Ui.section(this, "Étapes"), this, if (wide) 0 else 32)
        stepsBox = Ui.vertical(this)
        right.addSpaced(stepsBox, this, 6)
    }

    private fun scroll(content: View): ScrollView = ScrollView(this).apply {
        setBackgroundColor(Ui.BG)
        isFillViewport = true
        addView(content)
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
        profile.setPlan(plan)

        val dur = SpeedPlanner.estimatedSeconds(plan)
        val dist = SpeedPlanner.estimatedMeters(plan)
        stats.removeAllViews()
        stats.addView(Ui.stat(this, Format.duration(dur), "durée estimée", Ui.ACCENT), Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        stats.addView(Ui.stat(this, Format.distance(dist), "distance estimée"), Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        stats.addView(Ui.stat(this, plan.size.toString(), if (plan.size > 1) "étapes" else "étape"), Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.7f))

        val manual = plan.count { it.step.end is EndCondition.Manual }
        manualNote.text = when (manual) {
            0 -> ""
            1 -> "Une étape se termine quand tu touches « Suivant » : elle n'est pas comptée dans la durée."
            else -> "$manual étapes se terminent quand tu touches « Suivant » : elles ne sont pas comptées dans la durée."
        }
        manualNote.visibility = if (manual > 0) View.VISIBLE else View.GONE

        val pos = when {
            s.rangePosition <= 0.01 -> "au bout lent de la plage d'allure"
            s.rangePosition >= 0.99 -> "au bout rapide de la plage d'allure"
            abs(s.rangePosition - 0.5) < 0.01 -> "au milieu de la plage d'allure"
            else -> "à ${Math.round(s.rangePosition * 100)} % de la plage d'allure, vers le rapide"
        }
        val corr = if (abs(s.correctionPct) > 0.001) ", avec une correction de ${Format.num1(s.correctionPct)} %" else ""
        settingsLine.text = "Vitesses calculées $pos$corr."

        stepsBox.removeAllViews()
        plan.forEachIndexed { i, p -> stepsBox.addSpaced(stepRow(i, p), this, 8) }
    }

    /** Une ligne par étape : numéro, nom, durée et allure, vitesse envoyée au tapis. */
    private fun stepRow(i: Int, p: PlannedStep): View {
        val row = Ui.horizontal(this).apply {
            background = Ui.rounded(Ui.SURFACE, 4, this@PreviewActivity)
            val pd = Ui.dp(this@PreviewActivity, 14)
            setPadding(pd, pd, pd + Ui.dp(this@PreviewActivity, 4), pd)
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(
            Ui.display(this, (i + 1).toString(), 28f, if (p.fromTarget) Ui.ACCENT else Ui.MUTED).apply {
                gravity = Gravity.CENTER
            },
            Ui.lp(Ui.dp(this, 44), ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        val info = Ui.vertical(this)
        info.addView(Ui.label(this, p.title, 20f))
        info.addView(Ui.text(this, "${Format.endCondition(p.step.end)}, ${Format.pace(p.speedKmh)} /km", 15f, Ui.TEXT))
        info.addView(Ui.text(this, p.explanation, 14f, Ui.MUTED))
        p.step.description?.let { info.addView(Ui.text(this, it, 14f, Ui.MUTED)) }
        row.addView(info, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = Ui.dp(this@PreviewActivity, 8)
        })
        val speed = Ui.vertical(this).apply { gravity = Gravity.END }
        speed.addView(Ui.display(this, Format.kmh(p.speedKmh), 32f, if (p.fromTarget) Ui.ACCENT else Ui.TEXT))
        speed.addView(Ui.text(this, "km/h", 13f, Ui.MUTED).apply { gravity = Gravity.END })
        row.addView(speed)
        return row
    }

    private fun updateTreadmill() {
        val tm = app.treadmill
        val ready = tm.state == TreadmillManager.State.READY && tm.hasControlPoint
        when {
            ready -> tmLine.set("Tapis prêt : ${tm.deviceName ?: ""}", Ui.OK)
            tm.state == TreadmillManager.State.READY -> tmLine.set("Tapis connecté mais pas pilotable.", Ui.WARN)
            app.prefs.treadmillAddress == null -> tmLine.set("Aucun tapis choisi.", Ui.WARN)
            else -> tmLine.set("Tapis : ${tm.state.label.lowercase()}${tm.stateMessage?.let { ", $it" } ?: ""}", Ui.WARN)
        }
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
