package com.davidagb.inventario

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout

class CategoriesActivity : BaseActivity() {
    private lateinit var inv: Inventory
    private lateinit var body: LinearLayout

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        inv = Inventory(this)
        val root = vbox().apply { setBackgroundColor(c(R.color.bg)) }
        root.add(TopBar(this, "Categorías", "+ Nueva") { edit(null, null) }.view)
        body = vbox(16)
        root.add(scroll(body), h = 0, weight = 1f)
        setContentView(root)
        edgeToEdge(root)
        render()
    }

    private fun render() {
        body.removeAllViews()
        body.add(label("Toca una categoría para renombrarla, añadirle subcategorías o borrarla. Solo se borran si están vacías.", 13.5f, R.color.muted), start = 2)
        val cats = inv.categories()
        val counts = inv.productCounts()
        for (top in cats.filter { it.parentId == null }) {
            val subs = cats.filter { it.parentId == top.id }
            val total = (counts[top.id] ?: 0) + subs.sumOf { counts[it.id] ?: 0 }
            val card = card(4)
            body.add(card, top = 12)
            card.add(row(top.name, total, true) { options(top) })
            for (s in subs) card.add(row(s.name, counts[s.id] ?: 0, false) { options(s) })
        }
    }

    private fun row(name: String, count: Int, isTop: Boolean, onClick: () -> Unit): View = hbox().apply {
        setPadding(dp(if (isTop) 12 else 28), dp(11), dp(12), dp(11))
        background = ripple(shape(c(R.color.surface), 10))
        setOnClickListener { onClick() }
        add(label(name, if (isTop) 16f else 14.5f, bold = isTop), w = 0, weight = 1f)
        add(label(if (count == 1) "1 producto" else "$count productos", 12.5f, R.color.muted), w = WRAP)
    }

    private fun options(cat: Category) {
        val actions = if (cat.parentId == null) {
            listOf("Renombrar" to { edit(cat, null) }, "Añadir subcategoría" to { edit(null, cat.id) }, "Eliminar" to { delete(cat) })
        } else {
            listOf("Renombrar" to { edit(cat, cat.parentId) }, "Eliminar" to { delete(cat) })
        }
        AlertDialog.Builder(this).setTitle(cat.name)
            .setItems(actions.map { it.first }.toTypedArray()) { _, i -> actions[i].second() }
            .show()
    }

    /** cat != null: renombrar. cat == null: crear (subcategoría si [parent] no es null). */
    private fun edit(cat: Category?, parent: Long?) {
        val field = input("Nombre", value = cat?.name ?: "")
        val title = when {
            cat != null -> "Renombrar"
            parent != null -> "Nueva subcategoría"
            else -> "Nueva categoría"
        }
        formDialog(title, field, if (cat != null) "Guardar" else "Crear") {
            if (cat != null) inv.renameCategory(cat.id, field.text.toString())
            else inv.addCategory(field.text.toString(), parent)
            render()
        }
    }

    private fun delete(cat: Category) {
        confirm("¿Eliminar «${cat.name}»?", "Solo se puede si no tiene productos ni subcategorías.", "Eliminar") {
            inv.deleteCategory(cat.id)
            render()
        }
    }
}
