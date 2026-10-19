package fr.tapissync.ui

import android.app.Activity
import android.app.AlertDialog
import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import fr.tapissync.App
import fr.tapissync.run.RunSession
import fr.tapissync.run.WorkoutEngine.Phase
import fr.tapissync.run.WorkoutService
import fr.tapissync.ui.Ui.addSpaced
import fr.tapissync.workout.Format

/**
 * Écran de séance, lisible en courant : vitesse géante façon LED, temps restant,
 * profil de la séance. Passe en fenêtre incrustée quand tu ouvres une autre appli.
 */
class RunActivity : Activity(), RunSession.Listener {

    private val app get() = App.instance
    private var session: RunSession? = null
    private var inPip = false

    private lateinit var stepLabel: TextView
    private lateinit var stepCount: TextView
    private lateinit var notice: TextView
    private lateinit var target: TextView
    private lateinit var targetUnit: TextView
    private lateinit var pace: TextView
    private lateinit var actual: TextView
    private lateinit var remaining: TextView
    private lateinit var remainingLabel: TextView
    private lateinit var progress: ProgressBar
    private lateinit var profile: LedProfileView
    private lateinit var elapsedStat: TextView
    private lateinit var distanceStat: TextView
    private lateinit var nextLine: TextView
    private lateinit var controls: LinearLayout
    private lateinit var startBox: LinearLayout
    private lateinit var endBox: LinearLayout
    private lateinit var endSummary: TextView
    private lateinit var pauseButton: Button

