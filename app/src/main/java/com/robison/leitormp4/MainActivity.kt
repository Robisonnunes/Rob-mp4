package com.robison.leitormp4

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var search: EditText
    private lateinit var emptyView: TextView
    private lateinit var list: RecyclerView
    private lateinit var nowPlaying: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var timeCurrent: TextView
    private lateinit var timeTotal: TextView
    private lateinit var btnPlay: ImageButton
    private lateinit var btnPrev: ImageButton
    private lateinit var btnNext: ImageButton
    private lateinit var btnShuffle: ImageButton
    private lateinit var btnRepeat: ImageButton

    private val adapter = TrackAdapter { position -> playFromList(position) }
    private var allTracks: List<Track> = emptyList()
    private var shownTracks: List<Track> = emptyList()

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    private val io = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var userSeeking = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            loadTracks()
        }

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = updateUi()
    }

    private val progressTicker = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, 500)
        }
    }

    // ---------------------------------------------------------------- ciclo de vida

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        search = findViewById(R.id.search)
        emptyView = findViewById(R.id.emptyView)
        list = findViewById(R.id.trackList)
        nowPlaying = findViewById(R.id.nowPlaying)
        seekBar = findViewById(R.id.seekBar)
        timeCurrent = findViewById(R.id.timeCurrent)
        timeTotal = findViewById(R.id.timeTotal)
        btnPlay = findViewById(R.id.btnPlay)
        btnPrev = findViewById(R.id.btnPrev)
        btnNext = findViewById(R.id.btnNext)
        btnShuffle = findViewById(R.id.btnShuffle)
        btnRepeat = findViewById(R.id.btnRepeat)

        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        search.doAfterTextChanged { applyFilter() }

        btnPlay.setOnClickListener { togglePlay() }
        btnNext.setOnClickListener { controller?.seekToNextMediaItem() }
        btnPrev.setOnClickListener { controller?.seekToPrevious() }
        btnShuffle.setOnClickListener {
            controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled }
        }
        btnRepeat.setOnClickListener {
            controller?.let {
                it.repeatMode = when (it.repeatMode) {
                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                    else -> Player.REPEAT_MODE_OFF
                }
            }
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) timeCurrent.text = formatTime(progress.toLong())
            }
            override fun onStartTrackingTouch(sb: SeekBar) { userSeeking = true }
            override fun onStopTrackingTouch(sb: SeekBar) {
                userSeeking = false
                controller?.seekTo(sb.progress.toLong())
            }
        })

        if (hasMediaPermission()) loadTracks() else requestPermissions()
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            controller = future.get().also { it.addListener(playerListener) }
            updateUi()
        }, ContextCompat.getMainExecutor(this))
        handler.post(progressTicker)
    }

    override fun onStop() {
        handler.removeCallbacks(progressTicker)
        controller?.removeListener(playerListener)
        controller = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        super.onStop()
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_REFRESH, 0, R.string.refresh)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == MENU_REFRESH) {
            if (hasMediaPermission()) loadTracks() else requestPermissions()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    // ---------------------------------------------------------------- permissões

    private fun hasMediaPermission(): Boolean {
        fun granted(p: String) =
            ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
        return if (Build.VERSION.SDK_INT >= 33) {
            granted(Manifest.permission.READ_MEDIA_VIDEO) ||
                granted(Manifest.permission.READ_MEDIA_AUDIO) ||
                (Build.VERSION.SDK_INT >= 34 &&
                    granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))
        } else {
            granted(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private fun requestPermissions() {
        val perms = if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO,
                Manifest.permission.POST_NOTIFICATIONS
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        permissionLauncher.launch(perms)
    }

    // ---------------------------------------------------------------- lista

    private fun loadTracks() {
        if (!hasMediaPermission()) {
            showEmpty(getString(R.string.no_permission))
            return
        }
        emptyView.text = getString(R.string.loading)
        emptyView.visibility = View.VISIBLE
        io.execute {
            val tracks = TrackRepository.loadAll(applicationContext)
            runOnUiThread {
                allTracks = tracks
                applyFilter()
            }
        }
    }

    private fun applyFilter() {
        val q = search.text.toString().trim()
        shownTracks = if (q.isEmpty()) allTracks else allTracks.filter {
            it.title.contains(q, ignoreCase = true) || it.folder.contains(q, ignoreCase = true)
        }
        adapter.submit(shownTracks)
        when {
            allTracks.isEmpty() -> showEmpty(getString(R.string.no_files))
            shownTracks.isEmpty() -> showEmpty(getString(R.string.no_results))
            else -> emptyView.visibility = View.GONE
        }
    }

    private fun showEmpty(msg: String) {
        emptyView.text = msg
        emptyView.visibility = View.VISIBLE
    }

    // ---------------------------------------------------------------- reprodução

    /** Toca a faixa escolhida e coloca a lista visível como fila (para "próxima" funcionar). */
    private fun playFromList(position: Int) {
        val c = controller ?: return
        if (position !in shownTracks.indices) return
        c.setMediaItems(shownTracks.map { it.toMediaItem() }, position, 0L)
        c.prepare()
        c.play()
    }

    private fun togglePlay() {
        val c = controller ?: return
        if (c.mediaItemCount == 0) {
            if (shownTracks.isNotEmpty()) playFromList(0)
            return
        }
        if (c.isPlaying) {
            c.pause()
        } else {
            if (c.playbackState == Player.STATE_ENDED) c.seekToDefaultPosition(0)
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            c.play()
        }
    }

    private fun Track.toMediaItem(): MediaItem =
        MediaItem.Builder()
            .setMediaId(uri.toString())
            .setUri(uri)
            .setRequestMetadata(
                MediaItem.RequestMetadata.Builder().setMediaUri(uri).build()
            )
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setDisplayTitle(title)
                    .setArtist(folder.ifBlank { null })
                    .build()
            )
            .build()

    // ---------------------------------------------------------------- interface

    private fun updateUi() {
        val c = controller ?: return
        val item = c.currentMediaItem
        nowPlaying.text = item?.mediaMetadata?.title ?: getString(R.string.nothing_playing)

        btnPlay.setImageResource(if (c.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        btnPlay.contentDescription =
            getString(if (c.isPlaying) R.string.pause else R.string.play)

        btnShuffle.alpha = if (c.shuffleModeEnabled) 1f else 0.35f
        when (c.repeatMode) {
            Player.REPEAT_MODE_ONE -> {
                btnRepeat.setImageResource(R.drawable.ic_repeat_one); btnRepeat.alpha = 1f
            }
            Player.REPEAT_MODE_ALL -> {
                btnRepeat.setImageResource(R.drawable.ic_repeat); btnRepeat.alpha = 1f
            }
            else -> {
                btnRepeat.setImageResource(R.drawable.ic_repeat); btnRepeat.alpha = 0.35f
            }
        }

        adapter.setCurrent(item?.mediaId)
        updateProgress()
    }

    private fun updateProgress() {
        val c = controller ?: return
        val duration = c.duration.takeIf { it > 0 } ?: 0L
        seekBar.max = duration.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        timeTotal.text = formatTime(duration)
        if (!userSeeking) {
            seekBar.progress = c.currentPosition.toInt()
            timeCurrent.text = formatTime(c.currentPosition)
        }
    }

    companion object {
        private const val MENU_REFRESH = 1
    }
}
