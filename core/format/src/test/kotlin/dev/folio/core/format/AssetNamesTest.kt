package dev.folio.core.format

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AssetNamesTest {
    @Test
    fun sha256Hex_knownVector_isLowercaseHex() {
        val hex = AssetNames.sha256Hex("abc".encodeToByteArray())

        assertThat(hex).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
        assertThat(FolioIds.isAssetId(hex)).isTrue()
    }

    @Test
    fun info_equalBytes_sameAssetAndEntry() {
        val a = AssetNames.info(byteArrayOf(1, 2, 3), "image/jpeg", "a.jpg")
        val b = AssetNames.info(byteArrayOf(1, 2, 3), "image/jpeg", "copy.jpeg")
        val other = AssetNames.info(byteArrayOf(1, 2, 4), "image/jpeg", "a.jpg")

        assertThat(b.id).isEqualTo(a.id)
        assertThat(AssetNames.entry(b)).isEqualTo(AssetNames.entry(a))
        assertThat(other.id).isNotEqualTo(a.id)
        assertThat(AssetNames.entry(a)).isEqualTo("assets/${a.id.value}.jpg")
        assertThat(a.bytes).isEqualTo(3L)
    }
}
