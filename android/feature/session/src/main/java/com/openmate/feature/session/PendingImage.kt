package com.openmate.feature.session

import android.graphics.Bitmap

class PendingImage(
    val id: String,
    val name: String,
    val mime: String,
    val bytes: ByteArray,
)

data class MessageImageViewer(
    val messageId: String,
    val index: Int,
    val loading: Boolean = true,
    val image: Bitmap? = null,
    val error: String? = null,
)
