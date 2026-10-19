package fr.tapissync.ui

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import fr.tapissync.R

/**
 * Habillage de l'appli : noir et orange, comme les LED du tapis.
 * Titres et chiffres en Barlow Condensed extra-gras italique (l'élan d'un départ),
 * boutons biseautés, textes en Barlow. Tout est construit en code, sans AndroidX.
 */
object Ui {
    // Couleurs
    const val BG = 0xFF000000.toInt()          // noir de la console
    const val SURFACE = 0xFF151515.toInt()     // panneaux
    const val SURFACE_2 = 0xFF222222.toInt()   // boutons secondaires, champs
    const val BORDER = 0xFF2E2E2E.toInt()
    const val TEXT = 0xFFF3F3F3.toInt()
    const val MUTED = 0xFF8F8F8F.toInt()
    const val ACCENT = 0xFFFF7A1A.toInt()      // LED allumée
    const val ACCENT_PRESSED = 0xFFFF9A4D.toInt()
    const val ACCENT_DARK = 0xFF2A1406.toInt() // fond orangé très sombre
    const val LED_DIM = 0xFF6B3410.toInt()     // LED à venir
    const val LED_OFF = 0xFF1D0F06.toInt()     // LED éteinte
    const val OK = 0xFF43D17A.toInt()
    const val WARN = 0xFFFFC233.toInt()
    const val ERROR = 0xFFFF4D4D.toInt()
    const val ON_ACCENT = 0xFF000000.toInt()

    // Polices
    private var displayFace: Typeface? = null
    private var labelFace: Typeface? = null
    private var bodyFace: Typeface? = null
    private var bodyMediumFace: Typeface? = null

    fun init(ctx: Context) {
        val r = ctx.resources
        displayFace = runCatching { r.getFont(R.font.barlow_condensed_extrabold_italic) }.getOrNull()
        labelFace = runCatching { r.getFont(R.font.barlow_condensed_semibold) }.getOrNull()
        bodyFace = runCatching { r.getFont(R.font.barlow_regular) }.getOrNull()
        bodyMediumFace = runCatching { r.getFont(R.font.barlow_medium) }.getOrNull()
    }

    val display: Typeface get() = displayFace ?: Typeface.create("sans-serif-condensed", Typeface.BOLD_ITALIC)
    val label: Typeface get() = labelFace ?: Typeface.create("sans-serif-condensed", Typeface.BOLD)
    val body: Typeface get() = bodyFace ?: Typeface.DEFAULT
    val bodyMedium: Typeface get() = bodyMediumFace ?: Typeface.DEFAULT_BOLD

    fun dp(ctx: Context, v: Number): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()

    /** Écran large (tablette en paysage) : mise en page en deux colonnes. */
    fun isWide(ctx: Context): Boolean = ctx.resources.configuration.screenWidthDp >= 840

    // ---------- Fonds ----------

    fun rounded(color: Int, radiusDp: Int, ctx: Context, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(ctx, radiusDp).toFloat()
            if (stroke != null) setStroke(dp(ctx, 1), stroke)
        }

    /** Panneau avec un liseré orange à gauche : élément actif ou du jour. */
    fun accentBackground(ctx: Context): Drawable {
        val base = rounded(SURFACE, 4, ctx)
        val edge = GradientDrawable().apply { setColor(ACCENT) }
        return LayerDrawable(arrayOf(base, edge)).apply {
            setLayerWidth(1, dp(ctx, 4))
            setLayerGravity(1, Gravity.START or Gravity.FILL_VERTICAL)
        }
    }

