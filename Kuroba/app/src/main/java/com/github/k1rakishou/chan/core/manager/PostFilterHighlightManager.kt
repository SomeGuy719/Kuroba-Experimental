package com.github.k1rakishou.chan.core.manager

import com.github.k1rakishou.common.mutableMapWithCap
import com.github.k1rakishou.model.data.descriptor.PostDescriptor
import com.github.k1rakishou.model.data.filter.HighlightFilterKeyword
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

class PostFilterHighlightManager {
  private val lock = ReentrantReadWriteLock()

  private val currentHighlights = mutableMapOf<PostDescriptor, Set<HighlightFilterKeyword>>()

  fun contains(postDescriptor: PostDescriptor): Boolean {
    return lock.read { currentHighlights[postDescriptor]?.isNotEmpty() == true }
  }

  fun remove(postDescriptor: PostDescriptor) {
    lock.write { currentHighlights.remove(postDescriptor) }
  }

  fun store(postDescriptor: PostDescriptor, highlightFilterKeywords: Set<HighlightFilterKeyword>) {
    lock.write { currentHighlights[postDescriptor] = highlightFilterKeywords }
  }

  fun getHighlightFilterKeywordForDescriptor(
    postDescriptors: Collection<PostDescriptor>
  ): Map<PostDescriptor, Set<HighlightFilterKeyword>> {
    if (postDescriptors.isEmpty()) {
      return emptyMap()
    }

    return lock.read {
      val map = mutableMapWithCap<PostDescriptor, Set<HighlightFilterKeyword>>(postDescriptors.size)

      postDescriptors.forEach { postDescriptor ->
        map[postDescriptor] = currentHighlights[postDescriptor]
          ?.toSet()
          ?: emptySet()
      }

      return@read map
    }
  }

}