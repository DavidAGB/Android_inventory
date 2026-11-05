package com.davidagb.inventario

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.database.sqlite.SQLiteStatement
import java.time.LocalDate

/** Error que se muestra tal cual al usuario. */
class InventoryError(message: String) : Exception(message)

data class Category(val id: Long, val name: String, val parentId: Long?)

data class ProductRow(
    val id: Long,
    val name: String,
    val brand: String,
    val categoryId: Long,
    val categoryName: String,
    val parentId: Long?,
    val parentName: String?,
    val notes: String,
    val minStock: Int,
    val photo: String?,
    val sealed: Int,
    val opened: Int,
    val value: Long,
    val nextExpiry: String?,
    val lastCost: Long?,
) {
    val units get() = sealed + opened
    val topId get() = parentId ?: categoryId
    val topName get() = parentName ?: categoryName
    val subName get() = if (parentId != null) categoryName else null
    val empty get() = units == 0
    /** Por reponer: tiene un mínimo definido y quedan esas unidades o menos. */
    val low get() = minStock > 0 && units <= minStock
}

data class Batch(
    val id: Long,
    val purchaseDate: String?,
    val expiryDate: String?,
    val unitCost: Long,
    val sealed: Int,
    val opened: Int,
)

data class Movement(
    val id: Long,
    val productId: Long,
    val productName: String,
    val brand: String,
    val kind: String,
    val reason: String,
    val qty: Int,
    val unitCost: Long,
    val at: Long,
    val note: String,
)

data class ExpiringBatch(val productId: Long, val name: String, val brand: String, val expiry: String, val units: Int)

data class ProductInput(
    val name: String,
    val brand: String,
    val categoryId: Long,
    val notes: String,
    val minStock: Int,
    val photo: String?,
)

data class EntryInput(
    val reason: String,
    val qty: Int,
    val unitCost: Long,
    val purchaseDate: String?,
    val expiryDate: String?,
    val note: String,
)

/**
 * Tipos de movimiento. Una ENTRADA crea un lote nuevo; una SALIDA saca unidades
 * del inventario; una APERTURA solo pasa unidades de "nuevas" a "abiertas" (en uso)
 * y no cambia la cantidad disponible.
 */
object Reasons {
    const val IN = "IN"
    const val OUT = "OUT"
    const val OPEN = "OPEN"

    val entry = listOf(
        "compra" to "Compra",
        "regalo" to "Regalo",
        "devolucion" to "Devolución",
        "ajuste_mas" to "Ajuste (+)",
    )
    val exit = listOf(
        "terminado" to "Se terminó",
        "regalado" to "Regalado",
        "perdido" to "Perdido",
        "danado" to "Dañado",
        "vencido" to "Vencido",
        "ajuste_menos" to "Ajuste (−)",
    )

    fun label(code: String): String =
        (entry + exit).firstOrNull { it.first == code }?.second
            ?: if (code == "abierto") "Abierto / en uso" else code
}

