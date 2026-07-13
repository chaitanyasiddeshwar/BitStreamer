package com.bitstreamer.client.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import com.bitstreamer.client.R
import com.bitstreamer.client.discovery.ServerApi
import java.util.Locale

/**
 * File-explorer screen for folder-mode servers. Browses the served folder tree
 * (recursively, server-capped at depth 3), navigating into subfolders on click
 * and up on Back. Selecting a playable file launches PlayerActivity with the
 * current folder's playable files as a manual next/previous playlist.
 */
class BrowserActivity : Activity() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var api: ServerApi
    private lateinit var serverName: String

    private lateinit var titleView: TextView
    private lateinit var pathView: TextView
    private lateinit var listView: ListView

    private var currentPath = ""
    private val entries = mutableListOf<ServerApi.FolderEntry>()
    private lateinit var adapter: EntryAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_browser)

        val base = intent.getStringExtra(EXTRA_BASE_URL).orEmpty()
        serverName = intent.getStringExtra(EXTRA_NAME).orEmpty()
        api = ServerApi(base)

        titleView = findViewById(R.id.browser_title)
        pathView = findViewById(R.id.browser_path)
        listView = findViewById(R.id.browser_list)

        adapter = EntryAdapter()
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ ->
            val entry = entries.getOrNull(position) ?: return@setOnItemClickListener
            if (entry.isDir) {
                currentPath = join(currentPath, entry.name)
                loadPath()
            } else {
                playFile(entry)
            }
        }

        titleView.text = serverName
        loadPath()
    }

    override fun onBackPressed() {
        if (currentPath.isEmpty()) {
            super.onBackPressed()
        } else {
            currentPath = parent(currentPath)
            loadPath()
        }
    }

    private fun loadPath() {
        pathView.text = if (currentPath.isEmpty()) "/" else "/$currentPath"
        Thread {
            val listed = try {
                api.list(currentPath)
            } catch (e: Exception) {
                emptyList()
            }
            mainHandler.post {
                entries.clear()
                entries.addAll(listed)
                adapter.notifyDataSetChanged()
                if (entries.isNotEmpty()) {
                    listView.requestFocus()
                    listView.setSelection(0)
                }
            }
        }.start()
    }

    private fun playFile(selected: ServerApi.FolderEntry) {
        val files = entries.filter { !it.isDir }
        val index = files.indexOfFirst { it.name == selected.name }.coerceAtLeast(0)
        val urls = ArrayList<String>(files.size)
        val titles = ArrayList<String>(files.size)
        val infoPaths = ArrayList<String>(files.size)
        for (f in files) {
            val rel = join(currentPath, f.name)
            urls.add(api.streamUrlForPath(rel))
            titles.add(f.name)
            infoPaths.add(rel)
        }
        startActivity(Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_FOLDER_MODE, true)
            putExtra(PlayerActivity.EXTRA_URL, urls[index])
            putExtra(PlayerActivity.EXTRA_TITLE, titles[index])
            putExtra(PlayerActivity.EXTRA_INFO_PATH, infoPaths[index])
            putStringArrayListExtra(PlayerActivity.EXTRA_PL_URLS, urls)
            putStringArrayListExtra(PlayerActivity.EXTRA_PL_TITLES, titles)
            putStringArrayListExtra(PlayerActivity.EXTRA_PL_INFO_PATHS, infoPaths)
            putExtra(PlayerActivity.EXTRA_PL_INDEX, index)
        })
    }

    // ---- path helpers ----

    private fun join(base: String, name: String): String =
        if (base.isEmpty()) name else "$base/$name"

    private fun parent(path: String): String {
        val i = path.lastIndexOf('/')
        return if (i < 0) "" else path.substring(0, i)
    }

    private fun humanSize(bytes: Long): String {
        if (bytes <= 0) return ""
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var s = bytes.toDouble()
        var i = 0
        while (s >= 1024 && i < units.size - 1) { s /= 1024; i++ }
        return if (i == 0) "$bytes B" else String.format(Locale.US, "%.1f %s", s, units[i])
    }

    // ---- list adapter ----

    private inner class EntryAdapter : BaseAdapter() {
        override fun getCount(): Int = entries.size
        override fun getItem(position: Int): Any = entries[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(this@BrowserActivity)
                .inflate(R.layout.browser_row, parent, false)
            val entry = entries[position]
            view.findViewById<ImageView>(R.id.row_icon)
                .setImageResource(if (entry.isDir) R.drawable.ic_folder else R.drawable.ic_file)
            view.findViewById<TextView>(R.id.row_name).text = entry.name
            view.findViewById<TextView>(R.id.row_size).text =
                if (entry.isDir) "" else humanSize(entry.sizeBytes)
            return view
        }
    }

    companion object {
        const val EXTRA_BASE_URL = "baseUrl"
        const val EXTRA_NAME = "name"
    }
}
