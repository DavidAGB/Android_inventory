package com.davidagb.inventario

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews

/** Widget de pantalla de inicio: lo que hay que reponer y lo que está por vencer. */
class InventoryWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = Widget.render(ctx, mgr, ids)

    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, options: Bundle) =
        Widget.render(ctx, mgr, intArrayOf(id))
}

object Widget {
    private val rows = intArrayOf(R.id.row0, R.id.row1, R.id.row2, R.id.row3, R.id.row4, R.id.row5)
    private val dots = intArrayOf(R.id.dot0, R.id.dot1, R.id.dot2, R.id.dot3, R.id.dot4, R.id.dot5)
    private val names = intArrayOf(R.id.name0, R.id.name1, R.id.name2, R.id.name3, R.id.name4, R.id.name5)
    private val details = intArrayOf(R.id.detail0, R.id.detail1, R.id.detail2, R.id.detail3, R.id.detail4, R.id.detail5)

    /** Una fila por producto; [rank] ordena por urgencia (0 = más urgente). */
    private class Item(val productId: Long, val name: String, val detail: String, val color: Int, val rank: Int, val sort: String)

    private class State(val items: List<Item>, val low: Int, val expiring: Int, val noProducts: Boolean)

    /** Redibuja todos los widgets puestos. Se llama al salir de la app y en la revisión periódica. */
    fun update(ctx: Context) {
        val mgr = AppWidgetManager.getInstance(ctx) ?: return
        val ids = mgr.getAppWidgetIds(ComponentName(ctx, InventoryWidget::class.java))
        if (ids.isNotEmpty()) render(ctx, mgr, ids)
    }

    /** Pide al lanzador que ponga el widget en la pantalla de inicio (muestra su propio diálogo). */
    fun canPin(ctx: Context) = AppWidgetManager.getInstance(ctx)?.isRequestPinAppWidgetSupported == true

    fun pin(ctx: Context) {
        AppWidgetManager.getInstance(ctx)?.requestPinAppWidget(ComponentName(ctx, InventoryWidget::class.java), null, null)
    }

    fun render(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        val state = load(ctx)
        for (id in ids) mgr.updateAppWidget(id, build(ctx, state, mgr.getAppWidgetOptions(id)))
    }

    private fun load(ctx: Context): State {
        val inv = Inventory(ctx)
        val products = inv.products()
        val best = LinkedHashMap<Long, Item>()
        fun offer(i: Item) {
            val cur = best[i.productId]
            if (cur == null || i.rank < cur.rank) best[i.productId] = i
        }
        val low = products.filter { it.low }
        for (p in low) offer(
            if (p.empty) Item(p.id, p.name, "Agotado", R.color.danger, 1, p.name)
            else Item(p.id, p.name, "Quedan ${p.units}", R.color.warn, 3, p.name),
        )
        // Vienen ordenados por fecha: el primero de cada producto es el que vence antes.
        val expiring = inv.expiring(30)
        for (e in expiring) {
            val d = Fmt.daysUntil(e.expiry)
            val text = when {
                d < 0 -> "Vencido"
                d == 0L -> "Vence hoy"
                d == 1L -> "Vence mañana"
                else -> "Vence en $d días"
            }
            val rank = if (d < 0) 0 else if (d <= 7) 2 else 4
            offer(Item(e.productId, e.name, text, if (d < 0) R.color.danger else R.color.warn, rank, e.expiry))
        }
        val items = best.values.sortedWith(compareBy<Item>({ it.rank }, { it.sort.lowercase() }))
        return State(items, low.size, expiring.map { it.productId }.distinct().size, products.isEmpty())
    }