class Db private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "inventario.db", null, 1) {
    companion object {
        @Volatile
        private var instance: Db? = null

        fun get(ctx: Context): Db =
            instance ?: synchronized(this) { instance ?: Db(ctx.applicationContext).also { instance = it } }

        private val SCHEMA = listOf(
            """CREATE TABLE categories(
                id INTEGER PRIMARY KEY,
                name TEXT NOT NULL,
                parent_id INTEGER REFERENCES categories(id) ON DELETE RESTRICT,
                position INTEGER NOT NULL DEFAULT 0)""",
            "CREATE INDEX categories_parent ON categories(parent_id)",
            """CREATE TABLE products(
                id INTEGER PRIMARY KEY,
                name TEXT NOT NULL,
                brand TEXT NOT NULL DEFAULT '',
                category_id INTEGER NOT NULL REFERENCES categories(id) ON DELETE RESTRICT,
                notes TEXT NOT NULL DEFAULT '',
                min_stock INTEGER NOT NULL DEFAULT 0 CHECK(min_stock >= 0),
                photo TEXT,
                created_at INTEGER NOT NULL)""",
            "CREATE INDEX products_category ON products(category_id)",
            // Cada entrada es un lote: conserva su precio, fecha de compra y vencimiento.
            """CREATE TABLE batches(
                id INTEGER PRIMARY KEY,
                product_id INTEGER NOT NULL REFERENCES products(id) ON DELETE CASCADE,
                purchase_date TEXT,
                expiry_date TEXT,
                unit_cost INTEGER NOT NULL DEFAULT 0 CHECK(unit_cost >= 0),
                sealed INTEGER NOT NULL DEFAULT 0 CHECK(sealed >= 0),
                opened INTEGER NOT NULL DEFAULT 0 CHECK(opened >= 0),
                created_at INTEGER NOT NULL)""",
            "CREATE INDEX batches_product ON batches(product_id)",
            "CREATE INDEX batches_expiry ON batches(expiry_date)",
            """CREATE TABLE movements(
                id INTEGER PRIMARY KEY,
                product_id INTEGER NOT NULL REFERENCES products(id) ON DELETE CASCADE,
                batch_id INTEGER REFERENCES batches(id) ON DELETE SET NULL,
                kind TEXT NOT NULL CHECK(kind IN ('IN','OUT','OPEN')),
                reason TEXT NOT NULL,
                qty INTEGER NOT NULL CHECK(qty > 0),
                unit_cost INTEGER NOT NULL DEFAULT 0,
                at INTEGER NOT NULL,
                note TEXT NOT NULL DEFAULT '')""",
            "CREATE INDEX movements_product ON movements(product_id, at)",
            "CREATE INDEX movements_at ON movements(at)",
        )

        private val SEED = listOf(
            "Maquillaje" to listOf(
                "Rubores", "Labiales", "Bases", "Máscaras de pestañas", "Sombras",
                "Correctores", "Polvos", "Delineadores", "Brochas y esponjas",
            ),
            "Cuidado personal" to listOf(
                "Cremas", "Limpiadores", "Sérums y tratamientos", "Protector solar",
                "Champús y acondicionadores", "Cuidado corporal",
            ),
            "Perfumes" to listOf("Perfumes", "Splash y body mist"),
            "Ropa" to listOf("Blusas y camisetas", "Pantalones", "Vestidos", "Abrigos", "Calzado"),
            "Accesorios" to listOf("Bolsos", "Joyería", "Gafas", "Accesorios para el cabello"),
            "Otros" to emptyList(),
        )
    }

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        SCHEMA.forEach(db::execSQL)
        val ins = db.compileStatement("INSERT INTO categories(name, parent_id, position) VALUES(?, ?, ?)")
        SEED.forEachIndexed { i, (name, subs) ->
            ins.bindString(1, name); ins.bindNull(2); ins.bindLong(3, i.toLong())
            val parent = ins.executeInsert()
            subs.forEachIndexed { j, sub ->
                ins.bindString(1, sub); ins.bindLong(2, parent); ins.bindLong(3, j.toLong())
                ins.executeInsert()
            }
        }
        ins.close()
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
}

/**
 * Único punto de acceso a los datos. Las existencias solo cambian a través de
 * movimientos, cada uno dentro de una transacción; la base de datos además
 * rechaza cantidades negativas.
 */
class Inventory(ctx: Context) {
    private val db: SQLiteDatabase = Db.get(ctx).writableDatabase

    // ---- utilidades ----

    private inline fun <T> tx(block: () -> T): T {
        db.beginTransaction()
        try {
            val r = block()
            db.setTransactionSuccessful()
            return r
        } finally {
            db.endTransaction()
        }
    }

    private fun stmt(sql: String, args: Array<out Any?>): SQLiteStatement =
        db.compileStatement(sql).apply {
            args.forEachIndexed { i, a ->
                when (a) {
                    null -> bindNull(i + 1)
                    is Long -> bindLong(i + 1, a)
                    is Int -> bindLong(i + 1, a.toLong())
                    is String -> bindString(i + 1, a)
                    else -> throw IllegalArgumentException("Tipo no soportado: ${a::class}")
                }
            }
        }

    private fun long(sql: String, vararg args: Any?): Long = stmt(sql, args).use { it.simpleQueryForLong() }
    private fun exec(sql: String, vararg args: Any?): Int = stmt(sql, args).use { it.executeUpdateDelete() }
    private fun insert(sql: String, vararg args: Any?): Long = stmt(sql, args).use { it.executeInsert() }

    private fun <T> query(sql: String, args: Array<String> = emptyArray(), map: (Cursor) -> T): List<T> =
        db.rawQuery(sql, args).use { c ->
            val out = ArrayList<T>(c.count)
            while (c.moveToNext()) out.add(map(c))
            out
        }

