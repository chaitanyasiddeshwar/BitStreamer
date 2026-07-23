package com.bitstreamer.client.ui

import android.app.AlertDialog
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
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
 *
 * In multi-root mode, the screen first shows a list of root directories. The
 * user selects a root with D-pad OK, then browses it exactly like single-folder
 * mode. Back from the root-level of a root returns to the roots list.
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
    private var selectOnLoad: String? = null

    // Multi-root mode state.
    private var multiRoot = false
    private var rootIndices: ArrayList<Int>? = null
    private var rootNames: ArrayList<String>? = null
    private var currentRoot: Int? = null // null = showing roots list; non-null = inside a root

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_browser)

        val base = intent.getStringExtra(EXTRA_BASE_URL).orEmpty()
        serverName = intent.getStringExtra(EXTRA_NAME).orEmpty()
        api = ServerApi(base)

        // Check for multi-root extras.
        rootIndices = intent.getIntegerArrayListExtra(EXTRA_ROOT_INDICES)
        rootNames = intent.getStringArrayListExtra(EXTRA_ROOT_NAMES)
        multiRoot = rootIndices != null && rootNames != null && rootIndices!!.isNotEmpty()

        titleView = findViewById(R.id.browser_title)
        pathView = findViewById(R.id.browser_path)
        listView = findViewById(R.id.browser_list)

        if (savedInstanceState != null) {
            currentPath = savedInstanceState.getString("currentPath", "")
            selectOnLoad = savedInstanceState.getString("selectOnLoad")
            if (multiRoot) {
                currentRoot = if (savedInstanceState.containsKey("currentRoot")) {
                    savedInstanceState.getInt("currentRoot")
                } else {
                    null
                }
            }
        }

        adapter = EntryAdapter()
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ ->
            if (multiRoot && currentRoot == null) {
                // Selecting a root from the roots list.
                val idx = rootIndices?.getOrNull(position) ?: return@setOnItemClickListener
                currentRoot = idx
                currentPath = ""
                loadPath()
            } else {
                val entry = entries.getOrNull(position) ?: return@setOnItemClickListener
                if (entry.isDir) {
                    currentPath = join(currentPath, entry.name)
                    loadPath()
                } else {
                    playFile(entry)
                }
            }
        }

        titleView.text = serverName
        if (multiRoot && currentRoot == null) {
            showRootsList()
        } else {
            loadPath()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("currentPath", currentPath)
        if (multiRoot) {
            val root = currentRoot
            if (root != null) {
                outState.putInt("currentRoot", root)
            }
        }
        val position = listView.selectedItemPosition
        if (position != AdapterView.INVALID_POSITION) {
            if (multiRoot && currentRoot == null) {
                // On the roots list, save the root name for re-selection.
                outState.putString("selectOnLoad", rootNames?.getOrNull(position))
            } else {
                val entry = entries.getOrNull(position)
                if (entry != null) {
                    outState.putString("selectOnLoad", entry.name)
                }
            }
        }
    }

    override fun onBackPressed() {
        if (multiRoot && currentRoot != null && currentPath.isEmpty()) {
            // Back from root-level of a root → show roots list.
            selectOnLoad = rootNames?.getOrNull(rootIndices?.indexOf(currentRoot!!) ?: -1)
            currentRoot = null
            showRootsList()
        } else if (currentPath.isEmpty()) {
            super.onBackPressed()
        } else {
            val lastSlash = currentPath.lastIndexOf('/')
            selectOnLoad = if (lastSlash < 0) currentPath else currentPath.substring(lastSlash + 1)
            currentPath = parent(currentPath)
            loadPath()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            // Menu key only works when inside a root (not on roots list).
            if (multiRoot && currentRoot == null) return super.onKeyDown(keyCode, event)
            val position = listView.selectedItemPosition
            if (position != AdapterView.INVALID_POSITION) {
                val entry = entries.getOrNull(position)
                if (entry != null && !entry.isDir) {
                    fetchInfoAndShowMenu(entry)
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun fetchInfoAndShowMenu(entry: ServerApi.FolderEntry) {
        val rel = join(currentPath, entry.name)
        val progressDialog = AlertDialog.Builder(this)
            .setMessage("Reading file metadata...")
            .setCancelable(false)
            .show()

        Thread {
            val info = try {
                api.getInfo(rel, currentRoot)
            } catch (e: Exception) {
                null
            }
            mainHandler.post {
                progressDialog.dismiss()
                if (info != null) {
                    showFileMenu(entry, info.dvProfile >= 0, info.storyboardAvailable)
                } else {
                    android.widget.Toast.makeText(this, "Failed to read file metadata.", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun showFileMenu(entry: ServerApi.FolderEntry, isDv: Boolean, storyboardAvailable: Boolean) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 30, 40, 10)
        }

        val cbPreviews = CheckBox(this).apply {
            text = "Generate Seekbar Previews"
            visibility = if (storyboardAvailable) View.VISIBLE else View.GONE
        }
        container.addView(cbPreviews)

        val options = if (isDv) {
            arrayOf(
                "Play Normally",
                "Strip DV and Play",
                "Convert to DV8 and Play"
            )
        } else {
            arrayOf(
                "Play Normally"
            )
        }

        var dialog: AlertDialog? = null

        val listView = ListView(this)
        listView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, options)
        listView.setOnItemClickListener { _, _, which, _ ->
            dialog?.dismiss()
            val forceStrip = if (isDv) (which == 1) else false
            val convertDv8 = if (isDv) (which == 2) else false
            val genPreviews = cbPreviews.isChecked && storyboardAvailable
            playFile(
                entry,
                forceStripDv = forceStrip,
                convertDv8 = convertDv8,
                generatePreviews = genPreviews
            )
        }
        container.addView(listView)

        dialog = AlertDialog.Builder(this)
            .setTitle(entry.name)
            .setView(container)
            .create()
        dialog.show()
    }

    private fun playFile(
        selected: ServerApi.FolderEntry,
        forceStripDv: Boolean = false,
        convertDv8: Boolean = false,
        generatePreviews: Boolean = false
    ) {
        val files = entries.filter { !it.isDir }
        val index = files.indexOfFirst { it.name == selected.name }.coerceAtLeast(0)
        val urls = ArrayList<String>(files.size)
        val titles = ArrayList<String>(files.size)
        val infoPaths = ArrayList<String>(files.size)
        for (f in files) {
            val rel = join(currentPath, f.name)
            urls.add(api.streamUrlForPath(rel, currentRoot))
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
            if (forceStripDv) putExtra(PlayerActivity.EXTRA_FORCE_STRIP_DV, true)
            if (convertDv8) putExtra(PlayerActivity.EXTRA_CONVERT_DV8, true)
            if (generatePreviews) putExtra(PlayerActivity.EXTRA_GENERATE_PREVIEWS, true)
            currentRoot?.let { putExtra(PlayerActivity.EXTRA_ROOT_INDEX, it) }
        })
    }

    /** Shows the roots list (multi-root mode only). */
    private fun showRootsList() {
        titleView.text = serverName
        pathView.text = "Select a folder"
        entries.clear()
        adapter.notifyDataSetChanged()

        // Replace the adapter temporarily with a roots adapter.
        val names = rootNames ?: return
        val rootAdapter = object : BaseAdapter() {
            override fun getCount(): Int = names.size
            override fun getItem(position: Int): Any = names[position]
            override fun getItemId(position: Int): Long = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView ?: LayoutInflater.from(this@BrowserActivity)
                    .inflate(R.layout.browser_row, parent, false)
                view.findViewById<ImageView>(R.id.row_icon)
                    .setImageResource(R.drawable.ic_folder)
                view.findViewById<TextView>(R.id.row_name).text = names[position]
                view.findViewById<TextView>(R.id.row_size).text = ""
                return view
            }
        }
        listView.adapter = rootAdapter
        if (names.isNotEmpty()) {
            listView.requestFocus()
            val targetIndex = selectOnLoad?.let { targetName ->
                names.indexOfFirst { it == targetName }
            }?.takeIf { it >= 0 } ?: 0
            listView.setSelection(targetIndex)
            selectOnLoad = null
        }
    }

    private fun loadPath() {
        // Restore the entries adapter if coming from roots list.
        if (listView.adapter !== adapter) {
            listView.adapter = adapter
        }

        val rootName = if (multiRoot && currentRoot != null) {
            val idx = rootIndices?.indexOf(currentRoot!!) ?: -1
            rootNames?.getOrNull(idx) ?: ""
        } else ""
        if (multiRoot && rootName.isNotEmpty()) {
            titleView.text = "$serverName — $rootName"
        } else {
            titleView.text = serverName
        }
        pathView.text = if (currentPath.isEmpty()) "/" else "/$currentPath"

        Thread {
            val listed = try {
                api.list(currentPath, currentRoot)
            } catch (e: Exception) {
                emptyList()
            }
            mainHandler.post {
                entries.clear()
                entries.addAll(listed)
                adapter.notifyDataSetChanged()
                if (entries.isNotEmpty()) {
                    listView.requestFocus()
                    val targetIndex = selectOnLoad?.let { targetName ->
                        entries.indexOfFirst { it.name == targetName }
                    }?.takeIf { it >= 0 } ?: 0
                    listView.setSelection(targetIndex)
                    selectOnLoad = null
                }
            }
        }.start()
    }

    private fun playFile(selected: ServerApi.FolderEntry, forceStripDv: Boolean = false, generatePreviews: Boolean = false) {
        val files = entries.filter { !it.isDir }
        val index = files.indexOfFirst { it.name == selected.name }.coerceAtLeast(0)
        val urls = ArrayList<String>(files.size)
        val titles = ArrayList<String>(files.size)
        val infoPaths = ArrayList<String>(files.size)
        for (f in files) {
            val rel = join(currentPath, f.name)
            urls.add(api.streamUrlForPath(rel, currentRoot))
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
            if (forceStripDv) putExtra(PlayerActivity.EXTRA_FORCE_STRIP_DV, true)
            if (generatePreviews) putExtra(PlayerActivity.EXTRA_GENERATE_PREVIEWS, true)
            currentRoot?.let { putExtra(PlayerActivity.EXTRA_ROOT_INDEX, it) }
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
        const val EXTRA_ROOT_INDICES = "rootIndices"
        const val EXTRA_ROOT_NAMES = "rootNames"
    }
}
