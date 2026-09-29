package dev.folio.core.storage.work

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.folio.core.common.Outcome
import dev.folio.core.format.container.DocumentEntries
import dev.folio.core.storage.repo.FileNames
import dev.folio.core.storage.work.WorkFixture.orThrow
import dev.folio.core.testing.FakeClock
import dev.folio.core.testing.FaultyFolioFs
import dev.folio.core.testing.ModelFixtures
import dev.folio.core.testing.TempDirFolioFs
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.ZoneOffset
import kotlin.random.Random

class PackerTest {
    @get:Rule val fs = TempDirFolioFs()

    private val clock = FakeClock()
    private val source = "School/Physics.folio"

    @Test
    fun pack_injectedFailureAtRandomOffsets_neverCorruptsTarget() {
        val library = FaultyFolioFs(fs.sub("library"), ".folio")
        val app = fs.sub("app")
        val doc = WorkFixture.document()
        WorkFixture.writeFolio(library, source, doc)
        val targetFile = File(fs.root, "library/$source")
        val store = WorkingCopyStore(app, library)
        val packer = Packer(library, app, clock, ZoneOffset.UTC)
        val random = Random(SEED)
        repeat(100) { run ->
            val before = targetFile.readBytes()
            val copy = store.open(source).orThrow()
            val page = ModelFixtures.randomPage(random, 1 + random.nextInt(8)).copy(id = doc.pages[run % 3].id)
            copy.writeEntries(mapOf(DocumentEntries.encodePage(page))).orThrow()
            library.failAfterBytes = random.nextLong(0, before.size + 4096L)
            val failed = packer.pack(copy)
            if (library.fired) {
                assertWithMessage("seed=$SEED run=$run").that(failed).isInstanceOf(Outcome.Failure::class.java)
                assertWithMessage("seed=$SEED run=$run target unchanged").that(targetFile.readBytes()).isEqualTo(before)
                WorkFixture.readFolio(targetFile)
                assertThat(copy.isDirty).isTrue()
            }
            library.failAfterBytes = null
            packer.pack(copy).orThrow()
            val after = WorkFixture.readFolio(targetFile)
            assertWithMessage("seed=$SEED run=$run")
                .that(
                    after.pageBodies
                        .getValue(page.id)
                        .objects.size,
                ).isEqualTo(page.objects.size)
            assertThat(copy.isDirty).isFalse()
        }
        assertThat(File(fs.root, "library/School").list()!!.toList()).containsExactly("Physics.folio")
    }

    @Test
    fun pack_firstPack_keepsBackupOfOpenedVersion() {
        val library = fs.sub("library")
        val app = fs.sub("app")
        val doc = WorkFixture.document()
        WorkFixture.writeFolio(library, source, doc)
        val original = File(fs.root, "library/$source").readBytes()
        val copy = WorkingCopyStore(app, library).open(source).orThrow()
        copy.writeEntries(mapOf("flows/x.md" to "x".toByteArray())).orThrow()
        assertThat(Packer(library, app, clock).pack(copy).orThrow()).isEqualTo(PackResult.Packed(source))
        assertThat(File(fs.root, "app/backup/${doc.meta.id.value}.folio").readBytes()).isEqualTo(original)
        assertThat(copy.base.backupDone).isTrue()
    }

    @Test
    fun pack_clean_doesNothing() {
        val library = fs.sub("library")
        val app = fs.sub("app")
        WorkFixture.writeFolio(library, source, WorkFixture.document())
        val copy = WorkingCopyStore(app, library).open(source).orThrow()
        assertThat(Packer(library, app, clock).pack(copy).orThrow()).isEqualTo(PackResult.Clean)
    }

    @Test
    fun stemOf_replacesReservedCharactersAndLimitsBytes() {
        assertThat(FileNames.stemOf("Physics: Week 1/2 <draft>?")).isEqualTo("Physics- Week 1-2 -draft--")
        assertThat(FileNames.stemOf(".hidden")).isEqualTo("hidden")
        assertThat(FileNames.stemOf("   ")).isEqualTo("Untitled")
        val emoji = FileNames.stemOf("📚".repeat(100)) // 4 UTF-8 bytes each
        assertThat(emoji.encodeToByteArray().size).isAtMost(200)
        assertThat(emoji.length % 2).isEqualTo(0) // no split surrogate pair
        assertThat(FileNames.stemOf("x".repeat(300))).hasLength(120)
    }

    private companion object {
        const val SEED = 5150L
    }
}