    private fun build(ctx: Context, s: State, options: Bundle): RemoteViews {
        // Android 12+: el lanzador elige la vista según el tamaño real (vertical u horizontal).
        if (Build.VERSION.SDK_INT >= 31) {
            @Suppress("DEPRECATION")
            val sizes = options.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
            if (!sizes.isNullOrEmpty()) {
                return RemoteViews(sizes.distinct().take(16).associateWith { layout(ctx, s, it.width, it.height) })
            }
        }
        val w = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).takeIf { it > 0 } ?: 250
        val h = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).takeIf { it > 0 } ?: 110
        return layout(ctx, s, w.toFloat(), h.toFloat())
    }

    private fun layout(ctx: Context, s: State, w: Float, h: Float): RemoteViews =
        if (w < 200) small(ctx, s) else large(ctx, s, ((h - 76) / 34).toInt().coerceIn(0, rows.size))

    /** 2x2: solo los números. */
    private fun small(ctx: Context, s: State) = RemoteViews(ctx.packageName, R.layout.widget_small).apply {
        setOnClickPendingIntent(android.R.id.background, if (s.noProducts) newProduct(ctx) else openApp(ctx))
        when {
            s.noProducts -> {
                setTextViewText(R.id.big, "+")
                color(ctx, R.id.big, R.color.accent)
                setTextViewText(R.id.caption, "Añade tu primer producto")
                setViewVisibility(R.id.extra, View.GONE)
            }
            s.low == 0 && s.expiring == 0 -> {
                setTextViewText(R.id.big, "✓")
                color(ctx, R.id.big, R.color.ok)
                setTextViewText(R.id.caption, "Todo en orden")
                setViewVisibility(R.id.extra, View.GONE)
            }
            else -> {
                setTextViewText(R.id.big, s.low.toString())
                color(ctx, R.id.big, if (s.low > 0) R.color.warn else R.color.ink)
                setTextViewText(R.id.caption, "por reponer")
                setViewVisibility(R.id.extra, if (s.expiring > 0) View.VISIBLE else View.GONE)
                setTextViewText(R.id.extra, "${s.expiring} por vencer")
            }
        }
    }

    /** 3x2 o más: encabezado y lista de lo urgente. */
    private fun large(ctx: Context, s: State, slots: Int) = RemoteViews(ctx.packageName, R.layout.widget).apply {
        setOnClickPendingIntent(R.id.header, openApp(ctx))
        setOnClickPendingIntent(R.id.add, newProduct(ctx))

        val parts = listOfNotNull(
            if (s.low > 0) "${s.low} por reponer" else null,
            if (s.expiring > 0) "${s.expiring} por vencer" else null,
        )
        when {
            s.noProducts -> {
                setTextViewText(R.id.subtitle, "Sin productos todavía")
                color(ctx, R.id.subtitle, R.color.muted)
            }
            parts.isEmpty() -> {
                setTextViewText(R.id.subtitle, "Todo en orden ✓")
                color(ctx, R.id.subtitle, R.color.ok)
            }
            else -> {
                setTextViewText(R.id.subtitle, parts.joinToString(" · "))
                color(ctx, R.id.subtitle, R.color.warn)
            }
        }

        setViewVisibility(R.id.divider, if (slots > 0) View.VISIBLE else View.GONE)
        // Si no cabe todo, la última fila dice cuántos faltan.
        val more = s.items.size > slots && slots > 0
        val shown = s.items.take(if (more) slots - 1 else slots)
        for (i in rows.indices) {
            val item = shown.getOrNull(i)
            when {
                item != null -> {
                    setViewVisibility(rows[i], View.VISIBLE)
                    setViewVisibility(dots[i], View.VISIBLE)
                    setTextViewText(names[i], item.name)
                    color(ctx, names[i], R.color.ink)
                    setTextViewText(details[i], item.detail)
                    color(ctx, details[i], item.color)
                    color(ctx, dots[i], item.color)
                    setOnClickPendingIntent(rows[i], openProduct(ctx, item.productId))
                }
                more && i == shown.size -> {
                    setViewVisibility(rows[i], View.VISIBLE)
                    setViewVisibility(dots[i], View.INVISIBLE)
                    setTextViewText(names[i], "y ${s.items.size - shown.size} más…")
                    color(ctx, names[i], R.color.muted)
                    setTextViewText(details[i], "")
                    setOnClickPendingIntent(rows[i], openApp(ctx))
                }
                else -> setViewVisibility(rows[i], View.GONE)
            }
        }

        val empty = when {
            slots == 0 || s.items.isNotEmpty() -> null
            s.noProducts -> "Toca + para añadir el primero."
            else -> "Nada por reponer ni por vencer."
        }
        setViewVisibility(R.id.empty, if (empty != null) View.VISIBLE else View.GONE)
        setTextViewText(R.id.empty, empty ?: "")
    }

    /**
     * Color por recurso en Android 12+, así el lanzador lo resuelve con el modo claro/oscuro
     * vigente y el widget cambia solo al cambiar el tema del teléfono.
     */
    private fun RemoteViews.color(ctx: Context, id: Int, res: Int) {
        if (Build.VERSION.SDK_INT >= 31) setColorStateList(id, "setTextColor", res)
        else setTextColor(id, ctx.getColor(res))
    }

    // ---- a dónde lleva cada toque (siempre con el panel debajo para poder volver) ----

    private fun main(ctx: Context) = Intent(ctx, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

    private fun openApp(ctx: Context): PendingIntent =
        PendingIntent.getActivity(ctx, 1, main(ctx), PendingIntent.FLAG_IMMUTABLE)

    private fun newProduct(ctx: Context): PendingIntent = PendingIntent.getActivities(
        ctx, 2, arrayOf(main(ctx), Intent(ctx, EditProductActivity::class.java)), PendingIntent.FLAG_IMMUTABLE,
    )

    private fun openProduct(ctx: Context, id: Long): PendingIntent = PendingIntent.getActivities(
        ctx, 3,
        arrayOf(
            main(ctx),
            // El data distingue un producto de otro para que cada fila tenga su propio PendingIntent.
            Intent(ctx, ProductActivity::class.java).putExtra("id", id).setData(Uri.parse("inventario://producto/$id")),
        ),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
