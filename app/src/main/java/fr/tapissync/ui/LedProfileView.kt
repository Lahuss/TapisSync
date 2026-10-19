package fr.tapissync.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import fr.tapissync.workout.PlannedStep
import fr.tapissync.workout.SpeedPlanner
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Profil de la séance dessiné comme la matrice de LED d'une console de tapis :
 * chaque colonne est un instant de la séance, sa hauteur la vitesse prévue.
 * Le passé est allumé en orange, la suite en orange sombre, la position actuelle en blanc chaud.
 */
class LedProfileView(ctx: Context, private val rows: Int = 10) : View(ctx) {

    private var seconds = DoubleArray(0)
    private var speeds = DoubleArray(0)
    private var total = 0.0
    private var minSpeed = 0.0
    private var maxSpeed = 0.0

    /** Avancement dans la séance, entre 0 et 1 ; négatif tant que la séance n'a pas commencé. */
    var position: Double = -1.0
        set(v) {
            if (field != v) {
                field = v
                invalidate()
            }
        }

    private val dotPx = Ui.dp(ctx, 7).toFloat()
    private val gapPx = Ui.dp(ctx, 3).toFloat()
    private val pitch = dotPx + gapPx
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)

    fun setPlan(steps: List<PlannedStep>) {
        seconds = DoubleArray(steps.size) { SpeedPlanner.stepSeconds(steps[it]) }
        speeds = DoubleArray(steps.size) { steps[it].speedKmh }
        total = seconds.sum()
        minSpeed = speeds.minOrNull() ?: 0.0
        maxSpeed = speeds.maxOrNull() ?: 0.0
        invalidate()
    }

    /** Position (0..1) pour l'étape [index] avancée de [stepFraction]. */
    fun positionFor(index: Int, stepFraction: Double): Double {
        if (total <= 0 || index < 0) return -1.0
        var before = 0.0
        for (i in 0 until index.coerceAtMost(seconds.size)) before += seconds[i]
        val cur = seconds.getOrElse(index) { 0.0 } * stepFraction.coerceIn(0.0, 1.0)
        return ((before + cur) / total).coerceIn(0.0, 1.0)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = (rows * pitch - gapPx + paddingTop + paddingBottom).roundToInt()
        setMeasuredDimension(w, h)
    }

    private fun stepAt(t: Double): Int {
        var acc = 0.0
        for (i in seconds.indices) {
            acc += seconds[i]
            if (t * total <= acc) return i
        }
        return seconds.lastIndex
    }

    private fun levelFor(speed: Double): Int {
        if (maxSpeed - minSpeed < 0.05) return (rows * 0.6).roundToInt()
        val f = (speed - minSpeed) / (maxSpeed - minSpeed)
        return 2 + (f * (rows - 2)).roundToInt().coerceIn(0, rows - 2)
    }

    override fun onDraw(canvas: Canvas) {
        if (seconds.isEmpty() || total <= 0) return
        val usable = width - paddingLeft - paddingRight
        val cols = max(1, floor((usable + gapPx) / pitch).toInt())
        // Centre la matrice dans la largeur disponible.
        val startX = paddingLeft + (usable - (cols * pitch - gapPx)) / 2f
        val bottom = height - paddingBottom.toFloat()
        val r = dotPx / 2f
        val cursorCol = if (position >= 0) floor(position * cols).toInt().coerceIn(0, cols - 1) else -1

        for (c in 0 until cols) {
            val t = (c + 0.5) / cols
            val level = levelFor(speeds[stepAt(t)])
            val cx = startX + c * pitch + r
            for (row in 0 until rows) {
                val cy = bottom - row * pitch - r
                val lit = row < level
                val color = when {
                    !lit -> Ui.LED_OFF
                    c == cursorCol -> 0xFFFFE0C2.toInt()
                    position >= 0 && c < cursorCol -> Ui.ACCENT
                    position < 0 -> Ui.ACCENT
                    else -> Ui.LED_DIM
                }
                if (lit && color != Ui.LED_DIM) {
                    glow.color = color
                    glow.alpha = 55
                    canvas.drawCircle(cx, cy, r * 1.7f, glow)
                }
                paint.color = color
                canvas.drawCircle(cx, cy, r, paint)
            }
        }
    }
}
