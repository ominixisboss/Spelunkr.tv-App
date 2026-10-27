package tv.spelunkr.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/** A thin vertical slider (0 at the bottom, 1 at the top) for the lock screen's brightness and volume. */
class VerticalSlider @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Called with the new value whenever the user drags. */
    var onChange: ((Float) -> Unit)? = null

    var value = 0f
        set(v) {
            field = v.coerceIn(0f, 1f)
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val trackWidth = 6 * density
    private val thumbRadius = 9 * density
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(90, 255, 255, 255) }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.spelunkr_gold) }
    private val rect = RectF()

    private val top get() = paddingTop + thumbRadius
    private val bottom get() = height - paddingBottom - thumbRadius

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val y = bottom - (bottom - top) * value
        val r = trackWidth / 2
        rect.set(cx - r, top, cx + r, bottom)
        canvas.drawRoundRect(rect, r, r, trackPaint)
        rect.set(cx - r, y, cx + r, bottom)
        canvas.drawRoundRect(rect, r, r, fillPaint)
        canvas.drawCircle(cx, y, thumbRadius, fillPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                value = (bottom - event.y) / (bottom - top)
                onChange?.invoke(value)
            }
        }
        return true
    }
}
