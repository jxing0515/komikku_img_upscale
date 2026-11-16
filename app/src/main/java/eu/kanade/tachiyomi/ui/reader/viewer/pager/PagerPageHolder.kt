package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.core.view.isVisible
import eu.kanade.presentation.util.formattedMessage
import eu.kanade.tachiyomi.databinding.ReaderErrorBinding
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderProgressIndicator
import eu.kanade.tachiyomi.ui.webview.WebViewActivity
import eu.kanade.tachiyomi.util.waifu2x.ImageEnhancementCache
import eu.kanade.tachiyomi.util.waifu2x.ImageEnhancer
import eu.kanade.tachiyomi.util.waifu2x.Waifu2x
import eu.kanade.tachiyomi.widget.ViewPagerAdapter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import logcat.LogPriority
import okio.Buffer
import okio.BufferedSource
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import tachiyomi.decoder.ImageDecoder
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * View of the ViewPager that contains a page of a chapter.
 */
@SuppressLint("ViewConstructor")
class PagerPageHolder(
    readerThemedContext: Context,
    val viewer: PagerViewer,
    val page: ReaderPage,
    private var extraPage: ReaderPage? = null,
    // KMK -->
    @ColorInt private val seedColor: Int? = null,
    // KMK <--
) : ReaderPageImageView(readerThemedContext), ViewPagerAdapter.PositionableView {

    /**
     * Item that identifies this view. Needed by the adapter to not recreate views.
     */
    override val item
        get() = page to extraPage

    /**
     * Loading progress bar to indicate the current progress.
     */
    private var progressIndicator: ReaderProgressIndicator? = null // = ReaderProgressIndicator(readerThemedContext)

    /**
     * Error layout to show when the image fails to load.
     */
    private var errorLayout: ReaderErrorBinding? = null

    private val scope = MainScope()

    /**
     * Job for loading the page and processing changes to the page's status.
     */
    private var loadJob: Job? = null

    /**
     * Job for loading the page.
     */
    private var extraLoadJob: Job? = null

    /**
     * Job that keeps the two per-page enhancement status rows up to date in double-page mode.
     */
    private var enhancementStatusJob: Job? = null

    // KMK -->
    private val readerPreferences by lazy { Injekt.get<ReaderPreferences>() }
    // KMK <--

    init {
        // KMK -->
        // Set page index for enhancement priority tracking
        pageIndex = page.index
        mangaId = viewer.activity.viewModel.manga?.id ?: -1L
        chapterId = page.chapter.chapter.id ?: -1L
        readerPage = page
        // KMK <--
        loadJob = scope.launch { loadPageAndProcessStatus(1) }
        // SY -->
        extraLoadJob = scope.launch { loadPageAndProcessStatus(2) }
        // SY <--
    }

    /**
     * Called when this view is detached from the window. Unsubscribes any active subscription.
     */
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        loadJob?.cancel()
        loadJob = null
        extraLoadJob?.cancel()
        extraLoadJob = null
        enhancementStatusJob?.cancel()
        enhancementStatusJob = null
    }

    private fun initProgressIndicator() {
        if (progressIndicator == null) {
            progressIndicator = ReaderProgressIndicator(
                context = context,
                // KMK -->
                seedColor = seedColor,
                // KMK <--
            )
            addView(progressIndicator)
        }
    }

    /**
     * Loads the page and processes changes to the page's status.
     *
     * Returns immediately if the page has no PageLoader.
     * Otherwise, this function does not return. It will continue to process status changes until
     * the Job is cancelled.
     */
    private suspend fun loadPageAndProcessStatus(pageIndex: Int) {
        // SY -->
        val page = if (pageIndex == 1) page else extraPage
        page ?: return
        // SY <--
        val loader = page.chapter.pageLoader ?: return
        supervisorScope {
            launchIO {
                loader.loadPage(page)
            }
            page.statusFlow.collectLatest { state ->
                when (state) {
                    Page.State.Queue -> setQueued()
                    Page.State.LoadPage -> setLoading()
                    Page.State.DownloadImage -> {
                        setDownloading()
                        page.progressFlow.collectLatest { value ->
                            progressIndicator?.setProgress(value)
                        }
                    }
                    Page.State.Ready -> setImage()
                    is Page.State.Error -> setError(state.error)
                }
            }
        }
    }

    /**
     * Called when the page is queued.
     */
    private fun setQueued() {
        initProgressIndicator()
        progressIndicator?.show()
        removeErrorLayout()
    }

    /**
     * Called when the page is loading.
     */
    private fun setLoading() {
        initProgressIndicator()
        progressIndicator?.show()
        removeErrorLayout()
    }

    /**
     * Called when the page is downloading.
     */
    private fun setDownloading() {
        initProgressIndicator()
        progressIndicator?.show()
        removeErrorLayout()
    }

    /**
     * Called when the page is ready.
     */
    private suspend fun setImage() {
        if (extraPage == null) {
            progressIndicator?.setProgress(0)
        } else {
            progressIndicator?.setProgress(95)
        }

        val streamFn = page.stream ?: return
        val streamFn2 = extraPage?.stream

        // KMK --> The right half of a spread is loaded independently of the left one. When only the
        // left stream is ready the merge silently degenerates to a single page while the pager still
        // pairs two pages, which is what left the first spread of every chapter with a black right
        // half and a "1-2 / 30" counter over a single page. Wait for the missing half and redraw.
        if (extraPage != null && streamFn2 == null) {
            logcat(LogPriority.DEBUG) {
                "PagerPageHolder: page ${page.index} spread half ${extraPage?.index} has no stream yet, " +
                    "showing it alone for now and redrawing when it arrives"
            }
            scheduleSpreadRedraw()
        }
        // KMK <--

        try {
            val (source, isAnimated, background) = withIOContext {
                streamFn().buffered(16).use { source ->
                    // SY -->
                    if (extraPage != null) {
                        streamFn2?.invoke()
                            ?.buffered(16)
                    } else {
                        null
                    }.use { source2 ->
                        val itemSource = if (viewer.config.dualPageSplit) {
                            process(item.first, Buffer().readFrom(source))
                        } else {
                            mergePages(Buffer().readFrom(source), source2?.let { Buffer().readFrom(it) })
                        }
                        // SY <--
                        val isAnimated = ImageUtil.isAnimatedAndSupported(itemSource)
                        val background = if (!isAnimated && viewer.config.automaticBackground) {
                            ImageUtil.chooseBackground(context, itemSource.peek())
                        } else {
                            null
                        }
                        Triple(itemSource, isAnimated, background)
                    }
                }
            }
            withUIContext {
                setupDoublePageEnhancement()
                setImage(
                    source,
                    isAnimated,
                    Config(
                        zoomDuration = viewer.config.doubleTapAnimDuration,
                        minimumScaleType = viewer.config.imageScaleType,
                        cropBorders = viewer.config.imageCropBorders,
                        zoomStartPosition = viewer.config.imageZoomType,
                        landscapeZoom = viewer.config.landscapeZoom,
                        // KMK -->
                        disableZoomIn = viewer.config.disableZoomIn,
                        doubleTapZoom = viewer.config.doubleTapZoom,
                        landscapeZoomScaleType = viewer.config.landscapeZoomScaleType,
                        // KMK <--
                    ),
                    // KMK -->
                    streamFn,
                    // KMK <--
                )
                if (!isAnimated) {
                    pageBackground = background
                }
                removeErrorLayout()
            }
        } catch (e: CancellationException) {
            // KMK --> Turning a page cancels this holder's work on purpose. Logging that as an
            // ERROR produced 320 noise lines per stress run and buried real failures.
            logcat(LogPriority.DEBUG) { "PagerPageHolder: page load cancelled ${e.message}" }
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e)
            withUIContext {
                setError(e)
            }
        }
    }

    // KMK -->
    private var spreadRedrawScheduled = false

    /**
     * Redraws the spread once the missing half finally has a stream. Only the first attempt of a
     * holder schedules this, and a successful redraw clears the flag, so a page that never becomes
     * available does not spin.
     */
    private fun scheduleSpreadRedraw() {
        if (spreadRedrawScheduled) return
        spreadRedrawScheduled = true
        scope.launch {
            try {
                var waited = 0
                while (waited < 100 && extraPage?.stream == null) {
                    delay(100)
                    waited++
                }
                val second = extraPage
                if (second != null && second.stream != null) {
                    logcat(LogPriority.DEBUG) {
                        "PagerPageHolder: redrawing spread ${page.index}/${second.index}, second half is ready"
                    }
                    setImage()
                }
            } finally {
                spreadRedrawScheduled = false
            }
        }
    }
    // KMK <--

    private fun setupDoublePageEnhancement() {
        val second = extraPage
        val isRealDoublePage =
            viewer.config.doublePages &&
                !viewer.config.dualPageSplit &&
                second != null &&
                !page.fullPage &&
                !second.fullPage &&
                readerPreferences.realCuganEnabled().get()
        if (!isRealDoublePage) {
            suppressSinglePageProcessedSwap = false
            enhancedImageSourceFactory = null
            suppressDefaultStatus = false
            hideEnhancementStatus()
            return
        }
        suppressSinglePageProcessedSwap = true
        enhancedImageSourceFactory = { firstFile ->
            buildDoublePageSource(firstFile)
        }
        // The base class only ever enqueues the first half of a spread, so the second page was
        // never enhanced and its status stayed on "处理中" forever.
        enqueueSecondPageEnhancement(second)
        // The base view only tracks one page status; in double-page mode we show two rows ourselves.
        suppressDefaultStatus = true
        startEnhancementStatusTracking()
    }

    // KMK -->
    private fun enqueueSecondPageEnhancement(second: ReaderPage?) {
        val target = second ?: return
        if (!readerPreferences.realCuganEnabled().get()) return
        val mId = page.chapter.chapter.manga_id ?: -1L
        val cId = page.chapter.chapter.id ?: -1L
        if (mId == -1L || cId == -1L) return
        // Never queue a page whose source is not loaded yet: a null provider counts as a source
        // failure, and two of those make the page permanently un-enhanceable.
        if (target.stream == null && target.imageUrl == null) return
        val configHash = enhancementConfigHash()
        if (ImageEnhancementCache.getCachedImage(mId, cId, target.index, configHash, target.enhancementKeySuffix) != null) return
        if (ImageEnhancementCache.isSkipped(mId, cId, target.index, configHash, target.enhancementKeySuffix)) return
        if (ImageEnhancementCache.isFailed(mId, cId, target.index, configHash, target.enhancementKeySuffix)) return
        ImageEnhancer.enhanceLazy(
            context = context.applicationContext,
            mangaId = mId,
            chapterId = cId,
            pageIndex = target.index,
            // The visible spread must not wait behind preloads, otherwise its page sits on 排队中.
            highPriority = ImageEnhancer.isFocusedTarget(target.index, target.enhancementKeySuffix),
            pageVariant = target.enhancementKeySuffix,
        ) {
            try {
                target.stream?.let { stream -> stream().use { Buffer().readFrom(it) } } ?: target.imageUrl
            } catch (_: Exception) {
                null
            }
        }
    }

    override fun onPageSelected(forward: Boolean) {
        super.onPageSelected(forward)
        // KMK --> The right half of a spread is visible too. Without secondary focus it stays a
        // low-priority preload, is preempted on every page turn and never reaches an enhanced file.
        val second = extraPage
        ImageEnhancer.reprioritizeAround(
            pageIndex = page.index,
            pageVariant = page.enhancementKeySuffix,
            secondaryPageIndex = second?.index,
            secondaryPageVariant = second?.enhancementKeySuffix ?: "",
        )
        if (second != null) {
            enqueueSecondPageEnhancement(second)
        }
        // KMK <--
    }
    // KMK <--

    /**
     * Builds the merged double-page source from the enhanced cache of both pages. Returns null
     * until BOTH pages are enhanced, so the merged view is refreshed once, only when both are done.
     */
    private fun buildDoublePageSource(firstFile: File): BufferedSource? {
        val second = extraPage ?: return null
        val mId = page.chapter.chapter.manga_id ?: -1L
        val cId = page.chapter.chapter.id ?: -1L
        if (mId == -1L || cId == -1L) return null
        if (!readerPreferences.realCuganEnabled().get()) return null
        ImageEnhancementCache.init(context)
        val configHash = enhancementConfigHash()

        val secondFile = ImageEnhancementCache.getCachedImage(
            mId,
            cId,
            second.index,
            configHash,
            second.enhancementKeySuffix,
        ) ?: run {
            // KMK --> Not an error: the second half is simply still being processed.
            logcat(LogPriority.DEBUG) {
                "PagerPageHolder: spread ${page.index}/${second.index} still waiting for the second half"
            }
            return null
            // KMK <--
        }

        val bitmap1 = decodeEnhancedBitmap(firstFile) ?: run {
            logcat(LogPriority.WARN) {
                "PagerPageHolder: cannot decode enhanced page ${page.index} from ${firstFile.name}"
            }
            return null
        }
        val bitmap2 = decodeEnhancedBitmap(secondFile)
        if (bitmap2 == null) {
            bitmap1.recycle()
            logcat(LogPriority.WARN) {
                "PagerPageHolder: cannot decode enhanced page ${second.index} from ${secondFile.name}"
            }
            return null
        }
        try {
            val isLTR = (viewer !is R2LPagerViewer) xor viewer.config.invertDoublePages
            // KMK --> Both bitmaps are already upscaled: calculateCenterMargin derives the margin
            // from the bitmap heights, so it is already expressed in enhanced pixels. Multiplying
            // it by the model scale again doubled the visible gap between the two pages.
            val centerMargin = calculateCenterMargin(bitmap1.height, bitmap2.height).coerceAtLeast(0)
            // KMK <--
            return ImageUtil.mergeBitmaps(bitmap1, bitmap2, isLTR, centerMargin, viewer.config.pageCanvasColor)
        } finally {
            bitmap1.recycle()
            bitmap2.recycle()
        }
    }

    private fun enhancementConfigHash(): String {
        return ImageEnhancementCache.getConfigHash(
            noise = readerPreferences.realCuganNoiseLevel().get(),
            scale = readerPreferences.realCuganScale().get(),
            model = readerPreferences.realCuganModel().get(),
            realEsrganStyle = readerPreferences.realEsrganStyle().get(),
            maxWidth = readerPreferences.realCuganMaxSizeWidth().get(),
            maxHeight = readerPreferences.realCuganMaxSizeHeight().get(),
            skipMaxWidth = readerPreferences.realCuganSkipMaxSizeWidth().get(),
            skipMaxHeight = readerPreferences.realCuganSkipMaxSizeHeight().get(),
            tileSize = readerPreferences.realCuganTileSize().get(),
            precision = readerPreferences.realCuganPrecision().get(),
            fp16Arithmetic = readerPreferences.realCuganFp16Arithmetic().get(),
            processingBackend = readerPreferences.realCuganProcessingBackend().get(),
        )
    }

    private fun decodeEnhancedBitmap(file: File): Bitmap? {
        return try {
            android.graphics.BitmapFactory.decodeFile(file.absolutePath)
        } catch (_: Exception) {
            null
        }
    }

    // --- Two-line enhancement status (first page on top, second page below) ---

    private var enhancementStatusContainer: LinearLayout? = null
    private var enhancementStatusFirst: TextView? = null
    private var enhancementStatusSecond: TextView? = null

    private fun ensureEnhancementStatusViews(): Pair<TextView, TextView>? {
        if (enhancementStatusContainer == null) {
            val container = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                elevation = 20f
                layoutParams = LayoutParams(
                    LayoutParams.WRAP_CONTENT,
                    LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.START,
                ).apply {
                    // KMK --> Keep clear of the centred page counter (see ReaderPageImageView).
                    setMargins(20, 0, 0, (56 * resources.displayMetrics.density).toInt())
                    // KMK <--
                }

                addView(makeEnhancementStatusTextView())
                addView(makeEnhancementStatusTextView())
            }
            addView(container)
            container.bringToFront()
            enhancementStatusContainer = container
            enhancementStatusFirst = container.getChildAt(0) as TextView
            enhancementStatusSecond = container.getChildAt(1) as TextView
        }
        val first = enhancementStatusFirst ?: return null
        val second = enhancementStatusSecond ?: return null
        return first to second
    }

    private fun makeEnhancementStatusTextView(): OutlineTextView = OutlineTextView(context).apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setBackgroundColor(Color.TRANSPARENT)
        // KMK --> Long status text must not run under the centred page counter.
        maxWidth = (resources.displayMetrics.widthPixels * 0.62f).toInt()
        // KMK <--
    }

    private fun hideEnhancementStatus() {
        enhancementStatusJob?.cancel()
        enhancementStatusJob = null
        enhancementStatusContainer?.isVisible = false
    }

    // KMK -->
    /**
     * What the visible spread can tell the reader: whether it still has to wait, and nothing else.
     * "Already enhanced" and "will never be enhanced" both mean "do not wait", so neither shows text.
     */
    private enum class PageWaitState {
        DONE,
        /** Too large / unsupported backend: deliberately not enhanced, nothing to retry. */
        SKIPPED,
        /** Enhancement failed: the reader may retry, so this one is worth a prompt. */
        FAILED,
        WORKING,
        QUEUED,
    }

    /** Returns null when the page is neither cached, terminal, working nor queued (needs enqueueing). */
    private fun pageWaitState(mId: Long, cId: Long, index: Int, variant: String, configHash: String): PageWaitState? {
        if (ImageEnhancementCache.getCachedImage(mId, cId, index, configHash, variant) != null) return PageWaitState.DONE
        if (ImageEnhancementCache.isSavePending(mId, cId, index, variant)) return PageWaitState.QUEUED
        if (ImageEnhancementCache.isSkipped(mId, cId, index, configHash, variant)) return PageWaitState.SKIPPED
        if (ImageEnhancementCache.isFailed(mId, cId, index, configHash, variant)) return PageWaitState.FAILED
        if (Waifu2x.getProgressId() == index) return PageWaitState.WORKING
        if (ImageEnhancer.hasRequest(mId, cId, index, variant)) return PageWaitState.QUEUED
        return null
    }

    // KMK -->
    /** Guards the one-shot merged-source refresh once both halves are enhanced. */
    private var mergedRefreshDone = false

    /**
     * Highest spread progress already shown. The reported number must never decrease, not even when
     * a half is preempted and restarted.
     */
    private var lastShownPercent = 0

    /**
     * Rebuilds the spread after both halves landed in the cache. The per-page polling stops as soon
     * as its own half is cached (otherwise it re-read the disk every 500ms), so this is what turns
     * the raw merged spread into the enhanced one.
     */
    private fun refreshMergedSource() {
        if (!suppressSinglePageProcessedSwap) return
        logcat(LogPriority.DEBUG) {
            "PagerPageHolder: spread ${page.index}/${extraPage?.index} fully enhanced, refreshing merged source"
        }
        scope.launch { setImage() }
    }

    /** Clears the terminal markers of this spread and queues both halves again. */
    private fun retryFailedSpreadPages() {
        val mId = page.chapter.chapter.manga_id ?: -1L
        val cId = page.chapter.chapter.id ?: -1L
        if (mId == -1L || cId == -1L) return
        val configHash = enhancementConfigHash()
        listOfNotNull(page, extraPage).forEach { target ->
            if (ImageEnhancementCache.isFailed(mId, cId, target.index, configHash, target.enhancementKeySuffix)) {
                ImageEnhancementCache.clearFailure(mId, cId, target.index, target.enhancementKeySuffix)
            }
        }
        logcat(LogPriority.DEBUG) {
            "PagerPageHolder: retrying enhancement for spread ${page.index}/${extraPage?.index}"
        }
        mergedRefreshDone = false
        promoteEnhancementRequest(highPriority = true)
        extraPage?.let { enqueueSecondPageEnhancement(it) }
        startEnhancementStatusTracking()
    }
    // KMK <--

    private fun startEnhancementStatusTracking() {
        enhancementStatusJob?.cancel()
        // KMK --> A new monitoring round starts from zero; lastShownPercent only guards a run.
        lastShownPercent = 0
        // KMK <--
        val second = extraPage ?: return
        val mId = page.chapter.chapter.manga_id ?: -1L
        val cId = page.chapter.chapter.id ?: -1L
        if (mId == -1L || cId == -1L) return
        val views = ensureEnhancementStatusViews() ?: return
        val (statusView, hiddenView) = views
        hiddenView.isVisible = false
        // Cache lookups hit the filesystem, so keep the polling loop off the main thread.
        enhancementStatusJob = scope.launchIO {
            var attempts = 0
            // Cancellation is delivered via `delay`, so the loop exits on detach.
            while (attempts < 240) {
                val configHash = enhancementConfigHash()
                var firstState = pageWaitState(mId, cId, page.index, page.enhancementKeySuffix, configHash)
                var secondState = pageWaitState(mId, cId, second.index, second.enhancementKeySuffix, configHash)

                // A visible page that is neither cached nor queued would silently stay blurry forever;
                // put it back in the queue so the spread is always either ready or on its way.
                if (firstState == null || secondState == null) {
                    if (firstState == null) promoteEnhancementRequest(highPriority = true)
                    if (secondState == null) enqueueSecondPageEnhancement(second)
                    if (firstState == null) {
                        firstState = pageWaitState(mId, cId, page.index, page.enhancementKeySuffix, configHash)
                    }
                    if (secondState == null) {
                        secondState = pageWaitState(mId, cId, second.index, second.enhancementKeySuffix, configHash)
                    }
                }

                val states = listOfNotNull(firstState, secondState)
                val failed = states.any { it == PageWaitState.FAILED }
                val waiting = states.any { it == PageWaitState.WORKING || it == PageWaitState.QUEUED }
                // KMK --> Report the progress of the whole spread, not of whichever half happens to
                // own the single native progress slot: page A ending at 82% and page B starting at
                // 12% read as "the progress went backwards". (done + current/100) / pages only grows.
                val doneCount = states.count { it == PageWaitState.DONE || it == PageWaitState.SKIPPED }
                val progressOwner = Waifu2x.getProgressId()
                val activePercent = if (progressOwner == page.index || progressOwner == second.index) {
                    Waifu2x.getProgressPercent().coerceIn(0, 100)
                } else {
                    0
                }
                val overallPercent = if (states.isEmpty()) {
                    0
                } else {
                    ((doneCount + activePercent / 100f) / states.size * 100f).roundToInt().coerceIn(0, 100)
                }
                val shownPercent = max(overallPercent, lastShownPercent)
                lastShownPercent = shownPercent
                // KMK <--
                val text = when {
                    // KMK --> A page that terminally failed must say so and stay clickable: the
                    // alternative was a spread that silently lost one half and never explained why.
                    failed -> context.stringResource(KMR.strings.reader_status_failed)
                    // KMK <--
                    !waiting -> null
                    // KMK --> Once any half is done the line keeps showing the spread's progress.
                    // Going back to "waiting" (which is what the second half queued behind the
                    // first used to produce) made one spread change state four times.
                    states.contains(PageWaitState.WORKING) || doneCount > 0 -> {
                        context.stringResource(KMR.strings.reader_status_enhancing_progress, shownPercent)
                    }
                    // KMK <--
                    else -> context.stringResource(KMR.strings.reader_status_waiting)
                }

                // KMK --> Both halves are enhanced: rebuild the merged enhanced source once. The
                // per-page polling stops as soon as its own half is cached, so this is what turns
                // the raw merged spread into the enhanced one.
                if (
                    !mergedRefreshDone &&
                    firstState == PageWaitState.DONE &&
                    secondState == PageWaitState.DONE
                ) {
                    mergedRefreshDone = true
                    refreshMergedSource()
                }
                // KMK <--

                val showStatus = readerPreferences.realCuganShowStatus().get()
                withUIContext {
                    if (text == null || !showStatus) {
                        enhancementStatusContainer?.isVisible = false
                    } else {
                        statusView.text = text
                        statusView.isVisible = true
                        enhancementStatusContainer?.let {
                            it.isVisible = true
                            it.bringToFront()
                            // KMK --> Only the failure line is actionable.
                            if (failed) {
                                it.setOnClickListener { retryFailedSpreadPages() }
                            } else {
                                it.setOnClickListener(null)
                            }
                            // KMK <--
                        }
                    }
                }
                if (text == null) return@launchIO
                delay(500)
                attempts++
            }
            withUIContext { enhancementStatusContainer?.isVisible = false }
        }
    }
    // KMK <--

    /**
     * A simple [TextView] that draws a black outline around its text so the status stays
     * readable over bright manga pages.
     */
    private class OutlineTextView(context: Context) : TextView(context) {
        private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = Color.BLACK
        }

        override fun onDraw(canvas: Canvas) {
            val text = text?.toString().orEmpty()
            if (text.isNotEmpty()) {
                outlinePaint.typeface = paint.typeface
                outlinePaint.textSize = paint.textSize
                outlinePaint.textAlign = paint.textAlign
                canvas.drawText(text, compoundPaddingLeft.toFloat(), baseline.toFloat(), outlinePaint)
            }
            super.onDraw(canvas)
        }
    }

    private fun process(page: ReaderPage, imageSource: BufferedSource): BufferedSource {
        if (viewer.config.dualPageRotateToFit) {
            return rotateDualPage(imageSource)
        }

        if (!viewer.config.dualPageSplit) {
            return imageSource
        }

        if (page is InsertPage) {
            return splitInHalf(imageSource)
        }

        val isDoublePage = ImageUtil.isWideImage(imageSource)
        if (!isDoublePage) {
            return imageSource
        }

        onPageSplit(page)

        return splitInHalf(imageSource)
    }

    private fun rotateDualPage(imageSource: BufferedSource): BufferedSource {
        val isDoublePage = ImageUtil.isWideImage(imageSource)
        return if (isDoublePage) {
            val rotation = if (viewer.config.dualPageRotateToFitInvert) -90f else 90f
            ImageUtil.rotateImage(imageSource, rotation)
        } else {
            imageSource
        }
    }

    private fun mergePages(imageSource: BufferedSource, imageSource2: BufferedSource?): BufferedSource {
        // KMK --> Which branch a spread takes decides whether the reader sees one page or two, so
        // say it out loud: every report of "the right half is black" has to be traceable to a line.
        logcat(LogPriority.DEBUG) {
            "PagerPageHolder: mergePages page=${page.index} extra=${extraPage?.index} " +
                "secondSource=${imageSource2 != null} fullPage=${page.fullPage} " +
                "secondFullPage=${extraPage?.fullPage}"
        }
        // KMK <--
        // Handle adding a center margin to wide images if requested
        if (imageSource2 == null) {
            return handleWideImage(imageSource)
        }

        if (page.fullPage) {
            // KMK --> A full-page entry never pairs with the next one, so the pager has to be told
            // to stop showing them together. Without the re-chunk the holder keeps a two-page slot
            // and the second half is simply never drawn.
            logcat(LogPriority.DEBUG) { "PagerPageHolder: page ${page.index} is fullPage, splitting spread" }
            splitDoublePages()
            // KMK <--
            return imageSource
        }
        if (ImageUtil.isAnimatedAndSupported(imageSource)) {
            page.fullPage = true
            splitDoublePages()
            return imageSource
        } else if (ImageUtil.isAnimatedAndSupported(imageSource2)) {
            page.isolatedPage = true
            extraPage?.fullPage = true
            splitDoublePages()
            return imageSource
        }

        val imageBitmap = decodeImage(imageSource)
        if (imageBitmap == null) {
            imageSource2.close()
            page.fullPage = true
            splitDoublePages()
            logcat(LogPriority.ERROR) {
                "Cannot combine pages: page ${page.index} could not be decoded, showing it alone"
            }
            return imageSource
        }

        scope.launch { progressIndicator?.setProgress(96) }
        if (imageBitmap.height < imageBitmap.width) {
            imageSource2.close()
            page.fullPage = true
            splitDoublePages()
            return imageSource
        }

        val imageBitmap2 = decodeImage(imageSource2)
        if (imageBitmap2 == null) {
            imageSource2.close()
            extraPage?.fullPage = true
            page.isolatedPage = true
            splitDoublePages()
            logcat(LogPriority.ERROR) {
                "Cannot combine pages: half ${extraPage?.index} of page ${page.index} could not be decoded"
            }
            return imageSource
        }

        scope.launch { progressIndicator?.setProgress(97) }
        if (imageBitmap2.height < imageBitmap2.width) {
            imageSource2.close()
            extraPage?.fullPage = true
            page.isolatedPage = true
            splitDoublePages()
            return imageSource
        }

        val isLTR = (viewer !is R2LPagerViewer) xor viewer.config.invertDoublePages
        val centerMargin = calculateCenterMargin(imageBitmap.height, imageBitmap2.height)

        imageSource.close()
        imageSource2.close()

        return ImageUtil.mergeBitmaps(imageBitmap, imageBitmap2, isLTR, centerMargin, viewer.config.pageCanvasColor) {
            updateProgress(it)
        }
    }

    private fun handleWideImage(imageSource: BufferedSource): BufferedSource {
        return if (
            !ImageUtil.isAnimatedAndSupported(imageSource) &&
            ImageUtil.isWideImage(imageSource) &&
            viewer.config.centerMarginType and PagerConfig.CenterMarginType.WIDE_PAGE_CENTER_MARGIN > 0 &&
            !viewer.config.imageCropBorders
        ) {
            ImageUtil.addHorizontalCenterMargin(imageSource, height, context)
        } else {
            imageSource
        }
    }

    private fun decodeImage(imageSource: BufferedSource): Bitmap? {
        return try {
            ImageDecoder.newInstance(imageSource.inputStream())?.decode()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Cannot decode image" }
            null
        }
    }

    private fun calculateCenterMargin(height: Int, height2: Int): Int {
        return if (viewer.config.centerMarginType and PagerConfig.CenterMarginType.DOUBLE_PAGE_CENTER_MARGIN > 0 &&
            !viewer.config.imageCropBorders
        ) {
            96 / (this.height.coerceAtLeast(1) / max(height, height2).coerceAtLeast(1)).coerceAtLeast(1)
        } else {
            0
        }
    }

    private fun updateProgress(progress: Int) {
        scope.launch {
            if (progress == 100) {
                progressIndicator?.hide()
            } else {
                progressIndicator?.setProgress(progress)
            }
        }
    }

    private fun splitDoublePages() {
        scope.launch {
            delay(100)
            viewer.splitDoublePages(page)
            if (extraPage?.fullPage == true || page.fullPage) {
                extraPage = null
            }
        }
    }

    private fun splitInHalf(imageSource: BufferedSource): BufferedSource {
        var side = when {
            viewer is L2RPagerViewer && page is InsertPage -> ImageUtil.Side.RIGHT
            viewer !is L2RPagerViewer && page is InsertPage -> ImageUtil.Side.LEFT
            viewer is L2RPagerViewer && page !is InsertPage -> ImageUtil.Side.LEFT
            viewer !is L2RPagerViewer && page !is InsertPage -> ImageUtil.Side.RIGHT
            else -> error("We should choose a side!")
        }

        if (viewer.config.dualPageInvert) {
            side = when (side) {
                ImageUtil.Side.RIGHT -> ImageUtil.Side.LEFT
                ImageUtil.Side.LEFT -> ImageUtil.Side.RIGHT
            }
        }

        val sideMargin = if ((viewer.config.centerMarginType and PagerConfig.CenterMarginType.DOUBLE_PAGE_CENTER_MARGIN) >
            0 &&
            viewer.config.doublePages &&
            !viewer.config.imageCropBorders
        ) {
            48
        } else {
            0
        }

        return ImageUtil.splitInHalf(imageSource, side, sideMargin)
    }

    private fun onPageSplit(page: ReaderPage) {
        val newPage = InsertPage(page)
        viewer.onPageSplit(page, newPage)
    }

    /**
     * Called when the page has an error.
     */
    private fun setError(error: Throwable?) {
        progressIndicator?.hide()
        showErrorLayout(error)
    }

    override fun onImageLoaded() {
        super.onImageLoaded()
        progressIndicator?.hide()
    }

    /**
     * Called when an image fails to decode.
     */
    override fun onImageLoadError(error: Throwable?) {
        super.onImageLoadError(error)
        setError(error)
    }

    /**
     * Called when an image is zoomed in/out.
     */
    override fun onScaleChanged(newScale: Float) {
        super.onScaleChanged(newScale)
        viewer.activity.hideMenu()
    }

    private fun showErrorLayout(error: Throwable?): ReaderErrorBinding {
        if (errorLayout == null) {
            errorLayout = ReaderErrorBinding.inflate(LayoutInflater.from(context), this, true)
            errorLayout?.actionRetry?.viewer = viewer
            errorLayout?.actionRetry?.setOnClickListener {
                page.chapter.pageLoader?.retryPage(page)
            }
        }

        val imageUrl = page.imageUrl
        errorLayout?.actionOpenInWebView?.isVisible = imageUrl != null
        if (imageUrl != null) {
            if (imageUrl.startsWith("http", true)) {
                errorLayout?.actionOpenInWebView?.viewer = viewer
                errorLayout?.actionOpenInWebView?.setOnClickListener {
                    val sourceId = viewer.activity.viewModel.manga?.source

                    val intent = WebViewActivity.newIntent(context, imageUrl, sourceId)
                    context.startActivity(intent)
                }
            }
        }

        errorLayout?.errorMessage?.text = with(context) { error?.formattedMessage }
            ?: context.stringResource(MR.strings.decode_image_error)

        errorLayout?.root?.isVisible = true
        return errorLayout!!
    }

    /**
     * Removes the decode error layout from the holder, if found.
     */
    private fun removeErrorLayout() {
        errorLayout?.root?.isVisible = false
        errorLayout = null
    }
}
