package com.davidagb.inventario

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.AdapterView
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.roundToInt

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
const val NUMBER = InputType.TYPE_CLASS_NUMBER
const val TEXT = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES

// ---------- formatos ----------

object Fmt {
    val locale: Locale = Locale.forLanguageTag("es-CO")
    private val num = DecimalFormat("#,##0", DecimalFormatSymbols(locale))
    private val dateFmt = DateTimeFormatter.ofPattern("d MMM yyyy", locale)
    private val longFmt = DateTimeFormatter.ofPattern("EEEE d 'de' MMMM", locale)
    private val dateTimeFmt = DateTimeFormatter.ofPattern("d MMM yyyy · h:mm a", locale)
    private val stampFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    fun money(v: Long) = "$" + num.format(v)
    fun today(): String = LocalDate.now().toString()
    fun longToday(): String = LocalDate.now().format(longFmt).replaceFirstChar { it.uppercase(locale) }
    fun date(iso: String): String = try { LocalDate.parse(iso).format(dateFmt) } catch (e: Exception) { iso }
    fun dateTime(ms: Long): String = dateTimeFmt.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))
    fun stamp(ms: Long): String = stampFmt.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))
    fun daysUntil(iso: String): Long = ChronoUnit.DAYS.between(LocalDate.now(), LocalDate.parse(iso))

    fun expiryText(iso: String): String {
        val d = daysUntil(iso)
        return when {
            d < 0 -> "Vencido el ${date(iso)}"
            d == 0L -> "Vence hoy"
            d == 1L -> "Vence mañana"
            d <= 30 -> "Vence en $d días"
            else -> "Vence el ${date(iso)}"
        }
    }

    fun expiryColor(iso: String): Int {
        val d = daysUntil(iso)
        return if (d < 0) R.color.danger else if (d <= 30) R.color.warn else R.color.muted
    }

    fun parseMoney(s: String): Long? {
        val digits = s.filter(Char::isDigit)
        if (digits.isEmpty()) return null
        return digits.toLongOrNull()?.takeIf { it <= 10_000_000_000 } ?: throw InventoryError("Precio demasiado grande")
    }

    fun breakdown(sealed: Int, opened: Int): String = listOfNotNull(
        if (sealed > 0) (if (sealed == 1) "1 nueva" else "$sealed nuevas") else null,
        if (opened > 0) (if (opened == 1) "1 abierta" else "$opened abiertas") else null,
    ).joinToString(" · ").ifEmpty { "Sin unidades" }

    /** Minúsculas y sin tildes, para buscar. */
    fun norm(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase(locale)
}

/** Alerta más importante de un producto, o null si está bien. */
fun alertOf(p: ProductRow): Pair<String, Int>? {
    val exp = p.nextExpiry
    return when {
        p.empty -> "Agotado" to R.color.danger
        exp != null && Fmt.daysUntil(exp) < 0 -> "Tiene unidades vencidas" to R.color.danger
        p.low -> "Por reponer · mínimo ${p.minStock}" to R.color.warn
        exp != null && Fmt.daysUntil(exp) <= 30 -> Fmt.expiryText(exp) to R.color.warn
        else -> null
    }
}

// ---------- vistas ----------

fun Context.dp(v: Number): Int = (v.toFloat() * resources.displayMetrics.density).roundToInt()
fun Context.c(id: Int): Int = getColor(id)
private fun Context.size(v: Int) = if (v < 0) v else dp(v)

fun Context.shape(fill: Int, radius: Number = 14, stroke: Int? = null): GradientDrawable =
    GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

fun Context.ripple(d: Drawable): Drawable = RippleDrawable(ColorStateList.valueOf(c(R.color.ripple)), d, null)

/** Añade una vista a un LinearLayout. Anchos, altos y márgenes en dp (MATCH/WRAP se respetan). */
fun <T : View> LinearLayout.add(
    v: T, w: Int = MATCH, h: Int = WRAP, weight: Float = 0f,
    top: Int = 0, bottom: Int = 0, start: Int = 0, end: Int = 0,
): T {
    val ctx = context
    val lp = LinearLayout.LayoutParams(ctx.size(w), ctx.size(h), weight)
    lp.setMargins(ctx.dp(start), ctx.dp(top), ctx.dp(end), ctx.dp(bottom))
    addView(v, lp)
    return v
}

fun Context.label(s: CharSequence, size: Float = 15f, color: Int = R.color.ink, bold: Boolean = false): TextView =
    TextView(this).apply {
        text = s
        textSize = size
        setTextColor(c(color))
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

fun Context.caps(s: String): TextView = label(s.uppercase(Fmt.locale), 11f, R.color.muted).apply {
    letterSpacing = 0.12f
    typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
}

fun TextView.oneLine(): TextView = apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }

fun Context.vbox(pad: Int = 0): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    val p = dp(pad); setPadding(p, p, p, p)
}

fun Context.hbox(pad: Int = 0): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    val p = dp(pad); setPadding(p, p, p, p)
}

fun Context.card(pad: Int = 14): LinearLayout = vbox(pad).apply {
    background = shape(c(R.color.surface), 14, c(R.color.line))
}

fun Context.scroll(content: View): ScrollView = ScrollView(this).apply {
    isFillViewport = true
    addView(content)
}

/** style: 0 = suave, 1 = principal, 2 = peligro. */
fun Context.button(text: String, style: Int = 0, onClick: () -> Unit): TextView =
    label(text, 14f, bold = true).apply {
        gravity = Gravity.CENTER
        minHeight = dp(42)
        setPadding(dp(14), 0, dp(14), 0)
        val (bg, fg) = when (style) {
            1 -> R.color.accent to R.color.on_accent
            2 -> R.color.danger_soft to R.color.danger
            else -> R.color.accent_soft to R.color.accent
        }
        background = ripple(shape(c(bg), 12))
        setTextColor(c(fg))
        setOnClickListener { onClick() }
    }

fun Context.pill(text: String, color: Int): TextView = label(text, 12f, color, true).apply {
    val soft = when (color) {
        R.color.danger -> R.color.danger_soft
        R.color.warn -> R.color.warn_soft
        else -> R.color.accent_soft
    }
    background = shape(c(soft), 999)
    setPadding(dp(10), dp(4), dp(10), dp(4))
}

fun Context.input(hint: String, type: Int = TEXT, value: String = ""): EditText = EditText(this).apply {
    this.hint = hint
    inputType = type
    setText(value)
    textSize = 15f
    setTextColor(c(R.color.ink))
    setHintTextColor(c(R.color.muted))
    background = shape(c(R.color.surface), 10, c(R.color.line))
    setPadding(dp(12), dp(11), dp(12), dp(11))
}

fun LinearLayout.field(title: String, v: View): View {
    add(context.label(title, 12.5f, R.color.muted, true), top = 12, bottom = 5, start = 2)
    return add(v)
}

fun Context.toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

fun Context.bar(value: Int, total: Int): LinearLayout = LinearLayout(this).apply {
    background = shape(c(R.color.track), 99)
    if (total > 0 && value > 0) {
        addView(View(context).apply { background = shape(c(R.color.accent), 99) }, LinearLayout.LayoutParams(0, MATCH, value.toFloat()))
        if (total > value) addView(View(context), LinearLayout.LayoutParams(0, MATCH, (total - value).toFloat()))
    }
}

fun Context.thumb(photo: String?, name: String, size: Int): View {
    val bmp = photo?.let { Photos.thumb(this, it) }
    return if (bmp != null) {
        ImageView(this).apply {
            setImageBitmap(bmp)
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = shape(c(R.color.accent_soft), 10)
            clipToOutline = true
        }
    } else {
        label(name.trim().take(1).uppercase(Fmt.locale).ifEmpty { "?" }, size * 0.4f, R.color.accent, true).apply {
            gravity = Gravity.CENTER
            background = shape(c(R.color.accent_soft), 10)
        }
    }
}

fun Context.productRow(p: ProductRow, onClick: () -> Unit): View {
    val row = hbox(12).apply {
        background = ripple(shape(c(R.color.surface), 14, c(R.color.line)))
        setOnClickListener { onClick() }
    }
    row.add(thumb(p.photo, p.name, 52), w = 52, h = 52, end = 12)
    val mid = vbox()
    row.add(mid, w = 0, weight = 1f)
    mid.add(label(p.name, 15f, bold = true).oneLine())
    val sub = listOfNotNull(p.brand.ifBlank { null }, p.subName ?: p.topName).joinToString(" · ")
    mid.add(label(sub, 12.5f, R.color.muted).oneLine())
    val alert = alertOf(p)
    mid.add(label(alert?.first ?: Fmt.breakdown(p.sealed, p.opened), 12.5f, alert?.second ?: R.color.muted, alert != null).oneLine(), top = 2)
    val right = vbox().apply { gravity = Gravity.END }
    row.add(right, w = WRAP, start = 8)
    right.add(label(p.units.toString(), 22f, if (p.empty) R.color.danger else R.color.ink, true), w = WRAP)
    right.add(label("und.", 11f, R.color.muted), w = WRAP)
    return row
}

