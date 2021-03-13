/*
 * KurobaEx - *chan browser https://github.com/K1rakishou/Kuroba-Experimental/
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.github.k1rakishou.chan.ui.cell

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.view.View
import android.widget.ImageView
import android.widget.RelativeLayout
import android.widget.TextView
import com.github.k1rakishou.ChanSettings
import com.github.k1rakishou.chan.R
import com.github.k1rakishou.chan.core.manager.PostFilterManager
import com.github.k1rakishou.chan.ui.theme.widget.ColorizableDivider
import com.github.k1rakishou.chan.ui.view.ThumbnailView
import com.github.k1rakishou.chan.ui.view.floating_menu.FloatingListMenuItem
import com.github.k1rakishou.chan.utils.AppModuleAndroidUtils
import com.github.k1rakishou.common.AndroidUtils
import com.github.k1rakishou.core_themes.ThemeEngine
import com.github.k1rakishou.core_themes.ThemeEngine.ThemeChangesListener
import com.github.k1rakishou.model.data.post.ChanPost
import com.github.k1rakishou.model.data.post.ChanPostImage
import java.util.*
import javax.inject.Inject

class PostStubCell : RelativeLayout, PostCellInterface, View.OnClickListener, ThemeChangesListener {
  @Inject
  lateinit var themeEngine: ThemeEngine
  @Inject
  lateinit var postFilterManager: PostFilterManager

  private var postCellData: PostCellData? = null
  private var callback: PostCellInterface.PostCellCallback? = null

  private lateinit var title: TextView
  private lateinit var divider: ColorizableDivider
  private lateinit var options: ImageView

  constructor(context: Context?) : super(context) {
    init()
  }

  constructor(context: Context?, attrs: AttributeSet?) : super(context, attrs) {
    init()
  }

  constructor(context: Context?, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr) {
    init()
  }

  private fun init() {
    AppModuleAndroidUtils.extractActivityComponent(context)
      .inject(this)
  }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    themeEngine.addListener(this)
  }

  override fun onDetachedFromWindow() {
    super.onDetachedFromWindow()
    themeEngine.removeListener(this)
  }

  override fun onClick(v: View) {
    if (v === this) {
      if (callback != null) {
        callback?.onPostClicked(postCellData!!.postDescriptor)
      }
    }
  }

  override fun onPostRecycled(isActuallyRecycling: Boolean) {
    unbindPost(isActuallyRecycling)
  }

  private fun unbindPost(isActuallyRecycling: Boolean) {
    if (callback != null) {
      callback?.onPostUnbind(postCellData!!.postDescriptor, isActuallyRecycling)
    }

    callback = null
    postCellData = null
  }

  override fun postDataDiffers(postCellData: PostCellData): Boolean {
    return postCellData != this.postCellData
  }

  override fun setPost(postCellData: PostCellData) {
    val postDataDiffers = postDataDiffers(postCellData)
    if (!postDataDiffers) {
      return
    }

    preBindPost(postCellData)

    this.postCellData = postCellData
    this.callback = postCellData.postCellCallback

    bindPost(postCellData)

    onThemeChanged()
  }

  override fun getPost(): ChanPost? {
    return postCellData?.post
  }

  override fun getThumbnailView(postImage: ChanPostImage): ThumbnailView? {
    return null
  }

  override fun hasOverlappingRendering(): Boolean {
    return false
  }

  private fun preBindPost(postCellData: PostCellData) {
    if (this.postCellData != null) {
      return
    }

    title = findViewById(R.id.title)
    options = findViewById(R.id.options)
    divider = findViewById(R.id.divider)

    AndroidUtils.setBoundlessRoundRippleBackground(options)

    val textSizeSp = postCellData.fontSize
    title.textSize = textSizeSp.toFloat()

    val paddingPx = AppModuleAndroidUtils.dp((textSizeSp - 6).toFloat())
    title.setPadding(paddingPx, 0, 0, 0)

    val dividerParams = divider.layoutParams as LayoutParams
    dividerParams.leftMargin = paddingPx
    dividerParams.rightMargin = paddingPx
    divider.layoutParams = dividerParams

    setOnClickListener(this)

    options.setOnClickListener({
      val items = ArrayList<FloatingListMenuItem>()

      if (callback != null) {
        callback!!.onPopulatePostOptions(postCellData.post, items)

        if (items.size > 0) {
          callback!!.showPostOptions(postCellData.post, postCellData.inPopup, items)
        }
      }
    })
  }

  private fun bindPost(postCellData: PostCellData) {
    if (callback == null) {
      throw NullPointerException("Callback is null during bindPost()")
    }

    title.text = postCellData.postTitle

    val isGridOrStagger = (postCellData.postViewMode === ChanSettings.PostViewMode.GRID
      || postCellData.postViewMode === ChanSettings.PostViewMode.STAGGER)

    divider.visibility = if (isGridOrStagger) {
      GONE
    } else {
      if (postCellData.showDivider) {
        VISIBLE
      } else {
        GONE
      }
    }

    setOnClickListener {
      if (callback != null) {
        callback!!.onUnhidePostClick(postCellData.post)
      }
    }

    if (callback != null) {
      callback!!.onPostBind(postCellData.postDescriptor)
    }
  }

  override fun onThemeChanged() {
    if (::title.isInitialized) {
      title.setTextColor(themeEngine.chanTheme.textColorSecondary)
    }

    if (::options.isInitialized) {
      options.imageTintList = ColorStateList.valueOf(themeEngine.chanTheme.postDetailsColor)
    }
  }
}