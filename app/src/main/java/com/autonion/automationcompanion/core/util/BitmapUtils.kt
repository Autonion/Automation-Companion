package com.autonion.automationcompanion.core.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * Utility for memory-efficient bitmap decoding with downsampling.
 *
 * Follows the official Android pattern:
 * https://developer.android.com/topic/performance/graphics/load-bitmap
 */
object BitmapUtils {

    /**
     * Decodes a bitmap from [path], downsampled so that its dimensions
     * are at least [reqWidth] × [reqHeight] but as small as possible.
     * Returns null if decoding fails.
     */
    fun decodeSampledBitmapFromFile(
        path: String,
        reqWidth: Int,
        reqHeight: Int
    ): Bitmap? {
        // First pass: decode bounds only
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, options)

        // Calculate inSampleSize
        options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
        options.inJustDecodeBounds = false

        return BitmapFactory.decodeFile(path, options)
    }

    /**
     * Calculates the largest power-of-2 [BitmapFactory.Options.inSampleSize] value
     * that keeps both dimensions ≥ the requested size.
     */
    fun calculateInSampleSize(
        options: BitmapFactory.Options,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2

            while (halfHeight / inSampleSize >= reqHeight &&
                halfWidth / inSampleSize >= reqWidth
            ) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }
}
