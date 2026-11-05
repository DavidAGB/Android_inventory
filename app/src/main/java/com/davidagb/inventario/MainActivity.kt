package com.davidagb.inventario

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import java.time.LocalDate

class MainActivity : BaseActivity() {
    companion object {
        private const val REQ_EXPORT_INVENTORY = 10
        private const val REQ_EXPORT_MOVEMENTS = 11
    }

    lateinit var inv: Inventory
    private lateinit var content: FrameLayout
    private lateinit var nav: LinearLayout
    private lateinit var tabs: List<Tab>
    private lateinit var settings: SettingsDrawer
    private var current = 0

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        inv = Inventory(this)
        current = state?.getInt("tab", 0) ?: 0

        val root = vbox().apply { setBackgroundColor(c(R.color.bg)) }
        content = FrameLayout(this)
        root.add(content, h = 0, weight = 1f)
        root.add(View(this).apply { setBackgroundColor(c(R.color.line)) }, h = 1)
        nav = hbox().apply { setBackgroundColor(c(R.color.surface)) }
        root.add(nav)

        tabs = listOf(PanelTab(this), StockTab(this), HistoryTab(this))
        val fab = label("+", 30f, R.color.on_accent).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            background = ripple(shape(c(R.color.accent), 999))
            elevation = dp(6).toFloat()
            contentDescription = "Nuevo producto"
            setOnClickListener { newProduct() }
        }
        content.addView(fab, FrameLayout.LayoutParams(dp(58), dp(58), Gravity.BOTTOM or Gravity.END).apply {
            setMargins(0, 0, dp(18), dp(18))
        })

        val frame = FrameLayout(this)
        frame.addView(root, FrameLayout.LayoutParams(MATCH, MATCH))
        settings = SettingsDrawer(this)
        settings.attach(frame)
        setContentView(frame)
        edgeToEdge(root, nav)
        show(current, refresh = false)
        if (state?.getBoolean("settings") == true) settings.open(animate = false)
        Alerts.schedule(this)
        askNotifications()
    }

    override fun onResume() {
        super.onResume()
        tabs[current].refresh()
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putInt("tab", current)
        out.putBoolean("settings", settings.isOpen)
    }

    fun openSettings() = settings.open()

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (settings.isOpen) settings.close() else super.onBackPressed()
    }

    private fun show(i: Int, refresh: Boolean = true) {
        current = i
        if (content.childCount > 1) content.removeViewAt(0)
        content.addView(tabs[i].view, 0, FrameLayout.LayoutParams(MATCH, MATCH))
        nav.removeAllViews()
        listOf("Panel", "Inventario", "Historial").forEachIndexed { j, name ->
            val on = j == current
            nav.add(label(name, 13.5f, if (on) R.color.accent else R.color.muted, on).apply {
                gravity = Gravity.CENTER
                background = ripple(shape(c(R.color.surface), 0))
                setOnClickListener { if (current != j) show(j) }
            }, w = 0, h = 58, weight = 1f)
        }
        if (refresh) tabs[i].refresh()
    }

    fun newProduct() = startActivity(Intent(this, EditProductActivity::class.java))
    fun openProduct(id: Long) = startActivity(Intent(this, ProductActivity::class.java).putExtra("id", id))
    fun openCategories() = startActivity(Intent(this, CategoriesActivity::class.java))

    fun export(movements: Boolean) {
        val name = (if (movements) "movimientos" else "inventario") + "-${Fmt.today()}.csv"
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("text/csv")
            .putExtra(Intent.EXTRA_TITLE, name)
        App.externalFlow = true
        startActivityForResult(i, if (movements) REQ_EXPORT_MOVEMENTS else REQ_EXPORT_INVENTORY)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        try {
            contentResolver.openOutputStream(uri, "wt")?.use {
                when (requestCode) {
                    REQ_EXPORT_INVENTORY -> Export.inventory(inv, it)
                    REQ_EXPORT_MOVEMENTS -> Export.movements(inv, it)
                }
            }
            toast("Archivo exportado")
        } catch (e: Exception) {
            toast("No se pudo exportar: ${e.message}")
        }
    }

    private fun askNotifications() {
        if (Build.VERSION.SDK_INT < 33) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val prefs = getSharedPreferences("app", MODE_PRIVATE)
        if (prefs.getBoolean("askedNotifications", false)) return
        prefs.edit().putBoolean("askedNotifications", true).apply()
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }
}

interface Tab {
    val view: View
    fun refresh()
}

class PanelTab(private val a: MainActivity) : Tab {
    private val body = a.vbox(16)
    override val view: View = a.scroll(body)

