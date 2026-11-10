package com.example.myapplicationfirstapp.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 图片压缩。
 *
 * 目标：长边不超过 2048px、文件不超过 ~300KB。
 * 手机拍的照片动辄 4~10MB，直接存下来既占空间，解码也慢；
 * 2048px 已经比手机屏幕高，看大图足够清晰。
 *
 * 做法是「解码 → 缩放 → 反复试不同 JPEG 质量 → 挑第一个达标的」。
 * 只降低质量不满意的做法会留下噪点，所以先缩尺寸再降质量。
 */
object ImageCompressor {

    private const val MAX_DIMENSION = 2048
    private const val TARGET_BYTES = 300L * 1024

    /** 从高到低试；第一个小于目标体积的就被采用 */
    private val QUALITIES = intArrayOf(88, 80, 72, 64, 56, 48)

    /**
     * 就地压缩，成功返回 true。
     * 失败（文件损坏、内存不够）时保留原文件，不影响笔记能不能用。
     */
    fun compressInPlace(file: File): Boolean {
        if (!file.exists() || file.length() == 0L) return false
        // 已经很小的图片没必要再压一遍，二次编码只会掉画质
        if (file.length() <= TARGET_BYTES) return true

        val bitmap = runCatching { decodeForCompress(file) }.getOrNull() ?: return false
        return try {
            writeCompressed(file, bitmap)
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    // ============ 编码 ============

    private fun writeCompressed(file: File, bitmap: Bitmap): Boolean {
        val temp = File(file.parentFile, "${file.name}.tmp")
        return runCatching {
            QUALITIES.forEachIndexed { index, quality ->
                temp.outputStream().use { output ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)
                }

                val smallEnough = temp.length() <= TARGET_BYTES
                if (smallEnough || index == QUALITIES.lastIndex) {
                    // 同目录 renameTo 是原子替换，即使中途断掉也不会留下半张图
                    return@runCatching temp.renameTo(file)
                }
            }
            false
        }.getOrDefault(false).also { temp.delete() }
    }

    // ============ 解码 ============

    private fun decodeForCompress(file: File): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // ImageDecoder 会自动应用 EXIF 旋转，省掉手动转正
            val source = ImageDecoder.createSource(file)
            return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val width = info.size.width
                val height = info.size.height
                val (targetWidth, targetHeight) = fitInside(width, height)
                if (targetWidth != width || targetHeight != height) {
                    decoder.setTargetSize(targetWidth, targetHeight)
                }
            }
        }

        // Android 7/8 没有 ImageDecoder，只能先看尺寸、再采样解码、再按 EXIF 转正
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
        }
        val decoded = BitmapFactory.decodeFile(file.path, options) ?: return null
        val upright = applyExifRotation(decoded, file)

        val (targetWidth, targetHeight) = fitInside(upright.width, upright.height)
        return if (targetWidth == upright.width && targetHeight == upright.height) {
            upright
        } else {
            Bitmap.createScaledBitmap(upright, targetWidth, targetHeight, true)
        }
    }

    /** 等比缩到长边不超过 [MAX_DIMENSION]；本来就更小则原样返回 */
    private fun fitInside(width: Int, height: Int): Pair<Int, Int> {
        val longest = max(width, height)
        if (longest <= 0 || longest <= MAX_DIMENSION) return width to height

        val scale = MAX_DIMENSION.toFloat() / longest
        return (width * scale).roundToInt().coerceAtLeast(1) to
            (height * scale).roundToInt().coerceAtLeast(1)
    }

    private fun sampleSizeFor(width: Int, height: Int): Int {
        var sample = 1
        while (width / (sample * 2) >= MAX_DIMENSION || height / (sample * 2) >= MAX_DIMENSION) {
            sample *= 2
        }
        return sample
    }

    private fun applyExifRotation(bitmap: Bitmap, file: File): Bitmap = runCatching {
        val orientation = ExifInterface(file)
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            else -> return@runCatching bitmap
        }

        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }.getOrDefault(bitmap)
}
