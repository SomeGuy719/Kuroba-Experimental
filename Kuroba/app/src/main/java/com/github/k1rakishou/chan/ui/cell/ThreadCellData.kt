package com.github.k1rakishou.chan.ui.cell

import com.github.k1rakishou.ChanSettings
import com.github.k1rakishou.chan.core.manager.ChanThreadViewableInfoManager
import com.github.k1rakishou.chan.core.manager.PostFilterManager
import com.github.k1rakishou.chan.ui.adapter.PostsFilter
import com.github.k1rakishou.chan.utils.BackgroundUtils
import com.github.k1rakishou.common.mutableListWithCap
import com.github.k1rakishou.core_themes.ChanTheme
import com.github.k1rakishou.model.data.descriptor.ChanDescriptor
import com.github.k1rakishou.model.data.descriptor.PostDescriptor
import com.github.k1rakishou.model.data.post.PostIndexed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ThreadCellData(
  private val chanThreadViewableInfoManager: ChanThreadViewableInfoManager,
  private val postFilterManager: PostFilterManager,
  initialTheme: ChanTheme
): Iterable<PostCellData> {
  private val postCellDataList: MutableList<PostCellData> = mutableListWithCap(64)
  private val selectedPosts: MutableSet<PostDescriptor> = mutableSetOf()
  private val highlightedPosts: MutableSet<PostDescriptor> = mutableSetOf()
  private val highlightedPostsByPostId: MutableSet<PostDescriptor> = mutableSetOf()
  private val highlightedPostsByTripcode: MutableSet<PostDescriptor> = mutableSetOf()

  private var _chanDescriptor: ChanDescriptor? = null
  private var postCellCallback: PostCellInterface.PostCellCallback? = null
  private var currentTheme: ChanTheme = initialTheme
  private var _inPopup: Boolean = false
  private var _compact: Boolean = false
  private var _postViewMode: ChanSettings.PostViewMode = ChanSettings.PostViewMode.LIST
  private var _markedNo: Long = -1L
  private var _showDivider: Boolean = true

  var error: String? = null
  var selectedPost: PostDescriptor? = null
  var lastSeenIndicatorPosition: Int = -1

  val chanDescriptor: ChanDescriptor?
    get() = _chanDescriptor

  override fun iterator(): Iterator<PostCellData> {
    return postCellDataList.iterator()
  }

  suspend fun updateThreadData(
    postCellCallback: PostCellInterface.PostCellCallback,
    chanDescriptor: ChanDescriptor,
    postIndexedList: List<PostIndexed>,
    theme: ChanTheme
  ) {
    this._chanDescriptor = chanDescriptor
    this.postCellCallback = postCellCallback
    this.currentTheme = theme

    val newPostCellDataList = withContext(Dispatchers.Default) {
      return@withContext postIndexedListToPostCellDataList(
        postCellCallback = postCellCallback,
        chanDescriptor = chanDescriptor,
        theme = theme,
        postIndexedList = postIndexedList
      )
    }

    this.postCellDataList.clear()
    this.postCellDataList.addAll(newPostCellDataList)

    this.lastSeenIndicatorPosition = getLastSeenIndicatorPosition(chanDescriptor) ?: -1
  }

  private suspend fun postIndexedListToPostCellDataList(
    postCellCallback: PostCellInterface.PostCellCallback,
    chanDescriptor: ChanDescriptor,
    theme: ChanTheme,
    postIndexedList: List<PostIndexed>
  ): List<PostCellData> {
    BackgroundUtils.ensureBackgroundThread()

    val resultList = mutableListWithCap<PostCellData>(postIndexedList.size)
    val fontSize = ChanSettings.fontSize.get().toInt()
    val boardViewMode = ChanSettings.boardViewMode.get()
    val boardPostsSortOrder = PostsFilter.Order.find(ChanSettings.boardOrder.get())
    val neverShowPages = ChanSettings.neverShowPages.get()

    postIndexedList.forEach { postIndexed ->
      val postDescriptor = postIndexed.post.postDescriptor

      val postCellData = PostCellData(
        chanDescriptor = chanDescriptor,
        post = postIndexed.post,
        postIndex = postIndexed.postIndex,
        fontSize = fontSize,
        stub = postFilterManager.getFilterStub(postDescriptor),
        theme = theme,
        inPopup = _inPopup,
        highlighted = isPostHighlighted(postDescriptor),
        postSelected = isPostSelected(postDescriptor),
        markedNo = _markedNo,
        showDivider = _showDivider,
        compact = _compact,
        postViewMode = _postViewMode,
        boardViewMode = boardViewMode,
        boardPostsSortOrder = boardPostsSortOrder,
        neverShowPages = neverShowPages,
        filterHash = postFilterManager.getFilterHash(postDescriptor),
        filterHighlightedColor = postFilterManager.getFilterHighlightedColor(postDescriptor)
      )

      postCellData.postCellCallback = postCellCallback
      postCellData.preload()

      resultList += postCellData
    }

    return resultList
  }

  fun isEmpty(): Boolean = postCellDataList.isEmpty()

  fun cleanup() {
    highlightedPosts.clear()
    highlightedPostsByPostId.clear()
    highlightedPostsByTripcode.clear()
    selectedPosts.clear()

    postCellDataList.forEach { postCellData -> postCellData.cleanup() }
    postCellDataList.clear()
    selectedPost = null
    lastSeenIndicatorPosition = -1
    _markedNo = -1
    error = null
  }

  fun setPostViewMode(postViewMode: ChanSettings.PostViewMode) {
    _postViewMode = postViewMode
    postCellDataList.forEach { postCellData -> postCellData.postViewMode = postViewMode }
  }

  fun setCompact(compact: Boolean) {
    _compact = compact
    postCellDataList.forEach { postCellData -> postCellData.compact = compact }
  }

  fun selectPosts(postDescriptors: Set<PostDescriptor>) {
    if (postDescriptors.isEmpty()) {
      selectedPosts.clear()
    } else {
      selectedPosts.addAll(postDescriptors)
    }

    updatePostSelection()
  }

  fun highlightPosts(postDescriptors: Set<PostDescriptor>) {
    if (postDescriptors.isEmpty()) {
      highlightedPosts.clear()
    } else {
      highlightedPosts.addAll(postDescriptors)
      highlightedPostsByPostId.clear()
      highlightedPostsByTripcode.clear()
    }

    updatePostHighlighting()
  }

  fun highlightPostsByPostId(postId: String?) {
    if (postId == null) {
      highlightedPostsByPostId.clear()
    } else {
      val postDescriptors = postCellDataList
        .filter { postCellData -> postCellData.post.posterId == postId }
        .map { postCellData -> postCellData.postDescriptor }

      highlightedPostsByPostId.addAll(postDescriptors)
      highlightedPosts.clear()
      highlightedPostsByTripcode.clear()
    }

    updatePostHighlighting()
  }

  fun highlightPostsByTripcode(tripcode: CharSequence?) {
    if (tripcode == null) {
      highlightedPostsByTripcode.clear()
    } else {
      val postDescriptors = postCellDataList
        .filter { postCellData -> postCellData.post.tripcode == tripcode }
        .map { postCellData -> postCellData.postDescriptor }

      highlightedPostsByTripcode.addAll(postDescriptors)
      highlightedPostsByPostId.clear()
      highlightedPosts.clear()
    }

    updatePostHighlighting()
  }

  fun getPostCellData(index: Int): PostCellData {
    return postCellDataList.get(getPostPosition(index))
  }

  fun getPostCellDataIndexToUpdate(postDescriptor: PostDescriptor): Int? {
    var postIndex = postCellDataList
      .indexOfFirst { postCellData -> postCellData.postDescriptor == postDescriptor }

    if (postIndex < 0) {
      return null
    }

    if (lastSeenIndicatorPosition in 0..postIndex) {
      ++postIndex;
    }

    if (postIndex < 0 && postIndex > postsCount()) {
      return null
    }

    return postIndex
  }

  fun getLastPostCellDataOrNull(): PostCellData? = postCellDataList.lastOrNull()

  fun postsCount(): Int {
    var size = postCellDataList.size

    if (showStatusView()) {
      size++
    }

    if (lastSeenIndicatorPosition >= 0) {
      size++
    }

    return size
  }

  private fun isPostHighlighted(postDescriptor: PostDescriptor): Boolean {
    return highlightedPosts.contains(postDescriptor)
      || highlightedPostsByPostId.contains(postDescriptor)
      || highlightedPostsByTripcode.contains(postDescriptor)
  }

  private fun isPostSelected(postDescriptor: PostDescriptor): Boolean {
    return selectedPosts.contains(postDescriptor)
  }

  private fun updatePostHighlighting() {
    postCellDataList.forEach { postCellData ->
      postCellData.highlighted = isPostHighlighted(postCellData.postDescriptor)
    }
  }

  private fun updatePostSelection() {
    postCellDataList.forEach { postCellData ->
      postCellData.postSelected = isPostSelected(postCellData.postDescriptor)
    }
  }

  private fun showStatusView(): Boolean {
    val chanDescriptor = postCellCallback?.currentChanDescriptor
    // the chanDescriptor can be null while this adapter is used between cleanup and the removal
    // of the recyclerview from the view hierarchy, although it's rare.
    return chanDescriptor != null
  }

  fun getPostPosition(position: Int): Int {
    var postPosition = position
    if (lastSeenIndicatorPosition in 0 until position) {
      postPosition--
    }

    return postPosition
  }

  fun getScrollPosition(displayPosition: Int): Int {
    var postPosition = displayPosition
    if (lastSeenIndicatorPosition in 0 until displayPosition) {
      postPosition++
    }

    return postPosition
  }

  private fun getLastSeenIndicatorPosition(chanDescriptor: ChanDescriptor?): Int? {
    if (chanDescriptor == null) {
      return null
    }

    return chanThreadViewableInfoManager.view(chanDescriptor) { chanThreadViewableInfoView ->
      if (chanThreadViewableInfoView.lastViewedPostNo >= 0) {
        // Do not process the last post, the indicator does not have to appear at the bottom
        var postIndex = 0
        val displayListSize = postCellDataList.size - 1

        while (postIndex < displayListSize) {
          val postCellData = postCellDataList.getOrNull(postIndex)
            ?: break

          if (postCellData.postNo == chanThreadViewableInfoView.lastViewedPostNo) {
            return@view postIndex + 1
          }

          postIndex++
        }

        // fallthrough
      }

      return@view null
    }
  }

}