    private fun Cursor.strOrNull(i: Int): String? = if (isNull(i)) null else getString(i)
    private fun Cursor.longOrNull(i: Int): Long? = if (isNull(i)) null else getLong(i)

    // ---- categorías ----

    fun categories(): List<Category> =
        query("SELECT id, name, parent_id FROM categories ORDER BY position, name COLLATE NOCASE") {
            Category(it.getLong(0), it.getString(1), it.longOrNull(2))
        }

    fun productCounts(): Map<Long, Int> =
        query("SELECT category_id, COUNT(*) FROM products GROUP BY category_id") { it.getLong(0) to it.getInt(1) }.toMap()

    private fun cleanCategoryName(name: String, parentId: Long?, exceptId: Long): String {
        val n = name.trim().replace(Regex("\\s+"), " ")
        if (n.isEmpty()) throw InventoryError("Escribe un nombre")
        if (n.length > 60) throw InventoryError("El nombre es demasiado largo")
        val dup = if (parentId == null) {
            long("SELECT COUNT(*) FROM categories WHERE parent_id IS NULL AND name = ? COLLATE NOCASE AND id <> ?", n, exceptId)
        } else {
            long("SELECT COUNT(*) FROM categories WHERE parent_id = ? AND name = ? COLLATE NOCASE AND id <> ?", parentId, n, exceptId)
        }
        if (dup > 0) throw InventoryError("Ya existe «$n»")
        return n
    }

    fun addCategory(name: String, parentId: Long?): Long = tx {
        val n = cleanCategoryName(name, parentId, 0)
        val pos = if (parentId == null) {
            long("SELECT COALESCE(MAX(position), -1) + 1 FROM categories WHERE parent_id IS NULL")
        } else {
            long("SELECT COALESCE(MAX(position), -1) + 1 FROM categories WHERE parent_id = ?", parentId)
        }
        insert("INSERT INTO categories(name, parent_id, position) VALUES(?, ?, ?)", n, parentId, pos)
    }

    fun renameCategory(id: Long, name: String) = tx {
        val parent = query("SELECT parent_id FROM categories WHERE id = ?", arrayOf(id.toString())) { it.longOrNull(0) }
            .also { if (it.isEmpty()) throw InventoryError("La categoría ya no existe") }.first()
        exec("UPDATE categories SET name = ? WHERE id = ?", cleanCategoryName(name, parent, id), id)
    }

    fun deleteCategory(id: Long) = tx {
        if (long("SELECT COUNT(*) FROM categories WHERE parent_id = ?", id) > 0) {
            throw InventoryError("Primero borra sus subcategorías")
        }
        if (long("SELECT COUNT(*) FROM products WHERE category_id = ?", id) > 0) {
            throw InventoryError("Tiene productos. Muévelos a otra categoría antes de borrarla")
        }
        exec("DELETE FROM categories WHERE id = ?", id)
    }

    // ---- productos ----

    private val productSql = """
        SELECT p.id, p.name, p.brand, p.category_id, c.name, c.parent_id, pc.name, p.notes, p.min_stock, p.photo,
               COALESCE(SUM(b.sealed), 0), COALESCE(SUM(b.opened), 0),
               COALESCE(SUM((b.sealed + b.opened) * b.unit_cost), 0),
               MIN(CASE WHEN b.sealed + b.opened > 0 THEN b.expiry_date END),
               (SELECT x.unit_cost FROM batches x WHERE x.product_id = p.id ORDER BY x.id DESC LIMIT 1)
        FROM products p
        JOIN categories c ON c.id = p.category_id
        LEFT JOIN categories pc ON pc.id = c.parent_id
        LEFT JOIN batches b ON b.product_id = p.id
    """

    private fun productOf(c: Cursor) = ProductRow(
        id = c.getLong(0), name = c.getString(1), brand = c.getString(2), categoryId = c.getLong(3),
        categoryName = c.getString(4), parentId = c.longOrNull(5), parentName = c.strOrNull(6),
        notes = c.getString(7), minStock = c.getInt(8), photo = c.strOrNull(9),
        sealed = c.getInt(10), opened = c.getInt(11), value = c.getLong(12),
        nextExpiry = c.strOrNull(13), lastCost = c.longOrNull(14),
    )