    /** Éléments masqués en fenêtre incrustée. */
    private val fullOnly = mutableListOf<View>()
    private var wideLayout = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        session = app.session
        if (session == null) {
            finish()
            return
        }
        buildUi()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Rotation ou redimensionnement : on reconstruit la mise en page sans quitter l'écran.
        if (inPip || session == null) return
        val wide = isWideNow()
        if (wide != wideLayout) {
            buildUi()
            onUpdate()
        }
    }

    private fun isWideNow(): Boolean {
        val c = resources.configuration
        return c.orientation == Configuration.ORIENTATION_LANDSCAPE && c.screenWidthDp >= 600
    }

    private fun buildUi() {
        fullOnly.clear()
        wideLayout = isWideNow()
        val pad = Ui.dp(this, if (wideLayout) 28 else 18)

        val scroll = ScrollView(this).apply { setBackgroundColor(Ui.BG); isFillViewport = true }
        val root = Ui.vertical(this).apply { setPadding(pad, pad, pad, pad) }
        scroll.addView(root)
        setContentView(scroll)

        // Bandeau : étape en cours
        val top = Ui.horizontal(this)
        stepLabel = Ui.label(this, "", if (wideLayout) 30f else 24f, Ui.ACCENT)
        top.addView(stepLabel, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        stepCount = Ui.label(this, "", if (wideLayout) 22f else 18f, Ui.MUTED)
        top.addView(stepCount)
        root.addView(top)
        fullOnly += top

        notice = Ui.label(this, "", 20f, Ui.WARN).apply { visibility = View.GONE }
        root.addSpaced(notice, this, 6)
        fullOnly += notice

        // Vitesse cible
        val speedBox = Ui.vertical(this).apply { gravity = Gravity.CENTER_HORIZONTAL }
        val speedRow = Ui.horizontal(this).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL }
        target = Ui.display(this, "--", if (wideLayout) 190f else 128f, Ui.ACCENT, glow = true)
        targetUnit = Ui.label(this, " km/h", if (wideLayout) 34f else 26f, Ui.MUTED).apply {
            setPadding(0, 0, 0, Ui.dp(this@RunActivity, if (wideLayout) 26 else 18))
        }
        speedRow.addView(target)
        speedRow.addView(targetUnit)
        speedBox.addView(speedRow)
        pace = Ui.display(this, "", if (wideLayout) 40f else 32f, Ui.TEXT).apply { gravity = Gravity.CENTER_HORIZONTAL }
        speedBox.addView(pace)
        actual = Ui.text(this, "", 17f, Ui.MUTED).apply { gravity = Gravity.CENTER_HORIZONTAL }
        speedBox.addSpaced(actual, this, 6)
        fullOnly += actual

        // Temps ou distance restante
        // En paysage la colonne de droite est alignée à gauche ; en portrait tout est centré.
        val restGravity = if (wideLayout) Gravity.START else Gravity.CENTER_HORIZONTAL
        val restBox = Ui.vertical(this)
        remaining = Ui.display(this, "", if (wideLayout) 120f else 88f, Ui.TEXT).apply { gravity = restGravity }
        restBox.addView(remaining)
        remainingLabel = Ui.text(this, "", 17f, Ui.MUTED).apply { gravity = restGravity }
        restBox.addView(remainingLabel)
        fullOnly += remainingLabel
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(Ui.ACCENT)
            progressBackgroundTintList = ColorStateList.valueOf(Ui.LED_OFF)
        }
        restBox.addSpaced(progress, this, 12, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 10)))
        fullOnly += progress

        // Profil et totaux
        val infoBox = Ui.vertical(this)
        profile = LedProfileView(this, rows = if (wideLayout) 12 else 9)
        session?.let { profile.setPlan(it.engine.steps) }
        infoBox.addView(profile)
        val totals = Ui.horizontal(this).apply { gravity = Gravity.TOP }
        val elapsedBlock = Ui.stat(this, "0:00", "temps écoulé")
        val distanceBlock = Ui.stat(this, "0 m", "distance")
        elapsedStat = elapsedBlock.getChildAt(0) as TextView
        distanceStat = distanceBlock.getChildAt(0) as TextView
        totals.addView(elapsedBlock, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        totals.addView(distanceBlock, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        infoBox.addSpaced(totals, this, 14)
        nextLine = Ui.text(this, "", 17f, Ui.TEXT)
        infoBox.addSpaced(nextLine, this, 12)
        fullOnly += infoBox

        if (wideLayout) {
            val main = Ui.horizontal(this).apply { gravity = Gravity.CENTER_VERTICAL }
            main.addView(speedBox, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.15f))
            val rightCol = Ui.vertical(this)
            rightCol.addView(restBox)
            rightCol.addSpaced(infoBox, this, 18)
            main.addView(rightCol, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = Ui.dp(this@RunActivity, 28)
            })
            root.addSpaced(main, this, 8)
        } else {
            root.addSpaced(speedBox, this, 8)
            root.addSpaced(restBox, this, 14)
            root.addSpaced(infoBox, this, 18)
        }

        // Avant le départ
        startBox = Ui.vertical(this)
        startBox.addView(Ui.button(this, "Démarrer") { session?.start() }.apply {
            minHeight = Ui.dp(this@RunActivity, 76)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
        })
        startBox.addSpaced(Ui.text(this, "Le tapis démarre puis prend la vitesse de la première étape.", 16f, Ui.MUTED), this, 8)
        root.addSpaced(startBox, this, 20)
        fullOnly += startBox

        // Pendant la séance
        controls = Ui.vertical(this)
        val row = Ui.horizontal(this)
        row.addView(Ui.button(this, "− 0,1 km/h", primary = false) { session?.adjust(-0.1) }, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Ui.gap(this))
        row.addView(Ui.button(this, "+ 0,1 km/h", primary = false) { session?.adjust(0.1) }, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (wideLayout) row.addView(Ui.gap(this, 24))
        val nav = if (wideLayout) row else Ui.horizontal(this)
        nav.addView(Ui.button(this, "Précédent", primary = false) { session?.back() }, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        nav.addView(Ui.gap(this))
        pauseButton = Ui.button(this, "Pause") { togglePause() }
        nav.addView(pauseButton, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f))
        nav.addView(Ui.gap(this))
        nav.addView(Ui.button(this, "Suivant", primary = false) { session?.skip() }, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        controls.addView(row)
        if (!wideLayout) controls.addSpaced(nav, this, 10)
        controls.addSpaced(Ui.button(this, "Terminer la séance", primary = false) { confirmStop() }, this, 10)
        root.addSpaced(controls, this, 20)
        fullOnly += controls

        // Fin
        endBox = Ui.vertical(this)
        endSummary = Ui.text(this, "", 18f)
        endBox.addView(endSummary)
        endBox.addSpaced(Ui.button(this, "Fermer") { close() }, this, 12)
        root.addSpaced(endBox, this, 20)
        fullOnly += endBox
    }

    override fun onResume() {
        super.onResume()
        val s = app.session
        if (s == null) {
            finish()
            return
        }
        session = s
        s.listeners.add(this)
        onUpdate()
    }

    override fun onPause() {
        // En mode incrusté l'activité est en pause mais reste visible : on garde l'abonnement.
        if (!isInPictureInPictureMode) session?.listeners?.remove(this)
        super.onPause()
    }

    override fun onStop() {
        session?.listeners?.remove(this)
        super.onStop()
    }

    override fun onUpdate() {
        val s = session ?: return
        val e = s.engine
        val step = e.current
        val n = e.steps.size
        val finished = e.phase == Phase.FINISHED

        stepLabel.text = when {
            finished -> "Séance terminée"
            step == null -> s.workout.name
            else -> step.title
        }
        stepCount.text = if (finished || step == null) "" else "Étape ${e.index + 1} sur $n"

        val msg = s.notice ?: if (e.phase == Phase.PAUSED) "En pause" else null
        notice.text = msg ?: ""
        notice.visibility = if (msg != null && !inPip) View.VISIBLE else View.GONE

        val t = if (finished) null else e.targetKmh()
        target.text = t?.let { Format.kmh(it) } ?: "--"
        pace.text = t?.let { "${Format.pace(it)} /km" } ?: ""
        actual.text = buildString {
            append(s.lastTreadmillSpeed?.let { "Le tapis indique ${Format.kmh(it)} km/h" } ?: "Pas de données du tapis")
            if (e.adjustKmh != 0.0 && t != null) {
                append(", ajusté de ${if (e.adjustKmh > 0) "+" else ""}${Format.num1(e.adjustKmh)} km/h")
            }
        }

        val remS = e.stepRemainingS()
        val remM = e.stepRemainingM()
        when {
            finished -> {
                remaining.text = "Bravo"
                remainingLabel.text = ""
            }
            remS != null -> {
                remaining.text = Format.duration(Math.ceil(remS))
                remainingLabel.text = "restantes sur cette étape"
            }
            remM != null -> {
                remaining.text = if (remM >= 1000) Format.distance(remM) else "${Math.ceil(remM).toInt()} m"
                remainingLabel.text = "restants sur cette étape"
            }
            else -> {
                remaining.text = Format.duration(e.stepElapsedMs() / 1000.0)
                remainingLabel.text = "Touche « Suivant » quand tu as fini cette étape"
            }
        }
        val stepFraction = e.stepProgress() ?: (e.stepElapsedMs() / 60_000.0).coerceAtMost(0.95)
        progress.progress = ((e.stepProgress() ?: 0.0) * 1000).toInt()
        profile.position = when (e.phase) {
            Phase.READY -> -1.0
            Phase.FINISHED -> 1.0
            else -> profile.positionFor(e.index, stepFraction)
        }

        elapsedStat.text = Format.duration(e.totalElapsedMs() / 1000.0)
        distanceStat.text = Format.distance(e.totalDistanceM)

        val nx = e.next
        nextLine.text = when {
            finished -> ""
            nx == null -> "Dernière étape"
            else -> "Ensuite : ${nx.title.lowercase()}, ${Format.endCondition(nx.step.end)} à ${Format.kmh(nx.speedKmh)} km/h"
        }
        step?.step?.description?.let { if (!finished) nextLine.text = "$it\n${nextLine.text}" }

        startBox.visibility = if (!inPip && e.phase == Phase.READY) View.VISIBLE else View.GONE
        controls.visibility = if (!inPip && (e.phase == Phase.RUNNING || e.phase == Phase.PAUSED)) View.VISIBLE else View.GONE
        endBox.visibility = if (!inPip && finished) View.VISIBLE else View.GONE
        pauseButton.text = if (e.phase == Phase.PAUSED) "Reprendre" else "Pause"
        if (finished) {
            endSummary.text = "Séance terminée en ${Format.duration(e.totalElapsedMs() / 1000.0)} pour ${Format.distance(e.totalDistanceM)}.\n" +
                "Pense à arrêter l'activité sur ta montre."
        }
        updatePipParams(e.phase == Phase.RUNNING || e.phase == Phase.PAUSED)
    }

    private fun togglePause() {
        val s = session ?: return
        if (s.engine.phase == Phase.PAUSED) s.resume() else s.pause()
    }

    private fun confirmStop() {
        AlertDialog.Builder(this)
            .setTitle("Terminer la séance ?")
            .setMessage("Le tapis va s'arrêter.")
            .setPositiveButton("Terminer") { _, _ -> session?.stopEarly() }
            .setNegativeButton("Continuer", null)
            .show()
    }

    private fun close() {
        session?.release()
        app.session = null
        WorkoutService.stop(this)
        finish()
    }

    // ---------- Mode incrusté ----------

    private var lastPipEnabled: Boolean? = null

    private fun pipSupported(): Boolean =
        packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    private fun pipParams(): PictureInPictureParams {
        val b = PictureInPictureParams.Builder().setAspectRatio(Rational(4, 3))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            b.setAutoEnterEnabled(lastPipEnabled == true)
            b.setSeamlessResizeEnabled(false)
        }
        return b.build()
    }

    private fun updatePipParams(enabled: Boolean) {
        if (!pipSupported() || lastPipEnabled == enabled) return
        lastPipEnabled = enabled
        try {
            setPictureInPictureParams(pipParams())
        } catch (_: Exception) {
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val phase = session?.engine?.phase
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && pipSupported() &&
            (phase == Phase.RUNNING || phase == Phase.PAUSED)
        ) {
            try {
                enterPictureInPictureMode(pipParams())
            } catch (_: Exception) {
            }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
        if (!inPip && isWideNow() != wideLayout) buildUi()
        fullOnly.forEach { it.visibility = if (inPip) View.GONE else View.VISIBLE }
        target.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (inPip) 48f else if (wideLayout) 190f else 128f)
        targetUnit.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (inPip) 14f else if (wideLayout) 34f else 26f)
        pace.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (inPip) 16f else if (wideLayout) 40f else 32f)
        remaining.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (inPip) 30f else if (wideLayout) 120f else 88f)
        if (inPip) session?.listeners?.add(this)
        onUpdate()
    }

    @Deprecated("API Activity simple, sans AndroidX")
    override fun onBackPressed() {
        val phase = session?.engine?.phase
        if ((phase == Phase.RUNNING || phase == Phase.PAUSED) && pipSupported()) {
            try {
                enterPictureInPictureMode(pipParams())
                return
            } catch (_: Exception) {
            }
        }
        if (phase == Phase.FINISHED) {
            close()
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }
}
