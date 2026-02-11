package com.walkietalkie.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream

/**
 * Utility for capturing and encoding images for the server.
 */
object ImageCapture {

    /**
     * Load an image from a content URI and encode it as base64 JPEG.
     * Resizes to max 1024px on the longest side for reasonable transfer size.
     */
    fun encodeImageFromUri(context: Context, uri: Uri, maxSize: Int = 1024): String? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()

            val scaled = scaleBitmap(bitmap, maxSize)
            val outputStream = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
            if (scaled !== bitmap) scaled.recycle()
            bitmap.recycle()

            Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Encode a bitmap as base64 JPEG.
     */
    fun encodeBitmap(bitmap: Bitmap, maxSize: Int = 1024): String {
        val scaled = scaleBitmap(bitmap, maxSize)
        val outputStream = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
        if (scaled !== bitmap) scaled.recycle()
        return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
    }

    private fun scaleBitmap(bitmap: Bitmap, maxSize: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= maxSize && height <= maxSize) return bitmap

        val ratio = maxSize.toFloat() / maxOf(width, height)
        val newWidth = (width * ratio).toInt()
        val newHeight = (height * ratio).toInt()
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }
}
