package dev.folio.core.model

import java.util.UUID

/** Document id: UUIDv4, lowercase, no braces. */
@JvmInline
value class DocId(
    val value: String,
) {
    /** Factory for fresh ids. */
    companion object {
        /** New random UUIDv4 id. */
        fun random(): DocId = DocId(newUuid())
    }
}

/** Page id: UUIDv4, lowercase, no braces. */
@JvmInline
value class PageId(
    val value: String,
) {
    /** Factory for fresh ids. */
    companion object {
        /** New random UUIDv4 id. */
        fun random(): PageId = PageId(newUuid())
    }
}

/** Page object id: UUIDv4, lowercase, no braces. */
@JvmInline
value class ObjectId(
    val value: String,
) {
    /** Factory for fresh ids. */
    companion object {
        /** New random UUIDv4 id. */
        fun random(): ObjectId = ObjectId(newUuid())
    }
}

/** Text flow id: UUIDv4, lowercase, no braces. */
@JvmInline
value class FlowId(
    val value: String,
) {
    /** Factory for fresh ids. */
    companion object {
        /** New random UUIDv4 id. */
        fun random(): FlowId = FlowId(newUuid())
    }
}

/** Asset id: lowercase hex SHA-256 of the asset bytes (not random). */
@JvmInline
value class AssetId(
    val value: String,
)

private fun newUuid(): String = UUID.randomUUID().toString()
