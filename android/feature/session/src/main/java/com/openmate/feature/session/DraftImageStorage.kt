package com.openmate.feature.session

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
private data class DraftImageMeta(
    val id: String,
    val name: String,
    val mime: String,
)

class DraftImageStorage(private val context: Context) {
    private val prefs = context.getSharedPreferences("openmate_settings", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun saveImages(sessionId: String, images: List<PendingImage>) {
        if (images.isEmpty()) return
        val dir = sessionDir(sessionId)
        if (!dir.exists() && !dir.mkdirs()) return
        images.forEach { image ->
            runCatching { File(dir, "${image.id}.jpg").writeBytes(image.bytes) }
        }
    }

    fun writeMeta(sessionId: String, images: List<PendingImage>) {
        val raw = json.encodeToString(
            images.map { DraftImageMeta(it.id, it.name, it.mime) },
        )
        prefs.edit().putString(metaKey(sessionId), raw).apply()
    }

    fun load(sessionId: String): List<PendingImage> {
        val raw = prefs.getString(metaKey(sessionId), null) ?: return emptyList()
        val metas = runCatching {
            json.decodeFromString<List<DraftImageMeta>>(raw)
        }.getOrDefault(emptyList())
        val dir = sessionDir(sessionId)
        return metas.mapNotNull { meta ->
            val file = File(dir, "${meta.id}.jpg")
            if (!file.exists()) return@mapNotNull null
            runCatching {
                PendingImage(meta.id, meta.name, meta.mime, file.readBytes())
            }.getOrNull()
        }
    }

    fun deleteImage(sessionId: String, id: String) {
        runCatching { File(sessionDir(sessionId), "$id.jpg").delete() }
    }

    fun clear(sessionId: String) {
        runCatching { sessionDir(sessionId).deleteRecursively() }
        prefs.edit().remove(metaKey(sessionId)).apply()
    }

    private fun sessionDir(sessionId: String): File = File(File(context.filesDir, DIR_NAME), sessionId)

    private fun metaKey(sessionId: String): String = "$KEY_PREFIX$sessionId"

    companion object {
        private const val DIR_NAME = "draft_images"
        private const val KEY_PREFIX = "draft_images_"
    }
}
