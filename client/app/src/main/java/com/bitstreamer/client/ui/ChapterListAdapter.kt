package com.bitstreamer.client.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.TextView
import com.bitstreamer.client.R
import com.bitstreamer.client.discovery.ServerApi
import com.bitstreamer.client.playback.ChapterThumbnailLoader
import java.util.concurrent.TimeUnit

/**
 * Backs the chapter selector ListView: thumbnail + name + timestamp per row.
 * Thumbnails load asynchronously via [thumbnails]; rows are tagged with their
 * requested time so recycled views don't show a stale image.
 */
class ChapterListAdapter(
    context: Context,
    private val chapters: List<ServerApi.Chapter>,
    private val thumbnails: ChapterThumbnailLoader,
    private val durationMs: Long,
) : BaseAdapter() {

    private val inflater = LayoutInflater.from(context)

    override fun getCount(): Int = chapters.size
    override fun getItem(position: Int): ServerApi.Chapter = chapters[position]
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: inflater.inflate(R.layout.chapter_row, parent, false)
        val chapter = chapters[position]

        view.findViewById<TextView>(R.id.chapter_name).text = chapter.name
        view.findViewById<TextView>(R.id.chapter_time).text = formatTime(chapter.startMs)

        val thumb = view.findViewById<ImageView>(R.id.chapter_thumb)
        // A few seconds in avoids black fade-in frames, clamped inside the chapter.
        val ceiling = if (durationMs > 0) durationMs - 1000 else Long.MAX_VALUE
        val nextStart = chapters.getOrNull(position + 1)?.startMs ?: Long.MAX_VALUE
        val thumbTime = (chapter.startMs + THUMB_OFFSET_MS)
            .coerceAtMost(minOf(ceiling, nextStart - 1000))
            .coerceAtLeast(chapter.startMs)

        thumb.setImageDrawable(null)
        thumb.tag = thumbTime
        thumbnails.request(thumbTime) { bitmap ->
            if (bitmap != null && thumb.tag == thumbTime) {
                thumb.setImageBitmap(bitmap)
            }
        }
        return view
    }

    private fun formatTime(ms: Long): String {
        val h = TimeUnit.MILLISECONDS.toHours(ms)
        val m = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
        val s = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
        else String.format("%d:%02d", m, s)
    }

    companion object {
        private const val THUMB_OFFSET_MS = 5_000L
    }
}