fun Context.movementRow(m: Movement, showProduct: Boolean, onClick: (() -> Unit)? = null): View {
    val row = hbox(12).apply {
        val bg = shape(c(R.color.surface), 14, c(R.color.line))
        background = if (onClick != null) ripple(bg) else bg
        onClick?.let { f -> setOnClickListener { f() } }
    }
    val (sign, color) = when (m.kind) {
        Reasons.IN -> "+${m.qty}" to R.color.ok
        Reasons.OUT -> "−${m.qty}" to R.color.danger
        else -> "◐${m.qty}" to R.color.accent
    }
    row.add(label(sign, 17f, color, true).apply { gravity = Gravity.CENTER }, w = 46)
    val mid = vbox()
    row.add(mid, w = 0, weight = 1f, start = 6)
    if (showProduct) {
        mid.add(label(listOf(m.productName, m.brand).filter { it.isNotBlank() }.joinToString(" · "), 14.5f, bold = true).oneLine())
        mid.add(label(Reasons.label(m.reason), 12.5f, R.color.muted))
    } else {
        mid.add(label(Reasons.label(m.reason), 14.5f, bold = true))
    }
    mid.add(label(Fmt.dateTime(m.at), 12f, R.color.muted))
    if (m.note.isNotBlank()) mid.add(label("“${m.note}”", 12.5f, R.color.muted))
    if (m.kind != Reasons.OPEN && m.unitCost > 0) row.add(label(Fmt.money(m.unitCost * m.qty), 13f, R.color.muted), w = WRAP, start = 8)
    return row
}

// ---------- ventanas ----------

/** Contenido de borde a borde (obligatorio con targetSdk 35): rellena con las barras del sistema y el teclado. */
fun Activity.edgeToEdge(root: View, bottomTarget: View? = null) {
    if (Build.VERSION.SDK_INT < 30) return
    window.setDecorFitsSystemWindows(false)
    root.setOnApplyWindowInsetsListener { v, ins ->
        val b = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
        if (bottomTarget == null) {
            v.setPadding(b.left, b.top, b.right, b.bottom)
        } else {
            v.setPadding(b.left, b.top, b.right, 0)
            bottomTarget.setPadding(0, 0, 0, b.bottom)
        }
        WindowInsets.CONSUMED
    }
}

/** Diálogo con formulario: si [onOk] lanza InventoryError se muestra el error y el diálogo sigue abierto. */
fun Activity.formDialog(title: String, content: View, ok: String, onOk: () -> Unit): AlertDialog {
    val wrap = ScrollView(this).apply {
        setPadding(dp(20), dp(4), dp(20), dp(8))
        addView(content)
    }
    val d = AlertDialog.Builder(this).setTitle(title).setView(wrap)
        .setPositiveButton(ok, null).setNegativeButton("Cancelar", null).create()
    d.setOnShowListener {
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            try {
                onOk(); d.dismiss()
            } catch (e: InventoryError) {
                toast(e.message ?: "Error")
            }
        }
    }
    d.show()
    return d
}

fun Activity.confirm(title: String, message: String, ok: String, onOk: () -> Unit) {
    AlertDialog.Builder(this).setTitle(title).setMessage(message)
        .setPositiveButton(ok) { _, _ ->
            try { onOk() } catch (e: InventoryError) { toast(e.message ?: "Error") }
        }
        .setNegativeButton("Cancelar", null).show()
}

fun itemSelected(f: (Int) -> Unit) = object : AdapterView.OnItemSelectedListener {
    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = f(position)
    override fun onNothingSelected(parent: AdapterView<*>?) {}
}

class TopBar(a: Activity, titleText: String, actionText: String? = null, action: (() -> Unit)? = null) {
    val view = a.hbox().apply { setPadding(a.dp(4), a.dp(4), a.dp(8), a.dp(4)) }
    val title = a.label(titleText, 17f, bold = true).oneLine()

    init {
        view.add(a.label("←", 22f).apply {
            gravity = Gravity.CENTER
            background = a.ripple(a.shape(a.c(R.color.bg), 999))
            contentDescription = "Volver"
            setOnClickListener { a.finish() }
        }, w = 48, h = 48)
        view.add(title, w = 0, weight = 1f, start = 4)
        if (actionText != null && action != null) {
            view.add(a.label(actionText, 15f, R.color.accent, true).apply {
                setPadding(a.dp(12), a.dp(10), a.dp(12), a.dp(10))
                background = a.ripple(a.shape(a.c(R.color.bg), 10))
                setOnClickListener { action() }
            }, w = WRAP)
        }
    }
}

