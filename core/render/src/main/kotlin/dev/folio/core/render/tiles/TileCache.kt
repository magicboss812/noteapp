package dev.folio.core.render.tiles

import androidx.annotation.MainThread
import dev.folio.core.model.geometry.RectPt

/**
 * Tiles of one layer (05-canvas-rendering.md#tiles), LRU by bytes. An entry is pinned while it was
 * drawn in the latest frame ([beginFrame] + [markUsed]) or put since then; pinned entries are never
 * evicted, so the budget is soft while more than it is on screen. Invalidation marks entries stale;
 * stale entries keep drawing until their replacement arrives. Main thread only.
 */
@MainThread
@Suppress("TooManyFunctions") // cache API: frame pinning, lookup, put, invalidation, page and memory trims
class TileCache<T : Any>(
    /** Byte budget for all entries. */
    val budgetBytes: Long,
    private val release: (T) -> Unit,
    private val onEvicted: (count: Int, bytes: Long) -> Unit = { _, _ -> },
) {
    /** One cached tile; [payload] null = nothing to draw here (known empty). */
    class Entry<T : Any> internal constructor(
        /** Address. */
        val key: TileKey,
        /** The rendered tile, or null for an empty tile. */
        val payload: T?,
        /** Memory held by [payload]. */
        val bytes: Long,
    ) {
        /** Page-space rect covered. */
        val rectPt: RectPt = TileGrid.tileRectPt(key.bucket, key.tx, key.ty)

        /** True after an invalidation until a fresh render replaces the entry. */
        var stale: Boolean = false
            internal set

        internal var lastUsedFrame: Long = 0
    }

    private val byKey = HashMap<TileKey, Entry<T>>()
    private val byPage = HashMap<String, ArrayList<Entry<T>>>()
    private var frame = 1L

    /** Bytes held by all entries. */
    var bytes: Long = 0
        private set

    /** Number of entries (empty tiles included). */
    val size: Int get() = byKey.size

    /** Entries evicted by the budget since creation. */
    var evictions: Int = 0
        private set

    /** Starts a frame: entries not marked during it lose their pin once the next frame starts. */
    fun beginFrame() {
        frame++
    }

    /** Pins [entry] for the current frame and refreshes its LRU position. */
    fun markUsed(entry: Entry<T>) {
        entry.lastUsedFrame = frame
    }

    /** Entries of page [page] (any bucket), for the per-frame draw loop; do not modify. */
    fun entries(page: String): List<Entry<T>> = byPage[page] ?: emptyList()

    /** Entry at [key], or null. */
    operator fun get(key: TileKey): Entry<T>? = byKey[key]

    /** Stores [payload] (null = empty tile) at [key], releasing a replaced payload, then enforces the budget. */
    fun put(
        key: TileKey,
        payload: T?,
        bytes: Long,
    ): Entry<T> {
        val entry = Entry(key, payload, bytes).also { it.lastUsedFrame = frame }
        val old = byKey.put(key, entry)
        val list = byPage.getOrPut(key.page) { ArrayList() }
        if (old != null) {
            list[list.indexOf(old)] = entry
            drop(old)
        } else {
            list += entry
        }
        this.bytes += bytes
        trim()
        return entry
    }

    /** Marks stale every entry of [page] (all buckets) whose rect overlaps [rectPt] padded by one bucket pixel. */
    fun invalidate(
        page: String,
        rectPt: RectPt,
    ): Int {
        if (rectPt.isEmpty) return 0
        var marked = 0
        for (entry in entries(page)) {
            val padPt = TileGrid.tileSizePt(entry.key.bucket) / TileGrid.TILE_PX
            val r = entry.rectPt
            val hit =
                rectPt.left - padPt < r.right && r.left < rectPt.right + padPt &&
                    rectPt.top - padPt < r.bottom && r.top < rectPt.bottom + padPt
            if (hit && !entry.stale) {
                entry.stale = true
                marked++
            }
        }
        return marked
    }

    /** Marks every entry of [page] stale (page spec or background changed). */
    fun invalidatePage(page: String) {
        for (entry in entries(page)) entry.stale = true
    }

    /** Drops every entry of pages not in [keep]. */
    fun retainPages(keep: Set<String>) {
        val gone = byPage.keys.filter { it !in keep }
        for (page in gone) removePage(page)
    }

    /** Drops all entries of [page]. */
    fun removePage(page: String) {
        val list = byPage.remove(page) ?: return
        for (entry in list) {
            byKey.remove(entry.key)
            drop(entry)
        }
    }

    /** Drops every entry that is not pinned (memory pressure). Returns the number dropped. */
    fun trimUnpinned(): Int = evict(Long.MAX_VALUE)

    /** Drops everything. */
    fun clear() {
        for (page in byPage.keys.toList()) removePage(page)
    }

    private fun trim() {
        if (bytes <= budgetBytes) return
        evict(bytes - budgetBytes)
    }

    /** Evicts unpinned entries, least recently used first (stale first among equals), until [needBytes] are freed. */
    private fun evict(needBytes: Long): Int {
        val pinnedFrame = frame
        val candidates =
            byKey.values
                .filter { it.lastUsedFrame < pinnedFrame }
                .sortedWith(compareBy<Entry<T>> { it.lastUsedFrame }.thenByDescending { it.stale })
        var freed = 0L
        var count = 0
        for (entry in candidates) {
            if (freed >= needBytes) break
            byKey.remove(entry.key)
            byPage[entry.key.page]?.let { list ->
                list.remove(entry)
                if (list.isEmpty()) byPage.remove(entry.key.page)
            }
            freed += entry.bytes
            count++
            drop(entry)
        }
        if (count > 0) {
            evictions += count
            onEvicted(count, freed)
        }
        return count
    }

    private fun drop(entry: Entry<T>) {
        bytes -= entry.bytes
        entry.payload?.let(release)
    }
}
