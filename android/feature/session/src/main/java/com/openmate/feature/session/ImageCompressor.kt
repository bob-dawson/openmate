package com.openmate.feature.session

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

object ImageCompressor {
    const val MAX_EDGE = 1568
    const val MAX_IMAGES = 4
    const val MAX_BYTES_PER_IMAGE = 500 * 1024
    const val MAX_TOTAL_BYTES = 1_800_000
    private const val MAX_INPUT_BYTES = 25 * 1024 * 1024

    class TooLargeException : Exception()
    class DecodeException : Exception()

    fun compress(bytes: ByteArray): ByteArray {
        if (bytes.size > MAX_INPUT_BYTES) throw TooLargeException()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw DecodeException()

        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_EDGE) {
            sample *= 2
        }
        val decoded = BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: throw DecodeException()

        val oriented = applyOrientation(decoded, readOrientation(bytes))
        if (oriented !== decoded) decoded.recycle()

        var bitmap = scaleTo(oriented, MAX_EDGE)
        if (bitmap !== oriented) oriented.recycle()

        var edge = MAX_EDGE
        var quality = 85
        var result = encode(bitmap, quality)
        while (result.size > MAX_BYTES_PER_IMAGE) {
            if (quality > 55) {
                quality -= 10
            } else if (edge > 720) {
                edge /= 2
                quality = 65
                val smaller = scaleTo(bitmap, edge)
                if (smaller !== bitmap) bitmap.recycle()
                bitmap = smaller
            } else {
                break
            }
            result = encode(bitmap, quality)
        }
        bitmap.recycle()
        return result
    }

    private fun readOrientation(bytes: ByteArray): Int = runCatching {
        ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    private fun applyOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return bitmap
        }
        return runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }.getOrDefault(bitmap)
    }

    private fun scaleTo(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxEdge) return bitmap
        val ratio = maxEdge.toFloat() / longest
        val width = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val height = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    private fun encode(bitmap: Bitmap, quality: Int): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }
}
