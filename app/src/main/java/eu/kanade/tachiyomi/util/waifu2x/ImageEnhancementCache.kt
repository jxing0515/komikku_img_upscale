package eu.kanade.tachiyomi.util.waifu2x

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Manages disk cache for Real-CUGAN enhanced images to reduce memory usage.
 */
object ImageEnhancementCache {
    private const val CACHE_DIR_NAME = "realcugan_cache"
    private const val PHOTO_NPU_INT8_CACHE_REVISION = 4
    private const val REAL_CUGAN_NPU_INT8_CACHE_REVISION = 1
    private const val MAX_CACHE_SIZE = 3L * 1024 * 1024 * 1024 // 3GB
    private var cacheDir: File? = null
    private var lastTrimTime = 0L
    private val cacheGeneration = AtomicInteger(0)
    private val pendingSaveKeys = ConcurrentHashMap<String, Int>()
    private val saveQueue = Channel<SaveRequest>(capacity = 1)
    private val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private data class SaveRequest(
        val mangaId: Long,
        val chapterId: Long,
        val pageIndex: Int,
        val configHash: String,
        val bitmap: Bitmap,
        val pageVariant: String,
        val generation: Int,
        val key: String,
    )

    init {
        saveScope.launch {
            for (request in saveQueue) {
                try {
                    if (request.generation == cacheGeneration.get()) {
                        val file = writeToCache(request)
                        if (file != null) {
                            android.util.Log.d("ImageEnhancementCache", "Saved page ${request.pageIndex}/${request.pageVariant} to ${file.absolutePath}")
                        } else {
                            android.util.Log.e("ImageEnhancementCache", "Failed to save page ${request.pageIndex}/${request.pageVariant}")
                        }
                    }
                } finally {
                    pendingSaveKeys.remove(request.key, request.generation)
                    if (!request.bitmap.isRecycled) request.bitmap.recycle()
                }
            }
        }
    }

    fun init(context: Context) {
        if (cacheDir == null) {
            cacheDir = File(context.cacheDir, CACHE_DIR_NAME).apply {
                if (!exists()) mkdirs()
            }
        }
    }

    /**
     * Cache directory for a specific manga and chapter. Never creates it: this runs on hot
     * read paths (status polling, cache lookups) where mkdirs() would be wasted I/O.
     */
    private fun getChapterDir(mangaId: Long, chapterId: Long): File? {
        val root = cacheDir ?: return null
        return File(File(root, mangaId.toString()), chapterId.toString())
    }

    /**
     * Same as [getChapterDir], but creates the directory. Only for writers.
     */
    private fun ensureChapterDir(mangaId: Long, chapterId: Long): File? {
        return getChapterDir(mangaId, chapterId)?.apply {
            if (!exists()) mkdirs()
        }
    }

    /**
     * Get cached file if it exists
     */
    fun getCachedImage(mangaId: Long, chapterId: Long, pageIndex: Int, configHash: String, pageVariant: String = ""): File? {
        val file = File(getChapterDir(mangaId, chapterId) ?: return null, getFilename(pageIndex, configHash, pageVariant))
        return if (file.exists()) file else null
    }

    /**
     * Check if a file is already cached (helper for UI checks)
     */
    fun isCached(mangaId: Long, chapterId: Long, pageIndex: Int, configHash: String, pageVariant: String = ""): Boolean {
        return getCachedImage(mangaId, chapterId, pageIndex, configHash, pageVariant) != null
    }

    /**
     * Remove a cached enhanced image and its temporary file for the same page/config.
     */
    fun removeCachedImage(mangaId: Long, chapterId: Long, pageIndex: Int, configHash: String, pageVariant: String = ""): Boolean {
        return try {
            val dir = getChapterDir(mangaId, chapterId) ?: return false
            val file = File(dir, getFilename(pageIndex, configHash, pageVariant))
            val tempFile = File(file.parent, "${file.name}.tmp")
            val removedFile = !file.exists() || file.delete()
            val removedTemp = !tempFile.exists() || tempFile.delete()
            removedFile && removedTemp
        } catch (e: Exception) {
            android.util.Log.e("ImageEnhancementCache", "Failed to remove cached image for page $pageIndex", e)
            false
        }
    }

