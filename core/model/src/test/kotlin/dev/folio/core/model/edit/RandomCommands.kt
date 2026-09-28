package dev.folio.core.model.edit

import dev.folio.core.model.AssetId
import dev.folio.core.model.AssetInfo
import dev.folio.core.model.Document
import dev.folio.core.model.FlowId
import dev.folio.core.model.Orientation
import dev.folio.core.model.PageSpec
import dev.folio.core.model.PaperSize
import dev.folio.core.model.geometry.Affine
import dev.folio.core.testing.ModelFixtures
import kotlinx.collections.immutable.toPersistentSet
import kotlin.random.Random

/** Generates a random valid command for a document whose pages are all loaded. */
internal class RandomCommands(
    private val random: Random,
) {
    fun next(doc: Document): EditCommand {
        if (random.nextInt(BATCH_ONE_IN) == 0) return batch(doc)
        return single(doc)
    }

    private fun batch(doc: Document): EditCommand {
        var current = doc
        val commands =
            List(2 + random.nextInt(2)) {
                single(current).also { current = it.execute(current).doc }
            }
        return Batch(commands)
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod") // One branch per command type keeps the generator readable.
    private fun single(doc: Document): EditCommand {
        val ref = doc.pages[random.nextInt(doc.pages.size)]
        val page = doc.pageBodies.getValue(ref.id)
        val objects = page.objects
        return when (random.nextInt(COMMAND_KINDS)) {
            0 -> {
                AddObjects(ref.id, List(1 + random.nextInt(3)) { ModelFixtures.randomObject(random) }, randomIndexOrNull(objects.size))
            }

            1 -> {
                if (objects.isEmpty()) single(doc) else RemoveObjects(ref.id, subset(objects.map { it.id }))
            }

            2 -> {
                ReplaceObjects(
                    ref.id,
                    if (objects.isEmpty()) emptyList() else subset(objects.map { it.id }),
                    List(random.nextInt(3)) { ModelFixtures.randomObject(random) },
                )
            }

            3 -> {
                if (objects.isEmpty()) single(doc) else TransformObjects(ref.id, subset(objects.map { it.id }), randomAffine())
            }

            4 -> {
                if (objects.isEmpty()) single(doc) else RecolorObjects(ref.id, subset(objects.map { it.id }), random.nextInt())
            }

            5 -> {
                if (objects.isEmpty()) {
                    single(doc)
                } else {
                    ReorderObjects(ref.id, subset(objects.map { it.id }), ReorderOp.entries[random.nextInt(ReorderOp.entries.size)])
                }
            }

            6 -> {
                editFlow(doc)
            }

            7 -> {
                InsertPages(random.nextInt(doc.pages.size + 1), listOf(ModelFixtures.randomPage(random, random.nextInt(4))))
            }

            8 -> {
                if (doc.pages.size < 2) single(doc) else RemovePages(listOf(ref.id))
            }

            9 -> {
                MovePages(subset(doc.pages.map { it.id }), random.nextInt(doc.pages.size))
            }

            10 -> {
                UpdatePageSpec(ref.id, randomSpec())
            }

            11 -> {
                UpdateBackground(ref.id, page.background.copy(paperArgb = random.nextInt()))
            }

            12 -> {
                UpdateMeta(
                    doc.meta.copy(
                        title = "T${random.nextInt(1000)}",
                        favorite = random.nextBoolean(),
                        tags = List(random.nextInt(3)) { "tag${random.nextInt(5)}" }.toPersistentSet(),
                    ),
                )
            }

            13 -> {
                updateFlows(doc)
            }

            else -> {
                updateAssets(doc)
            }
        }
    }

    private fun editFlow(doc: Document): EditCommand {
        if (doc.flows.isEmpty()) return UpdateFlows(listOf(ModelFixtures.flow(ModelFixtures.randomId(random), "hello")))
        val flow = doc.flows.values.elementAt(random.nextInt(doc.flows.size))
        var length = flow.markdown.length
        val edits =
            List(1 + random.nextInt(3)) {
                val start = random.nextInt(length + 1)
                val end = start + random.nextInt(length - start + 1)
                val text = "x".repeat(random.nextInt(4)) + if (random.nextBoolean()) "\n" else ""
                length += text.length - (end - start)
                TextEdit(start, end, text)
            }
        return EditFlow(flow.id, edits)
    }

    private fun updateFlows(doc: Document): EditCommand {
        val existing = doc.flows.keys.toList()
        val put = mutableListOf(ModelFixtures.flow(ModelFixtures.randomId(random), "# new"))
        if (existing.isNotEmpty() && random.nextBoolean()) {
            val f = doc.flows.getValue(existing[random.nextInt(existing.size)])
            put += f.copy(style = f.style.copy(sizeRatio = random.nextFloat()))
        }
        val putIds = put.map { it.id }.toSet()
        val remove: List<FlowId> = existing.filter { it !in putIds && random.nextInt(4) == 0 }
        return UpdateFlows(put, remove)
    }

    private fun updateAssets(doc: Document): EditCommand {
        val put = listOf(AssetInfo(AssetId(ModelFixtures.hex64(random)), "image/png", random.nextLong(1, 1_000_000), null))
        val remove = doc.assets.keys.filter { random.nextInt(3) == 0 }
        return UpdateAssets(put, remove)
    }

    private fun <T> subset(items: List<T>): List<T> {
        val picked = items.filter { random.nextInt(3) == 0 }
        return picked.ifEmpty { listOf(items[random.nextInt(items.size)]) }
    }

    private fun randomIndexOrNull(size: Int): Int? = if (random.nextBoolean()) null else random.nextInt(size + 1)

    private fun randomAffine(): Affine =
        Affine
            .translate(random.nextFloat() * 100f - 50f, random.nextFloat() * 100f - 50f)
            .compose(Affine.rotate(random.nextFloat() * 90f - 45f, 200f, 300f))
            .compose(Affine.scale(0.5f + random.nextFloat(), 0.5f + random.nextFloat(), 200f, 300f))

    private fun randomSpec(): PageSpec {
        val fixed = PageSpec.Fixed(PaperSize.entries[random.nextInt(3)], Orientation.entries[random.nextInt(2)])
        return when (random.nextInt(3)) {
            0 -> fixed
            1 -> PageSpec.Custom(100f + random.nextFloat() * 500f, 100f + random.nextFloat() * 800f)
            else -> PageSpec.Infinite(fixed)
        }
    }

    private companion object {
        const val COMMAND_KINDS = 15
        const val BATCH_ONE_IN = 10
    }
}