    override fun refresh() {
        body.removeAllViews()
        val products = a.inv.products()
        val low = products.filter { it.low }
        val expiring = a.inv.expiring(30)

        body.add(a.header("Mi inventario"), top = 4)
        body.add(a.label(Fmt.longToday(), 13f, R.color.muted), bottom = 14)

        val row1 = a.hbox()
        body.add(row1)
        row1.add(a.tile(products.size.toString(), "Productos diferentes"), w = 0, h = MATCH, weight = 1f, end = 5)
        row1.add(a.tile(products.sumOf { it.units }.toString(), "Unidades totales"), w = 0, h = MATCH, weight = 1f, start = 5)
        val row2 = a.hbox()
        body.add(row2, top = 10)
        row2.add(a.tile(Fmt.money(products.sumOf { it.value }), "Valor inventariado"), w = 0, h = MATCH, weight = 1f, end = 5)
        row2.add(a.tile(low.size.toString(), "Por reponer", if (low.isEmpty()) R.color.ink else R.color.warn), w = 0, h = MATCH, weight = 1f, start = 5)
        body.add(a.tile(Fmt.money(a.inv.monthSpending()), "Compras de este mes"), top = 10)

        if (products.isEmpty()) {
            val card = a.card()
            body.add(card, top = 16)
            card.add(a.label("Empieza aquí", 16f, bold = true))
            card.add(a.label("Añade tu primer producto con el botón +. Puedes ponerle foto, precio, vencimiento y un stock mínimo para que la app te avise cuando toque reponerlo.", 14f, R.color.muted), top = 4)
        } else {
            val total = products.sumOf { it.units }
            val card = a.card()
            body.add(card, top = 16)
            card.add(a.label("Distribución por categoría", 16f, bold = true), bottom = 2)
            val byCat = products.groupBy { it.topId }
                .map { (_, ps) -> ps.first().topName to ps.sumOf { it.units } }
                .sortedByDescending { it.second }
            for ((name, units) in byCat) {
                val r = a.hbox()
                card.add(r, top = 12)
                r.add(a.label(name, 14f), w = 0, weight = 1f)
                r.add(a.label("$units und.", 13f, R.color.muted), w = WRAP)
                card.add(a.bar(units, total), h = 8, top = 6)
            }
        }

        // Lista de compras: lo que está por reponer.
        val shop = a.card()
        body.add(shop, top = 16)
        val head = a.hbox()
        shop.add(head)
        head.add(a.label("Lista de compras", 16f, bold = true), w = 0, weight = 1f)
        if (low.isNotEmpty()) head.add(a.label("Compartir", 14f, R.color.accent, true).apply {
            setPadding(a.dp(8), a.dp(6), a.dp(4), a.dp(6))
            setOnClickListener { shareList(low) }
        }, w = WRAP)
        if (low.isEmpty()) {
            shop.add(a.label("Nada por reponer. Los productos aparecen aquí cuando llegan a su stock mínimo.", 13.5f, R.color.muted), top = 4)
        } else {
            for (p in low) shop.add(a.simpleRow(
                listOf(p.name, p.brand).filter { it.isNotBlank() }.joinToString(" · "),
                "Quedan ${p.units} · mínimo ${p.minStock}", R.color.warn,
            ) { a.openProduct(p.id) }, top = 8)
        }

        if (expiring.isNotEmpty()) {
            val card = a.card()
            body.add(card, top = 16)
            card.add(a.label("Por vencer (30 días)", 16f, bold = true))
            for (e in expiring) card.add(a.simpleRow(
                listOf(e.name, e.brand).filter { it.isNotBlank() }.joinToString(" · ") + " · ${e.units} und.",
                Fmt.expiryText(e.expiry), Fmt.expiryColor(e.expiry),
            ) { a.openProduct(e.productId) }, top = 8)
        }

        body.add(View(a), h = 84)
    }

    private fun shareList(low: List<ProductRow>) {
        val text = "Lista de compras\n" + low.joinToString("\n") {
            "• ${it.name}" + (if (it.brand.isNotBlank()) " (${it.brand})" else "") + " — quedan ${it.units}"
        }
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        App.externalFlow = true
        a.startActivity(Intent.createChooser(send, "Compartir lista"))
    }
}

/** Título de pestaña con el botón de ajustes a la derecha. */
private fun MainActivity.header(title: String): View = hbox().apply {
    add(label(title, 26f, bold = true), w = 0, weight = 1f)
    add(android.widget.ImageView(this@header).apply {
        setImageResource(R.drawable.ic_settings)
        imageTintList = android.content.res.ColorStateList.valueOf(c(R.color.muted))
        setPadding(dp(12), dp(12), dp(12), dp(12))
        background = ripple(shape(c(R.color.bg), 999))
        contentDescription = "Ajustes"
        setOnClickListener { openSettings() }
    }, w = 48, h = 48)
}

private fun MainActivity.tile(value: String, caption: String, color: Int = R.color.ink): View = card().apply {
    add(label(value, 22f, color, true).oneLine())
    add(label(caption, 12.5f, R.color.muted), top = 2)
}

private fun MainActivity.simpleRow(title: String, subtitle: String, subColor: Int, onClick: () -> Unit): View =
    vbox(10).apply {
        background = ripple(shape(c(R.color.bg), 10))
        setOnClickListener { onClick() }
        add(label(title, 14.5f, bold = true).oneLine())
        add(label(subtitle, 13f, subColor))
    }

