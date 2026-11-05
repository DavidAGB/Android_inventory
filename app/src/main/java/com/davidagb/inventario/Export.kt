package com.davidagb.inventario

import java.io.OutputStream

/** CSV con «;» y BOM UTF-8, para que Excel en español lo abra con columnas y tildes correctas. */
object Export {
    fun inventory(inv: Inventory, out: OutputStream) = write(
        out,
        listOf(
            "Categoría", "Subcategoría", "Producto", "Marca", "Unidades", "Nuevas", "Abiertas",
            "Stock mínimo", "Por reponer", "Valor (COP)", "Último precio (COP)", "Próximo vencimiento", "Notas",
        ),
        inv.products().sortedWith(compareBy({ it.topName }, { it.subName ?: "" }, { it.name })).map {
            listOf(
                it.topName, it.subName ?: "", it.name, it.brand, it.units, it.sealed, it.opened,
                it.minStock, if (it.low) "Sí" else "No", it.value, it.lastCost ?: "", it.nextExpiry ?: "", it.notes,
            )
        },
    )

    fun movements(inv: Inventory, out: OutputStream) = write(
        out,
        listOf("Fecha", "Producto", "Marca", "Tipo", "Motivo", "Cantidad", "Costo unitario (COP)", "Total (COP)", "Nota"),
        inv.movements(limit = Int.MAX_VALUE).map {
            val kind = when (it.kind) { Reasons.IN -> "Entrada"; Reasons.OUT -> "Salida"; else -> "Apertura" }
            listOf(Fmt.stamp(it.at), it.productName, it.brand, kind, Reasons.label(it.reason), it.qty, it.unitCost, it.unitCost * it.qty, it.note)
        },
    )

    private fun write(out: OutputStream, header: List<String>, rows: List<List<Any>>) {
        val w = out.bufferedWriter(Charsets.UTF_8)
        w.write("﻿")
        for (row in listOf<List<Any>>(header) + rows) {
            w.write(row.joinToString(";") { cell(it) })
            w.write("\r\n")
        }
        w.flush()
    }

    private fun cell(v: Any): String {
        if (v is Number) return v.toString()
        var s = v.toString()
        // Evita que Excel interprete el texto como fórmula.
        if (s.isNotEmpty() && s[0] in "=+-@\t\r") s = "'$s"
        return "\"" + s.replace("\"", "\"\"") + "\""
    }
}
