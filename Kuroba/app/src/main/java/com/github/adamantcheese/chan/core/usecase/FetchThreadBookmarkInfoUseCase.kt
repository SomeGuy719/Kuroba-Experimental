package com.github.adamantcheese.chan.core.usecase

import com.github.adamantcheese.chan.core.di.NetModule
import com.github.adamantcheese.chan.core.manager.BookmarksManager
import com.github.adamantcheese.chan.core.manager.SiteManager
import com.github.adamantcheese.chan.core.site.parser.ChanReader
import com.github.adamantcheese.chan.utils.Logger
import com.github.adamantcheese.common.ModularResult
import com.github.adamantcheese.common.ModularResult.Companion.Try
import com.github.adamantcheese.common.suspendCall
import com.github.adamantcheese.model.data.bookmark.ThreadBookmarkInfoObject
import com.github.adamantcheese.model.data.bookmark.ThreadBookmarkInfoPostObject
import com.github.adamantcheese.model.data.descriptor.ChanDescriptor
import com.google.gson.stream.JsonReader
import kotlinx.coroutines.*
import okhttp3.HttpUrl
import okhttp3.Request
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import kotlin.math.max

@Suppress("FoldInitializerAndIfToElvis")
class FetchThreadBookmarkInfoUseCase(
  private val isDevFlavor: Boolean,
  private val verboseLogsEnabled: Boolean,
  private val appScope: CoroutineScope,
  private val okHttpClient: NetModule.ProxiedOkHttpClient,
  private val siteManager: SiteManager,
  private val bookmarksManager: BookmarksManager
) : ISuspendUseCase<List<ChanDescriptor.ThreadDescriptor>, ModularResult<List<ThreadBookmarkFetchResult>>> {

  override suspend fun execute(parameter: List<ChanDescriptor.ThreadDescriptor>): ModularResult<List<ThreadBookmarkFetchResult>> {
    Logger.d(TAG, "FetchThreadBookmarkInfoUseCase.execute(${parameter.size})")
    return Try { fetchThreadBookmarkInfoBatched(parameter) }
  }

  private suspend fun fetchThreadBookmarkInfoBatched(
    watchingBookmarkDescriptors: List<ChanDescriptor.ThreadDescriptor>
  ): List<ThreadBookmarkFetchResult> {
    return watchingBookmarkDescriptors
      .chunked(BATCH_SIZE)
      .flatMap { chunk ->
        return@flatMap supervisorScope {
          return@supervisorScope chunk.map { threadDescriptor ->
            return@map appScope.async(Dispatchers.IO) {
              val site = siteManager.bySiteDescriptor(threadDescriptor.siteDescriptor())
              if (site == null) {
                Logger.e(TAG, "Site with descriptor ${threadDescriptor.siteDescriptor()} " +
                  "not found in siteRepository!")
                return@async null
              }

              val threadJsonEndpoint = site.endpoints().thread(threadDescriptor)

              return@async fetchThreadBookmarkInfo(
                threadDescriptor,
                threadJsonEndpoint,
                site.chanReader()
              )
            }
          }
        }
          .awaitAll()
          .filterNotNull()
      }
  }

  private suspend fun fetchThreadBookmarkInfo(
    threadDescriptor: ChanDescriptor.ThreadDescriptor,
    threadJsonEndpoint: HttpUrl,
    chanReader: ChanReader
  ): ThreadBookmarkFetchResult {
    if (verboseLogsEnabled) {
      Logger.d(TAG, "fetchThreadBookmarkInfo() threadJsonEndpoint = $threadJsonEndpoint")
    }

    val request = Request.Builder()
      .url(threadJsonEndpoint)
      .get()
      .build()

    val response = try {
      okHttpClient.proxiedClient.suspendCall(request)
    } catch (error: IOException) {
      return ThreadBookmarkFetchResult.Error(error, threadDescriptor)
    }

    if (!response.isSuccessful) {
      if (response.code == NOT_FOUND_STATUS) {
        return ThreadBookmarkFetchResult.NotFoundOnServer(threadDescriptor)
      }

      return ThreadBookmarkFetchResult.BadStatusCode(response.code, threadDescriptor)
    }

    val body = response.body
      ?: return ThreadBookmarkFetchResult.Error(IOException("Response has no body"), threadDescriptor)

    return body.byteStream().use { inputStream ->
      return@use JsonReader(InputStreamReader(inputStream, StandardCharsets.UTF_8))
        .use { jsonReader ->
          val postsCount = bookmarksManager.mapBookmark(threadDescriptor) { threadBookmarkView ->
            threadBookmarkView.postsCount()
          }

          if (postsCount == null) {
            return@use ThreadBookmarkFetchResult.AlreadyDeleted(threadDescriptor)
          }

          val threadBookmarkInfoObject = chanReader.readThreadBookmarkInfoObject(
            threadDescriptor,
            max(postsCount, ChanReader.DEFAULT_POST_LIST_CAPACITY),
            jsonReader
          ).safeUnwrap { error -> return@use ThreadBookmarkFetchResult.Error(error, threadDescriptor) }

          if (isDevFlavor) {
            ensureCorrectPostOrder(threadBookmarkInfoObject.simplePostObjects)
          }

          return@use ThreadBookmarkFetchResult.Success(threadBookmarkInfoObject, threadDescriptor)
        }
    }
  }

  private fun ensureCorrectPostOrder(simplePostObjects: List<ThreadBookmarkInfoPostObject>) {
    if (simplePostObjects.isEmpty()) {
      return
    }

    var prevPostNo = 0L

    simplePostObjects.forEach { threadBookmarkInfoPostObject ->
      val currentPostNo = threadBookmarkInfoPostObject.postNo()

      check(prevPostNo <= currentPostNo) {
        "Incorrect post ordering detected: (prevPostNo=$prevPostNo, currentPostNo=${currentPostNo}"
      }

      prevPostNo = currentPostNo
    }
  }

  companion object {
    private const val TAG = "FetchThreadBookmarkInfoUseCase"
    private const val BATCH_SIZE = 8
    private const val NOT_FOUND_STATUS = 404
  }
}

sealed class ThreadBookmarkFetchResult(val threadDescriptor: ChanDescriptor.ThreadDescriptor) {
  class Error(
    val error: Throwable,
    threadDescriptor: ChanDescriptor.ThreadDescriptor
  ) : ThreadBookmarkFetchResult(threadDescriptor)

  class AlreadyDeleted(
    threadDescriptor: ChanDescriptor.ThreadDescriptor
  ) : ThreadBookmarkFetchResult(threadDescriptor)

  class NotFoundOnServer(
    threadDescriptor: ChanDescriptor.ThreadDescriptor
  ) : ThreadBookmarkFetchResult(threadDescriptor)

  class BadStatusCode(
    val statusCode: Int,
    threadDescriptor: ChanDescriptor.ThreadDescriptor
  ) : ThreadBookmarkFetchResult(threadDescriptor)

  class Success(
    val threadBookmarkInfoObject: ThreadBookmarkInfoObject,
    threadDescriptor: ChanDescriptor.ThreadDescriptor
  ) : ThreadBookmarkFetchResult(threadDescriptor)
}