package com.davidagb.inventario

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Spinner
import java.io.FileInputStream

class EditProductActivity : BaseActivity() {
    companion object {
        private const val REQ_CAMERA = 1
        private const val REQ_GALLERY = 2
    }

    private lateinit var inv: Inventory
    private var id = 0L
    private var photo: String? = null
    private var original: String? = null
    private var saved = false
    private var cats = emptyList<Category>()
    private var topId = 0L
    private var subId = 0L
    private lateinit var name: EditText
    private lateinit var brand: EditText
    private lateinit var minStock: EditText
    private lateinit var notes: EditText
    private lateinit var topSpinner: Spinner
    private lateinit var subSpinner: Spinner
    private lateinit var photoBox: FrameLayout
    private var entry: EntryForm? = null

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        inv = Inventory(this)
        id = intent.getLongExtra("id", 0)
        val p = if (id > 0) inv.product(id) ?: run { finish(); return } else null
        original = p?.photo
        photo = p?.photo
        cats = inv.categories()
        if (p != null) {
            topId = p.topId
            subId = if (p.parentId != null) p.categoryId else 0
        }

        val root = vbox().apply { setBackgroundColor(c(R.color.bg)) }
        root.add(TopBar(this, if (p == null) "Nuevo producto" else "Editar producto").view)
        val body = vbox(16)
        root.add(scroll(body), h = 0, weight = 1f)

        photoBox = FrameLayout(this)
        body.add(photoBox, h = 190)
        val photoButtons = hbox()
        body.add(photoButtons, top = 8)
        photoButtons.add(button("Cámara") { takePhoto() }, w = 0, h = 42, weight = 1f, end = 4)
        photoButtons.add(button("Galería") { pickPhoto() }, w = 0, h = 42, weight = 1f, start = 4, end = 4)
        photoButtons.add(button("Quitar") { setPhoto(null) }, w = 0, h = 42, weight = 1f, start = 4)

        name = input("Ej: Rubor Fit Me", value = p?.name ?: "")
        body.field("Nombre *", name)
        brand = input("Ej: Maybelline", value = p?.brand ?: "")
        body.field("Marca", brand)
        topSpinner = Spinner(this)
        subSpinner = Spinner(this)
        body.field("Categoría", spinnerRow(topSpinner) { newCategory(null) })
        body.field("Subcategoría", spinnerRow(subSpinner) { if (topId > 0) newCategory(topId) })
        minStock = input("0 = sin aviso", NUMBER, p?.minStock?.takeIf { it > 0 }?.toString() ?: "")
        body.field("Stock mínimo (avisar al quedar esta cantidad o menos)", minStock)
        notes = input("Tono, referencia, características", TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE, p?.notes ?: "").apply {
            minLines = 3
            gravity = Gravity.TOP or Gravity.START
        }
        body.field("Notas", notes)

        if (p == null) {
            val e = EntryForm(this, 0, Reasons.entry.take(2))
            entry = e
            body.add(label("Existencias iniciales", 17f, bold = true), top = 24)
            body.add(label("Pon 0 en cantidad si todavía no lo tienes.", 13f, R.color.muted), top = 2)
            body.add(e.view)
        }
        body.add(button("Guardar", 1) { save() }, h = 48, top = 24, bottom = 12)

