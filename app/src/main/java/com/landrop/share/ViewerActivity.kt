package com.landrop.share

import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class ViewerActivity : AppCompatActivity() {

    private var mediaPlayer: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var seeking = false

    private lateinit var photoView: ImageView
    private lateinit var videoView: VideoView
    private lateinit var musicPanel: View
    private lateinit var seekBar: SeekBar
    private lateinit var btnPlayPause: ImageButton
    private lateinit var tvPos: TextView
    private lateinit var tvDur: TextView
    private lateinit var tvMusicTitle: TextView
    private lateinit var tvMusicArtist: TextView
    private lateinit var tvTitle: TextView

    private val tick = object : Runnable {
        override fun run() {
            val mp = mediaPlayer ?: return
            if (!seeking && mp.isPlaying) {
                seekBar.progress = mp.currentPosition
                tvPos.text = fmt(mp.currentPosition)
            }
            handler.postDelayed(this, 400)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_viewer)

        photoView = findViewById(R.id.photoView)
        videoView = findViewById(R.id.videoView)
        musicPanel = findViewById(R.id.musicPanel)
        seekBar = findViewById(R.id.seekBar)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        tvPos = findViewById(R.id.tvPos)
        tvDur = findViewById(R.id.tvDur)
        tvMusicTitle = findViewById(R.id.tvMusicTitle)
        tvMusicArtist = findViewById(R.id.tvMusicArtist)
        tvTitle = findViewById(R.id.tvTitle)

        findViewById<ImageButton>(R.id.btnClose).setOnClickListener { finish() }

        val type = intent.getStringExtra(EXTRA_TYPE) ?: TYPE_PHOTO
        val name = intent.getStringExtra(EXTRA_NAME) ?: ""
        val path = intent.getStringExtra(EXTRA_PATH)
        val uriStr = intent.getStringExtra(EXTRA_URI)
        val artist = intent.getStringExtra(EXTRA_ARTIST) ?: ""

        tvTitle.text = name

        when (type) {
            TYPE_PHOTO -> openPhoto(uriStr, path)
            TYPE_VIDEO -> openVideo(uriStr, path)
            TYPE_MUSIC -> openMusic(path, name, artist)
            else -> {
                Toast.makeText(this, "Unsupported", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun openPhoto(uriStr: String?, path: String?) {
        photoView.visibility = View.VISIBLE
        try {
            when {
                !uriStr.isNullOrBlank() -> photoView.setImageURI(Uri.parse(uriStr))
                !path.isNullOrBlank() && File(path).exists() -> photoView.setImageURI(Uri.fromFile(File(path)))
                else -> {
                    Toast.makeText(this, "Cannot open photo", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Cannot open photo", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun openVideo(uriStr: String?, path: String?) {
        videoView.visibility = View.VISIBLE
        try {
            val uri = when {
                !uriStr.isNullOrBlank() -> Uri.parse(uriStr)
                !path.isNullOrBlank() && File(path).exists() -> Uri.fromFile(File(path))
                else -> null
            }
            if (uri == null) {
                Toast.makeText(this, "Cannot open video", Toast.LENGTH_SHORT).show()
                finish()
                return
            }
            videoView.setVideoURI(uri)
            videoView.setOnPreparedListener { mp ->
                mp.isLooping = false
                videoView.start()
            }
            videoView.setOnErrorListener { _, _, _ ->
                Toast.makeText(this, "Cannot play video", Toast.LENGTH_SHORT).show()
                true
            }
            videoView.setOnCompletionListener { /* finished */ }
            videoView.setOnClickListener {
                if (videoView.isPlaying) videoView.pause() else videoView.start()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Cannot open video", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun openMusic(path: String?, name: String, artist: String) {
        musicPanel.visibility = View.VISIBLE
        tvMusicTitle.text = name
        tvMusicArtist.text = if (artist.isNotBlank()) artist else "Unknown artist"

        if (path.isNullOrBlank() || !File(path).exists()) {
            Toast.makeText(this, "Cannot open music", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(path)
                prepare()
                start()
            }
            val mp = mediaPlayer!!
            seekBar.max = mp.duration
            tvDur.text = fmt(mp.duration)
            tvPos.text = "0:00"
            btnPlayPause.setImageResource(android.R.drawable.ic_media_pause)

            btnPlayPause.setOnClickListener {
                if (mp.isPlaying) {
                    mp.pause()
                    btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
                } else {
                    mp.start()
                    btnPlayPause.setImageResource(android.R.drawable.ic_media_pause)
                }
            }

            seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) tvPos.text = fmt(progress)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) { seeking = true }
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    seeking = false
                    mp.seekTo(sb?.progress ?: 0)
                }
            })

            mp.setOnCompletionListener {
                btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
                seekBar.progress = 0
                tvPos.text = "0:00"
            }

            handler.post(tick)
        } catch (e: Exception) {
            Toast.makeText(this, "Cannot play music", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun fmt(ms: Int): String {
        val s = (ms / 1000) % 60
        val m = (ms / 1000) / 60
        return "%d:%02d".format(m, s)
    }

    override fun onPause() {
        super.onPause()
        if (videoView.visibility == View.VISIBLE && videoView.isPlaying) videoView.pause()
        mediaPlayer?.let { if (it.isPlaying) it.pause() }
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        mediaPlayer?.release()
        mediaPlayer = null
        if (videoView.visibility == View.VISIBLE) videoView.stopPlayback()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_TYPE = "type"
        const val EXTRA_NAME = "name"
        const val EXTRA_PATH = "path"
        const val EXTRA_URI = "uri"
        const val EXTRA_ARTIST = "artist"
        const val TYPE_PHOTO = "photo"
        const val TYPE_VIDEO = "video"
        const val TYPE_MUSIC = "music"
    }
}
