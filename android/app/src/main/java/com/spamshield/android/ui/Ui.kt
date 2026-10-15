package com.spamshield.android.ui

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.DateFormat
import java.util.Date

/** Small helpers for building framework views in code (the app has no XML layouts). */
internal fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

internal fun Context.vertical(padding: Int = 16, block: LinearLayout.() -> Unit = {}): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
        block()
    }

internal fun Context.horizontal(block: LinearLayout.() -> Unit = {}): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        block()
    }

internal fun Context.scrolling(content: View): ScrollView =
    ScrollView(this).apply { addView(content) }

internal fun LinearLayout.text(value: CharSequence, sizeSp: Float = 15f, bold: Boolean = false): TextView =
    TextView(context).apply {
        text = value
        textSize = sizeSp
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, context.dp(4), 0, context.dp(4))
    }.also(::addView)

internal fun LinearLayout.header(value: String): TextView =
    text(value, 18f, bold = true).apply { setPadding(0, context.dp(20), 0, context.dp(6)) }

internal fun LinearLayout.button(label: String, onClick: () -> Unit): Button =
    Button(context).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }.also(::addView)

internal fun LinearLayout.input(hint: String, value: String? = null, inputType: Int = InputType.TYPE_CLASS_TEXT): EditText =
    EditText(context).apply {
        this.hint = hint
        setText(value.orEmpty())
        this.inputType = inputType
    }.also(::addView)

/** Adds [view] stretched to take the remaining width of a horizontal row. */
internal fun LinearLayout.addWeighted(view: View) {
    addView(view, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
}

internal fun Context.toast(message: CharSequence) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

internal fun formatTime(millis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(millis))

internal fun Activity.enableUpNavigation(title: String) {
    this.title = title
    actionBar?.setDisplayHomeAsUpEnabled(true)
}

/** Minimal list adapter whose rows are built by [bind] (no view recycling needed for these sizes). */
internal class SimpleListAdapter<T>(
    private val context: Context,
    private val bind: (item: T, row: LinearLayout) -> Unit,
) : BaseAdapter() {
    var items: List<T> = emptyList()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    override fun getCount() = items.size
    override fun getItem(position: Int): T = items[position]
    override fun getItemId(position: Int) = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View =
        context.vertical(padding = 12).also { bind(items[position], it) }
}
