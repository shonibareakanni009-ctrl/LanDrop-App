package com.landrop.share

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import android.widget.ImageView
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var btnAddFiles: MaterialButton
    private lateinit var btnAddApks: MaterialButton
    private lateinit var btnToggle: MaterialButton
    private lateinit var btnCopy: MaterialButton
    private lateinit var rvFiles: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var cardStatus: MaterialCardView
    private lateinit var tvUrl: TextView
    private lateinit var ivQr: ImageView

    private val sharedFiles = mutableListOf<SharedFile>()
    private lateinit var adapter: FileAdapter

    private var isSharing = false
    private var serverUrl: String? = null

    private val pickFiles = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        uris.forEach { uri -> addUri(uri) }
        refreshList()
    }

    private val requestPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnAddFiles = findViewById(R.id.btnAddFiles)
        btnAddApks = findViewById(R.id.btnAddApks)
        btnToggle = findViewById(R.id.btnToggle)
        btnCopy = findViewById(R.id.btnCopy)
        rvFiles = findViewById(R.id.rvFiles)
        tvEmpty = findViewById(R.id.tvEmpty)
        cardStatus = findViewById(R.id.cardStatus)
        tvUrl = findViewById(R.id.tvUrl)
        ivQr = findViewById(R.id.ivQr)

        adapter = FileAdapter(sharedFiles) { index ->
            sharedFiles.removeAt(index)
            refreshList()
            if (isSharing) restartServer()
        }
        rvFiles.layoutManager = LinearLayoutManager(this)
        rvFiles.adapter = adapter

        btnAddFiles.setOnClickListener {
            ensurePermissionThen {
                pickFiles.launch(arrayOf("*/*"))
            }
        }

        btnAddApks.setOnClickListener {
            pickInstalledApk()
        }

        btnToggle.setOnClickListener {
            if (isSharing) stopSharing() else startSharing()
        }

        btnCopy.setOnClickListener {
            serverUrl?.let { url ->
                val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("LanDrop", url))
                Toast.makeText(this, "Link copied", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun ensurePermissionThen(block: () -> Unit) {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT < 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
        if (perms.isNotEmpty()) {
            requestPermission.launch(perms.toTypedArray())
        }
        block()
    }

    private fun addUri(uri: Uri) {
        val name = contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && idx >= 0) c.getString(idx) else "file"
        } ?: "file"

        val outFile = File(cacheDir, "share_${System.currentTimeMillis()}_$name")
        contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(outFile).use { output -> input.copyTo(output) }
        }
        if (outFile.exists()) {
            sharedFiles.add(SharedFile(name, outFile.absolutePath, outFile.length()))
        }
    }

    private fun pickInstalledApk() {
        val pm = packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
            .sortedBy { it.loadLabel(pm).toString().lowercase() }

        val names = apps.map { it.loadLabel(pm).toString() }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Share installed app (APK)")
            .setItems(names) { _, which ->
                val app = apps[which]
                try {
                    val src = File(app.sourceDir)
                    val out = File(cacheDir, "${app.packageName}.apk")
                    src.copyTo(out, overwrite = true)
                    sharedFiles.add(
                        SharedFile(
                            "${app.loadLabel(pm)}.apk",
                            out.absolutePath,
                            out.length()
                        )
                    )
                    refreshList()
                } catch (e: Exception) {
                    Toast.makeText(this, "Cannot extract APK: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun refreshList() {
        adapter.notifyDataSetChanged()
        tvEmpty.visibility = if (sharedFiles.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun startSharing() {
        if (sharedFiles.isEmpty()) {
            Toast.makeText(this, "Add at least one file first", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, ShareService::class.java).apply {
            action = ShareService.ACTION_START
            putStringArrayListExtra(
                ShareService.EXTRA_PATHS,
                ArrayList(sharedFiles.map { it.path })
            )
            putStringArrayListExtra(
                ShareService.EXTRA_NAMES,
                ArrayList(sharedFiles.map { it.name })
            )
        }
        ContextCompat.startForegroundService(this, intent)
        isSharing = true
        btnToggle.text = getString(R.string.stop_sharing)
        val ip = ShareService.getLocalIpAddress(this)
        serverUrl = "http://$ip:${ShareService.PORT}"
        tvUrl.text = serverUrl
        ivQr.setImageBitmap(makeQr(serverUrl!!))
        cardStatus.visibility = View.VISIBLE
        Toast.makeText(this, "Sharing started", Toast.LENGTH_SHORT).show()
    }

    private fun stopSharing() {
        val intent = Intent(this, ShareService::class.java).apply {
            action = ShareService.ACTION_STOP
        }
        startService(intent)
        isSharing = false
        btnToggle.text = getString(R.string.start_sharing)
        cardStatus.visibility = View.GONE
        serverUrl = null
    }

    private fun restartServer() {
        stopSharing()
        startSharing()
    }

    private fun makeQr(text: String): Bitmap {
        val size = 512
        val bits = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bmp.setPixel(x, y, if (bits[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
            }
        }
        return bmp
    }

    data class SharedFile(val name: String, val path: String, val size: Long)
}

class FileAdapter(
    private val items: List<MainActivity.SharedFile>,
    private val onRemove: (Int) -> Unit
) : RecyclerView.Adapter<FileAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(android.R.id.text1)
        val size: TextView = view.findViewById(android.R.id.text2)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(android.R.layout.simple_list_item_2, parent, false)
        v.setBackgroundColor(0xFF252540.toInt())
        v.setPadding(24, 20, 24, 20)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.name.text = item.name
        holder.name.setTextColor(0xFFFFFFFF.toInt())
        holder.size.text = formatSize(item.size)
        holder.size.setTextColor(0xFFA0A0B0.toInt())
        holder.itemView.setOnLongClickListener {
            onRemove(position)
            true
        }
    }

    override fun getItemCount() = items.size

    private fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        if (bytes < 1024 * 1024) return "%.1f KB".format(bytes / 1024.0)
        return "%.1f MB".format(bytes / (1024.0 * 1024.0))
    }
}
