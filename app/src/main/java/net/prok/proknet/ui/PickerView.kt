package net.prok.proknet.ui

import android.app.AlertDialog
import android.content.Context
import android.util.AttributeSet
import android.widget.TextView
import net.prok.proknet.R

/**
 * v0.19.1: a single-choice field that looks like every other input (bg_input) and opens a
 * plain list when tapped - the stock Spinner did not fit the screen. [selected] is the index
 * into [items], like Spinner.selectedItemPosition was.
 */
class PickerView(context: Context, attrs: AttributeSet?) : TextView(context, attrs) {

    var items: List<String> = emptyList()
        set(value) { field = value; selected = 0 }

    var selected: Int = 0
        set(value) { field = value.coerceIn(0, maxOf(0, items.size - 1)); text = items.getOrNull(field) ?: "" }

    var onPick: ((Int) -> Unit)? = null

    init {
        isClickable = true; isFocusable = true
        setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.ic_chevron, 0)
        compoundDrawableTintList = context.getColorStateList(R.color.text_muted)
        setOnClickListener {
            if (items.isEmpty()) return@setOnClickListener
            AlertDialog.Builder(context).setItems(items.toTypedArray()) { _, i -> selected = i; onPick?.invoke(i) }.show()
        }
    }
}
