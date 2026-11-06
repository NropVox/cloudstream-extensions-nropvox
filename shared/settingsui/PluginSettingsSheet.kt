package com.nropvox

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.Coroutines.runOnMainThread

private const val RESOURCE_PACKAGE = "com.nropvox"

/** What a field's Check button found out about the value in the box. */
class SettingCheck(val valid: Boolean, val message: String)

/**
 * One value the user can set: a row in the sheet that opens a dialog with a single input.
 * [get] returns what is saved ("" when nothing is) and [set] saves [normalize]d input, "" meaning clear it.
 */
class SettingField(
    val kind: Kind,
    val title: String,
    /** Under the dialog title, e.g. the hosts the value is for. */
    val subtitle: String = "",
    val description: String,
    val label: String,
    val hint: String,
    val unsetSummary: String,
    val setSummary: (String) -> String,
    val get: () -> String,
    val set: (String) -> Unit,
    val normalize: (String) -> String = { it.trim() },
    val clearLabel: String = "Remove",
    val linkText: String = "",
    val linkUrl: String = "",
    /** Offers a Check button; it runs off the main thread. */
    val check: (suspend (String) -> SettingCheck)? = null,
) {
    enum class Kind { SECRET, URL }
}

/** A typed address as a bare `https://host/path`: scheme added when missing, no trailing slash; blank stays blank. */
fun normalizeSettingUrl(input: String): String {
    val url = input.trim().trimEnd('/')
    return if (url.isEmpty() || url.startsWith("http://") || url.startsWith("https://")) url else "https://$url"
}

class SettingSection(val title: String, val fields: List<SettingField>)

/**
 * A plugin's settings in the style of OmniStream's, which follows StreamPlay's: a dark sheet of sections whose rows
 * each open a dialog. Plugin layouts inflate against the app's resources, so icons and backgrounds are set here from
 * the plugin's own.
 */
