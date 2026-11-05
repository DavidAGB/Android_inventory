package com.davidagb.inventario

import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * Panel lateral de ajustes que entra desde la derecha sobre la pantalla principal.
 * Se añade como capa encima del contenido: [attach] lo coloca en el FrameLayout raíz.
 */
class SettingsDrawer(private val a: MainActivity) {
    private val scrim = View(a).apply {
        setBackgroundColor(0x99000000.toInt())
        alpha = 0f
        visibility = View.GONE
        setOnClickListener { close() }
    }
    private val body = a.vbox(20)
    private val panel = ScrollView(a).apply {
        background = a.shape(a.c(R.color.bg), 0)
        elevation = a.dp(16).toFloat()
        visibility = View.GONE
        isFillViewport = true
        addView(body)
        isClickable = true // que los toques no atraviesen al contenido de abajo
    }
    var isOpen = false
        private set

    fun attach(frame: FrameLayout) {
        frame.addView(scrim, FrameLayout.LayoutParams(MATCH, MATCH))
        val width = minOf(a.dp(360), (a.resources.displayMetrics.widthPixels * 0.86f).toInt())
        frame.addView(panel, FrameLayout.LayoutParams(width, MATCH, Gravity.END))
        if (Build.VERSION.SDK_INT >= 30) {
            panel.setOnApplyWindowInsetsListener { v, ins ->
                val b = ins.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(0, b.top, b.right, b.bottom)
                ins
            }
        }
    }

    fun open(animate: Boolean = true) {
        render()
        isOpen = true
        scrim.visibility = View.VISIBLE
        panel.visibility = View.VISIBLE
        val w = panel.layoutParams.width.toFloat()
        if (animate) {
            panel.translationX = w
            panel.animate().translationX(0f).setDuration(220).start()
            scrim.animate().alpha(1f).setDuration(220).start()
        } else {
            panel.translationX = 0f
            scrim.alpha = 1f
        }
    }

    fun close() {
        if (!isOpen) return
        isOpen = false
        val w = panel.layoutParams.width.toFloat()
        panel.animate().translationX(w).setDuration(200).withEndAction { panel.visibility = View.GONE }.start()
        scrim.animate().alpha(0f).setDuration(200).withEndAction { scrim.visibility = View.GONE }.start()
    }

    private fun render() {
        body.removeAllViews()
        val head = a.hbox()
        body.add(head)
        head.add(a.label("Ajustes", 24f, bold = true), w = 0, weight = 1f)
        head.add(a.label("✕", 20f, R.color.muted).apply {
            gravity = Gravity.CENTER
            background = a.ripple(a.shape(a.c(R.color.bg), 999))
            contentDescription = "Cerrar ajustes"
            setOnClickListener { close() }
        }, w = 44, h = 44)

        // Apariencia
        body.add(a.caps("Apariencia"), top = 22, bottom = 8, start = 2)
        val look = a.card(4)
        body.add(look)
        look.add(a.label("Modo de color", 13.5f, R.color.muted, true), top = 8, bottom = 2, start = 12)
        val current = Appearance.get(a)
        listOf(
            Triple(Appearance.DARK, "Oscuro", "Fondo oscuro siempre"),
            Triple(Appearance.LIGHT, "Claro", "Fondo claro siempre"),
            Triple(Appearance.SYSTEM, "Sistema", "Cambia solo según el modo del teléfono"),
        ).forEach { (mode, title, hint) ->
            look.add(option(title, hint, mode == current) {
                if (mode != current) {
                    Appearance.set(a, mode)
                    a.recreate()
                }
            })
        }

        // Icono de la app
        body.add(a.caps("Icono de la app"), top = 22, bottom = 8, start = 2)
        val icons = a.hbox()
        body.add(icons)
        val chosen = AppIcon.selected(a)
        AppIcon.options.forEachIndexed { i, o ->
            icons.add(iconTile(o, o.key == chosen) {
                if (o.key != chosen) {
                    AppIcon.choose(a, o.key)
                    if (o.key != AppIcon.current(a)) a.toast("El icono cambiará cuando salgas de la app")
                    render()
                }
            }, w = 0, weight = 1f, start = if (i == 0) 0 else 5, end = if (i == AppIcon.options.lastIndex) 0 else 5)
        }
        if (chosen != AppIcon.current(a)) {
            body.add(a.label("Se aplicará cuando salgas de la app. Si tenías el icono en la pantalla de inicio, puede que tengas que volver a ponerlo desde el cajón de apps.", 12.5f, R.color.muted), top = 8, start = 2)
        }

        // Datos
        body.add(a.caps("Datos"), top = 22, bottom = 8, start = 2)
        val data = a.card(4)
        body.add(data)
        data.add(action("Categorías y subcategorías", "Crear, renombrar o borrar") { close(); a.openCategories() })
        data.add(action("Exportar inventario", "Archivo CSV para Excel") { close(); a.export(movements = false) })
        data.add(action("Exportar movimientos", "Todo el historial en CSV") { close(); a.export(movements = true) })

        // Acerca de
        body.add(a.caps("Acerca de"), top = 22, bottom = 8, start = 2)
        val about = a.card()
        body.add(about)
        val version = try { a.packageManager.getPackageInfo(a.packageName, 0).versionName } catch (e: Exception) { "?" }
        about.add(a.label("Mi Inventario $version", 15f, bold = true))
        about.add(a.label("Tus datos y fotos se guardan solo en este teléfono. Exporta a CSV de vez en cuando como respaldo.", 13f, R.color.muted), top = 4)
    }

