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
 * Il peut être rangé : il ne reste alors qu'un petit onglet orange avec la vitesse,
 * qui le fait revenir d'un appui et qu'on peut faire glisser le long du bord bas.
 */
class RunBar(private val app: App) : RunSession.Listener {

    /** Vrai quand l'écran de séance est visible : le bandeau est alors masqué. */
    var activityVisible = false
        set(v) {
            field = v
            refresh()
        }

    private val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val themed = ContextThemeWrapper(app, R.style.AppTheme)
    private var view: BarView? = null
    private var tab: TabView? = null
    private var tabParams: WindowManager.LayoutParams? = null
    private var attached: RunSession? = null

    /** Bandeau rangé (seul l'onglet est visible). Revient à faux à chaque nouvelle séance. */
    private var tucked = false
    private var animating = false

    fun canDraw(): Boolean = Settings.canDrawOverlays(app)

    /** Affiche le bandeau ou l'onglet selon la séance, l'écran visible et les réglages. */
    fun refresh() {
        val s = app.session
        if (s == null || activityVisible || !app.prefs.barEnabled || !canDraw()) {
            removeAll()
            return
        }
        attach(s)
        if (animating) return
        if (tucked) showTab() else showBar(animate = false)
    }

    private fun attach(s: RunSession) {
        if (attached === s) return
        attached?.listeners?.remove(this)
        s.listeners.add(this)
        attached = s
        tucked = false
        view?.bind(s)
    }

    private fun overlayParams(w: Int, h: Int, gravity: Int) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        // Ne prend ni le clavier ni les touches hors de la fenêtre : l'appli dessous reste utilisable.
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT,
    ).apply {
        this.gravity = gravity
        title = "TapisSync"
    }

    private fun showBar(animate: Boolean) {
        removeTab()
        var v = view
        if (v == null) {
            v = BarView(themed, onOpen = ::openRunScreen, onHide = ::tuck)
            try {
                wm.addView(v, overlayParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
            } catch (e: Exception) {
                AppLog.add("Bandeau", "Affichage impossible : ${e.message}")
                return
            }
            view = v
            attached?.let { v.bind(it) }
            if (animate) {
                // Le bandeau remonte depuis le bord de l'écran.
                v.translationY = Ui.dp(app, 160).toFloat()
                animating = true
                v.post {
                    v.translationY = v.height.toFloat()
                    v.animate().translationY(0f).setDuration(200).withEndAction { animating = false }.start()
                }
            }
        }
        v.update()
    }

    private fun showTab() {
        removeBar()
        var t = tab
        if (t == null) {
            t = TabView(themed, onTap = { untuck() }, onMoved = { x -> app.prefs.tabOffset = x })
            val lp = overlayParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END)
            lp.x = app.prefs.tabOffset.takeIf { it >= 0 } ?: Ui.dp(app, 24)
            t.attachWindow(wm, lp)
            try {
                wm.addView(t, lp)
            } catch (e: Exception) {
                AppLog.add("Bandeau", "Onglet impossible : ${e.message}")
                return
            }
            tab = t
            tabParams = lp
        }
        attached?.let { t.update(it) }
    }

    /** Range le bandeau : il descend hors de l'écran et laisse place à l'onglet. */
    private fun tuck() {
        val v = view ?: return
        if (animating) return
        tucked = true
        animating = true
        v.animate().translationY(v.height.toFloat()).setDuration(180).withEndAction {
            animating = false
            refresh()
        }.start()
    }

    private fun untuck() {
        if (animating) return
        tucked = false
        if (app.session == null || activityVisible) return
        showBar(animate = true)
    }

    private fun removeBar() {
        val v = view ?: return
        view = null
        try {
            wm.removeView(v)
        } catch (_: Exception) {
        }
    }

    private fun removeTab() {
        val t = tab ?: return
        tab = null
        tabParams = null
        try {
            wm.removeView(t)
        } catch (_: Exception) {
        }
    }

    private fun removeAll() {
        attached?.listeners?.remove(this)
        attached = null
        animating = false
        removeBar()
        removeTab()
    }

    override fun onUpdate() {
        val s = app.session
        if (s == null) {
            removeAll()
            return
        }
        // Séance terminée : le bandeau revient pour afficher le bilan.
        if (tucked && s.engine.phase == Phase.FINISHED) {
            untuck()
            return
        }
        view?.update()
        tab?.update(s)
    }

    private fun openRunScreen() {
        app.startActivity(
            Intent(app, RunActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
        )
    }
}

/**
 * Onglet orange affiché quand le bandeau est rangé : vitesse cible et flèche.
 * Un appui fait revenir le bandeau ; un glissement le déplace le long du bord bas.
 */
