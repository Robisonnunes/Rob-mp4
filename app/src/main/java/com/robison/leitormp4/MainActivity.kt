package com.robison.leitormp4

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.content.Intent
import android.net.Uri
import android.media.MediaScannerConnection
import android.os.Bundle
import android.provider.Settings
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
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
import com.google.android.material.tabs.TabLayout
import com.google.common.util.concurrent.ListenableFuture
import java.io.File
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private enum class Tab { FOLDERS, PLAYLISTS }

    // Vistas
    private lateinit var tabs: TabLayout
    private lateinit var navBar: View
    private lateinit var navBack: ImageButton
    private lateinit var navTitle: TextView
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

    // Estado
    private lateinit var store: PlaylistStore
    private val adapter = RowAdapter(::onRowClick, ::onRowLongClick)
    private var allTracks: List<Track> = emptyList()
    private var tracksById: Map<String, Track> = emptyMap()
    private var volumes: List<String> = emptyList()
    private var currentTab = Tab.FOLDERS
    private var folderPath: String? = null
    private var openPlaylistId: String? = null
    private var visibleTracks: List<Track> = emptyList()
    private var loadedWithFullAccess: Boolean? = null

    // Leitor
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    private val io = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var userSeeking = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            loadTracks()
            offerFullAccessOnce()
        }

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = updatePlayerUi()
    }

    private val progressTicker = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, 500)
        }
    }

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = goUp()
    }

    // ================================================================ ciclo de vida

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        store = PlaylistStore(this)

        tabs = findViewById(R.id.tabs)
        navBar = findViewById(R.id.navBar)
        navBack = findViewById(R.id.navBack)
        navTitle = findViewById(R.id.navTitle)
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

        tabs.addTab(tabs.newTab().setText(R.string.tab_folders))
        tabs.addTab(tabs.newTab().setText(R.string.tab_playlists))
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(t: TabLayout.Tab) {
                currentTab = Tab.entries[t.position]
                clearSearchAndRender()
            }
            override fun onTabUnselected(t: TabLayout.Tab) {}
            override fun onTabReselected(t: TabLayout.Tab) {
                // Tocar de novo no separador volta ao início dele
                folderPath = null
                openPlaylistId = null
                clearSearchAndRender()
            }
        })

        navBack.setOnClickListener { goUp() }
        onBackPressedDispatcher.addCallback(this, backCallback)
        search.doAfterTextChanged { render() }

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

    override fun onResume() {
        super.onResume()
        // Voltou das definições depois de dar (ou tirar) o acesso a todas as pastas
        val full = TrackRepository.hasFullAccess(this)
        if (loadedWithFullAccess != null && loadedWithFullAccess != full) loadTracks()
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            controller = future.get().also { it.addListener(playerListener) }
            updatePlayerUi()
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

    // ================================================================ permissões

    private fun hasMediaPermission(): Boolean {
        if (TrackRepository.hasFullAccess(this)) return true
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
            arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
        }
        permissionLauncher.launch(perms)
    }

    // ================================================================ acesso a todas as pastas

    /** No Android 11+ é preciso "acesso a todos os ficheiros" para ver pastas como as do WhatsApp. */
    private fun needsFullAccess() =
        Build.VERSION.SDK_INT >= 30 && !TrackRepository.hasFullAccess(this)

    private fun offerFullAccessOnce() {
        if (!needsFullAccess()) return
        val prefs = getSharedPreferences("app", MODE_PRIVATE)
        if (prefs.getBoolean("asked_full_access", false)) return
        prefs.edit().putBoolean("asked_full_access", true).apply()
        AlertDialog.Builder(this)
            .setTitle(R.string.full_access_title)
            .setMessage(R.string.full_access_message)
            .setPositiveButton(R.string.full_access_go) { _, _ -> openFullAccessSettings() }
            .setNegativeButton(R.string.not_now, null)
            .show()
    }

    private fun openFullAccessSettings() {
        if (Build.VERSION.SDK_INT < 30) return
        val intent = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:$packageName")
        )
        try {
            startActivity(intent)
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }

    // ================================================================ dados

    private fun loadTracks() {
        if (!hasMediaPermission()) {
            showEmpty(getString(R.string.no_permission))
            return
        }
        showEmpty(getString(R.string.loading))
        val full = TrackRepository.hasFullAccess(this)
        io.execute {
            val result = TrackRepository.loadAll(applicationContext, full)
            val tracks = result.tracks
            runOnUiThread {
                loadedWithFullAccess = full
                store.migrate(result.aliases)
                allTracks = tracks
                tracksById = tracks.associateBy { it.id }
                volumes = tracks.map { FolderTree.volumeOf(it.dir) }.distinct().sorted()
                if (folderPath != null && allTracks.none { FolderTree.isInside(it.dir, folderPath!!) }) {
                    folderPath = null
                }
                render()
            }
        }
    }

    private fun tracksInFolder(path: String): List<Track> =
        allTracks.filter { FolderTree.isInside(it.dir, path) }
            .sortedWith(compareBy<Track>({ it.dir.lowercase() }, { it.title.lowercase() }))

    private fun tracksOf(p: Playlist): List<Track> = p.items.mapNotNull { tracksById[it] }

    // ================================================================ navegação

    /** Pasta mostrada no separador Pastas; com um só armazenamento, começa logo nele. */
    private fun effectiveFolder(): String? =
        folderPath ?: volumes.singleOrNull()

    private fun canGoUp(): Boolean = when (currentTab) {
        Tab.FOLDERS -> {
            val f = effectiveFolder()
            f != null && !(f in volumes && volumes.size == 1)
        }
        Tab.PLAYLISTS -> openPlaylistId != null
    }

    private fun goUp() {
        when (currentTab) {
            Tab.FOLDERS -> {
                val f = effectiveFolder() ?: return
                folderPath = if (f in volumes) null else f.substringBeforeLast('/')
            }
            Tab.PLAYLISTS -> openPlaylistId = null
        }
        clearSearchAndRender()
    }

    private fun clearSearchAndRender() {
        if (search.text.isNotEmpty()) search.setText("") // dispara render()
        else render()
        list.scrollToPosition(0)
    }

    // ================================================================ desenhar a lista

    private fun render() {
        val q = search.text.toString().trim()
        fun match(s: String) = q.isEmpty() || s.contains(q, ignoreCase = true)

        val rows = mutableListOf<Row>()
        var title: String? = null

        when (currentTab) {
            Tab.FOLDERS -> {
                val f = effectiveFolder()
                if (needsFullAccess() && !canGoUp() && q.isEmpty()) {
                    rows += Row.Action(ACT_FULL_ACCESS, getString(R.string.full_access_row), R.drawable.ic_lock_open)
                }
                if (f == null) {
                    volumes.forEach { v ->
                        rows += Row.FolderRow(v, FolderTree.displayName(v), tracksInFolder(v).size)
                    }
                } else {
                    title = FolderTree.displayName(f)
                    val inside = tracksInFolder(f)
                    if (inside.isNotEmpty() && q.isEmpty()) {
                        rows += Row.Action(ACT_PLAY_FOLDER, getString(R.string.play_folder), R.drawable.ic_play)
                        rows += Row.Action(ACT_SHUFFLE_FOLDER, getString(R.string.shuffle_folder), R.drawable.ic_shuffle)
                    }
                    FolderTree.subfolders(allTracks, f).filter { match(it.name) }
                        .forEach { rows += Row.FolderRow(it.path, it.name, it.count) }
                    // Sem pesquisa: só as faixas desta pasta. Com pesquisa: procura também nas subpastas.
                    val tracks = if (q.isEmpty()) allTracks.filter { it.dir == f }
                    else inside.filter { match(it.title) }
                    tracks.forEach { rows += Row.TrackRow(it) }
                }
            }

            Tab.PLAYLISTS -> {
                val p = store.get(openPlaylistId)
                if (p == null) {
                    openPlaylistId = null
                    if (q.isEmpty()) {
                        rows += Row.Action(ACT_NEW_PLAYLIST, getString(R.string.new_playlist), R.drawable.ic_add)
                    }
                    store.sorted().filter { match(it.name) }
                        .forEach { rows += Row.PlaylistRow(it, tracksOf(it).size) }
                } else {
                    title = p.name
                    val tracks = tracksOf(p)
                    if (tracks.isNotEmpty() && q.isEmpty()) {
                        rows += Row.Action(ACT_PLAY_PLAYLIST, getString(R.string.play_playlist), R.drawable.ic_play)
                        rows += Row.Action(ACT_SHUFFLE_PLAYLIST, getString(R.string.shuffle_playlist), R.drawable.ic_shuffle)
                    }
                    tracks.filter { match(it.title) }.forEach { rows += Row.TrackRow(it) }
                }
            }
        }

        visibleTracks = rows.filterIsInstance<Row.TrackRow>().map { it.track }
        adapter.submit(rows)
        adapter.setCurrent(controller?.currentMediaItem?.mediaId)

        navBar.visibility = if (title != null) View.VISIBLE else View.GONE
        navTitle.text = title
        navBack.visibility = if (canGoUp()) View.VISIBLE else View.INVISIBLE
        backCallback.isEnabled = canGoUp()

        val empty = when {
            !hasMediaPermission() -> getString(R.string.no_permission)
            rows.isNotEmpty() -> null
            q.isNotEmpty() -> getString(R.string.no_results)
            currentTab == Tab.PLAYLISTS && openPlaylistId != null -> getString(R.string.playlist_empty)
            allTracks.isEmpty() -> getString(R.string.no_files)
            else -> getString(R.string.no_results)
        }
        if (empty == null) emptyView.visibility = View.GONE else showEmpty(empty)
    }

    private fun showEmpty(msg: String) {
        emptyView.text = msg
        emptyView.visibility = View.VISIBLE
    }

    // ================================================================ toques na lista

    private fun onRowClick(row: Row) {
        when (row) {
            is Row.TrackRow -> {
                val i = visibleTracks.indexOfFirst { it.id == row.track.id }
                playQueue(visibleTracks, i.coerceAtLeast(0))
            }
            is Row.FolderRow -> {
                folderPath = row.path
                clearSearchAndRender()
            }
            is Row.PlaylistRow -> {
                openPlaylistId = row.playlist.id
                clearSearchAndRender()
            }
            is Row.Action -> when (row.action) {
                ACT_PLAY_FOLDER -> effectiveFolder()?.let { playQueue(tracksInFolder(it), 0) }
                ACT_SHUFFLE_FOLDER -> effectiveFolder()?.let { playQueue(tracksInFolder(it), 0, shuffle = true) }
                ACT_PLAY_PLAYLIST -> store.get(openPlaylistId)?.let { playQueue(tracksOf(it), 0) }
                ACT_SHUFFLE_PLAYLIST -> store.get(openPlaylistId)?.let { playQueue(tracksOf(it), 0, shuffle = true) }
                ACT_FULL_ACCESS -> openFullAccessSettings()
                ACT_NEW_PLAYLIST -> askName(getString(R.string.new_playlist), "") { name ->
                    store.create(name)
                    render()
                }
            }
        }
    }

    private fun onRowLongClick(row: Row) {
        when (row) {
            is Row.TrackRow -> trackMenu(row.track)
            is Row.FolderRow -> folderMenu(row)
            is Row.PlaylistRow -> playlistMenu(row.playlist)
            is Row.Action -> Unit
        }
    }

    private fun trackMenu(track: Track) {
        val playlist = if (currentTab == Tab.PLAYLISTS) store.get(openPlaylistId) else null
        val options = mutableListOf<Pair<String, () -> Unit>>()
        options += getString(R.string.add_to_playlist) to { chooseplaylist(listOf(track.id)) }
        options += getString(R.string.rename_file) to { renameTrack(track) }
        if (playlist != null) {
            options += getString(R.string.move_up) to { store.move(playlist.id, track.id, -1); render() }
            options += getString(R.string.move_down) to { store.move(playlist.id, track.id, +1); render() }
            options += getString(R.string.remove_from_playlist) to {
                store.remove(playlist.id, track.id); render()
            }
        }
        showOptions(track.title, options)
    }

    private fun folderMenu(row: Row.FolderRow) {
        val tracks = tracksInFolder(row.path)
        showOptions(
            row.name,
            listOf(
                getString(R.string.play_folder) to { playQueue(tracks, 0) },
                getString(R.string.shuffle_folder) to { playQueue(tracks, 0, shuffle = true) },
                getString(R.string.add_folder_to_playlist) to { chooseplaylist(tracks.map { it.id }) }
            )
        )
    }

    private fun playlistMenu(p: Playlist) {
        showOptions(
            p.name,
            listOf(
                getString(R.string.play_playlist) to { playQueue(tracksOf(p), 0) },
                getString(R.string.shuffle_playlist) to { playQueue(tracksOf(p), 0, shuffle = true) },
                getString(R.string.rename) to {
                    askName(getString(R.string.rename), p.name) { store.rename(p.id, it); render() }
                },
                getString(R.string.delete) to {
                    AlertDialog.Builder(this)
                        .setTitle(getString(R.string.delete_confirm, p.name))
                        .setPositiveButton(R.string.delete) { _, _ -> store.delete(p.id); render() }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                    Unit
                }
            )
        )
    }

    /** Escolher (ou criar) a playlist onde acrescentar faixas. */
    private fun chooseplaylist(trackIds: List<String>) {
        if (trackIds.isEmpty()) return
        val playlists = store.sorted()
        val names = playlists.map { it.name } + getString(R.string.new_playlist_option)
        AlertDialog.Builder(this)
            .setTitle(R.string.add_to_playlist)
            .setItems(names.toTypedArray()) { _, which ->
                if (which < playlists.size) {
                    addTo(playlists[which], trackIds)
                } else {
                    askName(getString(R.string.new_playlist), "") { name ->
                        addTo(store.create(name), trackIds)
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun addTo(p: Playlist, trackIds: List<String>) {
        val added = store.add(p.id, trackIds)
        val msg = if (added == 0) getString(R.string.already_in_playlist, p.name)
        else getString(R.string.added_to_playlist, added, p.name)
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        render()
    }

    private fun showOptions(title: String, options: List<Pair<String, () -> Unit>>) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(options.map { it.first }.toTypedArray()) { _, which -> options[which].second() }
            .show()
    }

    private fun askName(
        title: String,
        initial: String,
        hintRes: Int = R.string.playlist_name_hint,
        onOk: (String) -> Unit
    ) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setText(initial)
            setSelection(initial.length)
            hint = getString(hintRes)
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(box)
            .setPositiveButton(R.string.ok) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) onOk(name)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ================================================================ mudar o nome do ficheiro

    private fun canWriteFiles(): Boolean = when {
        Build.VERSION.SDK_INT >= 30 -> TrackRepository.hasFullAccess(this)
        else -> ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun renameTrack(track: Track) {
        if (!track.id.startsWith("/")) {
            toast(getString(R.string.rename_failed))
            return
        }
        if (!canWriteFiles()) {
            if (Build.VERSION.SDK_INT >= 30) {
                AlertDialog.Builder(this)
                    .setTitle(R.string.full_access_title)
                    .setMessage(R.string.rename_needs_access)
                    .setPositiveButton(R.string.full_access_go) { _, _ -> openFullAccessSettings() }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            } else {
                requestPermissions()
            }
            return
        }
        val file = File(track.id)
        val ext = file.extension
        askName(getString(R.string.rename_file), track.title, R.string.file_name_hint) { typed ->
            val clean = typed.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().trimEnd('.')
            if (clean.isEmpty()) return@askName
            val target = File(file.parentFile, if (ext.isEmpty()) clean else "$clean.$ext")
            when {
                target.absolutePath == file.absolutePath -> Unit
                target.exists() -> toast(getString(R.string.rename_exists))
                !file.renameTo(target) -> toast(getString(R.string.rename_failed))
                else -> {
                    val oldPath = file.absolutePath
                    val newPath = target.absolutePath
                    store.renamePath(oldPath, newPath)
                    updateQueueAfterRename(oldPath, target)
                    MediaScannerConnection.scanFile(this, arrayOf(oldPath, newPath), null, null)
                    toast(getString(R.string.renamed))
                    loadTracks()
                }
            }
        }
    }

    /** Se o ficheiro renomeado estiver na fila de reprodução, aponta para o nome novo. */
    private fun updateQueueAfterRename(oldPath: String, target: File) {
        val c = controller ?: return
        for (i in 0 until c.mediaItemCount) {
            if (i == c.currentMediaItemIndex) continue // a faixa atual continua a tocar
            if (c.getMediaItemAt(i).mediaId != oldPath) continue
            val t = Track(
                id = target.absolutePath,
                uri = Uri.fromFile(target),
                title = target.nameWithoutExtension,
                folder = target.parentFile?.name.orEmpty(),
                durationMs = 0L,
                dir = target.parent ?: "/"
            )
            c.replaceMediaItem(i, t.toMediaItem())
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // ================================================================ reprodução

    private fun playQueue(tracks: List<Track>, startIndex: Int, shuffle: Boolean = false) {
        val c = controller ?: return
        if (tracks.isEmpty()) return
        c.shuffleModeEnabled = shuffle
        val start = if (shuffle) tracks.indices.random() else startIndex.coerceIn(tracks.indices)
        c.setMediaItems(tracks.map { it.toMediaItem() }, start, 0L)
        c.prepare()
        c.play()
    }

    private fun togglePlay() {
        val c = controller ?: return
        if (c.mediaItemCount == 0) {
            if (visibleTracks.isNotEmpty()) playQueue(visibleTracks, 0)
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
            .setMediaId(id)
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

    // ================================================================ painel do leitor

    private fun updatePlayerUi() {
        val c = controller ?: return
        val item = c.currentMediaItem
        nowPlaying.text = item?.mediaMetadata?.title ?: getString(R.string.nothing_playing)

        btnPlay.setImageResource(if (c.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        btnPlay.contentDescription = getString(if (c.isPlaying) R.string.pause else R.string.play)

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
        private const val ACT_PLAY_FOLDER = 1
        private const val ACT_SHUFFLE_FOLDER = 2
        private const val ACT_NEW_PLAYLIST = 3
        private const val ACT_PLAY_PLAYLIST = 4
        private const val ACT_SHUFFLE_PLAYLIST = 5
        private const val ACT_FULL_ACCESS = 6
    }
}
