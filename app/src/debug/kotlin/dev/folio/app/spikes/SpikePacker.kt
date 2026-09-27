package dev.folio.app.spikes

import androidx.annotation.WorkerThread
import dev.folio.core.common.FolioLog
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.random.Random

/** Timings of one pack in ms, plus what the platform allowed. */
internal data class PackResult(
    val totalMs: Long,
    val crcMs: Long,
    val writeMs: Long,
    val fsyncMs: Long,
    val renameMs: Long,
    val bytes: Long,
    val dirFsync: Boolean,
)

/** Outcome of reading a packed file back completely (every CRC checked). */
internal data class VerifyResult(
    val ok: Boolean,
    val entries: Int,
    val mimetypeFirst: Boolean,
    val error: String?,
)

/**
 * Spike P01-S6: packs an unpacked working copy into a `.folio` ZIP with the write protocol of
 * 04-file-format.md#write-protocol (`<name>.folio.tmp` in the target folder, fsync, rename over
 * the target, fsync the folder where supported). Assets are STORED, everything else DEFLATED.
 * Plain java.io so it runs in JVM tests. Deleted or promoted (P02 packer) by P01-T08.
 */
internal object SpikePacker {
    const val MIMETYPE = "application/vnd.folio+zip"
    const val TMP_SUFFIX = ".tmp"
    private const val BUFFER_BYTES = 256 * 1024
    private const val NS_PER_MS = 1_000_000L

    /**
     * Writes a synthetic working copy into [dir]: [assetCount] random assets of [assetBytes] each
     * (incompressible, like photos) and [pageCount] compressible pages of [pageBytes] each.
     */
    @WorkerThread
    fun prepareWorkingCopy(
        dir: File,
        assetCount: Int,
        assetBytes: Int,
        pageCount: Int,
        pageBytes: Int,
        seed: Long = SEED,
    ) {
        dir.deleteRecursively()
        File(dir, "assets").mkdirs()
        File(dir, "pages").mkdirs()
        File(dir, "manifest.json").writeText("""{"formatVersion":1,"title":"Spike IO"}""")
        val random = Random(seed)
        val buffer = ByteArray(assetBytes)
        repeat(assetCount) { i ->
            random.nextBytes(buffer)
            File(dir, "assets/asset$i.bin").writeBytes(buffer)
        }
        repeat(pageCount) { i ->
            // Protobuf-like: repeated small varint-ish records compress about 4:1.
            val page = ByteArray(pageBytes) { b -> (b % PAGE_PERIOD + random.nextInt(PAGE_NOISE)).toByte() }
            File(dir, "pages/page$i.pb").writeBytes(page)
        }
    }

    /**
     * Packs [workDir] into [target]. [beforeEntry] runs before each entry is written (the crash
     * test uses it to slow the pack down). The target is replaced only by the final rename.
     */
    @WorkerThread
    fun pack(
        workDir: File,
        target: File,
        beforeEntry: (String) -> Unit = {},
    ): PackResult {
        val start = System.nanoTime()
        val entries = entriesInOrder(workDir)
        val crcs = HashMap<String, Long>()
        for ((name, file) in entries) if (isStored(name)) crcs[name] = crcOf(file)
        val afterCrc = System.nanoTime()
        val tmp = File(target.parentFile, target.name + TMP_SUFFIX)
        target.parentFile?.mkdirs()
        val stream = FileOutputStream(tmp)
        var afterWrite: Long
        stream.use { fileOut ->
            val zip = ZipOutputStream(BufferedOutputStream(fileOut, BUFFER_BYTES))
            writeStored(zip, "mimetype", MIMETYPE.toByteArray())
            for ((name, file) in entries) {
                beforeEntry(name)
                val crc = crcs[name]
                if (crc != null) {
                    val entry = ZipEntry(name)
                    entry.method = ZipEntry.STORED
                    entry.size = file.length()
                    entry.compressedSize = file.length()
                    entry.crc = crc
                    zip.putNextEntry(entry)
                } else {
                    zip.putNextEntry(ZipEntry(name))
                }
                file.inputStream().use { it.copyTo(zip, BUFFER_BYTES) }
                zip.closeEntry()
            }
            zip.finish()
            zip.flush()
            afterWrite = System.nanoTime()
            fileOut.fd.sync()
        }
        val afterSync = System.nanoTime()
        if (!tmp.renameTo(target)) throw IOException("rename ${tmp.name} -> ${target.name} failed")
        val dirFsync = fsyncDirectory(target.parentFile)
        val end = System.nanoTime()
        return PackResult(
            totalMs = (end - start) / NS_PER_MS,
            crcMs = (afterCrc - start) / NS_PER_MS,
            writeMs = (afterWrite - afterCrc) / NS_PER_MS,
            fsyncMs = (afterSync - afterWrite) / NS_PER_MS,
            renameMs = (end - afterSync) / NS_PER_MS,
            bytes = target.length(),
            dirFsync = dirFsync,
        )
    }