    fun removeSkipMarker(mangaId: Long, chapterId: Long, pageIndex: Int, configHash: String, pageVariant: String = ""): Boolean {
        return try {
            val dir = getChapterDir(mangaId, chapterId) ?: return false
            val file = File(dir, getFilename(pageIndex, configHash, pageVariant) + ".skip")
            !file.exists() || file.delete()
        } catch (e: Exception) {
            android.util.Log.e("ImageEnhancementCache", "Failed to remove skip marker for page $pageIndex", e)
            false
        }
    }

    /**
     * Transfers ownership of [bitmap] to the cache pipeline, including when the request is rejected.
     */
    suspend fun enqueueSaveToCache(mangaId: Long, chapterId: Long, pageIndex: Int, configHash: String, bitmap: Bitmap, pageVariant: String = ""): Boolean {
        if (cacheDir == null || !isDisplayable(bitmap)) {
            if (!bitmap.isRecycled) bitmap.recycle()
            return false
        }
        val key = pendingSaveKey(mangaId, chapterId, pageIndex, configHash, pageVariant)
        val generation = cacheGeneration.get()
        if (pendingSaveKeys.putIfAbsent(key, generation) != null) {
            if (!bitmap.isRecycled) bitmap.recycle()
            return false
        }

        val request = SaveRequest(
            mangaId = mangaId,
            chapterId = chapterId,
            pageIndex = pageIndex,
            configHash = configHash,
            bitmap = bitmap,
            pageVariant = pageVariant,
            generation = generation,
            key = key,
        )
        try {
            saveQueue.send(request)
            return true
        } catch (t: Throwable) {
            pendingSaveKeys.remove(key, generation)
            if (!bitmap.isRecycled) bitmap.recycle()
            throw t
        }
    }

    fun isSavePending(mangaId: Long, chapterId: Long, pageIndex: Int, pageVariant: String = ""): Boolean {
        val identity = pageIdentity(mangaId, chapterId, pageIndex, pageVariant)
        return pendingSaveKeys.keys.any { it.startsWith("$identity|") }
    }

    private fun writeToCache(request: SaveRequest): File? {
        if (cacheDir == null) return null
        val bitmap = request.bitmap
        if (!isDisplayable(bitmap)) {
            android.util.Log.e("ImageEnhancementCache", "Refusing to cache nearly transparent enhanced image for page ${request.pageIndex}")
            return null
        }

        try {
            val dir = ensureChapterDir(request.mangaId, request.chapterId) ?: return null
            val file = File(
                dir,
                getFilename(request.pageIndex, request.configHash, request.pageVariant),
            )
            val tempFile = File(file.parent, "${file.name}.tmp")

            // Lossy WebP drops the alpha channel, which would turn transparent manga pages black.
            val format = if (hasTransparency(bitmap)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Bitmap.CompressFormat.WEBP_LOSSLESS
                } else {
                    Bitmap.CompressFormat.PNG
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP
            }

            val written = FileOutputStream(tempFile).use { out ->
                val ok = bitmap.compress(format, 90, out)
                out.flush()
                ok
            }
            if (!written) {
                tempFile.delete()
                return null
            }

            if (request.generation != cacheGeneration.get()) {
                tempFile.delete()
                return null
            }

            if (tempFile.renameTo(file)) {
                return file
            } else {
                tempFile.delete()
                return null
            }
        } catch (t: Throwable) {
            android.util.Log.e("ImageEnhancementCache", "Failed to save to cache for page ${request.pageIndex}", t)
            return null
        }
    }

    private class AlphaSample(
        val total: Int,
        val visible: Int,
        val alphaSum: Long,
        val sawTransparent: Boolean,
    )

