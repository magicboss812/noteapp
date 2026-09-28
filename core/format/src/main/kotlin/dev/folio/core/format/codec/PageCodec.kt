package dev.folio.core.format.codec

import dev.folio.core.common.Outcome
import dev.folio.core.format.FormatError
import dev.folio.core.model.Page
import dev.folio.core.model.PageId
import dev.folio.core.model.PageSpec
import kotlinx.collections.immutable.toPersistentList
import java.io.IOException
import dev.folio.core.format.proto.v1.Page as PbPage

/** Page body <-> `pages/<pageId>.pb` (folio.v1.Page). */
object PageCodec {
    /** Protobuf bytes of [page] (before DEFLATE). */
    fun encode(page: Page): ByteArray = PbPage.ADAPTER.encode(toProto(page))

    /**
     * Decodes untrusted bytes. Failures carry [FormatError.Corrupt] for [entry]. Objects of kinds this
     * version does not know (newer writer) are skipped.
     */
    fun decode(
        bytes: ByteArray,
        entry: String,
    ): Outcome<Page> =
        try {
            Outcome.Success(fromProto(PbPage.ADAPTER.decode(bytes)))
        } catch (e: IOException) {
            corrupt(entry, "unreadable protobuf", e)
        } catch (e: CorruptDataException) {
            corrupt(entry, e.message.orEmpty(), e)
        } catch (e: IllegalArgumentException) {
            // Wire's generated constructors and model init blocks validate with require().
            corrupt(entry, e.message.orEmpty(), e)
        }

    private fun corrupt(
        entry: String,
        detail: String,
        cause: Throwable,
    ): Outcome.Failure = Outcome.Failure("cannot decode $entry: $detail", FormatError.Corrupt(entry, detail, cause))

    internal fun toProto(page: Page): PbPage =
        PbPage(
            id = page.id.value,
            spec = LayoutCodec.specToProto(page.spec),
            background = LayoutCodec.backgroundToProto(page.background),
            objects = page.objects.map(ObjectCodec::toProto),
            content_bounds = if (page.spec is PageSpec.Infinite) page.contentBounds()?.let(::rectToProto) else null,
        )

    internal fun fromProto(pb: PbPage): Page {
        corruptIf(pb.id.isBlank()) { "page id missing" }
        val spec = LayoutCodec.specFromProto(pb.spec.orCorrupt("page spec"))
        val background = LayoutCodec.backgroundFromProto(pb.background.orCorrupt("background"))
        val objects = pb.objects.mapNotNull(ObjectCodec::fromProto)
        corruptIf(objects.distinctBy { it.id }.size != objects.size) { "duplicate object ids" }
        return Page(PageId(pb.id), spec, background, objects.toPersistentList())
    }
}