    fun products(): List<ProductRow> =
        query("$productSql GROUP BY p.id ORDER BY p.name COLLATE NOCASE", map = ::productOf)

    fun product(id: Long): ProductRow? =
        query("$productSql WHERE p.id = ? GROUP BY p.id", arrayOf(id.toString()), ::productOf).firstOrNull()

    private fun validate(p: ProductInput) {
        if (p.name.isBlank()) throw InventoryError("Escribe el nombre del producto")
        if (p.name.length > 120) throw InventoryError("El nombre es demasiado largo")
        if (p.minStock !in 0..100_000) throw InventoryError("Stock mínimo no válido")
        if (long("SELECT COUNT(*) FROM categories WHERE id = ?", p.categoryId) == 0L) {
            throw InventoryError("Elige una categoría")
        }
    }

    fun createProduct(p: ProductInput, initial: EntryInput?): Long = tx {
        validate(p)
        val id = insert(
            "INSERT INTO products(name, brand, category_id, notes, min_stock, photo, created_at) VALUES(?, ?, ?, ?, ?, ?, ?)",
            p.name, p.brand, p.categoryId, p.notes, p.minStock, p.photo, System.currentTimeMillis(),
        )
        if (initial != null) addEntryInTx(id, initial)
        id
    }

    fun updateProduct(id: Long, p: ProductInput) = tx {
        validate(p)
        exec(
            "UPDATE products SET name = ?, brand = ?, category_id = ?, notes = ?, min_stock = ?, photo = ? WHERE id = ?",
            p.name, p.brand, p.categoryId, p.notes, p.minStock, p.photo, id,
        )
    }

    fun deleteProduct(id: Long) {
        exec("DELETE FROM products WHERE id = ?", id)
    }

    /** Lotes con existencias, en el orden en que conviene gastarlos: primero el que vence antes. */
    fun batches(productId: Long): List<Batch> = query(
        """SELECT id, purchase_date, expiry_date, unit_cost, sealed, opened FROM batches
           WHERE product_id = ? AND sealed + opened > 0
           ORDER BY expiry_date IS NULL, expiry_date, purchase_date IS NULL, purchase_date, id""",
        arrayOf(productId.toString()),
    ) { Batch(it.getLong(0), it.strOrNull(1), it.strOrNull(2), it.getLong(3), it.getInt(4), it.getInt(5)) }

    // ---- movimientos ----

    fun addEntry(productId: Long, e: EntryInput) = tx { addEntryInTx(productId, e) }

    private fun addEntryInTx(productId: Long, e: EntryInput) {
        if (e.qty <= 0) throw InventoryError("La cantidad debe ser mayor que cero")
        if (e.qty > 100_000) throw InventoryError("Cantidad demasiado grande")
        if (e.unitCost < 0) throw InventoryError("Precio no válido")
        if (e.reason !in Reasons.entry.map { it.first }) throw InventoryError("Motivo no válido")
        val now = System.currentTimeMillis()
        val batch = insert(
            "INSERT INTO batches(product_id, purchase_date, expiry_date, unit_cost, sealed, opened, created_at) VALUES(?, ?, ?, ?, ?, 0, ?)",
            productId, e.purchaseDate, e.expiryDate, e.unitCost, e.qty, now,
        )
        insert(
            "INSERT INTO movements(product_id, batch_id, kind, reason, qty, unit_cost, at, note) VALUES(?, ?, ?, ?, ?, ?, ?, ?)",
            productId, batch, Reasons.IN, e.reason, e.qty, e.unitCost, now, e.note,
        )
    }