class TabView(
    ctx: Context,
    private val onTap: () -> Unit,
    private val onMoved: (Int) -> Unit,
) : LinearLayout(ctx) {

    private val speed: TextView
    private var wm: WindowManager? = null
    private var lp: WindowManager.LayoutParams? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = Ui.ChamferDrawable(Ui.ACCENT, null, Ui.dp(ctx, 10).toFloat(), 0f)
        val h = Ui.dp(ctx, 16)
        setPadding(h, Ui.dp(ctx, 6), h, Ui.dp(ctx, 6))
        minimumHeight = Ui.dp(ctx, 48)
        contentDescription = "Afficher le bandeau de séance"
        speed = Ui.display(ctx, "--", 26f, Ui.ON_ACCENT)
        addView(speed)
        addView(Ui.label(ctx, " km/h", 15f, Ui.ON_ACCENT))
        addView(Ui.label(ctx, "▲", 18f, Ui.ON_ACCENT).apply { setPadding(Ui.dp(ctx, 12), 0, 0, 0) })
    }

    fun attachWindow(wm: WindowManager, lp: WindowManager.LayoutParams) {
        this.wm = wm
        this.lp = lp
    }

    fun update(s: RunSession) {
        val e = s.engine
        speed.text = if (e.phase == Phase.FINISHED) "--" else Format.kmh(e.targetKmh())
    }

    private var downRawX = 0f
    private var startX = 0
    private var dragging = false

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: android.view.MotionEvent): Boolean {
        val params = lp
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                downRawX = ev.rawX
                startX = params?.x ?: 0
                dragging = false
                alpha = 0.85f
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                val dx = ev.rawX - downRawX
                if (!dragging && kotlin.math.abs(dx) > Ui.dp(context, 8)) dragging = true
                if (dragging && params != null) {
                    // Gravité à droite : x se compte depuis le bord droit.
                    val maxX = (resources.displayMetrics.widthPixels - width).coerceAtLeast(0)
                    params.x = (startX - dx.toInt()).coerceIn(0, maxX)
                    try {
                        wm?.updateViewLayout(this, params)
                    } catch (_: Exception) {
                    }
                }
            }
            android.view.MotionEvent.ACTION_UP -> {
                alpha = 1f
                if (dragging) params?.let { onMoved(it.x) } else performClick()
            }
            android.view.MotionEvent.ACTION_CANCEL -> alpha = 1f
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        onTap()
        return true
    }
}

/**
 * Contenu du bandeau. Séparé de la fenêtre pour pouvoir le dessiner dans les tests.
 * Étape, vitesse, temps restant, profil LED, boutons, et flèche ▲ vers le plein écran.
 */
class BarView(
    ctx: Context,
    private val onOpen: () -> Unit,
    private val onHide: () -> Unit = {},
) : LinearLayout(ctx) {

    private var session: RunSession? = null
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
    private val expand: Button
    private val hide: Button
    private val stepBox: LinearLayout
    private val content: LinearLayout

    init {
        orientation = VERTICAL
        setBackgroundColor(0xF2000000.toInt())
        isClickable = true
        // Le halo orange de la vitesse doit pouvoir déborder de son cadre sans être coupé.
        clipChildren = false
        clipToPadding = false

        // Liseré orange en haut : la bande LED du tapis.
        addView(View(ctx).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Ui.ACCENT, 0xFFFFA45C.toInt(), Ui.ACCENT),
            )
        }, LayoutParams(LayoutParams.MATCH_PARENT, Ui.dp(ctx, 3)))

        content = Ui.horizontal(ctx).apply {
            clipChildren = false
            clipToPadding = false
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
        val speedRow = Ui.horizontal(ctx).apply {
            gravity = Gravity.BOTTOM
            clipChildren = false
            clipToPadding = false
        }
        speed = Ui.display(ctx, "--", 46f, Ui.ACCENT, glow = true).apply {
            // Marge du halo réduite à droite pour garder « km/h » collé au chiffre.
            setPadding(Ui.dp(ctx, 6), 0, Ui.dp(ctx, 4), 0)
        }
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

        // Boutons : à droite sur tablette, sur une deuxième ligne sur un écran étroit.
        minus = small(Ui.button(ctx, "−0,1", primary = false) { session?.adjust(-0.1) })
        plus = small(Ui.button(ctx, "+0,1", primary = false) { session?.adjust(0.1) })
        main = small(Ui.button(ctx, "Pause") { onMain() }, minDp = if (wide) 104 else 0)
        next = small(Ui.button(ctx, "Suivant", primary = false) { session?.skip() })
        // Flèche montante : retour à l'écran de séance en plein écran.
        expand = small(Ui.button(ctx, "▲", primary = false) { onOpen() }, minDp = if (wide) 64 else 0).apply {
            contentDescription = "Passer en plein écran"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        }
        hide = small(Ui.button(ctx, "Masquer", primary = false) { onHide() }).apply {
            contentDescription = "Ranger le bandeau"
        }
        val buttons = listOf(minus to 1f, plus to 1f, main to 1.4f, next to 1.2f, hide to 1.3f, expand to 0.9f)
        if (wide) {
            for ((b, _) in buttons) {
                content.addView(b, Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = Ui.dp(ctx, 8)
                })
            }
        } else {
            val row = Ui.horizontal(ctx).apply {
                val h = Ui.dp(ctx, 12)
                setPadding(h, 0, h, Ui.dp(ctx, 10))
            }
            buttons.forEachIndexed { i, (b, w) ->
                if (b !== expand) b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                b.setPadding(Ui.dp(ctx, 4), b.paddingTop, Ui.dp(ctx, 4), b.paddingBottom)
                row.addView(b, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, w).apply {
                    if (i > 0) marginStart = Ui.dp(ctx, 6)
                })
            }
            addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        if (!wide) {
            // Écran étroit : pas de profil, le bloc de l'étape prend la place libre.
            profile.visibility = View.GONE
            (stepBox.layoutParams as LayoutParams).apply {
                width = 0
                weight = 1f
            }
        }
    }

    private var downY = 0f

    /** Glisser le bandeau vers le bas le range (les boutons gardent leurs propres appuis). */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: android.view.MotionEvent): Boolean {
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> downY = ev.rawY
            android.view.MotionEvent.ACTION_UP ->
                if (ev.rawY - downY > Ui.dp(context, 40)) onHide()
        }
        return true
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
        minus.visibility = if (running) View.VISIBLE else View.GONE
        plus.visibility = if (running) View.VISIBLE else View.GONE
        next.visibility = if (running) View.VISIBLE else View.GONE
    }
}