    /** Reads every entry of [file] to the end; ZipInputStream checks each CRC. */
    @WorkerThread
    fun verify(file: File): VerifyResult {
        if (!file.exists()) return VerifyResult(ok = false, entries = 0, mimetypeFirst = false, error = "missing")
        var count = 0
        var mimetypeFirst = false
        return try {
            ZipInputStream(file.inputStream().buffered()).use { zip ->
                val sink = ByteArray(BUFFER_BYTES)
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (count == 0) mimetypeFirst = entry.name == "mimetype"
                    while (zip.read(sink) >= 0) Unit
                    count++
                }
            }
            VerifyResult(ok = count > 0 && mimetypeFirst, entries = count, mimetypeFirst = mimetypeFirst, error = null)
        } catch (e: IOException) {
            VerifyResult(ok = false, entries = count, mimetypeFirst = mimetypeFirst, error = e.message)
        }
    }

    /** Deletes leftover `*.folio.tmp` files in [dir] (04-file-format.md#crash-recovery); returns their names. */
    @WorkerThread
    fun removeStaleTmp(dir: File): List<String> =
        dir
            .listFiles { f -> f.name.endsWith(".folio$TMP_SUFFIX") }
            .orEmpty()
            .filter { it.delete() }
            .map { it.name }

    /** Entries in container order: manifest, pages, then assets (04-file-format.md#container-layout). */
    private fun entriesInOrder(workDir: File): List<Pair<String, File>> {
        val manifest = listOf("manifest.json" to File(workDir, "manifest.json"))
        val pages =
            File(workDir, "pages")
                .listFiles()
                .orEmpty()
                .sortedBy { it.name }
                .map { "pages/${it.name}" to it }
        val assets =
            File(workDir, "assets")
                .listFiles()
                .orEmpty()
                .sortedBy { it.name }
                .map { "assets/${it.name}" to it }
        return manifest + pages + assets
    }

    private fun isStored(name: String): Boolean = name.startsWith("assets/")

    private fun writeStored(
        zip: ZipOutputStream,
        name: String,
        bytes: ByteArray,
    ) {
        val entry = ZipEntry(name)
        entry.method = ZipEntry.STORED
        entry.size = bytes.size.toLong()
        entry.compressedSize = bytes.size.toLong()
        entry.crc = CRC32().apply { update(bytes) }.value
        zip.putNextEntry(entry)
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun crcOf(file: File): Long {
        val crc = CRC32()
        val buffer = ByteArray(BUFFER_BYTES)
        file.inputStream().use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                crc.update(buffer, 0, read)
            }
        }
        return crc.value
    }

    /** Forces the directory entry to disk; false where the file system refuses (reported by the spike). */
    private fun fsyncDirectory(dir: File?): Boolean =
        try {
            FileChannel.open(checkNotNull(dir).toPath(), StandardOpenOption.READ).use { it.force(true) }
            true
        } catch (e: IOException) {
            FolioLog.w(TAG, "directory fsync not supported for $dir", e)
            false
        }

    private const val TAG = "SpikePacker"
    const val SEED = 20260927L
    private const val PAGE_PERIOD = 16
    private const val PAGE_NOISE = 4
}