        setContentView(root)
        edgeToEdge(root)
        if (state != null) restore(state)
        renderPhoto()
        renderCats()
    }

    private fun spinnerRow(spinner: Spinner, onAdd: () -> Unit): View = hbox().apply {
        add(spinner.apply { background = shape(c(R.color.surface), 10, c(R.color.line)) }, w = 0, h = 46, weight = 1f)
        add(button("+") { onAdd() }.apply { contentDescription = "Crear nueva" }, w = 46, h = 46, start = 8)
    }

    private fun adapter(items: List<String>) =
        ArrayAdapter(this, android.R.layout.simple_spinner_item, items).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

    private fun renderCats() {
        val tops = cats.filter { it.parentId == null }
        if (tops.none { it.id == topId }) topId = tops.firstOrNull()?.id ?: 0
        topSpinner.adapter = adapter(tops.map { it.name })
        topSpinner.setSelection(tops.indexOfFirst { it.id == topId }.coerceAtLeast(0))
        val subs = cats.filter { it.parentId == topId }
        if (subs.none { it.id == subId }) subId = 0
        subSpinner.adapter = adapter(listOf("(Ninguna)") + subs.map { it.name })
        subSpinner.setSelection(subs.indexOfFirst { it.id == subId } + 1)
        topSpinner.onItemSelectedListener = itemSelected { pos ->
            val nid = tops.getOrNull(pos)?.id ?: 0
            if (nid != topId) { topId = nid; subId = 0; renderCats() }
        }
        subSpinner.onItemSelectedListener = itemSelected { pos ->
            subId = if (pos == 0) 0 else subs.getOrNull(pos - 1)?.id ?: 0
        }
    }

    private fun newCategory(parent: Long?) {
        val field = input(if (parent == null) "Ej: Joyería" else "Ej: Iluminadores")
        formDialog(if (parent == null) "Nueva categoría" else "Nueva subcategoría", field, "Crear") {
            val nid = inv.addCategory(field.text.toString(), parent)
            cats = inv.categories()
            if (parent == null) { topId = nid; subId = 0 } else subId = nid
            renderCats()
        }
    }

    // ---- foto ----

    private fun renderPhoto() {
        photoBox.removeAllViews()
        val bmp = photo?.let { Photos.full(this, it) }
        val v: View = if (bmp != null) {
            ImageView(this).apply {
                setImageBitmap(bmp)
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = shape(c(R.color.surface), 16)
                clipToOutline = true
            }
        } else {
            label("Sin foto", 14f, R.color.muted).apply {
                gravity = Gravity.CENTER
                background = shape(c(R.color.surface), 16, c(R.color.line))
            }
        }
        photoBox.addView(v, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    /** Cambia la foto mostrada. Las fotos nuevas que se descartan se borran al momento. */
    private fun setPhoto(n: String?) {
        val prev = photo
        if (prev != null && prev != original && prev != n) Photos.delete(this, prev)
        photo = n
        renderPhoto()
    }

    private fun takePhoto() {
        Photos.cameraFile(this).delete()
        val uri = Photos.cameraUri(this)
        val i = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            .putExtra(MediaStore.EXTRA_OUTPUT, uri)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        i.clipData = ClipData.newRawUri("", uri)
        try {
            App.externalFlow = true
            startActivityForResult(i, REQ_CAMERA)
        } catch (e: ActivityNotFoundException) {
            toast("No hay una app de cámara disponible")
        }
    }

    private fun pickPhoto() {
        val i = if (Build.VERSION.SDK_INT >= 33) {
            Intent(MediaStore.ACTION_PICK_IMAGES)
        } else {
            Intent(Intent.ACTION_GET_CONTENT).setType("image/*")
        }
        try {
            App.externalFlow = true
            startActivityForResult(i, REQ_GALLERY)
        } catch (e: ActivityNotFoundException) {
            toast("No hay una galería disponible")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        try {
            when (requestCode) {
                REQ_CAMERA -> {
                    val f = Photos.cameraFile(this)
                    if (f.length() == 0L) throw InventoryError("La cámara no devolvió ninguna foto")
                    setPhoto(Photos.import(this) { FileInputStream(f) })
                    f.delete()
                }
                REQ_GALLERY -> {
                    val uri = data?.data ?: return
                    setPhoto(Photos.import(this) { contentResolver.openInputStream(uri) })
                }
            }
        } catch (e: InventoryError) {
            toast(e.message ?: "Error")
        } catch (e: Exception) {
            toast("No se pudo guardar la foto")
        }
    }

    // ---- guardar ----

    private fun save() {
        try {
            if (topId == 0L) throw InventoryError("Crea una categoría primero")
            val min = minStock.text.toString().trim().let {
                if (it.isEmpty()) 0 else it.toIntOrNull() ?: throw InventoryError("Stock mínimo no válido")
            }
            val input = ProductInput(
                name = name.text.toString().trim(),
                brand = brand.text.toString().trim(),
                categoryId = if (subId > 0) subId else topId,
                notes = notes.text.toString().trim(),
                minStock = min,
                photo = photo,
            )
            if (id == 0L) {
                val newId = inv.createProduct(input, entry?.readOptional())
                saved = true
                startActivity(Intent(this, ProductActivity::class.java).putExtra("id", newId))
            } else {
                inv.updateProduct(id, input)
                saved = true
            }
            val old = original
            if (old != null && old != photo) Photos.delete(this, old)
            finish()
        } catch (e: InventoryError) {
            toast(e.message ?: "Error")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Si se sale sin guardar, borra la foto nueva que no llegó a usarse.
        val p = photo
        if (isFinishing && !saved && p != null && p != original) Photos.delete(this, p)
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putString("photo", photo)
        out.putLong("top", topId)
        out.putLong("sub", subId)
        out.putString("name", name.text.toString())
        out.putString("brand", brand.text.toString())
        out.putString("min", minStock.text.toString())
        out.putString("notes", notes.text.toString())
        entry?.save(out)
    }

    private fun restore(s: Bundle) {
        photo = s.getString("photo")
        topId = s.getLong("top")
        subId = s.getLong("sub")
        name.setText(s.getString("name", ""))
        brand.setText(s.getString("brand", ""))
        minStock.setText(s.getString("min", ""))
        notes.setText(s.getString("notes", ""))
        entry?.restore(s)
    }
}
