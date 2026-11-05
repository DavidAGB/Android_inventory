package com.davidagb.inventario

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.LruCache
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID

/**
 * Fotos de productos: se guardan reducidas (máx. 1280 px) en el almacenamiento
 * privado de la app, con una miniatura cuadrada para las listas.
 */
object Photos {
    private const val MAX = 1280
    private const val THUMB = 240
    private const val CAMERA_FILE = "captura.jpg"

    private val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    private fun dir(ctx: Context) = File(ctx.filesDir, "photos").apply { mkdirs() }
    private fun file(ctx: Context, name: String) = File(dir(ctx), "$name.jpg")
    private fun thumbFile(ctx: Context, name: String) = File(dir(ctx), "${name}_t.jpg")

    fun cameraFile(ctx: Context) = File(File(ctx.cacheDir, "camera").apply { mkdirs() }, CAMERA_FILE)
    fun cameraUri(ctx: Context): Uri = Uri.parse("content://${ctx.packageName}.photos/$CAMERA_FILE")

    fun thumb(ctx: Context, name: String): Bitmap? =
        cache.get(name) ?: BitmapFactory.decodeFile(thumbFile(ctx, name).path)?.also { cache.put(name, it) }

    fun full(ctx: Context, name: String): Bitmap? = BitmapFactory.decodeFile(file(ctx, name).path)

    fun delete(ctx: Context, name: String) {
        file(ctx, name).delete()
        thumbFile(ctx, name).delete()
        cache.remove(name)
    }

    /** Valida, reduce, endereza y guarda la imagen. [open] se llama varias veces. Devuelve el nombre guardado. */
    fun import(ctx: Context, open: () -> InputStream?): String {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        (open() ?: throw InventoryError("No se pudo abrir la imagen")).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw InventoryError("El archivo no es una imagen válida")

        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX) sample *= 2
        val raw = (open() ?: throw InventoryError("No se pudo abrir la imagen")).use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw InventoryError("No se pudo leer la imagen")

        val orientation = try {
            open()?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        } catch (e: Exception) {
            null
        }
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }

        val scale = minOf(1f, MAX.toFloat() / maxOf(raw.width, raw.height))
        val m = Matrix().apply { postScale(scale, scale); postRotate(degrees) }
        val big = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)

        val name = UUID.randomUUID().toString()
        FileOutputStream(file(ctx, name)).use { big.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        val side = minOf(big.width, big.height)
        val square = Bitmap.createBitmap(big, (big.width - side) / 2, (big.height - side) / 2, side, side)
        val small = Bitmap.createScaledBitmap(square, THUMB, THUMB, true)
        FileOutputStream(thumbFile(ctx, name)).use { small.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        return name
    }
}

/**
 * Entrega a la app de cámara un único archivo temporal donde escribir la foto.
 * No está exportado: solo se accede con el permiso temporal que da el Intent.
 */
class PhotoProvider : ContentProvider() {
    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val ctx = context ?: throw FileNotFoundException(uri.toString())
        if (uri.lastPathSegment != Photos.cameraFile(ctx).name) throw FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(Photos.cameraFile(ctx), ParcelFileDescriptor.parseMode(mode))
    }

    override fun getType(uri: Uri) = "image/jpeg"

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val f = Photos.cameraFile(context!!)
        return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), 1).apply {
            addRow(arrayOf<Any>(f.name, f.length()))
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
