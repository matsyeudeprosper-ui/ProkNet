package net.prok.proknet.ui

import android.app.Activity
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import net.prok.proknet.R

/**
 * v0.19.3: the sliding panel. One card rises from the bottom over a dimmed screen with the
 * content a caller hands it; tap outside, swipe down or the phone's back key closes it.
 * No library: a FrameLayout added to the activity's content view.
 */
object Sheet {

    private var current: FrameLayout? = null

    fun isOpen(): Boolean = current != null

    fun show(a: Activity, content: View) {
        dismiss()
        val d = a.resources.displayMetrics.density
        val root = a.findViewById<ViewGroup>(android.R.id.content)
        val dim = FrameLayout(a).apply {
            setBackgroundColor(0x99000000.toInt())
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            isClickable = true
        }
        val panel = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_sheet)
            val p = (20 * d).toInt(); setPadding(p, (10 * d).toInt(), p, (28 * d).toInt())
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
            isClickable = true
            addView(View(a).apply {
                setBackgroundResource(R.drawable.bg_grabber)
                layoutParams = LinearLayout.LayoutParams((44 * d).toInt(), (5 * d).toInt()).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = (16 * d).toInt() }
            })
            addView(content)
        }
        dim.addView(panel)
        dim.setOnClickListener { dismiss() }
        // swipe down on the panel closes it
        var downY = 0f
        panel.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downY = e.rawY; false }
                MotionEvent.ACTION_MOVE -> { val dy = e.rawY - downY; if (dy > 0) v.translationY = dy; dy > 8 * d }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val dy = e.rawY - downY
                    if (dy > 110 * d) dismiss() else v.animate().translationY(0f).setDuration(160).start()
                    dy > 8 * d
                }
                else -> false
            }
        }
        root.addView(dim)
        current = dim
        dim.alpha = 0f; dim.animate().alpha(1f).setDuration(160).start()
        panel.translationY = 400 * d
        panel.animate().translationY(0f).setDuration(220).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
    }

    fun dismiss() {
        val v = current ?: return
        current = null
        val parent = v.parent as? ViewGroup ?: return
        v.animate().alpha(0f).setDuration(140).withEndAction { parent.removeView(v) }.start()
    }
}
