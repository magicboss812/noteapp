package dev.folio.core.testing

import dev.folio.core.common.FolioFs
import dev.folio.core.common.Outcome
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * [FolioFs] that simulates a crash or full disk: the next atomic write whose path ends with
 * [pathSuffix] throws after [failAfterBytes] bytes. Everything else goes to [delegate].
 */
class FaultyFolioFs(
    private val delegate: FolioFs,
    private val pathSuffix: String,
) : FolioFs by delegate {
    /** Bytes the next matching write may emit before failing; null = no failure armed. Arming resets [fired]. */
    var failAfterBytes: Long? = null
        set(value) {
            field = value
            if (value != null) fired = false
        }

    /** True once the armed failure fired. */
    var fired: Boolean = false
        private set

    // Delegation would send this straight to the delegate and skip the injected failure.
    override fun writeBytesAtomic(
        path: String,
        bytes: ByteArray,
    ): Outcome<Unit> = writeAtomic(path) { it.write(bytes) }

    override fun writeAtomic(
        path: String,
        write: (OutputStream) -> Unit,
    ): Outcome<Unit> {
        val limit = failAfterBytes
        if (limit == null || !path.endsWith(pathSuffix)) return delegate.writeAtomic(path, write)
        failAfterBytes = null
        return delegate.writeAtomic(path) { out -> write(FailingStream(out, limit) { fired = true }) }
    }

    private class FailingStream(
        out: OutputStream,
        private var remaining: Long,
        private val onFire: () -> Unit,
    ) : FilterOutputStream(out) {
        override fun write(b: Int) {
            consume(1)
            out.write(b)
        }

        override fun write(
            b: ByteArray,
            off: Int,
            len: Int,
        ) {
            val allowed = minOf(remaining, len.toLong()).toInt()
            if (allowed > 0) out.write(b, off, allowed)
            consume(len.toLong())
        }

        private fun consume(n: Long) {
            if (n > remaining) {
                remaining = 0
                onFire()
                throw IOException("injected failure")
            }
            remaining -= n
        }
    }
}
