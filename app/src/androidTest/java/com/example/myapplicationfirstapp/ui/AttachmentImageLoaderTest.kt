package com.example.myapplicationfirstapp.ui

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil.decode.DataSource
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AttachmentImageLoaderTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun localImageLoadsAndSecondRequestUsesMemoryCache() = runBlocking {
        val file = File(context.cacheDir, "img1a-test-${UUID.randomUUID()}.png")
        val imageLoader = createAttachmentImageLoader(context)
        try {
            val bitmap = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.MAGENTA)
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
            val request = ImageRequest.Builder(context)
                .data(file).size(64, 64).allowHardware(false)
                .memoryCacheKey(attachmentImageCacheKey(file))
                .build()
            val first = imageLoader.execute(request)
            assertTrue(first is SuccessResult)
            val second = imageLoader.execute(request)
            assertTrue(second is SuccessResult)
            assertEquals(DataSource.MEMORY_CACHE, (second as SuccessResult).dataSource)
        } finally {
            imageLoader.shutdown()
            file.delete()
        }
    }

    @Test
    fun missingAndInvalidImagesReturnErrors() = runBlocking {
        val file = File(context.cacheDir, "img1a-invalid-${UUID.randomUUID()}.png")
        val imageLoader = createAttachmentImageLoader(context)
        try {
            val missing = ImageRequest.Builder(context).data(file).size(64, 64).build()
            assertTrue(imageLoader.execute(missing) is ErrorResult)
            file.writeText("not an image")
            val invalid = ImageRequest.Builder(context).data(file).size(64, 64).build()
            assertTrue(imageLoader.execute(invalid) is ErrorResult)
        } finally {
            imageLoader.shutdown()
            file.delete()
        }
    }

    @Test
    fun fileSizeChangeInvalidatesVersionKey() {
        val file = File(context.cacheDir, "img1a-key-${UUID.randomUUID()}")
        try {
            file.writeText("a")
            val before = attachmentImageCacheKey(file)
            file.writeText("longer")
            assertNotEquals(before, attachmentImageCacheKey(file))
        } finally { file.delete() }
    }
}
