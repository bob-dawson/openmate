package com.openmate.feature.session

import android.content.Context
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class DraftImageStorageTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val storage = DraftImageStorage(context)
    private val sessionId = "ses_draft_test"

    private fun image(id: String, bytes: ByteArray = byteArrayOf(1, 2, 3)) =
        PendingImage(id = id, name = "$id.jpg", mime = "image/jpeg", bytes = bytes)

    private fun fileOf(id: String) = File(context.filesDir, "draft_images/$sessionId/$id.jpg")

    @Test
    fun saveAndLoad_roundTripsImages() {
        val images = listOf(image("a"), image("b", byteArrayOf(9, 8, 7)))
        storage.saveImages(sessionId, images)
        storage.writeMeta(sessionId, images)

        val loaded = storage.load(sessionId)

        assertThat(loaded.map { it.id }).containsExactly("a", "b").inOrder()
        assertThat(loaded[1].bytes).isEqualTo(byteArrayOf(9, 8, 7))
        assertThat(loaded[1].name).isEqualTo("b.jpg")
        assertThat(fileOf("a").exists()).isTrue()
    }

    @Test
    fun deleteImage_removesFileAndMeta() {
        val images = listOf(image("a"), image("b"))
        storage.saveImages(sessionId, images)
        storage.writeMeta(sessionId, images)

        storage.deleteImage(sessionId, "a")
        storage.writeMeta(sessionId, images.filterNot { it.id == "a" })

        assertThat(storage.load(sessionId).map { it.id }).containsExactly("b")
        assertThat(fileOf("a").exists()).isFalse()
        assertThat(fileOf("b").exists()).isTrue()
    }

    @Test
    fun load_skipsEntriesWhoseFileIsGone() {
        storage.writeMeta(sessionId, listOf(image("missing")))

        assertThat(storage.load(sessionId)).isEmpty()
    }

    @Test
    fun clear_removesFilesAndMeta() {
        val images = listOf(image("a"))
        storage.saveImages(sessionId, images)
        storage.writeMeta(sessionId, images)

        storage.clear(sessionId)

        assertThat(storage.load(sessionId)).isEmpty()
        assertThat(fileOf("a").exists()).isFalse()
    }

    @Test
    fun load_returnsEmptyForUnknownSession() {
        assertThat(storage.load("ses_never_saved")).isEmpty()
    }
}
