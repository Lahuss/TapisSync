package fr.tapissync.ui

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Petits outils pour construire les écrans en code (pas de XML, pas d'AndroidX). */
object Ui {
    const val BG = 0xFF0E1014.toInt()
    const val SURFACE = 0xFF181B21.toInt()
    const val SURFACE_2 = 0xFF22262E.toInt()
    const val BORDER = 0xFF2C313B.toInt()
    const val TEXT = 0xFFF2F3F5.toInt()
    const val MUTED = 0xFF9AA1AC.toInt()
    const val ACCENT = 0xFFFF6B2C.toInt()
    const val ACCENT_DARK = 0xFF3A2116.toInt()
    const val OK = 0xFF3DDC84.toInt()
    const val WARN = 0xFFFFC145.toInt()
    const val ERROR = 0xFFFF5A5F.toInt()

    fun dp(ctx: Context, v: Number): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()

    fun rounded(color: Int, radiusDp: Int, ctx: Context, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(ctx, radiusDp).toFloat()
            if (stroke != null) setStroke(dp(ctx, 1), stroke)
        }

    fun text(
        ctx: Context, s: CharSequence, sizeSp: Float = 16f, color: Int = TEXT,
        bold: Boolean = false, mono: Boolean = false,
    ): TextView = TextView(ctx).apply {
        text = s
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        typeface = when {
            mono && bold -> Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            mono -> Typeface.MONOSPACE
            bold -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
            else -> Typeface.create("sans-serif", Typeface.NORMAL)
        }
        setLineSpacing(0f, 1.15f)
    }

    fun button(ctx: Context, label: String, primary: Boolean = true, onClick: (View) -> Unit): Button =
        Button(ctx).apply {
            text = label
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(if (primary) 0xFF1A0E07.toInt() else TEXT)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = rounded(if (primary) ACCENT else SURFACE_2, 12, ctx, if (primary) null else BORDER)
            stateListAnimator = null
            minHeight = dp(ctx, 52)
            setPadding(dp(ctx, 18), dp(ctx, 10), dp(ctx, 18), dp(ctx, 10))
            setOnClickListener(onClick)
        }

    fun card(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(SURFACE, 16, ctx, BORDER)
        val p = dp(ctx, 16)
        setPadding(p, p, p, p)
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

    /** Page défilante standard avec titre ; renvoie le conteneur où ajouter le contenu. */
    fun page(activity: Activity, title: String, subtitle: String? = null): LinearLayout {
        val scroll = ScrollView(activity).apply {
            setBackgroundColor(BG)
            isFillViewport = true
        }
        val root = vertical(activity).apply {
            val p = dp(activity, 20)
            setPadding(p, p, p, dp(activity, 40))
        }
        root.addView(text(activity, title, 26f, TEXT, bold = true))
        if (subtitle != null) root.addSpaced(text(activity, subtitle, 15f, MUTED), activity, 4)
        scroll.addView(root)
        activity.setContentView(scroll)
        return root
    }

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
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            var type = InputType.TYPE_CLASS_NUMBER
            if (decimals) type = type or InputType.TYPE_NUMBER_FLAG_DECIMAL
            if (signed) type = type or InputType.TYPE_NUMBER_FLAG_SIGNED
            inputType = type
            background = rounded(SURFACE_2, 10, ctx, BORDER)
            val p = dp(ctx, 12)
            setPadding(p, p, p, p)
            gravity = Gravity.END
        }

    /** Lit un nombre saisi en français ou en anglais (virgule ou point). */
    fun parseNumber(s: CharSequence?): Double? =
        s?.toString()?.trim()?.replace(',', '.')?.takeIf { it.isNotEmpty() }?.toDoubleOrNull()
}
