package com.github.jamal.sortedsetpaginator

import kotlinx.coroutines.*
import java.util.*

class SortedSetPaginator<T>(
    private val coroutineScope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val uiDispatcher: CoroutineDispatcher,
    private val requestFactory: suspend (page: Int, pivotNext: T?, pivotPrev: T?) -> List<T>,
    private val viewController: ViewController<T>,
    private val comparator: Comparator<T>? = null,
    defaultPages: Map<Int, List<T>>? = null
) {

    private val pages by lazy { sortedSetOf<Int>() }
    private val data by lazy { comparator?.let { sortedSetOf(it) } ?: sortedSetOf() }

    var currentPage = INCORRECT_PAGE
        private set
    var beforePage = currentPage
        private set

    val minPage get() = pages.firstOrNull() ?: INCORRECT_PAGE
    val maxPage get() = pages.lastOrNull() ?: INCORRECT_PAGE
    val isCorrectPages: Boolean
        get() = minPage != INCORRECT_PAGE && maxPage != INCORRECT_PAGE
                && currentPage != INCORRECT_PAGE

    init {
        defaultPages?.let {
            pages.addAll(it.keys)
            it.values.forEach { pageData -> data.addAll(pageData) }
        }
    }

    val isEmptyState get() = data.isEmpty()
    var jumpJob: Job? = null
        private set

    /** Прыжок на определенную страницу, используется также для загрузки стартовой/первой */
    fun jump(page: Int = FIRST_PAGE) {
        if (page < FIRST_PAGE) throw IllegalArgumentException("Jump to $page impossible")
        if (page == currentPage + 1) return loadNextPage()
        else if (page == currentPage - 1) return loadPrevPage()
        if (jumpJob != null) return
        jumpJob = coroutineScope.launch {
            viewController.showEmptyProgress(isEmptyState)

            release()
            loadPage(page)
            loadPageJobs.values.forEach { it.join() }

            jumpJob = null
            viewController.showEmptyProgress(isEmptyState)
        }
    }

    fun jumpBack() {
        if (beforePage == INCORRECT_PAGE) throw IllegalArgumentException("Jump to $beforePage impossible")
        jump(beforePage)
    }

    private var loadingNextPagesLazy: Lazy<*>? = null
    private val loadingNextPagesSafe get() = if (loadingNextPagesLazy?.isInitialized() == true) loadingNextPages else null
    val isLoadingNextPage get() = loadingNextPagesSafe?.isNotEmpty() == true
    private val loadingNextPages by lazy { mutableListOf<Int>() }
        .also { loadingNextPagesLazy = it }

    private var errorNextPagesLazy: Lazy<*>? = null
    private val errorNextPagesSafe get() = if (errorNextPagesLazy?.isInitialized() == true) errorNextPages else null
    private val errorNextPages by lazy { mutableListOf<Int>() }
        .also { loadingNextPagesLazy = it }

    /** Загружеает следующию страницу от последней существующией */
    fun loadNextPage() {
        if (currentPage < FIRST_PAGE) return jump()
        val nextPage = maxPage + 1
        loadingNextPages.add(nextPage)
        loadPage(nextPage)
    }

    private var loadingPrevPagesLazy: Lazy<*>? = null
    private val loadingPrevPagesSafe get() = if (loadingPrevPagesLazy?.isInitialized() == true) loadingPrevPages else null
    val isLoadingPrevPage get() = loadingPrevPagesSafe?.isNotEmpty() == true
    private val loadingPrevPages by lazy { mutableListOf<Int>() }
        .also { loadingPrevPagesLazy = it }

    private var errorPrevPagesLazy: Lazy<*>? = null
    private val errorPrevPagesSafe get() = if (errorPrevPagesLazy?.isInitialized() == true) errorPrevPages else null
    private val errorPrevPages by lazy { mutableListOf<Int>() }
        .also { loadingPrevPagesLazy = it }

    /** Загружеает предыдущию страницу от последней существующией */
    fun loadPrevPage() {
        if (currentPage <= FIRST_PAGE) return jump()
        val prevPage = minPage - 1
        if (prevPage > FIRST_PAGE) {
            loadingPrevPages.add(prevPage)
            loadPage(prevPage)
        } else {
            throw IllegalArgumentException("loadPrevPage for $prevPage impossible")
        }
    }

    val isRefreshingState get() = refreshAllJob != null || refreshJob != null
    var refreshAllJob: Job? = null
        private set

    fun refreshAll() {
        if (currentPage < FIRST_PAGE) return jump()
        if (isRefreshingState) return
        refreshAllJob = coroutineScope.launch {
            viewController.showRefreshProgress(show = isRefreshingState)

            pages.forEach { loadPage(it) }
            loadPageJobs.values.forEach { it.join() }
            refreshAllJob = null

            viewController.showRefreshProgress(show = isRefreshingState)
        }
    }

    private var refreshJob: Job? = null
    fun refresh() {
        if (currentPage < FIRST_PAGE) return jump()
        if (isRefreshingState) return
        refreshJob = coroutineScope.launch {
            viewController.showRefreshProgress(show = isRefreshingState)

            loadPage(currentPage)
            loadPageJobs.values.forEach { it.join() }
            refreshJob = null

            viewController.showRefreshProgress(show = isRefreshingState)
        }
    }

    fun release() {
        setCurrentPage(INCORRECT_PAGE)
        pages.clear()
        data.clear()
        loadingNextPagesSafe?.clear()
        loadingPrevPagesSafe?.clear()
        errorNextPagesSafe?.clear()
        errorPrevPagesSafe?.clear()
        loadPageJobsSafe?.values?.forEach { it.cancel() }
        loadPageJobsSafe?.clear()
        refreshAllJob = null
        refreshJob = null
    }

    fun add(element: T) {
        data.add(element)
        viewController.showDataOrState(data)
    }

    fun replaceFirstIf(item: T, predicate: (T) -> Boolean): T? {
        val removed = removeFirstIf(predicate)
        if (removed != null) add(item)
        viewController.showDataOrState(data)
        return removed
    }

    fun replaceFirstIf(item: () -> T, predicate: (T) -> Boolean): T? {
        val removed = removeFirstIf(predicate)
        if (removed != null) add(item())
        viewController.showDataOrState(data)
        return removed
    }

    fun removeFirstIf(predicate: (T) -> Boolean): T? {
        val each: Iterator<T> = data.iterator()
        while (each.hasNext()) {
            val next = each.next()
            if (predicate(next)) {
                data.remove(next)
                viewController.showDataOrState(data)
                return next
            }
        }
        return null
    }

    fun removeAllIf(predicate: (T) -> Boolean): Boolean {
        var removed = false
        val iterator: Iterator<T> = data.iterator()
        while (iterator.hasNext()) {
            val next = iterator.next()
            if (predicate(next)) {
                data.remove(next)
                removed = true
            }
        }
        viewController.showDataOrState(data)
        return removed
    }

    fun remove(element: T) {
        data.remove(element)
        viewController.showDataOrState(data)
    }

    private var loadPageJobsLazy: Lazy<*>? = null
    private val loadPageJobsSafe get() = if (loadPageJobsLazy?.isInitialized() == true) loadPageJobs else null
    private val loadPageJobs by lazy { hashMapOf<Int, Job>() }
        .also { loadPageJobsLazy = it }

    fun loadPageSilently(page: Int) = loadPage(page, silently = true)
    private fun loadPage(page: Int, silently: Boolean = false) {
        if (loadPageJobs[page]?.isActive == true) return
        loadPageJobs[page] = coroutineScope.launch(ioDispatcher) {
            try {
                val pivotNext = if (page > currentPage) data.lastOrNull() else null
                val pivotPrev = if (page < currentPage) data.firstOrNull() else null

                val requestData = requestFactory.invoke(page, pivotNext, pivotPrev)
                pages.add(page)
                data.addAll(requestData)

                errorNextPagesSafe?.remove(page)
                errorPrevPagesSafe?.remove(page)

                setCurrentPage(page)
                if (!silently) withContext(uiDispatcher) { viewController.showDataOrState(data) }
            } catch (e: Exception) {
                e.printStackTrace()
                if (page > currentPage) errorNextPages.add(page)
                else if (page < currentPage) errorPrevPages.add(page)
                else if (!silently) withContext(uiDispatcher) {
                    viewController.showDataOrState(data, e)
                }
            } finally {
                loadingNextPagesSafe?.remove(page)
                loadingPrevPagesSafe?.remove(page)
            }
        }
    }

    private fun setCurrentPage(page: Int) {
        beforePage = currentPage
        currentPage = page
    }

    companion object {
        private const val FIRST_PAGE = 1
        private const val INCORRECT_PAGE = -1
    }

    interface ViewController<T> {
        fun showEmptyProgress(show: Boolean) {}
        fun showDataOrState(data: TreeSet<T>, error: Throwable? = null) {}
        fun showRefreshProgress(show: Boolean) {}
    }
}