    private fun option(title: String, hint: String, selected: Boolean, onClick: () -> Unit): View = a.hbox().apply {
        setPadding(a.dp(12), a.dp(12), a.dp(12), a.dp(12))
        background = a.ripple(a.shape(a.c(if (selected) R.color.accent_soft else R.color.surface), 10))
        setOnClickListener { onClick() }
        val text = a.vbox()
        add(text, w = 0, weight = 1f)
        text.add(a.label(title, 15f, if (selected) R.color.accent else R.color.ink, true))
        text.add(a.label(hint, 12.5f, R.color.muted))
        add(radio(selected), w = 22, h = 22, start = 10)
    }

    private fun iconTile(o: AppIcon.Option, selected: Boolean, onClick: () -> Unit): View = a.vbox(10).apply {
        gravity = Gravity.CENTER_HORIZONTAL
        val fill = a.c(if (selected) R.color.accent_soft else R.color.surface)
        val border = a.c(if (selected) R.color.accent else R.color.line)
        background = a.ripple(a.shape(fill, 14, border).apply { if (selected) setStroke(a.dp(2), border) })
        contentDescription = "Icono ${o.name}"
        setOnClickListener { onClick() }
        add(android.widget.ImageView(a).apply { setImageResource(o.icon) }, w = 56, h = 56)
        add(a.label(o.name, 13f, if (selected) R.color.accent else R.color.ink, selected).apply { gravity = Gravity.CENTER }, w = WRAP, top = 6)
    }

    private fun radio(on: Boolean): View = FrameLayout(a).apply {
        background = a.shape(a.c(R.color.surface), 999, a.c(if (on) R.color.accent else R.color.muted)).apply { setStroke(a.dp(2), a.c(if (on) R.color.accent else R.color.muted)) }
        if (on) addView(View(a).apply { background = a.shape(a.c(R.color.accent), 999) }, FrameLayout.LayoutParams(a.dp(10), a.dp(10), Gravity.CENTER))
    }

    private fun action(title: String, hint: String, onClick: () -> Unit): View = a.hbox().apply {
        setPadding(a.dp(12), a.dp(12), a.dp(12), a.dp(12))
        background = a.ripple(a.shape(a.c(R.color.surface), 10))
        setOnClickListener { onClick() }
        val text = a.vbox()
        add(text, w = 0, weight = 1f)
        text.add(a.label(title, 15f, bold = true))
        text.add(a.label(hint, 12.5f, R.color.muted))
        add(a.label("›", 22f, R.color.muted), w = WRAP, start = 8)
    }
}