class PluginSettingsSheet(
    plugin: Plugin,
    private val appName: String,
    private val sections: List<SettingSection>,
    private val footnote: String,
) : BottomSheetDialogFragment() {
    private val res = plugin.resources ?: throw Exception("Unable to access plugin resources")
    private val summaries = mutableListOf<Pair<SettingField, TextView>>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = inflate(inflater, "settings_sheet", container)
        view.background = GradientDrawable().apply {
            setColor(SHEET)
            val r = dp(20f)
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        }
        view.find<View>("settingsHandle").background = shape(0x33FFFFFF, 2f)
        view.find<TextView>("settingsOverline").text = appName
        view.find<TextView>("settingsFootnote").text = footnote

        val rows = view.find<ViewGroup>("settingsRows")
        summaries.clear()
        for (section in sections) {
            inflate(inflater, "settings_section", rows).also {
                (it as TextView).text = section.title
                rows.addView(it)
            }
            for (field in section.fields) rows.addView(buildRow(inflater, rows, field))
        }
        return view
    }

    override fun onStart() {
        super.onStart()
        // The sheet's themed surface would show around the rounded top; open expanded so a TV shows every row.
        (view?.parent as? View)?.setBackgroundColor(Color.TRANSPARENT)
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            skipCollapsed = true
            state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

    private fun buildRow(inflater: LayoutInflater, parent: ViewGroup, field: SettingField): View {
        val row = inflate(inflater, "settings_row", parent)
        row.background = states(shape(CARD, 12f), pressed = shape(PRESSED, 12f), focused = shape(CARD, 12f, stroke = WHITE, strokeWidth = 2f))
        badge(row.find("settingsRowBadge"), row.find("settingsRowIcon"), field)
        row.find<ImageView>("settingsRowChevron").setImageDrawable(icon("settings_ic_chevron", MUTED))
        row.find<TextView>("settingsRowTitle").text = field.title
        val summary = row.find<TextView>("settingsRowSummary")
        summaries += field to summary
        refresh(field, summary)
        row.setOnClickListener { showDialog(field) }
        return row
    }

    private fun refresh(field: SettingField, summary: TextView) {
        val value = field.get()
        summary.text = if (value.isEmpty()) field.unsetSummary else field.setSummary(value)
        summary.setTextColor(if (value.isEmpty()) MUTED else GREEN)
    }

    private fun showDialog(field: SettingField) {
        val context = requireContext()
        val view = inflate(LayoutInflater.from(context), "settings_dialog", null)
        val dialog = Dialog(context).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(view)
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            @Suppress("DEPRECATION")
            window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            setOnDismissListener { if (isAdded) summaries.forEach { (f, v) -> refresh(f, v) } }
        }
        view.background = shape(DIALOG, 16f, stroke = 0x22FFFFFF)
        badge(view.find("fieldBadge"), view.find("fieldIcon"), field)

        val box = view.find<View>("fieldBox")
        val input = view.find<EditText>("fieldInput")
        val status = view.find<TextView>("fieldStatus")
        val reveal = view.find<ImageView>("fieldReveal")
        val paste = view.find<ImageView>("fieldPaste")
        val remove = view.find<TextView>("fieldRemove")
        val check = view.find<TextView>("fieldCheck")
        val save = view.find<TextView>("fieldSave")
        val link = view.find<TextView>("fieldLink")
        val secret = field.kind == SettingField.Kind.SECRET

        view.find<TextView>("fieldTitle").text = field.title
        view.find<TextView>("fieldHost").apply {
            text = field.subtitle
            visibility = if (field.subtitle.isEmpty()) View.GONE else View.VISIBLE
        }
        view.find<TextView>("fieldDescription").text = field.description
        view.find<TextView>("fieldLabel").text = field.label
        input.hint = field.hint
        remove.text = field.clearLabel
        link.text = field.linkText
        link.visibility = if (field.linkUrl.isEmpty()) View.GONE else View.VISIBLE
        check.visibility = if (field.check == null) View.GONE else View.VISIBLE
        reveal.visibility = if (secret) View.VISIBLE else View.GONE

        // The EditText takes focus, not the field around it, so the field outline follows it through `activated`.
        box.background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_activated), shape(INPUT_FOCUSED, 8f, stroke = WHITE, strokeWidth = 2f))
            addState(intArrayOf(), shape(INPUT, 8f, stroke = INPUT_STROKE))
        }
        input.setOnFocusChangeListener { _, hasFocus -> box.isActivated = hasFocus }
        listOf(paste, reveal).forEach {
            it.background = states(shape(Color.TRANSPARENT, 20f), pressed = shape(0x22FFFFFF, 20f), focused = shape(0x33FFFFFF, 20f))
        }
        paste.setImageDrawable(icon("settings_ic_paste", MUTED))
        link.background = states(shape(Color.TRANSPARENT, 6f), pressed = shape(0x22FFFFFF, 6f), focused = shape(0x22FFFFFF, 6f))
        remove.background = states(shape(Color.TRANSPARENT, 20f), pressed = shape(0x22FF6B6B, 20f), focused = shape(0x33FF6B6B, 20f))
        check.background = states(shape(Color.TRANSPARENT, 20f, stroke = INPUT_STROKE),
            pressed = shape(PRESSED, 20f, stroke = INPUT_STROKE), focused = shape(Color.TRANSPARENT, 20f, stroke = WHITE, strokeWidth = 2f))
        save.background = states(shape(WHITE, 20f), pressed = shape(0xFFDDDDDD.toInt(), 20f), focused = shape(WHITE, 20f, stroke = ACCENT, strokeWidth = 3f))

        fun say(text: String, color: Int) {
            status.text = text
            status.setTextColor(color)
            status.visibility = View.VISIBLE
        }

        val saved = field.get()
        input.setText(saved)
        input.setSelection(input.length())
        remove.visibility = if (saved.isEmpty()) View.GONE else View.VISIBLE
        input.doAfterTextChanged { status.visibility = View.GONE }

        var revealed = false
        fun showValue(show: Boolean) {
            revealed = show
            input.inputType = InputType.TYPE_CLASS_TEXT or when {
                !secret -> InputType.TYPE_TEXT_VARIATION_URI
                show -> InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                else -> InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            // Changing the input type resets the typeface.
            input.typeface = Typeface.MONOSPACE
            input.setSelection(input.length())
            reveal.setImageDrawable(icon(if (show) "settings_ic_visibility_off" else "settings_ic_visibility", MUTED))
            reveal.contentDescription = if (show) "Hide key" else "Show key"
        }
        showValue(false)
        reveal.setOnClickListener { showValue(!revealed) }

        paste.setOnClickListener {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val text = clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()?.trim()
            if (text.isNullOrEmpty()) say("The clipboard is empty", MUTED)
            else {
                input.setText(text)
                input.setSelection(input.length())
            }
        }

        val runCheck = field.check
        if (runCheck != null) check.setOnClickListener {
            val value = field.normalize(input.text.toString())
            if (value.isEmpty()) return@setOnClickListener say("Enter a value first", RED)
            say("Checking…", MUTED)
            check.isEnabled = false
            check.alpha = 0.5f
            ioSafe {
                val result = try {
                    runCheck(value)
                } catch (e: Exception) {
                    SettingCheck(false, "Couldn't run the check, check your connection")
                }
                runOnMainThread {
                    check.isEnabled = true
                    check.alpha = 1f
                    // A result for a value that has since been edited would describe the wrong value.
                    if (dialog.isShowing && field.normalize(input.text.toString()) == value) say(result.message, if (result.valid) GREEN else RED)
                }
            }
        }

        link.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(field.linkUrl)))
            } catch (e: Exception) {
                say("No browser here: open ${field.linkUrl.removePrefix("https://")} on another device", MUTED)
            }
        }
        save.setOnClickListener {
            field.set(field.normalize(input.text.toString()))
            dialog.dismiss()
        }
        remove.setOnClickListener {
            field.set("")
            dialog.dismiss()
        }

        dialog.show()
        val width = minOf(context.resources.displayMetrics.widthPixels - dp(32f).toInt(), dp(460f).toInt())
        dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun badge(frame: View, icon: ImageView, field: SettingField) {
        val secret = field.kind == SettingField.Kind.SECRET
        frame.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (secret) KEY_BADGE else URL_BADGE)
        }
        icon.setImageDrawable(icon(if (secret) "settings_ic_key" else "settings_ic_link", if (secret) KEY_BADGE_ICON else URL_BADGE_ICON))
    }

    @SuppressLint("DiscouragedApi")
    private fun identifier(name: String, type: String): Int =
        res.getIdentifier(name, type, RESOURCE_PACKAGE).takeIf { it != 0 } ?: throw Exception("Resource $type/$name not found")

    private fun inflate(inflater: LayoutInflater, layout: String, container: ViewGroup?): View =
        inflater.inflate(res.getLayout(identifier(layout, "layout")), container, false)

    private fun <T : View> View.find(name: String): T = findViewById(identifier(name, "id"))

    private fun icon(name: String, tint: Int): Drawable? =
        res.getDrawable(identifier(name, "drawable"), null)?.mutate()?.apply { setTint(tint) }

    private fun dp(value: Float) = value * resources.displayMetrics.density

    private fun shape(fill: Int, radius: Float, stroke: Int = 0, strokeWidth: Float = 1f) = GradientDrawable().apply {
        cornerRadius = dp(radius)
        setColor(fill)
        if (stroke != 0) setStroke(dp(strokeWidth).toInt(), stroke)
    }

    /** [normal], [pressed] while held and [focused] under a D-pad, like StreamPlay's rows. */
    private fun states(normal: Drawable, pressed: Drawable, focused: Drawable) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), focused)
        addState(intArrayOf(android.R.attr.state_pressed), pressed)
        addState(intArrayOf(), normal)
    }

    private companion object {
        val SHEET = 0xFF141414.toInt()
        val DIALOG = 0xFF1A1A1A.toInt()
        val CARD = 0xFF1E1E1E.toInt()
        val PRESSED = 0xFF2D2D2D.toInt()
        val INPUT = 0xFF1F1F1F.toInt()
        val INPUT_FOCUSED = 0xFF222222.toInt()
        val INPUT_STROKE = 0xFF3A3A3A.toInt()
        val WHITE = 0xFFFFFFFF.toInt()
        val MUTED = 0xFF888888.toInt()
        val GREEN = 0xFF5BC98C.toInt()
        val RED = 0xFFFF6B6B.toInt()
        val ACCENT = 0xFF7AA7FF.toInt()
        val KEY_BADGE = 0xFF164E4A.toInt()
        val KEY_BADGE_ICON = 0xFF5EEAD4.toInt()
        val URL_BADGE = 0xFF1E3A5F.toInt()
        val URL_BADGE_ICON = 0xFF7AA7FF.toInt()
    }
}
