package com.robison.leitormp4

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
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
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.common.util.concurrent.ListenableFuture
import java.io.File
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    /** Ecrãs: lista de playlists → dentro de uma playlist → escolher músicas nas pastas. */
    private enum class Screen { HOME, LIST, PICKER }

    // Vistas
    private lateinit var navBack: ImageButton
    private lateinit var navTitle: TextView
    private lateinit var search: EditText
    private lateinit var emptyView: TextView
    private lateinit var list: RecyclerView
    private lateinit var fab: ExtendedFloatingActionButton
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
    private lateinit var favs: Favorites
    private val adapter = RowAdapter(::onRowClick, ::onRowLongClick, ::prefixOf)
    private var allTracks: List<Track> = emptyList()
    private var tracksById: Map<String, Track> = emptyMap()
    private var volumes: List<String> = emptyList()
    private var visibleTracks: List<Track> = emptyList()
    private var loadedWithFullAccess: Boolean? = null

    private var screen = Screen.HOME
    private var openListId: String? = null      // playlist aberta (ou Library.FAV_ID)
    private var pickFolder: String? = null      // pasta aberta no seletor

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
        favs = Favorites(this)

        navBack = findViewById(R.id.navBack)
        navTitle = findViewById(R.id.navTitle)
        search = findViewById(R.id.search)
        emptyView = findViewById(R.id.emptyView)
        list = findViewById(R.id.trackList)
        fab = findViewById(R.id.fab)
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

        navBack.setOnClickListener { goUp() }
        onBackPressedDispatcher.addCallback(this, backCallback)
        search.doAfterTextChanged { render() }
        fab.setOnClickListener { onFabClick() }

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
        try {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }

    // ================================================================ dados

    private fun loadTracks() {
        if (!hasMediaPermission()) {
            render()
            return
        }
        showEmpty(getString(R.string.loading))
        val full = TrackRepository.hasFullAccess(this)
        io.execute {
            val result = TrackRepository.loadAll(applicationContext, full)
            runOnUiThread {
                loadedWithFullAccess = full
                store.migrate(result.aliases)
                allTracks = result.tracks
                tracksById = allTracks.associateBy { it.id }
                volumes = allTracks.map { FolderTree.volumeOf(it.dir) }.distinct().sorted()
                val pf = pickFolder
                if (pf != null && allTracks.none { FolderTree.isInside(it.dir, pf) }) pickFolder = null
                render()
            }
        }
    }

    private fun tracksInFolder(path: String): List<Track> =
        allTracks.filter { FolderTree.isInside(it.dir, path) }
            .sortedWith(compareBy<Track>({ it.dir.lowercase() }, { it.title.lowercase() }))

    /** Faixa conhecida pelo caminho; se não estiver na lista carregada, lê do disco. */
    private fun trackFor(path: String): Track? =
        tracksById[path] ?: File(path).takeIf { it.isFile }?.let { Library.trackFromFile(it) }

    private fun tracksOf(p: Playlist): List<Track> = p.items.mapNotNull { trackFor(it) }

    /** Favoritos = faixas favoritas + tudo o que está dentro das pastas favoritas. */
    private fun favoriteTracks(): List<Track> {
        val single = favs.trackIds().mapNotNull { trackFor(it) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
        val fromFolders = favs.folderPaths().sorted().flatMap { tracksInFolder(it) }
        return (single + fromFolders).distinctBy { it.id }
    }

    private fun favoriteCount(): Int = favoriteTracks().size

    // ================================================================ navegação

    /** Pasta mostrada no seletor; com um só armazenamento, começa logo nele. */
    private fun effectiveFolder(): String? = pickFolder ?: volumes.singleOrNull()

    private fun pickerCanGoUpFolder(): Boolean {
        val f = effectiveFolder() ?: return false
        return !(f in volumes && volumes.size == 1)
    }

    private fun goUp() {
        when (screen) {
            Screen.HOME -> return
            Screen.LIST -> {
                screen = Screen.HOME
                openListId = null
            }
            Screen.PICKER -> {
                if (pickerCanGoUpFolder()) {
                    val f = effectiveFolder()!!
                    pickFolder = if (f in volumes) null else f.substringBeforeLast('/')
                } else {
                    screen = Screen.LIST
                }
            }
        }
        clearSearchAndRender()
    }

    private fun openList(id: String) {
        openListId = id
        screen = Screen.LIST
        clearSearchAndRender()
    }

    private fun openPicker() {
        if (openListId == null || openListId == Library.FAV_ID) return
        screen = Screen.PICKER
        clearSearchAndRender()
    }

    private fun clearSearchAndRender() {
        if (search.text.isNotEmpty()) search.setText("") // dispara render()
        else render()
        list.scrollToPosition(0)
    }

    private fun onFabClick() {
        when (screen) {
            Screen.HOME -> createPlaylist()
            Screen.LIST -> openPicker()
            Screen.PICKER -> {
                screen = Screen.LIST
                clearSearchAndRender()
            }
        }
    }

    private fun createPlaylist() {
        askName(getString(R.string.new_playlist), "") { name ->
            val p = store.create(name)
            openListId = p.id
            pickFolder = null
            screen = Screen.PICKER   // logo a seguir, escolher as músicas
            clearSearchAndRender()
        }
    }

    // ================================================================ desenhar a lista

    private fun render() {
        val q = search.text.toString().trim()
        fun match(s: String) = q.isEmpty() || s.contains(q, ignoreCase = true)

        val rows = mutableListOf<Row>()
        val title: String

        if (screen != Screen.HOME && openListId != Library.FAV_ID && store.get(openListId) == null) {
            screen = Screen.HOME
            openListId = null
        }

        when (screen) {
            Screen.HOME -> {
                title = getString(R.string.my_playlists)
                store.sorted().filter { match(it.name) }
                    .forEach { rows += Row.PlaylistRow(it, tracksOf(it).size) }
                fab.text = getString(R.string.new_playlist)
                fab.setIconResource(R.drawable.ic_add_dark)
            }

            Screen.LIST -> {
                val isFav = openListId == Library.FAV_ID
                title = if (isFav) getString(R.string.favorites) else store.get(openListId)!!.name
                val tracks = if (isFav) favoriteTracks() else tracksOf(store.get(openListId)!!)
                if (tracks.isNotEmpty() && q.isEmpty()) {
                    rows += Row.Action(ACT_PLAY_LIST, getString(R.string.play_playlist), R.drawable.ic_play)
                    rows += Row.Action(ACT_SHUFFLE_LIST, getString(R.string.shuffle_playlist), R.drawable.ic_shuffle)
                }
                if (isFav) {
                    favs.folderPaths().sorted()
                        .map { it to tracksInFolder(it).size }
                        .filter { (p, n) -> n > 0 && match(p.substringAfterLast('/')) }
                        .forEach { (p, n) -> rows += Row.FolderRow(p, FolderTree.displayName(p), n) }
                    favs.trackIds().mapNotNull { trackFor(it) }
                        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
                        .filter { match(it.title) }
                        .forEach { rows += Row.TrackRow(it) }
                } else {
                    tracks.filter { match(it.title) }.forEach { rows += Row.TrackRow(it) }
                }
                fab.text = getString(R.string.add_songs)
                fab.setIconResource(R.drawable.ic_add_dark)
            }

            Screen.PICKER -> {
                val p = store.get(openListId)!!
                val f = effectiveFolder()
                title = getString(R.string.adding_to, p.name) +
                    (f?.let { "  ›  " + FolderTree.displayName(it) } ?: "")
                val atStart = !pickerCanGoUpFolder()
                if (needsFullAccess() && atStart && q.isEmpty()) {
                    rows += Row.Action(ACT_FULL_ACCESS, getString(R.string.full_access_row), R.drawable.ic_lock_open)
                }
                // Atalhos: pastas favoritas no topo do início
                if (atStart && q.isEmpty()) {
                    favs.folderPaths()
                        .filter { it != f && allTracks.any { t -> FolderTree.isInside(t.dir, it) } }
                        .sortedBy { it.substringAfterLast('/').lowercase() }
                        .forEach { rows += Row.FolderRow(it, FolderTree.displayName(it), tracksInFolder(it).size) }
                }
                // Marcar / desmarcar a pasta aberta como favorita
                if (f != null && q.isEmpty() && !(f in volumes)) {
                    rows += if (favs.isFolder(f)) {
                        Row.Action(ACT_TOGGLE_FAV_FOLDER, getString(R.string.unfavorite_folder), R.drawable.ic_star)
                    } else {
                        Row.Action(ACT_TOGGLE_FAV_FOLDER, getString(R.string.favorite_folder), R.drawable.ic_star_outline)
                    }
                }
                if (f == null) {
                    volumes.forEach { v ->
                        rows += Row.FolderRow(v, FolderTree.displayName(v), tracksInFolder(v).size)
                    }
                } else {
                    val inside = tracksInFolder(f)
                    if (inside.isNotEmpty() && q.isEmpty()) {
                        rows += Row.Action(
                            ACT_ADD_FOLDER,
                            getString(R.string.add_whole_folder, inside.size),
                            R.drawable.ic_add
                        )
                    }
                    FolderTree.subfolders(allTracks, f).filter { match(it.name) }
                        .forEach { rows += Row.FolderRow(it.path, it.name, it.count) }
                    val tracks = if (q.isEmpty()) allTracks.filter { it.dir == f }
                    else inside.filter { match(it.title) }
                    tracks.forEach { rows += Row.TrackRow(it) }
                }
                fab.text = getString(R.string.done)
                fab.setIconResource(R.drawable.ic_check_dark)
            }
        }

        fab.visibility = if (screen == Screen.LIST && openListId == Library.FAV_ID) View.GONE else View.VISIBLE

        visibleTracks = rows.filterIsInstance<Row.TrackRow>().map { it.track }
        adapter.submit(rows)
        adapter.setCurrent(controller?.currentMediaItem?.mediaId?.let { Library.pathOf(it) })

        navTitle.text = title
        navBack.visibility = if (screen == Screen.HOME) View.GONE else View.VISIBLE
        backCallback.isEnabled = screen != Screen.HOME

        val empty = when {
            !hasMediaPermission() -> getString(R.string.no_permission)
            rows.isNotEmpty() -> null
            q.isNotEmpty() -> getString(R.string.no_results)
            screen == Screen.LIST && openListId == Library.FAV_ID -> getString(R.string.favorites_empty)
            screen == Screen.LIST -> getString(R.string.playlist_empty)
            screen == Screen.PICKER && allTracks.isEmpty() -> getString(R.string.no_files)
            else -> null
        }
        if (empty == null) emptyView.visibility = View.GONE else showEmpty(empty)
    }

    private fun showEmpty(msg: String) {
        emptyView.text = msg
        emptyView.visibility = View.VISIBLE
    }

    /** Marca antes do nome: ✓ = já está na playlist (no seletor), ★ = favorito. */
    private fun prefixOf(row: Row): String {
        val sb = StringBuilder()
        if (row is Row.TrackRow && screen == Screen.PICKER &&
            store.get(openListId)?.items?.contains(row.track.id) == true
        ) sb.append("✓ ")
        val fav = when (row) {
            is Row.FolderRow -> favs.isFolder(row.path)
            else -> false
        }
        if (fav) sb.append("★ ")
        return sb.toString()
    }

    // ================================================================ toques na lista

    private fun onRowClick(row: Row) {
        when (row) {
            is Row.PlaylistRow -> openList(row.playlist.id)

            is Row.FolderRow -> when (screen) {
                Screen.PICKER -> {
                    pickFolder = row.path
                    clearSearchAndRender()
                }
                else -> playTracks(tracksInFolder(row.path), 0, listId = null, listName = row.name)
            }

            is Row.TrackRow -> when (screen) {
                Screen.PICKER -> togglePicked(row.track)
                else -> {
                    val i = visibleTracks.indexOfFirst { it.id == row.track.id }.coerceAtLeast(0)
                    playCurrentList(i)
                }
            }

            is Row.Action -> when (row.action) {
                ACT_FAVORITES -> openList(Library.FAV_ID)
                ACT_PLAY_LIST -> playCurrentList(0)
                ACT_SHUFFLE_LIST -> playCurrentList(0, shuffle = true)
                ACT_FULL_ACCESS -> openFullAccessSettings()
                ACT_TOGGLE_FAV_FOLDER -> effectiveFolder()?.let {
                    val now = favs.toggleFolder(it)
                    toast(getString(if (now) R.string.folder_favorited else R.string.folder_unfavorited))
                    render()
                }
                ACT_ADD_FOLDER -> {
                    val p = store.get(openListId) ?: return
                    val f = effectiveFolder() ?: return
                    addTo(p, tracksInFolder(f).map { it.id })
                }
            }
        }
    }

    private fun togglePicked(t: Track) {
        val p = store.get(openListId) ?: return
        if (t.id in p.items) {
            store.remove(p.id, t.id)
            toast(getString(R.string.removed_from, p.name))
        } else {
            store.add(p.id, listOf(t.id))
            toast(getString(R.string.added_one, p.name))
        }
        render()
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
        val playlist = if (screen == Screen.LIST) store.get(openListId) else null
        val options = mutableListOf<Pair<String, () -> Unit>>()
        options += getString(R.string.add_to_playlist) to { choosePlaylist(listOf(track.id)) }
        if (playlist != null) {
            options += getString(R.string.move_up) to { store.move(playlist.id, track.id, -1); render() }
            options += getString(R.string.move_down) to { store.move(playlist.id, track.id, +1); render() }
            options += getString(R.string.remove_from_playlist) to {
                store.remove(playlist.id, track.id); render()
            }
        }
        options += getString(R.string.rename_file) to { renameTrack(track) }
        showOptions(track.title, options)
    }

    private fun folderMenu(row: Row.FolderRow) {
        val tracks = tracksInFolder(row.path)
        val options = mutableListOf<Pair<String, () -> Unit>>()
        if (screen == Screen.PICKER) {
            store.get(openListId)?.let { p ->
                options += getString(R.string.add_whole_folder, tracks.size) to { addTo(p, tracks.map { it.id }) }
            }
        }
        options += getString(R.string.play_folder) to { playTracks(tracks, 0, null, row.name) }
        options += getString(R.string.shuffle_folder) to { playTracks(tracks, 0, null, row.name, shuffle = true) }
        options += getString(
            if (favs.isFolder(row.path)) R.string.unfavorite else R.string.favorite
        ) to { favs.toggleFolder(row.path); render() }
        showOptions(row.name, options)
    }

    private fun playlistMenu(p: Playlist) {
        showOptions(
            p.name,
            listOf(
                getString(R.string.play_playlist) to { openListId = p.id; playTracks(tracksOf(p), 0, p.id, p.name) },
                getString(R.string.shuffle_playlist) to { playTracks(tracksOf(p), 0, p.id, p.name, shuffle = true) },
                getString(R.string.add_songs) to { openListId = p.id; openPicker() },
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
    private fun choosePlaylist(trackIds: List<String>) {
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
        toast(msg)
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
                    favs.renamePath(oldPath, newPath)
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
            val item = c.getMediaItemAt(i)
            if (Library.pathOf(item.mediaId) != oldPath) continue
            val listId = item.mediaMetadata.extras?.getString(Library.EXTRA_LIST)
            val meta = item.mediaMetadata
            c.replaceMediaItem(
                i,
                Library.mediaItem(
                    Library.trackFromFile(target), listId, meta.albumTitle?.toString(),
                    (meta.trackNumber ?: 1) - 1, meta.totalTrackCount ?: c.mediaItemCount
                )
            )
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // ================================================================ reprodução

    /** Toca a lista aberta (playlist ou favoritos), começando na faixa [index] das visíveis. */
    private fun playCurrentList(index: Int, shuffle: Boolean = false) {
        val id = openListId ?: return
        val name = Library.listName(this, id)
        val tracks = if (id == Library.FAV_ID) favoriteTracks() else store.get(id)?.let { tracksOf(it) }.orEmpty()
        // Índice da faixa tocada dentro da lista completa
        val chosen = visibleTracks.getOrNull(index)
        val start = chosen?.let { c -> tracks.indexOfFirst { it.id == c.id } }?.takeIf { it >= 0 } ?: 0
        playTracks(tracks, start, id, name, shuffle)
    }

    private fun playTracks(
        tracks: List<Track>,
        startIndex: Int,
        listId: String?,
        listName: String?,
        shuffle: Boolean = false
    ) {
        val c = controller ?: return
        if (tracks.isEmpty()) return
        c.shuffleModeEnabled = shuffle
        val start = if (shuffle) tracks.indices.random() else startIndex.coerceIn(tracks.indices)
        val items = tracks.mapIndexed { i, t -> Library.mediaItem(t, listId, listName, i, tracks.size) }
        c.setMediaItems(items, start, 0L)
        c.prepare()
        c.play()
    }

    private fun togglePlay() {
        val c = controller ?: return
        if (c.mediaItemCount == 0) {
            if (screen == Screen.LIST) playCurrentList(0)
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

    // ================================================================ painel do leitor

    private fun updatePlayerUi() {
        val c = controller ?: return
        val item = c.currentMediaItem
        val meta = item?.mediaMetadata
        val songTitle = meta?.title
        val listTitle = meta?.albumTitle
        nowPlaying.text = when {
            songTitle == null -> getString(R.string.nothing_playing)
            listTitle.isNullOrBlank() -> songTitle
            else -> "$songTitle  ·  $listTitle"
        }

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

        adapter.setCurrent(item?.mediaId?.let { Library.pathOf(it) })
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
        private const val ACT_FAVORITES = 1
        private const val ACT_PLAY_LIST = 2
        private const val ACT_SHUFFLE_LIST = 3
        private const val ACT_FULL_ACCESS = 4
        private const val ACT_ADD_FOLDER = 5
        private const val ACT_TOGGLE_FAV_FOLDER = 6
    }
}
