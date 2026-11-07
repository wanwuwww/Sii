// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.sii.browser.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** PDF 离线阅读：PdfRenderer 逐页渲染，列表滚动时按需加载。 */
class PdfViewerFragment : Fragment() {

    private var renderer: PdfRenderer? = null
    private var descriptor: ParcelFileDescriptor? = null
    private var pageCount = 0
    private var renderWidth = 1080
    private var rowHeight = 0
    private val cache = object : android.util.LruCache<Int, Bitmap>(3) {
        override fun entryRemoved(
            evicted: Boolean, key: Int, oldValue: Bitmap, newValue: Bitmap?
        ) {
            if (evicted) oldValue.recycle()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        parent: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val context = requireContext()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(context.getColor(R.color.bg))
        }
        val path = arguments?.getString(ARG_PATH).orEmpty()
        root.addView(
            PageHeader.build(context, File(path).name, onBack = {
                parentFragmentManager.popBackStack()
            })
        )
        val list = ListView(context).apply {
            divider = null
            setBackgroundColor(Color.WHITE)
        }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        renderWidth = resources.displayMetrics.widthPixels
        viewLifecycleOwner.lifecycleScope.launch {
            val opened = withContext(Dispatchers.IO) {
                runCatching {
                    val fd = ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
                    val r = PdfRenderer(fd)
                    Triple(fd, r, r.pageCount)
                }.getOrNull()
            }
            if (opened == null) {
                Toast.makeText(context, R.string.readlater_file_missing, Toast.LENGTH_SHORT).show()
                parentFragmentManager.popBackStack()
                return@launch
            }
            descriptor = opened.first
            renderer = opened.second
            pageCount = opened.third
            if (pageCount > 0) {
                val first = opened.second.openPage(0)
                rowHeight = (renderWidth * first.height / first.width.toFloat()).toInt()
                first.close()
            }
            list.adapter = PageAdapter()
        }
        return root
    }

    private inner class PageAdapter : BaseAdapter() {
        override fun getCount(): Int = pageCount
        override fun getItem(position: Int): Any = position
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val image = (convertView as? ImageView) ?: ImageView(requireContext()).apply {
                scaleType = ImageView.ScaleType.FIT_XY
                setBackgroundColor(Color.WHITE)
                layoutParams = android.widget.AbsListView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, rowHeight
                )
            }
            image.tag = position
            val cached = cache.get(position)
            if (cached != null) {
                image.setImageBitmap(cached)
            } else {
                image.setImageDrawable(null)
                image.post {
                    if (image.tag != position) return@post
                    viewLifecycleOwner.lifecycleScope.launch {
                        val bitmap = withContext(Dispatchers.IO) { renderPage(position) }
                        if (bitmap == null) return@launch
                        cache.put(position, bitmap)
                        if (image.tag == position) image.setImageBitmap(bitmap)
                    }
                }
            }
            return image
        }
    }

    private fun renderPage(position: Int): Bitmap? = runCatching {
        val renderer = renderer ?: return null
        val page = synchronized(renderer) { renderer.openPage(position) }
        val scale = renderWidth.toFloat() / page.width
        val height = (page.height * scale).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(renderWidth, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        page.close()
        bitmap
    }.getOrNull()

    override fun onDestroyView() {
        cache.evictAll()
        runCatching { renderer?.close() }
        renderer = null
        runCatching { descriptor?.close() }
        descriptor = null
        super.onDestroyView()
    }

    companion object {
        private const val ARG_PATH = "path"

        fun newInstance(path: String): PdfViewerFragment = PdfViewerFragment().apply {
            arguments = Bundle().apply { putString(ARG_PATH, path) }
        }
    }
}