/** Fila de opciones de las que se elige una. */
class Chips(
    private val ctx: Context,
    options: List<Pair<String, String>>,
    selected: String,
    private val onChange: (String) -> Unit,
) {
    var options: List<Pair<String, String>> = options
        set(v) { field = v; render() }
    var selected: String = selected
        private set
    private val row = ctx.hbox()
    val view = HorizontalScrollView(ctx).apply {
        isHorizontalScrollBarEnabled = false
        addView(row)
    }

    init { render() }

    fun select(key: String) { selected = key; render() }

    private fun render() {
        row.removeAllViews()
        for ((key, text) in options) {
            val on = key == selected
            row.add(ctx.label(text, 13f, if (on) R.color.ink else R.color.muted, on).apply {
                gravity = Gravity.CENTER
                setPadding(ctx.dp(14), 0, ctx.dp(14), 0)
                background = ctx.ripple(ctx.shape(ctx.c(if (on) R.color.accent_soft else R.color.surface), 999, ctx.c(if (on) R.color.accent else R.color.line)))
                setOnClickListener {
                    if (selected != key) { selected = key; render(); onChange(key) }
                }
            }, w = WRAP, h = 34, end = 6)
        }
    }
}

class DateField(private val a: Activity, initial: String?, private val optional: Boolean, private val emptyText: String) {
    var value: String? = initial
        set(v) { field = v; render() }
    private val text = a.label("", 15f).apply {
        setPadding(a.dp(12), a.dp(11), a.dp(12), a.dp(11))
        background = a.ripple(a.shape(a.c(R.color.surface), 10, a.c(R.color.line)))
    }
    private val clear = a.label("Quitar", 13f, R.color.accent, true).apply {
        setPadding(a.dp(14), a.dp(10), a.dp(4), a.dp(10))
    }
    val view = a.hbox()

    init {
        view.add(text, w = 0, weight = 1f)
        if (optional) view.add(clear, w = WRAP)
        text.setOnClickListener { pick() }
        clear.setOnClickListener { value = null }
        render()
    }

    private fun render() {
        val v = value
        text.text = if (v == null) emptyText else Fmt.date(v)
        text.setTextColor(a.c(if (v == null) R.color.muted else R.color.ink))
        clear.visibility = if (optional && v != null) View.VISIBLE else View.GONE
    }

    private fun pick() {
        val d = value?.let { LocalDate.parse(it) } ?: LocalDate.now()
        DatePickerDialog(a, { _, y, m, day -> value = LocalDate.of(y, m + 1, day).toString() }, d.year, d.monthValue - 1, d.dayOfMonth).show()
    }
}

/** Formulario de entrada (compra, regalo, devolución…). Cada entrada crea un lote. */
class EntryForm(
    a: Activity,
    defaultCost: Long,
    reasons: List<Pair<String, String>> = Reasons.entry,
    initialQty: String = "1",
) {
    var reason = reasons.first().first
        private set
    private val qty = a.input("Cantidad", NUMBER, initialQty)
    private val cost = a.input("Ej: 38000", NUMBER, if (defaultCost > 0) defaultCost.toString() else "")
    private val purchase = DateField(a, Fmt.today(), false, "Sin fecha")
    private val expiry = DateField(a, null, true, "Sin vencimiento (toca para elegir)")
    private val note = a.input("Opcional")
    private val chips = Chips(a, reasons, reason) { reason = it }
    val view = a.vbox()

    init {
        if (reasons.size > 1) view.field("Motivo", chips.view)
        view.field("Cantidad", qty)
        view.field("Precio por unidad (COP)", cost)
        view.field("Fecha de compra", purchase.view)
        view.field("Vencimiento", expiry.view)
        view.field("Nota", note)
    }

    fun read(): EntryInput {
        val q = qty.text.toString().trim().toIntOrNull() ?: throw InventoryError("Escribe la cantidad")
        if (q <= 0) throw InventoryError("La cantidad debe ser mayor que cero")
        return EntryInput(reason, q, Fmt.parseMoney(cost.text.toString()) ?: 0, purchase.value, expiry.value, note.text.toString().trim())
    }

    /** Para las existencias iniciales: vacío o 0 significa "sin unidades todavía". */
    fun readOptional(): EntryInput? {
        val t = qty.text.toString().trim()
        return if (t.isEmpty() || t.toIntOrNull() == 0) null else read()
    }

    fun save(b: Bundle) {
        b.putString("e_reason", reason); b.putString("e_qty", qty.text.toString())
        b.putString("e_cost", cost.text.toString()); b.putString("e_note", note.text.toString())
        b.putString("e_purchase", purchase.value); b.putString("e_expiry", expiry.value)
    }

    fun restore(b: Bundle) {
        b.getString("e_reason")?.let { reason = it; chips.select(it) }
        qty.setText(b.getString("e_qty", "")); cost.setText(b.getString("e_cost", ""))
        note.setText(b.getString("e_note", ""))
        purchase.value = b.getString("e_purchase"); expiry.value = b.getString("e_expiry")
    }
}
