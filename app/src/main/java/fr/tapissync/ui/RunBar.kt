package fr.tapissync.ui

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import fr.tapissync.App
import fr.tapissync.AppLog
import fr.tapissync.R
import fr.tapissync.run.RunSession
import fr.tapissync.run.WorkoutEngine.Phase
import fr.tapissync.workout.Format

/**
 * Bandeau de séance affiché en bas de l'écran, par-dessus les autres applis
 * (Kinomap, Netflix…), quand l'écran de séance n'est pas au premier plan.
 * Le reste de l'écran reste utilisable.
 */
class RunBar(private val app: App) : RunSession.Listener {

    /** Vrai quand l'écran de séance est visible : le bandeau est alors masqué. */
    var activityVisible = false
        set(v) {
            field = v
            refresh()
        }

    private val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: BarView? = null
    private var attached: RunSession? = null

    fun canDraw(): Boolean = Settings.canDrawOverlays(app)

    /** Affiche ou masque le bandeau selon la séance, l'écran visible et les réglages. */
    fun refresh() {
        val s = app.session
        if (s != null && !activityVisible && app.prefs.barEnabled && canDraw()) show(s) else hide()
    }

    private fun show(s: RunSession) {
        var v = view
        if (v == null) {
            v = BarView(ContextThemeWrapper(app, R.style.AppTheme), onOpen = ::openRunScreen)
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                // Ne prend ni le clavier ni les touches hors du bandeau : l'appli dessous reste utilisable.
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.BOTTOM
                title = "TapisSync"
            }
            try {
                wm.addView(v, lp)
            } catch (e: Exception) {
                AppLog.add("Bandeau", "Affichage impossible : ${e.message}")
                return
            }
            view = v
        }
        if (attached !== s) {
            attached?.listeners?.remove(this)
            s.listeners.add(this)
            attached = s
            v.bind(s)
        }
        v.update()
    }

    private fun hide() {
        attached?.listeners?.remove(this)
        attached = null
        val v = view ?: return
        view = null
        try {
            wm.removeView(v)
        } catch (_: Exception) {
        }
    }

    override fun onUpdate() {
        if (app.session == null) hide() else view?.update()
    }

    private fun openRunScreen() {
        app.startActivity(
            Intent(app, RunActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
        )
    }
}

/**
 * Contenu du bandeau. Séparé de la fenêtre pour pouvoir le dessiner dans les tests.
 * Version complète : étape, vitesse, temps restant, profil LED, boutons.
 * Version réduite : vitesse, temps restant, Pause et Ouvrir.
 */
class BarView(ctx: Context, private val onOpen: () -> Unit) : LinearLayout(ctx) {

    private var session: RunSession? = null
    private val prefs get() = App.instance.prefs
    private val wide = ctx.resources.configuration.screenWidthDp >= 720

    private val title: TextView
    private val sub: TextView
    private val speed: TextView
    private val speedUnit: TextView
    private val remaining: TextView
    private val remainingLabel: TextView
    private val profile: LedProfileView
    private val minus: Button
    private val plus: Button
    private val main: Button
    private val next: Button
    private val open: Button
    private val toggle: Button
    private val stepBox: LinearLayout
    private val content: LinearLayout

