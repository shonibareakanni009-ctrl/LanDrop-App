package com.landrop.share

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.io.File
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var homeScreen: View
    private lateinit var browseScreen: View
    private lateinit var rvCategories: RecyclerView
    private lateinit var rvContent: RecyclerView
    private lateinit var progress: ProgressBar
    private lateinit var emptyState: View
    private lateinit var tvEmptyMsg: TextView
    private lateinit var tvBrowseTitle: TextView
    private lateinit var tvBrowseHint: TextView
    private lateinit var btnBack: ImageButton
    private lateinit var btnSelectMode: MaterialButton
    private lateinit var shareBar: View
    private lateinit var statusBar: View
    private lateinit var btnToggle: MaterialButton
    private lateinit var btnCopy: MaterialButton
    private lateinit var tvUrl: TextView
    private lateinit var tvSelectedCount: TextView
    private lateinit var ivQrSmall: ImageView

    private val selected = linkedMapOf<String, ShareItem>()
    private var isSharing = false
    private var selectMode = false
    private var currentCategory = -1
    private var serverUrl: String? = null
    private val io = Executors.newSingleThreadExecutor()

    private val categories = listOf(
        Category(0, "Photos", android.R.drawable.ic_menu_gallery),
        Category(1, "Videos", android.R.drawable.ic_menu_slideshow),
        Category(2, "Music", android.R.drawable.ic_media_play),
        Category(3, "Apps", android.R.drawable.ic_menu_manage),
        Category(4, "Files", android.R.drawable.ic_menu_save)
    )

    private val requestPerms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refreshHomeCounts() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        homeScreen = findViewById(R.id.homeScreen)
        browseScreen = findViewById(R.id.browseScreen)
        rvCategories = findViewById(R.id.rvCategories)
        rvContent = findViewById(R.id.rvContent)
        progress = findViewById(R.id.progress)
        emptyState = findViewById(R.id.emptyState)
        tvEmptyMsg = findViewById(R.id.tvEmptyMsg)
        tvBrowseTitle = findViewById(R.id.tvBrowseTitle)
        tvBrowseHint = findViewById(R.id.tvBrowseHint)
        btnBack = findViewById(R.id.btnBack)
        btnSelectMode = findViewById(R.id.btnSelectMode)
        shareBar = findViewById(R.id.shareBar)
        statusBar = findViewById(R.id.statusBar)
        btnToggle = findViewById(R.id.btnToggle)
        btnCopy = findViewById(R.id.btnCopy)
        tvUrl = findViewById(R.id.tvUrl)
        tvSelectedCount = findViewById(R.id.tvSelectedCount)
        ivQrSmall = findViewById(R.id.ivQrSmall)

        rvCategories.layoutManager = GridLayoutManager(this, 2)
        rvCategories.adapter = CategoryAdapter(categories) { openCategory(it.id) }

        btnBack.setOnClickListener { showHome() }
        btnSelectMode.setOnClickListener { toggleSelectMode() }
        btnToggle.setOnClickListener {
            if (isSharing) stopSharing() else startSharing()
        }
        btnCopy.setOnClickListener {
            serverUrl?.let {
                val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("LanDrop", it))
                Toast.makeText(this, "Link copied", Toast.LENGTH_SHORT).show()
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    selectMode -> {
                        selectMode = false
                        updateSelectModeUi()
                        rvContent.adapter?.notifyDataSetChanged()
                    }
                    browseScreen.visibility == View.VISIBLE -> showHome()
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })

        ensurePerms()
        showHome()
        refreshHomeCounts()
        updateSelectedUi()
    }

    private fun ensurePerms() {
        val need = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            listOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO
            ).forEach {
                if (ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED)
                    need.add(it)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) need.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (need.isNotEmpty()) requestPerms.launch(need.toTypedArray())
    }

    private fun showHome() {
        homeScreen.visibility = View.VISIBLE
        browseScreen.visibility = View.GONE
        currentCategory = -1
        selectMode = false
        if (!isSharing) selected.clear()
        updateSelectModeUi()
        updateSelectedUi()
        refreshHomeCounts()
    }

    private fun openCategory(id: Int) {
        currentCategory = id
        selectMode = false
        homeScreen.visibility = View.GONE
        browseScreen.visibility = View.VISIBLE
        tvBrowseTitle.text = categories[id].title
        updateSelectModeUi()
        loadCategory(id)
    }

    private fun toggleSelectMode() {
        selectMode = !selectMode
        if (!selectMode && !isSharing) selected.clear()
        updateSelectModeUi()
        rvContent.adapter?.notifyDataSetChanged()
        updateSelectedUi()
    }

    private fun updateSelectModeUi() {
        btnSelectMode.text = if (selectMode) "Cancel" else "Select"
        tvBrowseHint.text = if (selectMode) "Tap items to select for sharing" else "Tap to open · Select to share"
        shareBar.visibility = if (selectMode || isSharing || selected.isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun refreshHomeCounts() {
        io.execute {
            val counts = listOf(
                loadMedia(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false).size,
                loadMedia(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true).size,
                loadAudio().size,
                loadApps().size,
                loadFiles().size
            )
            runOnUiThread {
                (rvCategories.adapter as? CategoryAdapter)?.updateCounts(counts)
            }
        }
    }

    private fun loadCategory(id: Int) {
        progress.visibility = View.VISIBLE
        emptyState.visibility = View.GONE
        rvContent.adapter = null
        io.execute {
            val items = when (id) {
                0 -> loadMedia(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false)
                1 -> loadMedia(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
                2 -> loadAudio()
                3 -> loadApps()
                else -> loadFiles()
            }
            runOnUiThread {
                progress.visibility = View.GONE
                if (items.isEmpty()) {
                    emptyState.visibility = View.VISIBLE
                    tvEmptyMsg.text = when (id) {
                        0 -> "No photos found on this phone"
                        1 -> "No videos found on this phone"
                        2 -> "No music found on this phone"
                        3 -> "No installed apps found"
                        else -> "No documents in Download or Documents"
                    }
                    return@runOnUiThread
                }
                emptyState.visibility = View.GONE
                when (id) {
                    0, 1 -> {
                        rvContent.layoutManager = GridLayoutManager(this, 2)
                        rvContent.adapter = MediaAdapter(items)
                    }
                    3 -> {
                        rvContent.layoutManager = GridLayoutManager(this, 4)
                        rvContent.adapter = AppAdapter(items)
                    }
                    else -> {
                        rvContent.layoutManager = LinearLayoutManager(this)
                        rvContent.adapter = ListAdapter(items)
                    }
                }
            }
        }
    }

    private fun onItemTap(item: ShareItem) {
        if (selectMode) {
            toggleSelect(item)
        } else {
            when (item.type) {
                ShareItem.Type.PHOTO, ShareItem.Type.VIDEO, ShareItem.Type.MUSIC -> openViewer(item)
                ShareItem.Type.APP, ShareItem.Type.FILE -> {
                    // Enter select mode and select this item
                    selectMode = true
                    toggleSelect(item)
                    updateSelectModeUi()
                    rvContent.adapter?.notifyDataSetChanged()
                }
            }
        }
    }

    private fun openViewer(item: ShareItem) {
        val type = when (item.type) {
            ShareItem.Type.PHOTO -> ViewerActivity.TYPE_PHOTO
            ShareItem.Type.VIDEO -> ViewerActivity.TYPE_VIDEO
            ShareItem.Type.MUSIC -> ViewerActivity.TYPE_MUSIC
            else -> return
        }
        startActivity(Intent(this, ViewerActivity::class.java).apply {
            putExtra(ViewerActivity.EXTRA_TYPE, type)
            putExtra(ViewerActivity.EXTRA_NAME, item.name)
            putExtra(ViewerActivity.EXTRA_PATH, item.path)
            item.mediaUri?.let { putExtra(ViewerActivity.EXTRA_URI, it.toString()) }
            if (item.meta.isNotEmpty()) putExtra(ViewerActivity.EXTRA_ARTIST, item.meta)
        })
    }

    private fun loadApps(): List<ShareItem> {
        val pm = packageManager
        return pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
            .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
            .sortedBy { it.loadLabel(pm).toString().lowercase() }
            .map { app ->
                val label = app.loadLabel(pm).toString()
                val src = File(app.sourceDir)
                val icon = try { app.loadIcon(pm) } catch (_: Exception) { null }
                ShareItem(
                    id = "apk:${app.packageName}",
                    name = "$label.apk",
                    path = app.sourceDir,
                    size = if (src.exists()) src.length() else 0L,
                    type = ShareItem.Type.APP,
                    icon = icon
                )
            }
    }

    private fun loadMedia(uri: Uri, isVideo: Boolean): List<ShareItem> {
        val list = mutableListOf<ShareItem>()
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATA,
            if (isVideo) MediaStore.Video.Media.DURATION else MediaStore.MediaColumns._ID
        )
        try {
            contentResolver.query(uri, projection, null, null, "${MediaStore.MediaColumns.DATE_ADDED} DESC")
                ?.use { c ->
                    val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    val sizeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                    val dataCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                    val durCol = if (isVideo) c.getColumnIndex(MediaStore.Video.Media.DURATION) else -1
                    var count = 0
                    while (c.moveToNext() && count < 400) {
                        val id = c.getLong(idCol)
                        val name = c.getString(nameCol) ?: "media"
                        val size = c.getLong(sizeCol)
                        val path = c.getString(dataCol) ?: continue
                        if (!File(path).exists()) continue
                        val contentUri = Uri.withAppendedPath(uri, id.toString())
                        val dur = if (durCol >= 0) c.getLong(durCol) else 0L
                        list.add(
                            ShareItem(
                                id = "media:$id",
                                name = name,
                                path = path,
                                size = size,
                                type = if (isVideo) ShareItem.Type.VIDEO else ShareItem.Type.PHOTO,
                                mediaUri = contentUri,
                                durationMs = dur
                            )
                        )
                        count++
                    }
                }
        } catch (_: Exception) {}
        return list
    }

    private fun loadAudio(): List<ShareItem> {
        val list = mutableListOf<ShareItem>()
        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.ARTIST
        )
        try {
            contentResolver.query(uri, projection, null, null, "${MediaStore.Audio.Media.DATE_ADDED} DESC")
                ?.use { c ->
                    val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                    val nameCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                    val sizeCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                    val dataCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                    val durCol = c.getColumnIndex(MediaStore.Audio.Media.DURATION)
                    val artistCol = c.getColumnIndex(MediaStore.Audio.Media.ARTIST)
                    var count = 0
                    while (c.moveToNext() && count < 400) {
                        val path = c.getString(dataCol) ?: continue
                        if (!File(path).exists()) continue
                        val name = c.getString(nameCol) ?: "audio"
                        val artist = if (artistCol >= 0) c.getString(artistCol) ?: "" else ""
                        val dur = if (durCol >= 0) c.getLong(durCol) else 0L
                        list.add(
                            ShareItem(
                                id = "audio:${c.getLong(idCol)}",
                                name = name,
                                path = path,
                                size = c.getLong(sizeCol),
                                type = ShareItem.Type.MUSIC,
                                durationMs = dur,
                                meta = artist
                            )
                        )
                        count++
                    }
                }
        } catch (_: Exception) {}
        return list
    }

    private fun loadFiles(): List<ShareItem> {
        val roots = listOf(
            File("/storage/emulated/0/Download"),
            File("/storage/emulated/0/Documents")
        ).filter { it.exists() }

        val out = mutableListOf<ShareItem>()
        val exts = setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "zip", "rar", "7z", "apk")
        for (root in roots) {
            root.listFiles()?.sortedByDescending { it.lastModified() }?.forEach { f ->
                if (f.isFile && f.extension.lowercase() in exts && out.size < 200) {
                    out.add(
                        ShareItem(
                            id = "file:${f.absolutePath}",
                            name = f.name,
                            path = f.absolutePath,
                            size = f.length(),
                            type = ShareItem.Type.FILE
                        )
                    )
                }
            }
        }
        return out
    }

    private fun toggleSelect(item: ShareItem) {
        if (selected.containsKey(item.id)) selected.remove(item.id)
        else selected[item.id] = item
        updateSelectedUi()
        rvContent.adapter?.notifyDataSetChanged()
        if (isSharing) restartServer()
    }

    private fun updateSelectedUi() {
        tvSelectedCount.text = "${selected.size} selected"
        btnToggle.isEnabled = selected.isNotEmpty() || isSharing
        if (selected.isNotEmpty() || isSharing) shareBar.visibility = View.VISIBLE
        else if (!selectMode) shareBar.visibility = View.GONE
    }

    private fun startSharing() {
        if (selected.isEmpty()) {
            Toast.makeText(this, "Select at least one item first", Toast.LENGTH_SHORT).show()
            return
        }
        progress.visibility = View.VISIBLE
        io.execute {
            val prepared = selected.values.mapNotNull { item ->
                try {
                    val out = when (item.type) {
                        ShareItem.Type.APP -> {
                            val dest = File(cacheDir, item.name)
                            if (!dest.exists() || dest.length() != item.size) {
                                File(item.path).copyTo(dest, overwrite = true)
                            }
                            dest
                        }
                        else -> File(item.path)
                    }
                    if (out.exists()) item.copy(path = out.absolutePath) else null
                } catch (_: Exception) { null }
            }
            runOnUiThread {
                progress.visibility = View.GONE
                if (prepared.isEmpty()) {
                    Toast.makeText(this, "Could not prepare files", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                val intent = Intent(this, ShareService::class.java).apply {
                    action = ShareService.ACTION_START
                    putStringArrayListExtra(ShareService.EXTRA_PATHS, ArrayList(prepared.map { it.path }))
                    putStringArrayListExtra(ShareService.EXTRA_NAMES, ArrayList(prepared.map { it.name }))
                }
                ContextCompat.startForegroundService(this, intent)
                isSharing = true
                btnToggle.text = "Stop Sharing"
                val ip = ShareService.getLocalIpAddress(this)
                serverUrl = "http://$ip:${ShareService.PORT}"
                tvUrl.text = serverUrl
                ivQrSmall.setImageBitmap(makeQr(serverUrl!!))
                statusBar.visibility = View.VISIBLE
                shareBar.visibility = View.VISIBLE
                Toast.makeText(this, "Sharing ${prepared.size} item(s)", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun stopSharing() {
        startService(Intent(this, ShareService::class.java).apply { action = ShareService.ACTION_STOP })
        isSharing = false
        btnToggle.text = "Start Sharing"
        statusBar.visibility = View.GONE
        serverUrl = null
        updateSelectedUi()
    }

    private fun restartServer() {
        if (!isSharing) return
        stopSharing()
        startSharing()
    }

    private fun makeQr(text: String): Bitmap {
        val size = 256
        val bits = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) for (y in 0 until size) {
            bmp.setPixel(x, y, if (bits[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
        }
        return bmp
    }

    fun isSelected(id: String) = selected.containsKey(id)

    data class Category(val id: Int, val title: String, val iconRes: Int, var count: Int = 0)

    data class ShareItem(
        val id: String,
        val name: String,
        val path: String,
        val size: Long,
        val type: Type,
        val icon: Drawable? = null,
        val mediaUri: Uri? = null,
        val durationMs: Long = 0,
        val meta: String = ""
    ) {
        enum class Type { APP, PHOTO, VIDEO, MUSIC, FILE }
    }

    inner class CategoryAdapter(
        private val items: List<Category>,
        private val onClick: (Category) -> Unit
    ) : RecyclerView.Adapter<CategoryAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val icon: ImageView = v.findViewById(R.id.icon)
            val title: TextView = v.findViewById(R.id.title)
            val count: TextView = v.findViewById(R.id.count)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_category, parent, false))
        override fun getItemCount() = items.size
        override fun onBindViewHolder(h: VH, pos: Int) {
            val c = items[pos]
            h.title.text = c.title
            h.count.text = if (c.count > 0) "${c.count} items" else "Open"
            h.icon.setImageResource(c.iconRes)
            h.icon.setColorFilter(0xFFA78BFA.toInt())
            h.itemView.setOnClickListener { onClick(c) }
        }
        fun updateCounts(counts: List<Int>) {
            counts.forEachIndexed { i, n -> if (i < items.size) items[i].count = n }
            notifyDataSetChanged()
        }
    }

    inner class MediaAdapter(private val items: List<ShareItem>) :
        RecyclerView.Adapter<MediaAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val thumb: ImageView = v.findViewById(R.id.thumb)
            val duration: TextView = v.findViewById(R.id.duration)
            val check: View = v.findViewById(R.id.check)
            val overlay: View = v.findViewById(R.id.selectOverlay)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_media, parent, false))
        override fun getItemCount() = items.size
        override fun onBindViewHolder(h: VH, pos: Int) {
            val item = items[pos]
            h.thumb.setImageURI(item.mediaUri)
            if (item.type == ShareItem.Type.VIDEO && item.durationMs > 0) {
                h.duration.visibility = View.VISIBLE
                h.duration.text = fmtDur(item.durationMs)
            } else h.duration.visibility = View.GONE
            val sel = isSelected(item.id)
            h.check.visibility = if (sel) View.VISIBLE else View.GONE
            h.overlay.visibility = if (selectMode && sel) View.VISIBLE else View.GONE
            h.itemView.setOnClickListener { onItemTap(item) }
        }
    }

    inner class AppAdapter(private val items: List<ShareItem>) :
        RecyclerView.Adapter<AppAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val icon: ImageView = v.findViewById(R.id.icon)
            val name: TextView = v.findViewById(R.id.name)
            val size: TextView = v.findViewById(R.id.size)
            val check: View = v.findViewById(R.id.check)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false))
        override fun getItemCount() = items.size
        override fun onBindViewHolder(h: VH, pos: Int) {
            val item = items[pos]
            h.name.text = item.name.removeSuffix(".apk")
            h.size.text = fmt(item.size)
            h.icon.setImageDrawable(item.icon)
            h.check.visibility = if (isSelected(item.id)) View.VISIBLE else View.GONE
            h.itemView.setOnClickListener { onItemTap(item) }
        }
    }

    inner class ListAdapter(private val items: List<ShareItem>) :
        RecyclerView.Adapter<ListAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val icon: ImageView = v.findViewById(R.id.icon)
            val name: TextView = v.findViewById(R.id.name)
            val meta: TextView = v.findViewById(R.id.meta)
            val check: View = v.findViewById(R.id.check)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_list, parent, false))
        override fun getItemCount() = items.size
        override fun onBindViewHolder(h: VH, pos: Int) {
            val item = items[pos]
            h.name.text = item.name
            val extra = if (item.meta.isNotEmpty()) " · ${item.meta}" else ""
            val dur = if (item.durationMs > 0) " · ${fmtDur(item.durationMs)}" else ""
            h.meta.text = "${fmt(item.size)}$dur$extra"
            when (item.type) {
                ShareItem.Type.MUSIC -> h.icon.setImageResource(android.R.drawable.ic_media_play)
                else -> h.icon.setImageResource(android.R.drawable.ic_menu_save)
            }
            h.check.visibility = if (isSelected(item.id)) View.VISIBLE else View.GONE
            h.itemView.setOnClickListener { onItemTap(item) }
        }
    }

    companion object {
        fun fmt(bytes: Long): String {
            if (bytes < 1024) return "$bytes B"
            if (bytes < 1024 * 1024) return "%.1f KB".format(bytes / 1024.0)
            if (bytes < 1024L * 1024 * 1024) return "%.1f MB".format(bytes / (1024.0 * 1024))
            return "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
        }
        fun fmtDur(ms: Long): String {
            val s = (ms / 1000) % 60
            val m = (ms / 1000) / 60
            return "%d:%02d".format(m, s)
        }
    }
}
