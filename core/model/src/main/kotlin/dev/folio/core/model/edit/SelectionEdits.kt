package dev.folio.core.model.edit

import dev.folio.core.model.Attachment
import dev.folio.core.model.FlowFrame
import dev.folio.core.model.ImageObject
import dev.folio.core.model.InkStroke
import dev.folio.core.model.ObjectId
import dev.folio.core.model.PageId
import dev.folio.core.model.PageObject
import dev.folio.core.model.Shape
import dev.folio.core.model.StickyNote
import dev.folio.core.model.geometry.Affine
import dev.folio.core.model.geometry.RectPt

/** An operation on a lasso selection (06-ink-input.md#lasso); each is one undo step. */
sealed interface SelectionEdit {
    /** Move, scale or rotate the selection by [affine]. */
    data class Transform(
        val affine: Affine,
    ) : SelectionEdit

    /** Remove the selection (Delete, Cut). */
    data object Delete : SelectionEdit

    /** Give the strokes and shapes of the selection the color [argb]. */
    data class Recolor(
        val argb: Int,
    ) : SelectionEdit

    /** Change the z-order of the selection. */
    data class Reorder(
        val op: ReorderOp,
    ) : SelectionEdit

    /** Put [objects] on top of the page (Duplicate, Paste); they become the selection. */
    data class Add(
        val objects: List<PageObject>,
    ) : SelectionEdit
}

/** The command of this edit on objects [ids] of page [pageId], for objects without text of their own. */
fun SelectionEdit.command(
    pageId: PageId,
    ids: List<ObjectId>,
): EditCommand =
    when (this) {
        is SelectionEdit.Transform -> TransformObjects(pageId, ids, affine)
        SelectionEdit.Delete -> RemoveObjects(pageId, ids)
        is SelectionEdit.Recolor -> RecolorObjects(pageId, ids, argb)
        is SelectionEdit.Reorder -> ReorderObjects(pageId, ids, op)
        is SelectionEdit.Add -> AddObjects(pageId, objects)
    }

/** True for objects that show a text flow of their own (text boxes, sticky notes): copies would need a new flow. */
val PageObject.ownsFlow: Boolean get() = this is FlowFrame || this is StickyNote

/** Selection copies (Duplicate, Copy and Paste). */
object ObjectCopies {
    /** How far Duplicate puts the copies right of and below the originals. */
    const val DUPLICATE_OFFSET_PT = 16f

    /**
     * Copies of [objects] mapped by [affine] with ids from [newId]; objects that [ownsFlow] are left out (P07-T03,
     * A-066). The order is kept.
     */
    fun of(
        objects: List<PageObject>,
        affine: Affine = Affine.IDENTITY,
        newId: () -> ObjectId = ObjectId::random,
    ): List<PageObject> =
        objects.filter { !it.ownsFlow }.map { o ->
            val moved = if (affine == Affine.IDENTITY) o else o.transformed(affine)
            moved.withId(newId())
        }

    /** Union of the bounds of [objects]; empty for none. */
    fun bounds(objects: List<PageObject>): RectPt = objects.fold(RectPt.EMPTY) { acc, o -> acc.union(o.bounds) }
}

/** This object under id [id]. */
fun PageObject.withId(id: ObjectId): PageObject =
    when (this) {
        is InkStroke -> copy(id = id)
        is Shape -> copy(id = id)
        is FlowFrame -> copy(id = id)
        is ImageObject -> copy(id = id)
        is StickyNote -> copy(id = id)
        is Attachment -> copy(id = id)
    }