    /**
     * Saca [qty] unidades del inventario, de las nuevas o de las abiertas según [fromOpened].
     * Reparte la salida entre lotes empezando por el que vence antes y deja un movimiento por lote.
     */
    fun removeUnits(productId: Long, reason: String, qty: Int, fromOpened: Boolean, note: String) = tx {
        if (qty <= 0) throw InventoryError("La cantidad debe ser mayor que cero")
        if (reason !in Reasons.exit.map { it.first }) throw InventoryError("Motivo no válido")
        val list = batches(productId)
        val available = list.sumOf { if (fromOpened) it.opened else it.sealed }
        val pool = if (fromOpened) "abiertas" else "nuevas"
        if (qty > available) {
            throw InventoryError(if (available == 0) "No hay unidades $pool" else "Solo hay $available unidades $pool")
        }
        val col = if (fromOpened) "opened" else "sealed"
        val now = System.currentTimeMillis()
        var left = qty
        for (b in list) {
            if (left == 0) break
            val take = minOf(left, if (fromOpened) b.opened else b.sealed)
            if (take == 0) continue
            if (exec("UPDATE batches SET $col = $col - ? WHERE id = ? AND $col >= ?", take, b.id, take) != 1) {
                throw InventoryError("El inventario cambió; inténtalo de nuevo")
            }
            insert(
                "INSERT INTO movements(product_id, batch_id, kind, reason, qty, unit_cost, at, note) VALUES(?, ?, ?, ?, ?, ?, ?, ?)",
                productId, b.id, Reasons.OUT, reason, take, b.unitCost, now, note,
            )
            left -= take
        }
    }

    /** Marca unidades nuevas como abiertas / en uso. La cantidad disponible no cambia. */
    fun openUnits(productId: Long, qty: Int) = tx {
        if (qty <= 0) throw InventoryError("La cantidad debe ser mayor que cero")
        val list = batches(productId)
        if (qty > list.sumOf { it.sealed }) throw InventoryError("No hay suficientes unidades nuevas")
        val now = System.currentTimeMillis()
        var left = qty
        for (b in list) {
            if (left == 0) break
            val take = minOf(left, b.sealed)
            if (take == 0) continue
            if (exec("UPDATE batches SET sealed = sealed - ?, opened = opened + ? WHERE id = ? AND sealed >= ?", take, take, b.id, take) != 1) {
                throw InventoryError("El inventario cambió; inténtalo de nuevo")
            }
            insert(
                "INSERT INTO movements(product_id, batch_id, kind, reason, qty, unit_cost, at, note) VALUES(?, ?, ?, 'abierto', ?, ?, ?, '')",
                productId, b.id, Reasons.OPEN, take, b.unitCost, now,
            )
            left -= take
        }
    }

    fun movements(productId: Long? = null, kind: String? = null, limit: Int = 300): List<Movement> {
        val where = mutableListOf<String>()
        val args = mutableListOf<String>()
        if (productId != null) { where += "m.product_id = ?"; args += productId.toString() }
        if (kind != null) { where += "m.kind = ?"; args += kind }
        val sql = "SELECT m.id, m.product_id, p.name, p.brand, m.kind, m.reason, m.qty, m.unit_cost, m.at, m.note " +
            "FROM movements m JOIN products p ON p.id = m.product_id" +
            (if (where.isEmpty()) "" else " WHERE " + where.joinToString(" AND ")) +
            " ORDER BY m.at DESC, m.id DESC LIMIT $limit"
        return query(sql, args.toTypedArray()) {
            Movement(
                it.getLong(0), it.getLong(1), it.getString(2), it.getString(3), it.getString(4),
                it.getString(5), it.getInt(6), it.getLong(7), it.getLong(8), it.getString(9),
            )
        }
    }

    // ---- consultas del panel ----

    /** Lo gastado en compras con fecha de compra en el mes actual. */
    fun monthSpending(): Long {
        val first = LocalDate.now().withDayOfMonth(1)
        return long(
            """SELECT COALESCE(SUM(m.qty * m.unit_cost), 0) FROM movements m JOIN batches b ON b.id = m.batch_id
               WHERE m.kind = 'IN' AND m.reason = 'compra' AND b.purchase_date >= ? AND b.purchase_date < ?""",
            first.toString(), first.plusMonths(1).toString(),
        )
    }

    /** Lotes con existencias que vencen dentro de [days] días o ya vencieron. */
    fun expiring(days: Int): List<ExpiringBatch> = query(
        """SELECT p.id, p.name, p.brand, b.expiry_date, b.sealed + b.opened FROM batches b
           JOIN products p ON p.id = b.product_id
           WHERE b.expiry_date IS NOT NULL AND b.expiry_date <= ? AND b.sealed + b.opened > 0
           ORDER BY b.expiry_date""",
        arrayOf(LocalDate.now().plusDays(days.toLong()).toString()),
    ) { ExpiringBatch(it.getLong(0), it.getString(1), it.getString(2), it.getString(3), it.getInt(4)) }
}
