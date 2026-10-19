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

/** Écran de séance. Passe en mode incrusté (PiP) quand tu ouvres Netflix ou une autre appli. */
class RunActivity : Activity(), RunSession.Listener {

    private val app get() = App.instance
    private var session: RunSession? = null
    private var inPip = false

    private lateinit var stepLabel: TextView
    private lateinit var totals: TextView
    private lateinit var notice: TextView
    private lateinit var target: TextView
    private lateinit var targetUnit: TextView
    private lateinit var pace: TextView
    private lateinit var actual: TextView
    private lateinit var remaining: TextView
    private lateinit var remainingLabel: TextView
    private lateinit var progress: ProgressBar
    private lateinit var nextLine: TextView
    private lateinit var controls: LinearLayout
    private lateinit var startBox: LinearLayout
    private lateinit var endBox: LinearLayout
    private lateinit var endSummary: TextView
    private lateinit var pauseButton: Button
    private lateinit var header: LinearLayout

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

    private fun buildUi() {
        val scroll = ScrollView(this).apply { setBackgroundColor(Ui.BG); isFillViewport = true }
        val root = Ui.vertical(this).apply {
            val p = Ui.dp(this@RunActivity, 16)
            setPadding(p, p, p, p)
        }
        scroll.addView(root)
        setContentView(scroll)

        header = Ui.horizontal(this)
        stepLabel = Ui.text(this, "", 18f, bold = true)
        header.addView(stepLabel, Ui.lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        totals = Ui.text(this, "", 14f, Ui.MUTED).apply { gravity = Gravity.END }
        header.addView(totals)
        root.addView(header)

        notice = Ui.text(this, "", 15f, Ui.WARN).apply { visibility = View.GONE }
        root.addSpaced(notice, this, 8)

        val targetRow = Ui.horizontal(this).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL }
        target = Ui.text(this, "--", 96f, Ui.ACCENT, bold = true, mono = true)
        targetUnit = Ui.text(this, " km/h", 24f, Ui.MUTED)
        targetRow.addView(target)
        targetRow.addView(targetUnit)
        root.addSpaced(targetRow, this, 8)

        pace = Ui.text(this, "", 22f, Ui.TEXT).apply { gravity = Gravity.CENTER_HORIZONTAL }
        root.addView(pace, Ui.lp())
        actual = Ui.text(this, "", 15f, Ui.MUTED).apply { gravity = Gravity.CENTER_HORIZONTAL }
        root.addView(actual, Ui.lp())

        remaining = Ui.text(this, "", 64f, Ui.TEXT, bold = true, mono = true).apply { gravity = Gravity.CENTER_HORIZONTAL }
        root.addSpaced(remaining, this, 16)
        remainingLabel = Ui.text(this, "", 15f, Ui.MUTED).apply { gravity = Gravity.CENTER_HORIZONTAL }
        root.addView(remainingLabel, Ui.lp())

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(Ui.ACCENT)
            progressBackgroundTintList = ColorStateList.valueOf(Ui.SURFACE_2)
        }
        root.addSpaced(progress, this, 12, Ui.lp(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 10)))

        nextLine = Ui.text(this, "", 16f, Ui.MUTED)
        root.addSpaced(nextLine, this, 12)

        // Avant le départ
        startBox = Ui.vertical(this)
        startBox.addView(Ui.button(this, "Démarrer") { session?.start() }.apply {
            minHeight = Ui.dp(this@RunActivity, 72)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        })
        startBox.addSpaced(Ui.text(this, "Le tapis démarre puis prend la vitesse de la première étape.", 14f, Ui.MUTED), this, 8)
        root.addSpaced(startBox, this, 20)

        // Pendant la séance
        controls = Ui.vertical(this)
        val adjust = Ui.horizontal(this)
        adjust.addView(Ui.button(this, "− 0,1 km/h", primary = false) { session?.adjust(-0.1) }, Ui.lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        adjust.addView(View(this), Ui.lp(Ui.dp(this, 8), 1))
        adjust.addView(Ui.button(this, "+ 0,1 km/h", primary = false) { session?.adjust(0.1) }, Ui.lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        controls.addView(adjust)
        val nav = Ui.horizontal(this)
        nav.addView(Ui.button(this, "◀ Étape", primary = false) { session?.back() }, Ui.lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        nav.addView(View(this), Ui.lp(Ui.dp(this, 8), 1))
        pauseButton = Ui.button(this, "Pause") { togglePause() }
        nav.addView(pauseButton, Ui.lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.3f))
        nav.addView(View(this), Ui.lp(Ui.dp(this, 8), 1))
        nav.addView(Ui.button(this, "Suivant ▶", primary = false) { session?.skip() }, Ui.lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        controls.addSpaced(nav, this, 10)
        controls.addSpaced(Ui.button(this, "Terminer la séance", primary = false) { confirmStop() }, this, 10)
        root.addSpaced(controls, this, 20)

        // Fin
        endBox = Ui.vertical(this)
        endSummary = Ui.text(this, "", 16f)
        endBox.addView(endSummary)
        endBox.addSpaced(Ui.button(this, "Fermer") { close() }, this, 12)
        root.addSpaced(endBox, this, 20)
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

        stepLabel.text = when {
            step == null -> s.workout.name
            e.phase == Phase.FINISHED -> "Séance terminée"
            else -> "${e.index + 1}/$n · ${step.title}"
        }
        totals.text = "${Format.duration(e.totalElapsedMs() / 1000.0)}\n${Format.distance(e.totalDistanceM)}"

        val msg = s.notice ?: if (e.phase == Phase.PAUSED) "En pause" else null
        notice.text = msg ?: ""
        notice.visibility = if (msg != null) View.VISIBLE else View.GONE

        val t = if (e.phase == Phase.FINISHED) null else e.targetKmh()
        target.text = t?.let { Format.kmh(it) } ?: "--"
        pace.text = t?.let { "${Format.pace(it)} /km" } ?: ""
        actual.text = s.lastTreadmillSpeed?.let { "Tapis : ${Format.kmh(it)} km/h" } ?: "Tapis : pas de données"
        if (e.adjustKmh != 0.0 && t != null) {
            actual.text = "${actual.text} · ajusté de ${if (e.adjustKmh > 0) "+" else ""}${Format.num1(e.adjustKmh)}"
        }

        val remS = e.stepRemainingS()
        val remM = e.stepRemainingM()
        when {
            e.phase == Phase.FINISHED -> {
                remaining.text = "✓"
                remainingLabel.text = ""
            }
            remS != null -> {
                remaining.text = Format.duration(Math.ceil(remS))
                remainingLabel.text = "restantes"
            }
            remM != null -> {
                remaining.text = if (remM >= 1000) Format.distance(remM) else "${Math.ceil(remM).toInt()} m"
                remainingLabel.text = "restants"
            }
            else -> {
                remaining.text = Format.duration(e.stepElapsedMs() / 1000.0)
                remainingLabel.text = "Appuie sur « Suivant » quand tu as fini"
            }
        }
        progress.progress = ((e.stepProgress() ?: 0.0) * 1000).toInt()

        val nx = e.next
        nextLine.text = when {
            e.phase == Phase.FINISHED -> ""
            nx == null -> "Dernière étape"
            else -> "Ensuite : ${nx.title} · ${Format.endCondition(nx.step.end)} · ${Format.kmh(nx.speedKmh)} km/h"
        }
        step?.step?.description?.let { if (e.phase != Phase.FINISHED) nextLine.text = "${it}\n${nextLine.text}" }

        startBox.visibility = if (!inPip && e.phase == Phase.READY) View.VISIBLE else View.GONE
        controls.visibility = if (!inPip && (e.phase == Phase.RUNNING || e.phase == Phase.PAUSED)) View.VISIBLE else View.GONE
        endBox.visibility = if (!inPip && e.phase == Phase.FINISHED) View.VISIBLE else View.GONE
        pauseButton.text = if (e.phase == Phase.PAUSED) "Reprendre" else "Pause"
        if (e.phase == Phase.FINISHED) {
            endSummary.text = "Durée ${Format.duration(e.totalElapsedMs() / 1000.0)} · ${Format.distance(e.totalDistanceM)}\n" +
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
        header.visibility = if (inPip) View.GONE else View.VISIBLE
        nextLine.visibility = if (inPip) View.GONE else View.VISIBLE
        actual.visibility = if (inPip) View.GONE else View.VISIBLE
        target.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (inPip) 40f else 96f)
        targetUnit.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (inPip) 14f else 24f)
        pace.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (inPip) 14f else 22f)
        remaining.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (inPip) 30f else 64f)
        remainingLabel.visibility = if (inPip) View.GONE else View.VISIBLE
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
