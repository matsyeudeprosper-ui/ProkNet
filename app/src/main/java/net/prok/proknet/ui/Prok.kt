package net.prok.proknet.ui

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt
import net.prok.proknet.R

/**
 * v0.19.1: the design system for views built in code. A row a screen assembles in Kotlin
 * must look exactly like a row written in XML (colors.xml / styles.xml / bg_*), so every
 * screen builds its cards, pills, chips and buttons here and nowhere else.
 */
object Prok {

    enum class Tone { OK, WARN, MUTED, BRAND, DANGER }

    fun dp(c: Context, v: Int): Int = (v * c.resources.displayMetrics.density).roundToInt()

    /** The shared header (view_header.xml): back closes the screen; an empty subtitle hides its line. */
    fun header(a: Activity, title: String, sub: String = "") {
        a.findViewById<View?>(R.id.hdBack)?.setOnClickListener { a.finish() }
        a.findViewById<TextView?>(R.id.hdTitle)?.text = title
        a.findViewById<TextView?>(R.id.hdSub)?.let { it.text = sub; it.visibility = if (sub.isEmpty()) View.GONE else View.VISIBLE }
    }

    private fun lp(c: Context, top: Int = 0, bottom: Int = 0, width: Int = LinearLayout.LayoutParams.MATCH_PARENT): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(width, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(c, top); bottomMargin = dp(c, bottom) }

    // ---- containers ------------------------------------------------------------------------