class StockTab(private val a: MainActivity) : Tab {
    private var filter = "todos"
    private var cat = 0L
    private var query = ""
    private var products = emptyList<ProductRow>()
    private var cats = emptyList<Category>()
    private val list = a.vbox()
    private val filters = Chips(
        a,
        listOf("todos" to "Todos", "reponer" to "Por reponer", "agotados" to "Agotados", "abiertos" to "En uso", "vencer" to "Por vencer"),
        "todos",
    ) { filter = it; renderList() }
    private val catChips = Chips(a, emptyList(), "0") { cat = it.toLong(); renderList() }
    override val view: View

    init {
        val body = a.vbox(16)
        body.add(a.header("Inventario"), top = 4)
        val search = a.input("Buscar por nombre, marca o nota", android.text.InputType.TYPE_CLASS_TEXT)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query = Fmt.norm(s?.toString()?.trim() ?: "")
                renderList()
            }
        })
        body.add(search, top = 12)
        body.add(filters.view, top = 10)
        body.add(catChips.view, top = 8)
        body.add(list, top = 2)
        body.add(View(a), h = 84)
        view = a.scroll(body)
    }

    override fun refresh() {
        products = a.inv.products()
        cats = a.inv.categories()
        val tops = cats.filter { it.parentId == null }
        if (tops.none { it.id == cat }) cat = 0
        catChips.options = listOf("0" to "Todas") + tops.map { it.id.toString() to it.name }
        catChips.select(cat.toString())
        renderList()
    }

    private fun renderList() {
        list.removeAllViews()
        val soon = LocalDate.now().plusDays(30).toString()
        val shown = products.filter { p ->
            val exp = p.nextExpiry
            (cat == 0L || p.topId == cat) &&
                when (filter) {
                    "reponer" -> p.low
                    "agotados" -> p.empty
                    "abiertos" -> p.opened > 0
                    "vencer" -> exp != null && exp <= soon
                    else -> true
                } &&
                (query.isEmpty() || Fmt.norm("${p.name} ${p.brand} ${p.notes} ${p.categoryName} ${p.parentName ?: ""}").contains(query))
        }
        if (shown.isEmpty()) {
            val msg = if (products.isEmpty()) "Aún no hay productos.\nPulsa + para añadir el primero." else "No hay productos con ese filtro."
            list.add(a.label(msg, 14f, R.color.muted).apply { gravity = Gravity.CENTER }, top = 40)
            return
        }
        val order = cats.withIndex().associate { it.value.id to it.index }
        val groups = shown.groupBy { it.topId }.toList().sortedBy { order[it.first] ?: Int.MAX_VALUE }
        for ((_, ps) in groups) {
            val head = a.hbox()
            list.add(head, top = 18, bottom = 2, start = 2)
            head.add(a.caps(ps.first().topName), w = 0, weight = 1f)
            head.add(a.label("${ps.sumOf { it.units }} und.", 12f, R.color.muted, true), w = WRAP)
            val subs = ps.groupBy { it.subName }.toList()
                .sortedWith(compareBy({ it.first != null }, { order[it.second.first().categoryId] ?: 0 }))
            for ((sub, items) in subs) {
                if (sub != null) list.add(a.label("$sub · ${items.sumOf { it.units }} und.", 13f, R.color.muted, true), top = 10, bottom = 2, start = 4)
                for (p in items) list.add(a.productRow(p) { a.openProduct(p.id) }, top = 6)
            }
        }
    }
}

class HistoryTab(private val a: MainActivity) : Tab {
    private var kind: String? = null
    private val list = a.vbox()
    private val chips = Chips(
        a,
        listOf("" to "Todos", Reasons.IN to "Entradas", Reasons.OUT to "Salidas", Reasons.OPEN to "Aperturas"),
        "",
    ) { kind = it.ifEmpty { null }; refresh() }
    override val view: View

    init {
        val body = a.vbox(16)
        body.add(a.header("Historial"), top = 4)
        body.add(a.label("Cada cambio de cantidad queda registrado con su motivo y fecha.", 13f, R.color.muted), top = 2)
        body.add(chips.view, top = 12)
        body.add(list, top = 4)
        body.add(View(a), h = 84)
        view = a.scroll(body)
    }

    override fun refresh() {
        list.removeAllViews()
        val ms = a.inv.movements(kind = kind, limit = 300)
        if (ms.isEmpty()) {
            list.add(a.label("Todavía no hay movimientos.", 14f, R.color.muted).apply { gravity = Gravity.CENTER }, top = 40)
            return
        }
        for (m in ms) list.add(a.movementRow(m, showProduct = true) { a.openProduct(m.productId) }, top = 6)
        if (ms.size == 300) list.add(a.label("Se muestran los 300 más recientes. Exporta los movimientos para verlos todos.", 12.5f, R.color.muted), top = 10)
    }
}