    init {
        orientation = VERTICAL
        setBackgroundColor(0xF2000000.toInt())
        isClickable = true

        // Liseré orange en haut : la bande LED du tapis.
        addView(View(ctx).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Ui.ACCENT, 0xFFFFA45C.toInt(), Ui.ACCENT),
            )
        }, LayoutParams(LayoutParams.MATCH_PARENT, Ui.dp(ctx, 3)))

        content = Ui.horizontal(ctx).apply {
            val h = Ui.dp(ctx, 16)
            setPadding(h, Ui.dp(ctx, 8), h, Ui.dp(ctx, 8))
        }
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // Étape en cours
        stepBox = Ui.vertical(ctx)
        title = Ui.label(ctx, "", 20f, Ui.ACCENT).apply { singleLine() }
        sub = Ui.text(ctx, "", 14f, Ui.MUTED).apply { singleLine() }
        stepBox.addView(title)
        stepBox.addView(sub)
        content.addView(stepBox, Ui.lp(Ui.dp(ctx, if (wide) 260 else 150), ViewGroup.LayoutParams.WRAP_CONTENT))

        // Vitesse cible
        val speedRow = Ui.horizontal(ctx).apply { gravity = Gravity.BOTTOM }
        speed = Ui.display(ctx, "--", 46f, Ui.ACCENT, glow = true)
        speedUnit = Ui.label(ctx, "km/h", 16f, Ui.MUTED).apply { setPadding(0, 0, 0, Ui.dp(ctx, 8)) }
        speedRow.addView(speed)
        speedRow.addView(speedUnit)
        content.addView(speedRow, Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = Ui.dp(ctx, 8)
        })

        // Temps ou distance restante
        val restBox = Ui.vertical(ctx)
        remaining = Ui.display(ctx, "", 36f, Ui.TEXT)
        remainingLabel = Ui.text(ctx, "", 13f, Ui.MUTED)
        restBox.addView(remaining)
        restBox.addView(remainingLabel)
        content.addView(restBox, Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = Ui.dp(ctx, 20)
        })

        // Profil de la séance (prend la place restante)
        profile = LedProfileView(ctx, rows = 4)
        content.addView(profile, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = Ui.dp(ctx, 20)
            marginEnd = Ui.dp(ctx, 20)
        })

        // Boutons
        minus = small(Ui.button(ctx, "−0,1", primary = false) { session?.adjust(-0.1) })
        plus = small(Ui.button(ctx, "+0,1", primary = false) { session?.adjust(0.1) })
        main = small(Ui.button(ctx, "Pause") { onMain() }, minDp = 104)
        next = small(Ui.button(ctx, "Suivant", primary = false) { session?.skip() })
        open = small(Ui.button(ctx, "Ouvrir", primary = false) { onOpen() })
        toggle = small(Ui.button(ctx, "", primary = false) { toggleCollapsed() }, minDp = 48)
        for (b in listOf(minus, plus, main, next, open, toggle)) {
            content.addView(b, Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = Ui.dp(ctx, 8)
            })
        }
        applyCollapsed()
    }

    private fun TextView.singleLine() {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun small(b: Button, minDp: Int = 0): Button = b.apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        minHeight = Ui.dp(context, 48)
        minimumHeight = Ui.dp(context, 48)
        minWidth = Ui.dp(context, minDp)
        minimumWidth = Ui.dp(context, minDp)
        val h = Ui.dp(context, 14)
        setPadding(h, Ui.dp(context, 6), h, Ui.dp(context, 6))
    }

    fun bind(s: RunSession) {
        session = s
        profile.setPlan(s.engine.steps)
        update()
    }

    private fun onMain() {
        val s = session ?: return
        when (s.engine.phase) {
            Phase.READY -> s.start()
            Phase.RUNNING -> s.pause()
            Phase.PAUSED -> s.resume()
            Phase.FINISHED -> onOpen()
        }
    }

    private fun toggleCollapsed() {
        prefs.barCollapsed = !prefs.barCollapsed
        applyCollapsed()
        update()
    }

    private fun applyCollapsed() {
        val c = prefs.barCollapsed
        toggle.text = if (c) "▲" else "▼"
        val full = if (c) View.GONE else View.VISIBLE
        sub.visibility = full
        minus.visibility = full
        plus.visibility = full
        next.visibility = full
        profile.visibility = if (c || !wide) View.GONE else View.VISIBLE
        remainingLabel.visibility = full
        speed.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (c) 32f else 46f)
        remaining.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (c) 28f else 36f)
        // En version réduite, le profil disparaît : un espace élastique garde les boutons à droite.
        (stepBox.layoutParams as LayoutParams).apply {
            width = if (c || !wide) 0 else Ui.dp(context, 260)
            weight = if (c || !wide) 1f else 0f
        }
        stepBox.requestLayout()
    }

    fun update() {
        val s = session ?: return
        val e = s.engine
        val step = e.current
        val finished = e.phase == Phase.FINISHED

        title.text = when {
            finished -> "Séance terminée"
            e.phase == Phase.READY -> s.workout.name
            step == null -> s.workout.name
            else -> step.title
        }
        val notice = s.notice ?: if (e.phase == Phase.PAUSED) "En pause" else null
        val nx = e.next
        sub.text = when {
            notice != null -> notice
            finished -> "${Format.duration(e.totalElapsedMs() / 1000.0)}, ${Format.distance(e.totalDistanceM)}"
            e.phase == Phase.READY -> "Prêt à démarrer"
            nx == null -> "Étape ${e.index + 1} sur ${e.steps.size}, dernière étape"
            else -> "Étape ${e.index + 1} sur ${e.steps.size}, ensuite ${nx.title.lowercase()} à ${Format.kmh(nx.speedKmh)} km/h"
        }
        sub.setTextColor(if (notice != null) Ui.WARN else Ui.MUTED)

        speed.text = if (finished) "--" else Format.kmh(e.targetKmh())

        val remS = e.stepRemainingS()
        val remM = e.stepRemainingM()
        when {
            finished -> {
                remaining.text = "Bravo"
                remainingLabel.text = ""
            }
            e.phase == Phase.READY -> {
                remaining.text = ""
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
                remainingLabel.text = "touche Suivant"
            }
        }

        val fraction = e.stepProgress() ?: (e.stepElapsedMs() / 60_000.0).coerceAtMost(0.95)
        profile.position = when (e.phase) {
            Phase.READY -> -1.0
            Phase.FINISHED -> 1.0
            else -> profile.positionFor(e.index, fraction)
        }

        main.text = when (e.phase) {
            Phase.READY -> "Démarrer"
            Phase.RUNNING -> "Pause"
            Phase.PAUSED -> "Reprendre"
            Phase.FINISHED -> "Terminer"
        }
        val running = e.phase == Phase.RUNNING || e.phase == Phase.PAUSED
        val full = !prefs.barCollapsed
        minus.visibility = if (full && running) View.VISIBLE else View.GONE
        plus.visibility = if (full && running) View.VISIBLE else View.GONE
        next.visibility = if (full && running) View.VISIBLE else View.GONE
    }
}
