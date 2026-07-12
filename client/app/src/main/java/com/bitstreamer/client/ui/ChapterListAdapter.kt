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
 * Backs the chapter selector ListView: name + timestamp per row, plus a
 * server-generated thumbnail when [thumbnails] is non-null. When it is null
 * (server has no ffmpeg) the thumbnail image is removed and rows are a compact
 * name + time list. Thumbnails load asynchronously by chapter index; rows are
 * tagged so recycled views don't show a stale image.
 */
class ChapterListAdapter(
    context: Context,
    private val chapters: List<ServerApi.Chapter>,
    private val thumbnails: ChapterThumbnailLoader?,
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
        if (thumbnails == null) {
            thumb.visibility = View.GONE // name-only rows when the server has no thumbnails
        } else {
            thumb.visibility = View.VISIBLE
            thumb.setImageDrawable(null)
            thumb.tag = position
            thumbnails.request(position) { bitmap ->
                if (bitmap != null && thumb.tag == position) {
                    thumb.setImageBitmap(bitmap)
                }
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
}
