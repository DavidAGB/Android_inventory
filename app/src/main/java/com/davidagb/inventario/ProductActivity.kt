package com.davidagb.inventario

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout

class ProductActivity : BaseActivity() {
    private lateinit var inv: Inventory
    private lateinit var body: LinearLayout
    private lateinit var bar: TopBar
    private var id = 0L

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        inv = Inventory(this)
        id = intent.getLongExtra("id", 0)
        val root = vbox().apply { setBackgroundColor(c(R.color.bg)) }
        bar = TopBar(this, "", "Editar") {
            startActivity(Intent(this, EditProductActivity::class.java).putExtra("id", id))
        }
        root.add(bar.view)
        body = vbox(16)
        root.add(scroll(body), h = 0, weight = 1f)
        setContentView(root)
        edgeToEdge(root)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val p = inv.product(id) ?: run { finish(); return }
        bar.title.text = p.subName ?: p.topName
        body.removeAllViews()

        p.photo?.let { Photos.full(this, it) }?.let { bmp ->
            body.add(ImageView(this).apply {
                setImageBitmap(bmp)
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = shape(c(R.color.surface), 16)
                clipToOutline = true
            }, h = 230, bottom = 14)
        }
        body.add(label(p.name, 24f, bold = true))
        val where = listOfNotNull(p.topName, p.subName).joinToString(" / ")
        body.add(label(listOfNotNull(p.brand.ifBlank { null }, where).joinToString(" · "), 14f, R.color.muted), top = 2)

        // Existencias
        val stock = card()
        body.add(stock, top = 14)
        val top = hbox()
        stock.add(top)
        val left = vbox()
        top.add(left, w = 0, weight = 1f)
        left.add(label(p.units.toString(), 40f, if (p.empty) R.color.danger else R.color.ink, true))
        left.add(label(if (p.units == 1) "unidad disponible" else "unidades disponibles", 13f, R.color.muted))
        val alert = alertOf(p)
        top.add(pill(alert?.first ?: "Disponible", alert?.second ?: R.color.ok), w = WRAP)
        stock.add(label(Fmt.breakdown(p.sealed, p.opened), 15f, bold = true), top = 12)
        val facts = listOfNotNull(
            if (p.minStock > 0) "Avisa al quedar ${p.minStock} o menos" else "Sin stock mínimo",
            if (p.value > 0) "Valor en inventario: ${Fmt.money(p.value)}" else null,
            p.lastCost?.takeIf { it > 0 }?.let { "Último precio: ${Fmt.money(it)} c/u" },
        )
        for (f in facts) stock.add(label(f, 13.5f, R.color.muted), top = 3)

        val actions = hbox()
        body.add(actions, top = 12)
        actions.add(button("+ Entrada", 1) { entryDialog(p) }, w = 0, h = 46, weight = 1f, end = 4)
        actions.add(button("− Salida") { exitDialog(p) }, w = 0, h = 46, weight = 1f, start = 4, end = 4)
        actions.add(button("Abrir una") { openOne(p) }, w = 0, h = 46, weight = 1f, start = 4)

        // Lotes con existencias
        val batches = inv.batches(id)
        if (batches.isNotEmpty()) {
            val card = card()
            body.add(card, top = 14)
            card.add(caps("Lotes"))
            for (b in batches) {
                val r = vbox()
                card.add(r, top = 10)
                val bought = b.purchaseDate?.let { "Comprado el ${Fmt.date(it)}" } ?: "Sin fecha de compra"
                val cost = if (b.unitCost > 0) "${Fmt.money(b.unitCost)} c/u" else "sin costo"
                r.add(label("$bought · $cost", 14f, bold = true))
                r.add(label(Fmt.breakdown(b.sealed, b.opened), 13f, R.color.muted))
                b.expiryDate?.let { r.add(label(Fmt.expiryText(it), 13f, Fmt.expiryColor(it), true)) }
            }
        }

        if (p.notes.isNotBlank()) {
            val card = card()
            body.add(card, top = 14)
            card.add(caps("Notas"))
            card.add(label(p.notes, 14.5f), top = 6)
        }

        val moves = inv.movements(productId = id, limit = 100)
        body.add(caps("Historial"), top = 22, start = 2)
        if (moves.isEmpty()) body.add(label("Sin movimientos todavía.", 13.5f, R.color.muted), top = 6, start = 2)
        for (m in moves) body.add(movementRow(m, showProduct = false), top = 6)

        body.add(button("Eliminar producto", 2) { delete(p) }, h = 46, top = 26, bottom = 12)
    }

    private fun entryDialog(p: ProductRow) {
        val form = EntryForm(this, p.lastCost ?: 0)
        formDialog("Entrada · ${p.name}", form.view, "Registrar") {
            inv.addEntry(id, form.read())
            toast("Entrada registrada")
            render()
        }
    }

    private fun exitDialog(p: ProductRow) {
        if (p.empty) { toast("No hay unidades para sacar"); return }
        var reason = "terminado"
        var fromOpened = p.opened > 0
        val v = vbox()
        val poolOptions = listOfNotNull(
            if (p.sealed > 0) "s" to "Nuevas (${p.sealed})" else null,
            if (p.opened > 0) "o" to "Abiertas (${p.opened})" else null,
        )
        val pool = Chips(this, poolOptions, if (fromOpened) "o" else "s") { fromOpened = it == "o" }
        val reasons = Chips(this, Reasons.exit, reason) { r ->
            reason = r
            // Lo que se termina suele ser lo abierto; lo que se regala o se pierde, lo nuevo.
            fromOpened = (r == "terminado" && p.opened > 0) || p.sealed == 0
            pool.select(if (fromOpened) "o" else "s")
        }
        v.field("Motivo", reasons.view)
        val qty = input("Cantidad", NUMBER, "1")
        v.field("Cantidad", qty)
        if (poolOptions.size > 1) v.field("¿De cuáles?", pool.view)
        val note = input("Opcional")
        v.field("Nota", note)
        formDialog("Salida · ${p.name}", v, "Registrar") {
            val q = qty.text.toString().trim().toIntOrNull() ?: throw InventoryError("Escribe la cantidad")
            inv.removeUnits(id, reason, q, fromOpened, note.text.toString().trim())
            val after = inv.product(id)
            toast(if (after != null && after.low) "Salida registrada. Pasó a la lista de compras" else "Salida registrada")
            render()
        }
    }

    private fun openOne(p: ProductRow) {
        if (p.sealed == 0) {
            toast(if (p.empty) "No hay unidades" else "Todas las unidades ya están abiertas")
            return
        }
        try {
            inv.openUnits(id, 1)
            toast("1 unidad marcada como abierta")
            render()
        } catch (e: InventoryError) {
            toast(e.message ?: "Error")
        }
    }

    private fun delete(p: ProductRow) {
        confirm("¿Eliminar «${p.name}»?", "Se borrarán también sus lotes y su historial. No se puede deshacer.", "Eliminar") {
            inv.deleteProduct(id)
            p.photo?.let { Photos.delete(this, it) }
            toast("Producto eliminado")
            finish()
        }
    }
}
