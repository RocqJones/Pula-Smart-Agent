package com.jonesmb.pulasmartagent.platform.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import java.io.File
import java.io.FileOutputStream

actual fun compressPhoto(
    sourcePath: String,
    destPath: String,
    quality: Int,
    maxDimension: Int,
): Boolean = runCatching {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(sourcePath, options)

    val scale = maxOf(options.outWidth, options.outHeight).toFloat() / maxDimension
    val sampleSize = if (scale > 1f) scale.toInt() else 1

    val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    val bitmap = BitmapFactory.decodeFile(sourcePath, decodeOptions) ?: return false

    val scaled = when {
        scale > 1f -> {
            val matrix = Matrix().apply { postScale(1f / scale, 1f / scale) }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                .also { if (it !== bitmap) bitmap.recycle() }
        }
        else -> bitmap
    }

    FileOutputStream(File(destPath)).use { out ->
        scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
    }
    scaled.recycle()
    true
}.getOrDefault(false)