    /**
     * Alpha statistics from a 24x24 grid of the bitmap. Cheap enough for the save path and shared by
     * [hasTransparency] and [isDisplayable].
     */
    private fun sampleAlpha(bitmap: Bitmap): AlphaSample {
        val stepX = (bitmap.width / 24).coerceAtLeast(1)
        val stepY = (bitmap.height / 24).coerceAtLeast(1)
        var total = 0
        var visible = 0
        var alphaSum = 0L
        var sawTransparent = false

        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val alpha = bitmap.getPixel(x, y) ushr 24
                if (alpha > 16) visible++
                if (alpha != 255) sawTransparent = true
                alphaSum += alpha.toLong()
                total++
                x += stepX
            }
            y += stepY
        }
        return AlphaSample(total, visible, alphaSum, sawTransparent)
    }

    /**
     * Lossy WebP drops the alpha channel, which would turn transparent manga pages black. ARGB_8888
     * bitmaps report [Bitmap.hasAlpha] even when fully opaque, so sample instead of trusting it.
     */
    private fun hasTransparency(bitmap: Bitmap): Boolean {
        return bitmap.hasAlpha() && sampleAlpha(bitmap).sawTransparent
    }

    fun isDisplayable(bitmap: Bitmap): Boolean {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return false
        if (!bitmap.hasAlpha()) return true

        val sample = sampleAlpha(bitmap)
        if (sample.total == 0) return false
        return sample.visible > sample.total / 20 || sample.alphaSum / sample.total > 32
    }

    /**
     * Mark a page as skipped (too large to process) in the cache
     */
    fun saveSkippedToCache(mangaId: Long, chapterId: Long, pageIndex: Int, configHash: String, pageVariant: String = "") {
        try {
            val dir = ensureChapterDir(mangaId, chapterId) ?: return
            val file = File(dir, getFilename(pageIndex, configHash, pageVariant) + ".skip")
            if (!file.exists()) {
                file.createNewFile()
            }
        } catch (e: Exception) {
            android.util.Log.e("ImageEnhancementCache", "Failed to save skip marker", e)
        }
    }

    /**
     * Check if a page was marked as skipped in the cache
     */
    fun isSkipped(mangaId: Long, chapterId: Long, pageIndex: Int, configHash: String, pageVariant: String = ""): Boolean {
        val dir = getChapterDir(mangaId, chapterId) ?: return false
        return File(dir, getFilename(pageIndex, configHash, pageVariant) + ".skip").exists()
    }

    /**
     * Mark a page as failed so it is not decoded and re-enhanced over and over. Without this the
     * reader polling loop re-enqueues a page that can never produce a cache entry (unsupported
     * Vulkan, missing model, disk full, transparent result) for as long as it stays in the
     * preload window.
     */
    fun saveFailedToCache(mangaId: Long, chapterId: Long, pageIndex: Int, configHash: String, pageVariant: String = "") {
        try {
            val dir = ensureChapterDir(mangaId, chapterId) ?: return
            val file = File(dir, getFilename(pageIndex, configHash, pageVariant) + ".fail")
            if (!file.exists()) {
                file.createNewFile()
            }
        } catch (e: Exception) {
            android.util.Log.e("ImageEnhancementCache", "Failed to save failure marker", e)
        }
    }

    fun isFailed(mangaId: Long, chapterId: Long, pageIndex: Int, configHash: String, pageVariant: String = ""): Boolean {
        val dir = getChapterDir(mangaId, chapterId) ?: return false
        return File(dir, getFilename(pageIndex, configHash, pageVariant) + ".fail").exists()
    }

    /**
     * Delete all cache files.
     *
     * The generation bump is synchronous so queued saves are dropped immediately, but the
     * recursive delete runs on the save worker: every caller is a preference-change handler on the
     * main thread and this directory can hold gigabytes.
     */
    fun clear(context: Context) {
        init(context)
        cacheGeneration.incrementAndGet()
        pendingSaveKeys.clear()
        val dir = cacheDir ?: return
        saveScope.launch {
            try {
                dir.deleteRecursively()
            } catch (t: Throwable) {
                android.util.Log.e("ImageEnhancementCache", "Failed to clear cache directory", t)
            }
            dir.mkdirs()
        }
    }

    private fun pendingSaveKey(
        mangaId: Long,
        chapterId: Long,
        pageIndex: Int,
        configHash: String,
        pageVariant: String,
    ): String {
        return "${pageIdentity(mangaId, chapterId, pageIndex, pageVariant)}|$configHash"
    }

    private fun pageIdentity(mangaId: Long, chapterId: Long, pageIndex: Int, pageVariant: String): String {
        return "$mangaId|$chapterId|$pageIndex|$pageVariant"
    }

    private fun getFilename(pageIndex: Int, configHash: String, pageVariant: String = ""): String {
        return buildString {
            append(pageIndex)
            append('_')
            append(configHash)
            if (pageVariant.isNotEmpty()) {
                append('_')
                append(pageVariant)
            }
            append(".webp")
        }
    }

    /**
     * Generate a unique hash string based on current settings
     */
    fun getConfigHash(
        noise: Int,
        scale: Int,
        model: Int = 0,
        realEsrganStyle: Int = Waifu2x.REAL_ESRGAN_STYLE_ANIME,
        maxWidth: Int = 0,
        maxHeight: Int = 0,
        skipMaxWidth: Int = 0,
        skipMaxHeight: Int = 0,
        tileSize: Int = 128,
        precision: Int = 0,
        fp16Arithmetic: Boolean = false,
        processingBackend: Int = Waifu2x.PROCESSING_BACKEND_VULKAN,
    ): String {
        val effectiveScale = getEffectiveScale(model, scale, realEsrganStyle)
        val resolvedBackend = Waifu2x.resolveProcessingBackend(processingBackend, model, effectiveScale)
        val resolvedPrecision = Waifu2x.resolvePrecision(precision, resolvedBackend, model, effectiveScale)
        val modelRevision = if (
            model == Waifu2x.MODEL_REAL_ESRGAN_ANIME &&
            realEsrganStyle == Waifu2x.REAL_ESRGAN_STYLE_PHOTO &&
            resolvedPrecision == 2 &&
            resolvedBackend == Waifu2x.PROCESSING_BACKEND_QUALCOMM_NPU
        ) {
            "_mv$PHOTO_NPU_INT8_CACHE_REVISION"
        } else if (
            (model == 0 || model == 1) &&
            resolvedPrecision == 2 &&
            resolvedBackend == Waifu2x.PROCESSING_BACKEND_QUALCOMM_NPU
        ) {
            "_cv$REAL_CUGAN_NPU_INT8_CACHE_REVISION"
        } else {
            ""
        }
        return "${noise}x${effectiveScale}_m${model}_rs${realEsrganStyle}_w${maxWidth}_h${maxHeight}_sw${skipMaxWidth}_sh${skipMaxHeight}_t${tileSize}_p${resolvedPrecision}_fa${if (fp16Arithmetic) 1 else 0}_b${resolvedBackend}$modelRevision"
    }

    fun getEffectiveScale(model: Int, scale: Int, realEsrganStyle: Int = Waifu2x.REAL_ESRGAN_STYLE_ANIME): Int {
        Waifu2x.w2xExScaleFor(model)?.let { return it }
        return when (model) {
            3, 4, 5 -> 2
            Waifu2x.MODEL_REAL_ESRGAN_ANIME -> if (realEsrganStyle == Waifu2x.REAL_ESRGAN_STYLE_PHOTO) 2 else scale
            else -> scale
        }
    }

    /**
     * Check cache size and trim if it exceeds limit (3GB)
     * Should be called from background thread
     */
    fun checkAndTrim(context: Context) {
        // Debounce: only check once every 10 minutes
        if (System.currentTimeMillis() - lastTrimTime < 10 * 60 * 1000) return
        lastTrimTime = System.currentTimeMillis()

        init(context)
        val dir = cacheDir ?: return

        try {
            var size = dir.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
            if (size > MAX_CACHE_SIZE) {
                android.util.Log.d("ImageEnhancementCache", "Cache size ${size / 1024 / 1024}MB > 3GB, trimming...")

                // Get all files sorted by last modified (oldest first)
                val files = dir.walkTopDown()
                    .filter { it.isFile }
                    .sortedBy { it.lastModified() }
                    .iterator()

                while (files.hasNext() && size > MAX_CACHE_SIZE * 0.9) { // Trim to 90%
                    val file = files.next()
                    val len = file.length()
                    if (file.delete()) {
                        size -= len
                    }
                }
                android.util.Log.d("ImageEnhancementCache", "Trim complete, new size: ${size / 1024 / 1024}MB")
            }
        } catch (e: Exception) {
            android.util.Log.e("ImageEnhancementCache", "Failed to trim cache", e)
        }
    }
}