    fun card(c: Context, accent: Boolean = false, alt: Boolean = false, onClick: (() -> Unit)? = null): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(if (accent) R.drawable.bg_card_accent else if (alt) R.drawable.bg_card_alt else R.drawable.bg_card)
        val p = dp(c, 18); setPadding(p, p, p, p)
        layoutParams = lp(c, bottom = 12)
        if (onClick != null) { isClickable = true; isFocusable = true; setOnClickListener { onClick() } }
    }

    fun row(c: Context, top: Int = 12): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutParams = lp(c, top = top)
    }

    /** Buttons side by side; scrolls sideways instead of squeezing when there are many. */
    fun actions(c: Context, vararg buttons: View, top: Int = 12): View {
        val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        for ((i, b) in buttons.withIndex()) {
            (b.layoutParams as? LinearLayout.LayoutParams ?: LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(c, 46))).let {
                it.marginEnd = if (i == buttons.size - 1) 0 else dp(c, 8); b.layoutParams = it
            }
            row.addView(b)
        }
        return HorizontalScrollView(c).apply { isHorizontalScrollBarEnabled = false; addView(row); layoutParams = lp(c, top = top) }
    }

    fun divider(c: Context, gap: Int = 14): View = View(c).apply {
        setBackgroundColor(c.getColor(R.color.divider))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(c, 1)).apply { topMargin = dp(c, gap); bottomMargin = dp(c, gap) }
    }

    // ---- type ----------------------------------------------------------------------------------

    private fun text(c: Context, s: CharSequence, size: Float, color: Int, bold: Boolean, top: Int): TextView = TextView(c).apply {
        text = s; textSize = size; setTextColor(c.getColor(color))
        if (bold) setTypeface(typeface, Typeface.BOLD)
        layoutParams = lp(c, top = top)
    }

    fun h2(c: Context, s: CharSequence, top: Int = 0): TextView = text(c, s, 17f, R.color.text, true, top)
    fun h3(c: Context, s: CharSequence, top: Int = 0): TextView = text(c, s, 15f, R.color.text, true, top)
    fun body(c: Context, s: CharSequence, top: Int = 6): TextView = text(c, s, 15f, R.color.text, false, top).apply { setLineSpacing(dp(c, 3).toFloat(), 1f) }
    fun muted(c: Context, s: CharSequence, top: Int = 4): TextView = text(c, s, 13f, R.color.text_muted, false, top).apply { setLineSpacing(dp(c, 2).toFloat(), 1f) }
    fun small(c: Context, s: CharSequence, top: Int = 4): TextView = text(c, s, 12f, R.color.text_muted, false, top)
    fun stat(c: Context, s: CharSequence, top: Int = 4): TextView = text(c, s, 22f, R.color.text, true, top).apply { letterSpacing = -0.01f }
    fun caption(c: Context, s: CharSequence, top: Int = 0): TextView = text(c, s, 11f, R.color.text_muted, true, top).apply { isAllCaps = true; letterSpacing = 0.1f }

    /** A rounded status word. */
    fun pill(c: Context, s: CharSequence, tone: Tone): TextView = TextView(c).apply {
        text = s; textSize = 11f; setTypeface(typeface, Typeface.BOLD)
        val (bg, fg) = when (tone) {
            Tone.OK -> R.drawable.bg_pill_ok to R.color.legend_text
            Tone.WARN -> R.drawable.bg_pill_warn to R.color.legend_text
            Tone.DANGER -> R.drawable.bg_danger to R.color.on_brand
            Tone.BRAND -> R.drawable.bg_pill_brand to R.color.brand
            Tone.MUTED -> R.drawable.bg_pill_muted to R.color.text
        }
        setBackgroundResource(bg); setTextColor(c.getColor(fg))
        setPadding(dp(c, 10), dp(c, 4), dp(c, 10), dp(c, 4))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(c, 6) }
    }

    fun pills(c: Context, vararg items: Pair<CharSequence, Tone>, top: Int = 8): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL; layoutParams = lp(c, top = top)
        for ((s, t) in items) addView(pill(c, s, t))
    }

    /** A title line with a status pill at its end. */
    fun titleRow(c: Context, title: CharSequence, status: CharSequence? = null, tone: Tone = Tone.MUTED): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutParams = lp(c)
        addView(h2(c, title).apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
        if (status != null) addView(pill(c, status, tone).apply { (layoutParams as LinearLayout.LayoutParams).apply { marginEnd = 0; marginStart = dp(c, 8) } })
    }

    // ---- buttons -------------------------------------------------------------------------------

    private fun base(c: Context, s: CharSequence, onClick: () -> Unit): Button = Button(c).apply {
        text = s; textSize = 14f; setTypeface(typeface, Typeface.BOLD); isAllCaps = false
        minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0; stateListAnimator = null
        setPadding(dp(c, 18), 0, dp(c, 18), 0)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(c, 46))
        setOnClickListener { onClick() }
    }

    fun primary(c: Context, s: CharSequence, onClick: () -> Unit): Button = base(c, s, onClick).apply { setBackgroundResource(R.drawable.bg_primary); setTextColor(c.getColor(R.color.on_brand)) }
    fun secondary(c: Context, s: CharSequence, onClick: () -> Unit): Button = base(c, s, onClick).apply { setBackgroundResource(R.drawable.bg_secondary); setTextColor(c.getColor(R.color.text)) }
    fun danger(c: Context, s: CharSequence, onClick: () -> Unit): Button = base(c, s, onClick).apply { setBackgroundResource(R.drawable.bg_danger); setTextColor(c.getColor(R.color.on_brand)) }
    fun ghost(c: Context, s: CharSequence, danger: Boolean = false, onClick: () -> Unit): Button = base(c, s, onClick).apply {
        setBackgroundResource(android.R.color.transparent); setTextColor(c.getColor(if (danger) R.color.danger else R.color.brand)); setPadding(dp(c, 10), 0, dp(c, 10), 0)
    }
    /** A full-width primary button for the one action of a card. */
    fun wide(c: Context, s: CharSequence, onClick: () -> Unit): Button = primary(c, s, onClick).apply {
        textSize = 16f; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(c, 54)).apply { topMargin = dp(c, 14) }
    }
    fun wideSecondary(c: Context, s: CharSequence, onClick: () -> Unit): Button = secondary(c, s, onClick).apply {
        textSize = 16f; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(c, 54)).apply { topMargin = dp(c, 10) }
    }

    /** A selectable filter word. */
    fun chip(c: Context, s: CharSequence, on: Boolean, enabled: Boolean = true, onClick: () -> Unit): TextView = TextView(c).apply {
        text = s; textSize = 13f; setTypeface(typeface, Typeface.BOLD)
        setBackgroundResource(if (on) R.drawable.bg_chip_on else R.drawable.bg_chip)
        setTextColor(c.getColor(if (on) R.color.on_brand else R.color.text))
        alpha = if (enabled) 1f else 0.45f
        setPadding(dp(c, 14), dp(c, 9), dp(c, 14), dp(c, 9))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(c, 8) }
        isClickable = enabled; isFocusable = enabled
        if (enabled) setOnClickListener { onClick() }
    }

    /** The three-way selector on top of a screen (Parcourir / Vendre / Mes annonces). */
    fun segment(b: TextView, on: Boolean) {
        val c = b.context
        b.setBackgroundResource(if (on) R.drawable.bg_segment_on else android.R.color.transparent)
        b.setTextColor(c.getColor(if (on) R.color.text else R.color.text_muted))
    }

    fun icon(c: Context, res: Int, size: Int = 44): ImageView = ImageView(c).apply {
        setImageResource(res); setBackgroundResource(R.drawable.bg_icon_circle); imageTintList = c.getColorStateList(R.color.brand)
        val p = dp(c, 10); setPadding(p, p, p, p)
        layoutParams = LinearLayout.LayoutParams(dp(c, size), dp(c, size))
    }

    /** A quiet card for "nothing here yet". */
    fun empty(c: Context, s: CharSequence): LinearLayout = card(c).apply { addView(body(c, s, 0)) }

    /** A tappable row with a title, a line and a chevron, for lists of destinations. */
    fun link(c: Context, title: CharSequence, sub: CharSequence, iconRes: Int? = null, onClick: () -> Unit): LinearLayout = card(c, onClick = onClick).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        if (iconRes != null) addView(icon(c, iconRes, 40).apply { (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(c, 14) })
        addView(LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(h3(c, title)); if (sub.isNotEmpty()) addView(muted(c, sub, 2))
        })
        addView(ImageView(c).apply { setImageResource(R.drawable.ic_chevron); imageTintList = c.getColorStateList(R.color.text_muted); layoutParams = LinearLayout.LayoutParams(dp(c, 22), dp(c, 22)) })
    }
}
