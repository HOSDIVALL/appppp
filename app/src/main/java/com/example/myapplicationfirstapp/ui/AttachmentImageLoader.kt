package com.example.myapplicationfirstapp.ui

import android.content.Context
import coil.ImageLoader
import coil.request.CachePolicy
import coil.memory.MemoryCache
import java.io.File

/** Local attachment loader. Requests are File-only; network reads and disk cache are disabled. */
internal object AttachmentImageLoader {
    @Volatile private var instance: ImageLoader? = null

    fun get(context: Context): ImageLoader = instance ?: synchronized(this) {
        instance ?: createAttachmentImageLoader(context.applicationContext).also { instance = it }
    }
}

internal fun createAttachmentImageLoader(context: Context): ImageLoader =
    ImageLoader.Builder(context.applicationContext)
        .memoryCache {
            MemoryCache.Builder(context.applicationContext)
                .maxSizePercent(0.15)
                .build()
        }
        .networkCachePolicy(CachePolicy.DISABLED)
        .networkObserverEnabled(false)
        .diskCache(null)
        .crossfade(false)
        .build()

/** Immutable attachments today; future editing must generate a new file/version. */
internal fun attachmentImageCacheKey(file: File): String =
    "local:${file.absolutePath}|${file.lastModified()}|${file.length()}"