    /** Forme biseautée (coins haut-gauche et bas-droit coupés), signature des boutons. */
    class ChamferDrawable(
        private val fill: Int,
        private val stroke: Int?,
        private val cut: Float,
        private val strokeWidth: Float,
    ) : Drawable() {
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill; style = Paint.Style.FILL }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = stroke ?: 0
            style = Paint.Style.STROKE
            this.strokeWidth = this@ChamferDrawable.strokeWidth
        }
        private val path = Path()

        override fun onBoundsChange(bounds: Rect) {
            super.onBoundsChange(bounds)
            val i = if (stroke != null) strokeWidth / 2 else 0f
            val l = bounds.left + i
            val t = bounds.top + i
            val r = bounds.right - i
            val b = bounds.bottom - i
            val c = cut.coerceAtMost((b - t) / 2)
            path.reset()
            path.moveTo(l + c, t)
            path.lineTo(r, t)
            path.lineTo(r, b - c)
            path.lineTo(r - c, b)
            path.lineTo(l, b)
            path.lineTo(l, t + c)
            path.close()
        }

        override fun draw(canvas: Canvas) {
            canvas.drawPath(path, fillPaint)
            if (stroke != null) canvas.drawPath(path, strokePaint)
        }

        override fun setAlpha(alpha: Int) {
            fillPaint.alpha = alpha
            strokePaint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            fillPaint.colorFilter = colorFilter
            strokePaint.colorFilter = colorFilter
        }

        @Deprecated("Requis par Drawable")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private fun chamferStates(ctx: Context, normal: Int, pressed: Int, stroke: Int?): StateListDrawable {
        val cut = dp(ctx, 10).toFloat()
        val sw = dp(ctx, 1.5f).toFloat()
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), ChamferDrawable(pressed, stroke, cut, sw))
            addState(intArrayOf(-android.R.attr.state_enabled), ChamferDrawable(SURFACE_2, null, cut, sw))
            addState(intArrayOf(), ChamferDrawable(normal, stroke, cut, sw))
        }
    }

    // ---------- Textes ----------

    fun text(
        ctx: Context, s: CharSequence, sizeSp: Float = 16f, color: Int = TEXT,
        bold: Boolean = false, mono: Boolean = false,
    ): TextView = TextView(ctx).apply {
        text = s
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        typeface = when {
            mono -> Typeface.MONOSPACE
            bold -> Ui.bodyMedium
            else -> Ui.body
        }
        setLineSpacing(0f, 1.2f)
    }

    /** Titres et grands chiffres. glow = halo orangé façon LED. */
    fun display(ctx: Context, s: CharSequence, sizeSp: Float, color: Int = TEXT, glow: Boolean = false): TextView =
        TextView(ctx).apply {
            text = s
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            typeface = Ui.display
            includeFontPadding = false
            letterSpacing = -0.01f
            if (glow) setShadowLayer(dp(ctx, 14).toFloat(), 0f, 0f, 0xAAFF6A00.toInt())
        }

    /** Libellés courts, boutons, noms d'étapes. */
    fun label(ctx: Context, s: CharSequence, sizeSp: Float = 18f, color: Int = TEXT): TextView =
        TextView(ctx).apply {
            text = s
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            typeface = Ui.label
        }

    /** Ligne d'état précédée d'une pastille de couleur (connecté, attente, erreur). */
    fun status(ctx: Context): StatusLine = StatusLine(ctx)

    class StatusLine(ctx: Context) : LinearLayout(ctx) {
        private val dot = View(ctx)
        val textView: TextView = Ui.text(ctx, "", 15f, Ui.MUTED)

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val s = Ui.dp(ctx, 10)
            addView(dot, LayoutParams(s, s).apply { marginEnd = Ui.dp(ctx, 10) })
            addView(textView, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            set("", Ui.MUTED)
        }

        fun set(s: CharSequence, color: Int) {
            textView.text = s
            textView.setTextColor(if (color == Ui.MUTED) Ui.MUTED else Ui.TEXT)
            dot.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }
        }
    }

    // ---------- Boutons ----------

    fun button(ctx: Context, label: String, primary: Boolean = true, onClick: (View) -> Unit): Button =
        Button(ctx).apply {
            text = label
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Ui.label
            letterSpacing = 0.01f
            setTextColor(if (primary) ON_ACCENT else TEXT)
            background = if (primary) chamferStates(ctx, ACCENT, ACCENT_PRESSED, null)
            else chamferStates(ctx, SURFACE_2, 0xFF333333.toInt(), 0xFF3A3A3A.toInt())
            stateListAnimator = null
            minHeight = dp(ctx, 52)
            minimumHeight = dp(ctx, 52)
            setPadding(dp(ctx, 20), dp(ctx, 10), dp(ctx, 20), dp(ctx, 10))
            setOnClickListener(onClick)
        }

    /** Bouton d'onglet : orange quand il est sélectionné. */
    fun styleTab(b: Button, selected: Boolean) {
        val ctx = b.context
        b.background = if (selected) chamferStates(ctx, ACCENT, ACCENT_PRESSED, null)
        else chamferStates(ctx, SURFACE, SURFACE_2, BORDER)
        b.setTextColor(if (selected) ON_ACCENT else TEXT)
    }

    // ---------- Conteneurs ----------

    fun card(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(SURFACE, 4, ctx)
        val p = dp(ctx, 18)
        setPadding(p, p, p, p)
    }

    fun accentCard(ctx: Context): LinearLayout = card(ctx).apply {
        background = accentBackground(ctx)
        val p = dp(ctx, 18)
        setPadding(p + dp(ctx, 4), p, p, p)
    }

    fun vertical(ctx: Context): LinearLayout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

    fun horizontal(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, weight: Float = 0f) =
        LinearLayout.LayoutParams(w, h, weight)

    fun LinearLayout.addSpaced(v: View, ctx: Context, topDp: Int = 12, params: LinearLayout.LayoutParams = lp()) {
        params.topMargin = dp(ctx, topDp)
        addView(v, params)
    }

    fun gap(ctx: Context, dpW: Int = 10): View = View(ctx).apply { layoutParams = lp(dp(ctx, dpW), 1) }

    /** Trois traits obliques orange : le motif « vitesse » du titre. */
    fun stripes(ctx: Context): View = object : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT }
        private val path = Path()
        override fun onDraw(canvas: Canvas) {
            val h = height.toFloat()
            val w = h * 0.45f
            val step = w * 1.45f
            for (i in 0 until 3) {
                val x = i * step
                path.reset()
                path.moveTo(x + w * 0.6f, 0f)
                path.lineTo(x + w * 1.6f, 0f)
                path.lineTo(x + w, h)
                path.lineTo(x, h)
                path.close()
                p.alpha = 255 - i * 70
                canvas.drawPath(path, p)
            }
        }
    }

    /** En-tête de page : titre extra-gras italique et motif de vitesse. */
    fun header(ctx: Context, title: String, sizeSp: Float = 38f): LinearLayout {
        val row = horizontal(ctx).apply { gravity = Gravity.BOTTOM }
        row.addView(display(ctx, title, sizeSp), lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(stripes(ctx), lp(dp(ctx, 46), dp(ctx, (sizeSp * 0.55f).toInt())).apply {
            bottomMargin = dp(ctx, 8)
        })
        return row
    }

    /** Page défilante standard, largeur limitée sur tablette ; renvoie le conteneur du contenu. */
    fun page(activity: Activity, title: String, subtitle: String? = null): LinearLayout {
        val scroll = ScrollView(activity).apply {
            setBackgroundColor(BG)
            isFillViewport = true
        }
        val root = vertical(activity).apply {
            val p = dp(activity, 24)
            setPadding(p, p, p, dp(activity, 48))
        }
        root.addView(header(activity, title))
        if (subtitle != null) root.addSpaced(text(activity, subtitle, 16f, MUTED), activity, 6)
        val maxW = dp(activity, 820)
        val screenW = activity.resources.displayMetrics.widthPixels
        scroll.addView(root, FrameLayout.LayoutParams(minOf(maxW, screenW), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
        activity.setContentView(scroll)
        return root
    }

    /** Titre de section. */
    fun section(ctx: Context, title: String): TextView = display(ctx, title, 26f)

    /** Bloc chiffre + légende (durée, distance…). */
    fun stat(ctx: Context, value: String, caption: String, valueColor: Int = TEXT): LinearLayout = vertical(ctx).apply {
        addView(display(ctx, value, 34f, valueColor))
        addView(text(ctx, caption, 14f, MUTED))
    }

    // ---------- Champs ----------

    fun numberField(ctx: Context, value: Double, decimals: Boolean = true, signed: Boolean = false): EditText =
        EditText(ctx).apply {
            setText(
                when {
                    value.isNaN() -> ""
                    !decimals -> Math.round(value).toString()
                    else -> fr.tapissync.workout.Format.num1(value)
                },
            )
            setTextColor(TEXT)
            setHintTextColor(MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Ui.label
            var type = InputType.TYPE_CLASS_NUMBER
            if (decimals) type = type or InputType.TYPE_NUMBER_FLAG_DECIMAL
            if (signed) type = type or InputType.TYPE_NUMBER_FLAG_SIGNED
            inputType = type
            background = rounded(SURFACE_2, 4, ctx, BORDER)
            val p = dp(ctx, 12)
            setPadding(p, p, p, p)
            gravity = Gravity.END
        }

    /** Lit un nombre saisi en français ou en anglais (virgule ou point). */
    fun parseNumber(s: CharSequence?): Double? =
        s?.toString()?.trim()?.replace(',', '.')?.takeIf { it.isNotEmpty() }?.toDoubleOrNull()
}
