package org.androidaudioplugin.minidahost

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.SoundPool
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.androidaudioplugin.ParameterInformation
import org.androidaudioplugin.PluginInformation
import org.androidaudioplugin.hosting.AudioPluginClientBase
import org.androidaudioplugin.hosting.AudioPluginHostHelper
import org.androidaudioplugin.hosting.NativeRemotePluginInstance
import org.androidaudioplugin.hosting.UmpHelper
import org.androidaudioplugin.manager.PluginPlayer
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sin

private const val DEFAULT_CONTROL_BYTES_PER_BLOCK = 0x10000
private const val NOTE_VELOCITY_16 = 12000
private const val MAX_KNOBS_PER_DEVICE = 8
private const val METER_LOG_TAG = "MiniDAHost"
private const val PICK_AUDIO = 1
private const val PICK_KIT_ZIP = 2
private const val PICK_KIT_FOLDER = 3
private const val MAX_AUDIO_BYTES = 32 * 1024 * 1024

class MainActivity : Activity() {
    private class Device(val info: PluginInformation, val instance: NativeRemotePluginInstance,
                         val group: Int, val parameters: MutableMap<Int, Double>) {
        val ayumi = info.pluginId == "urn:org.androidaudioplugin/samples/aappluginsample/InstrumentSample"
        val sampleEffect = info.pluginId == "urn:org.androidaudioplugin/samples/aappluginsample/EffectSample"
        val noteChannels = mutableMapOf<Int, Int>()
        val knobs = mutableListOf<Pair<Int, KnobView>>()
    }
    private data class NoteKey(val trackId: String, val pitch: Int)
    private data class LiveNote(val track: ProjectTrack, val start: Long, val recorded: ClipNote?)
    private data class TrackSnap(val id: String, val gain: Float, val muted: Boolean, val soloed: Boolean,
                                 val drum: Boolean, val hasClip: Boolean, val notes: List<ClipNote>)
    private data class UndoSnapshot(val bpm: Int, val masterGain: Float, val masterMuted: Boolean,
                                    val tracks: List<TrackSnap>, val arrangement: List<ArrangementClip>)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val client by lazy { AudioPluginClientBase(applicationContext) }
    private val store by lazy { ProjectStore(this) }
    private val kitStore by lazy { DrumKitStore(this) }
    private val builtinNames = linkedMapOf("sub" to "Sub Bass", "poly" to "Poly Synth", "drums" to "Drum Kit")
    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private val sampleRate by lazy {
        audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toInt() ?: 48000
    }
    private val framesPerCallback by lazy {
        intent.getIntExtra("fpc", 0).takeIf { it > 0 }
            ?: audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toInt() ?: 96
    }
    private var project: DawProject? = null
    private val devicesById = mutableMapOf<String, Device>()
    private val audioIds = mutableMapOf<String, Int>()
    private val builtinIds = mutableMapOf<String, Int>()
    private var samplePreview: MediaPlayer? = null
    private var kitTargetTrack: String? = null
    private var kitTargetPitch: Int? = null
    private var pendingKitRequest: Int? = null
    private var pendingKitUri: Uri? = null
    private var player: PluginPlayer? = null
    private var processing = false
    private var stopping = false
    private var loading = false
    private var foreground = false
    private var selected = -1
    private var rollOpen = false
    private var mixerOpen = false
    private var devicesOpen = false
    private var recording = false
    private var metronomeOn = false
    private var soundPool: SoundPool? = null
    private var clickSound = 0
    private var accentSound = 0
    private val tapTimes = ArrayDeque<Long>()
    private var position = 0.0
    private var lastFrames = 0L
    private var scheduledStep = 0L
    private var sequencerJob: Job? = null
    private var meterJob: Job? = null
    private var saveJob: Job? = null
    private var pendingAudioTrack: String? = null
    private var pendingAudioUri: Uri? = null
    private val pendingOffs = mutableMapOf<NoteKey, Long>()
    private val liveNotes = mutableMapOf<Int, LiveNote>()
    private val stripMeters = mutableListOf<Pair<ProjectTrack, PeakMeterView>>()
    private val paramBuffer = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder())
    private val prefs by lazy { getPreferences(Context.MODE_PRIVATE) }
    private val undoStack = ArrayDeque<UndoSnapshot>()
    private val collapsedDevices = mutableSetOf<String>()
    private val devicePage = mutableMapOf<String, Int>()
    private var showKeyNames = true
    private var ghostNotes = true
    private val animTarget = HashMap<View, Boolean>()

    private lateinit var status: TextView
    private lateinit var transportButton: Button
    private lateinit var recordButton: Button
    private lateinit var bpmLabel: TextView
    private lateinit var positionLabel: TextView
    private lateinit var rollLabel: TextView
    private lateinit var playlist: PlaylistView
    private val audioDurationFrames = mutableMapOf<String, Long>()
    private var insertLane: Int? = null
    private var insertStep = 0
    private lateinit var rackStack: LinearLayout
    private lateinit var rackTitle: TextView
    private lateinit var masterFxButton: Button
    private lateinit var sidebar: LinearLayout
    private lateinit var editorPanel: LinearLayout
    private lateinit var mixerPanel: LinearLayout
    private lateinit var masterChannel: LinearLayout
    private lateinit var mixerScroll: HorizontalScrollView
    private lateinit var mixerStrips: LinearLayout
    private lateinit var quickControls: LinearLayout
    private lateinit var quickGain: TextView
    private lateinit var arrangeButton: Button
    private lateinit var mixerButton: Button
    private lateinit var devicesButton: Button
    private lateinit var undoButton: Button
    private lateinit var drumButton: Button
    private lateinit var padsButton: Button
    private lateinit var namesButton: Button
    private lateinit var ghostButton: Button
    private lateinit var octaveDown: Button
    private lateinit var octaveUp: Button
    private lateinit var arrangementPanel: LinearLayout
    private lateinit var rollPanel: LinearLayout
    private lateinit var meter: PeakMeterView
    private lateinit var quickMeter: PeakMeterView
    private lateinit var pianoRoll: PianoRollView
    private lateinit var keyboard: KeyboardView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.hide()
        Flm.applyTheme(prefs.getInt("theme", 0))
        showKeyNames = prefs.getBoolean("keyNames", true)
        prepareClicks()
        ghostNotes = prefs.getBoolean("ghost", true)
        pendingAudioTrack = savedInstanceState?.getString("pendingAudioTrack")
        pendingAudioUri = savedInstanceState?.getString("pendingAudioUri")?.let { Uri.parse(it) }
        kitTargetTrack = savedInstanceState?.getString("kitTargetTrack")
        kitTargetPitch = savedInstanceState?.getInt("kitTargetPitch", -1)?.takeIf { it >= 0 }
        pendingKitRequest = savedInstanceState?.getInt("pendingKitRequest", -1)?.takeIf { it >= 0 }
        pendingKitUri = savedInstanceState?.getString("pendingKitUri")?.let { Uri.parse(it) }
        showProjects()
        savedInstanceState?.getString("projectId")?.let { id ->
            scope.launch {
                openProject(id)
                if (project?.id != id) return@launch
                val index = savedInstanceState.getInt("selected", -1)
                if (index in project?.tracks.orEmpty().indices)
                    selectTrack(index, savedInstanceState.getBoolean("rollOpen") && project!!.tracks[index].clip != null)
                mixerOpen = savedInstanceState.getBoolean("mixerOpen")
                devicesOpen = savedInstanceState.getBoolean("devicesOpen")
                renderTracks()
                insertLane = savedInstanceState.getInt("insertLane", -1).takeIf { it >= 0 }
                insertStep = savedInstanceState.getInt("insertStep", 0)
                consumeAudioResult()
                consumeKitResult()
            }
        }
    }

    private fun rootLayout() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Flm.bg)
        fitsSystemWindows = true
    }

    private fun showProjects() {
        val root = rootLayout().apply { setPadding(dp(28), dp(16), dp(28), dp(16)) }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(label("Floton  /  Projects", 20f).apply { setTextColor(Flm.text) },
            LinearLayout.LayoutParams(0, dp(48), 1f))
        header.addView(button("NEW PROJECT") { newProjectDialog() }, LinearLayout.LayoutParams(dp(148), dp(38)))
        root.addView(header)
        root.addView(label("Create an empty project or open a saved one", 12f),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(32)))
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        try {
            val projects = store.list()
            if (projects.isEmpty()) list.addView(label("No saved projects", 14f))
            projects.forEach { saved ->
                list.addView(button("${saved.name}   ·   ${tracksLabel(saved.tracks.size)}   ·   ${saved.bpm} BPM") {
                    scope.launch { openProject(saved.id) }
                }.apply {
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    setPadding(dp(16), 0, dp(16), 0)
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply {
                        bottomMargin = dp(8)
                    }
                })
            }
        } catch (e: Exception) {
            list.addView(label("Cannot read projects: ${e.message}", 12f))
        }
        root.addView(ScrollView(this).apply { addView(list) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun tracksLabel(count: Int) = if (count == 1) "1 track" else "$count tracks"

    private fun prepareClicks() {
        try {
            val pool = SoundPool.Builder().setMaxStreams(4).build()
            clickSound = pool.load(clickFile("minidaw-click.wav", 1200.0, 45), 1)
            accentSound = pool.load(clickFile("minidaw-accent.wav", 1800.0, 55), 1)
            soundPool = pool
        } catch (e: Exception) {
            Log.w(METER_LOG_TAG, "Metronome click unavailable", e)
            soundPool = null
        }
    }

    // Generates the metronome clicks once into the cache, so no assets are needed in the APK.
    private fun clickFile(name: String, frequency: Double, millis: Int): String {
        val file = File(cacheDir, name)
        if (!file.exists()) {
            val sampleRate = 44100
            val frames = sampleRate * millis / 1000
            val pcm = ByteArray(frames * 2)
            for (frame in 0 until frames) {
                val seconds = frame.toDouble() / sampleRate
                val envelope = exp(-seconds * 90.0)
                val value = (sin(2 * Math.PI * frequency * seconds) * envelope * 0.45 * Short.MAX_VALUE).toInt()
                pcm[frame * 2] = (value and 0xff).toByte()
                pcm[frame * 2 + 1] = ((value shr 8) and 0xff).toByte()
            }
            FileOutputStream(file).use { out ->
                out.write("RIFF".toByteArray())
                out.write(littleEndianInt(36 + pcm.size))
                out.write("WAVEfmt ".toByteArray())
                out.write(littleEndianInt(16))
                out.write(littleEndianShort(1))
                out.write(littleEndianShort(1))
                out.write(littleEndianInt(sampleRate))
                out.write(littleEndianInt(sampleRate * 2))
                out.write(littleEndianShort(2))
                out.write(littleEndianShort(16))
                out.write("data".toByteArray())
                out.write(littleEndianInt(pcm.size))
                out.write(pcm)
            }
        }
        return file.absolutePath
    }

    private fun littleEndianInt(value: Int) = byteArrayOf(
        (value and 0xff).toByte(), ((value shr 8) and 0xff).toByte(),
        ((value shr 16) and 0xff).toByte(), ((value shr 24) and 0xff).toByte())

    private fun littleEndianShort(value: Int) = byteArrayOf(
        (value and 0xff).toByte(), ((value shr 8) and 0xff).toByte())

    private fun toggleMetronome() {
        metronomeOn = !metronomeOn
        prefs.edit().putBoolean("metronome", metronomeOn).apply()
        status.text = if (metronomeOn) "Metronome on" else "Metronome off"
    }

    private fun tapTempo() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (tapTimes.isNotEmpty() && now - tapTimes.last() > 3000) tapTimes.clear()
        tapTimes.addLast(now)
        while (tapTimes.size > 5) tapTimes.removeFirst()
        if (tapTimes.size < 2) { status.text = "Tap BPM again"; return }
        val average = tapTimes.zipWithNext { first, second -> second - first }.average()
        val bpm = (60000.0 / average).roundToInt().coerceIn(40, 240)
        val current = project ?: return
        if (bpm == current.bpm) return
        current.bpm = bpm
        bpmLabel.text = "$bpm BPM"
        refreshClips(); scheduleSave()
        status.text = "Tempo $bpm BPM"
    }

    private fun nameInput(initial: String) = EditText(this).apply {
        setSingleLine()
        imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
        setText(initial)
        selectAll()
        filters = arrayOf(android.text.InputFilter.LengthFilter(80))
        textSize = 14f
        setTextColor(Flm.text)
        setHintTextColor(Flm.textDim)
        background = GradientDrawable().apply {
            setColor(Flm.panelAlt)
            cornerRadius = dp(10).toFloat()
        }
        setPadding(dp(12), dp(10), dp(12), dp(10))
    }

    private fun renameProject() {
        val current = project ?: return
        if (loading || stopping) return
        val input = nameInput(current.name)
        Sheet(this, "Rename project", "Shown in the projects list").apply {
            custom(input)
            primary("SAVE") {
                val name = input.text.toString().trim()
                if (name.isEmpty()) { input.error = "Enter a name"; return@primary false }
                current.name = name
                if (!saveNow()) { input.error = "Cannot save"; return@primary false }
                status.text = "$name · autosave"
                true
            }
        }.present()
        input.requestFocus()
    }

    private fun renameTrack(index: Int) {
        val current = project ?: return
        val track = current.tracks.getOrNull(index) ?: return
        if (loading || stopping) return
        val input = nameInput(track.name)
        Sheet(this, "Rename track", "Track ${index + 1}").apply {
            custom(input)
            primary("SAVE") {
                val name = input.text.toString().trim()
                if (name.isEmpty()) { input.error = "Enter a name"; return@primary false }
                track.name = name
                refreshClips(); renderTracks(); scheduleSave()
                true
            }
        }.present()
        input.requestFocus()
    }

    private fun duplicateTrack(index: Int) {
        val current = project ?: return
        val source = current.tracks.getOrNull(index) ?: return
        if (loading || stopping) return
        if (current.tracks.size >= 16) { status.text = "Maximum 16 tracks"; return }
        val id = current.id
        scope.launch {
            stopTransport()
            val copy = ProjectTrack(
                name = "${source.name} copy",
                pluginId = source.pluginId,
                audioFile = source.audioFile,
                audioName = source.audioName,
                clip = source.clip?.let { clip ->
                    Clip().also { target -> target.notes.addAll(clip.notes.map { it.copy() }) }
                },
                gain = source.gain,
                muted = source.muted,
                soloed = source.soloed,
                drum = source.drum,
                parameters = HashMap(source.parameters),
                builtinId = source.builtinId,
                samples = HashMap(source.samples),
                sampleNames = HashMap(source.sampleNames)
            )
            current.tracks.add(copy)
            if (copy.clip != null || copy.audioFile != null) {
                val lane = ((current.arrangement.maxOfOrNull { it.lane } ?: -1) + 1).coerceAtMost(PLAYLIST_LANES - 1)
                current.arrangement.add(ArrangementClip(trackId = copy.id, lane = lane, startStep = 0))
            }
            if (!saveNow()) return@launch
            openProject(id)
            status.text = "${source.name} duplicated"
        }
    }

    private fun newProjectDialog() {
        val input = EditText(this).apply {
            setSingleLine()
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setText("Untitled ${store.list().size + 1}")
            selectAll()
            filters = arrayOf(android.text.InputFilter.LengthFilter(80))
            textSize = 14f
            setTextColor(Flm.text)
            setHintTextColor(Flm.textDim)
            background = GradientDrawable().apply {
                setColor(Flm.panelAlt)
                cornerRadius = dp(10).toFloat()
            }
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        Sheet(this, "New empty project", "Name your session").apply {
            custom(input)
            primary("CREATE") {
                val name = input.text.toString().trim()
                if (name.isEmpty()) { input.error = "Enter a project name"; return@primary false }
                val created = DawProject(name = name)
                try { store.save(created) } catch (e: Exception) {
                    input.error = "Cannot save: ${e.message}"
                    return@primary false
                }
                scope.launch { openProject(created.id) }
                true
            }
        }.present()
        input.requestFocus()
    }

    private suspend fun openProject(id: String) {
        if (loading || stopping) return
        val saved = try { store.load(id) } catch (e: Exception) {
            Sheet(this, "Cannot open project", e.message).apply { primary("OK") { true } }.present()
            return
        }
        stopTransport()
        if (!saveNow()) return
        releaseEngine()
        project = saved
        selected = -1
        rollOpen = false
        mixerOpen = false
        devicesOpen = false
        clearUndo()
        buildUi()
        loading = true
        status.text = "Opening ${saved.name}…"
        val problems = mutableListOf<String>()
        val installed = installedPlugins()
        try {
            saved.tracks.forEach { track ->
                if (track.builtinId != null) {
                    try { attachBuiltin(track) }
                    catch (e: Exception) { problems.add("${track.name}: ${e.message}") }
                } else if (track.pluginId == null) {
                    val nativeId = ensurePlayer().addAudioTrack()
                    audioIds[track.id] = nativeId
                    track.audioFile?.let { name ->
                        try {
                            val file = store.audioFile(saved, name)
                            require(file.length() in 1..MAX_AUDIO_BYTES.toLong()) { "Audio missing or too large" }
                            require(ensurePlayer().loadAudioTrack(nativeId, file.readBytes(), name)) { "Unsupported or oversized audio" }
                            audioDurationFrames[track.id] = ensurePlayer().getAudioTrackDurationFrames(nativeId)
                        } catch (e: Exception) { problems.add("${track.name}: ${e.message}") }
                    }
                } else {
                    val info = installed.firstOrNull { it.pluginId == track.pluginId }
                    if (info == null) problems.add("Install ${track.name}")
                    else try { attachDevice(track.id, info, track.parameters, true) }
                    catch (e: Exception) { problems.add("${track.name}: ${e.message}") }
                }
            }
            saved.effects.forEach { effect ->
                val info = installed.firstOrNull { it.pluginId == effect.pluginId }
                if (info == null) problems.add("Install ${effect.name}")
                else try { attachDevice(effect.id, info, effect.parameters, false) }
                catch (e: Exception) { problems.add("${effect.name}: ${e.message}") }
            }
            applyMix()
        } finally {
            loading = false
            renderTracks()
            status.text = if (problems.isEmpty()) "${saved.name} · autosave" else problems.joinToString(" · ")
        }
    }

    private fun buildUi() {
        val root = rootLayout()
        val bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Flm.panel)
        }
        metronomeOn = prefs.getBoolean("metronome", false)
        bar.addView(button("MENU") { showMainMenu() }, LinearLayout.LayoutParams(dp(64), dp(34)))
        transportButton = button("PLAY") { scope.launch { toggleTransport() } }
        recordButton = button("REC") { toggleRecording() }
        bar.addView(transportButton, LinearLayout.LayoutParams(dp(58), dp(34)))
        bar.addView(recordButton, LinearLayout.LayoutParams(dp(46), dp(34)))
        bar.addView(button("−") { changeTempo(-5) }, LinearLayout.LayoutParams(dp(30), dp(34)))
        bpmLabel = label("${project!!.bpm} BPM", 12f)
        bpmLabel.setOnClickListener { tapTempo() }
        bar.addView(bpmLabel, LinearLayout.LayoutParams(dp(74), dp(34)))
        bar.addView(button("+") { changeTempo(5) }, LinearLayout.LayoutParams(dp(30), dp(34)))
        positionLabel = label("1:01", 12f)
        bar.addView(positionLabel, LinearLayout.LayoutParams(dp(56), dp(34)))
        status = label("${project!!.name} · autosave", 11f)
        status.setOnClickListener { showMainMenu() }
        bar.addView(status, LinearLayout.LayoutParams(0, dp(34), 1f))
        bar.addView(button("+ TRACK") { insertLane = null; insertStep = 0; showTrackMenu() }, LinearLayout.LayoutParams(dp(74), dp(34)))
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)))

        val workspace = LinearLayout(this)
        sidebar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Flm.panel)
            visibility = View.GONE
        }
        val rackHeader = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        rackHeader.addView(label("RACK / INSPECTOR", 11f), LinearLayout.LayoutParams(0, dp(32), 1f))
        rackHeader.addView(button("≡") { showRackMenu() }, LinearLayout.LayoutParams(dp(28), dp(28)))
        rackHeader.addView(button("×") { toggleDevices() }, LinearLayout.LayoutParams(dp(28), dp(28)))
        sidebar.addView(rackHeader)
        val rackTitleRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        rackTitle = label("MASTER", 11f)
        rackTitleRow.addView(rackTitle, LinearLayout.LayoutParams(0, dp(28), 1f))
        masterFxButton = button("+ MASTER FX") { showPluginPicker(false) }
        rackTitleRow.addView(masterFxButton, LinearLayout.LayoutParams(dp(98), dp(28)))
        sidebar.addView(rackTitleRow)
        rackStack = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sidebar.addView(ScrollView(this).apply { addView(rackStack) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        editorPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        arrangementPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        playlist = PlaylistView(this).apply {
            contentDescription = "Playlist grid"
            project = this@MainActivity.project
            canEdit = { canEditPlaylist() }
            onInsert = { lane, step -> showPlaylistInsert(lane, step) }
            onSelect = { item, open ->
                val index = this@MainActivity.project!!.tracks.indexOfFirst { it.id == item.trackId }
                if (index >= 0) selectTrack(index, open)
            }
            onMove = { id, lane, step ->
                if (canEditPlaylist()) {
                    val items = this@MainActivity.project!!.arrangement
                    val index = items.indexOfFirst { it.id == id }
                    if (index >= 0) {
                        pushUndo()
                        items[index] = items[index].copy(lane = lane, startStep = step)
                        selectedClip = id
                        refreshClips(); scheduleSave()
                    }
                }
            }
            onMenu = { showPlaylistClipMenu(it) }
            onTrackMenu = { lane -> showTrackSheet(lane) }
        }
        arrangementPanel.addView(playlist, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        arrangementPanel.addView(label("Tap empty: insert · Drag clip: move · Double tap: edit · Hold clip: menu · Hold track name: delete track", 10f),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(20)))
        editorPanel.addView(arrangementPanel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        rollPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        val rollHeader = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        rollLabel = label("PIANO ROLL", 11f)
        rollHeader.addView(rollLabel, LinearLayout.LayoutParams(0, dp(28), 1f))
        octaveDown = button("−8") { shiftOctave(-12) }
        rollHeader.addView(octaveDown, LinearLayout.LayoutParams(dp(34), dp(28)))
        octaveUp = button("+8") { shiftOctave(12) }
        rollHeader.addView(octaveUp, LinearLayout.LayoutParams(dp(34), dp(28)))
        drumButton = button("DRUM") { toggleDrumMode() }
        rollHeader.addView(drumButton, LinearLayout.LayoutParams(dp(50), dp(28)))
        padsButton = button("PADS") { project?.tracks?.getOrNull(selected)?.let { showDrumPads(it) } }
        rollHeader.addView(padsButton, LinearLayout.LayoutParams(dp(46), dp(28)))
        namesButton = button("NAMES") {
            showKeyNames = !showKeyNames
            prefs.edit().putBoolean("keyNames", showKeyNames).apply()
            refreshClips()
        }
        rollHeader.addView(namesButton, LinearLayout.LayoutParams(dp(52), dp(28)))
        ghostButton = button("GHOST") {
            ghostNotes = !ghostNotes
            prefs.edit().putBoolean("ghost", ghostNotes).apply()
            refreshClips()
        }
        rollHeader.addView(ghostButton, LinearLayout.LayoutParams(dp(52), dp(28)))
        rollHeader.addView(button("ERASE") {
            val notes = project!!.tracks.getOrNull(selected)?.clip?.notes
            if (notes != null && notes.isNotEmpty()) {
                pushUndo()
                notes.clear()
                refreshClips(); scheduleSave()
            }
        }, LinearLayout.LayoutParams(dp(52), dp(28)))
        rollHeader.addView(button("CLOSE") { closeRoll() }, LinearLayout.LayoutParams(dp(52), dp(28)))
        rollPanel.addView(rollHeader)
        pianoRoll = PianoRollView(this).apply {
            contentDescription = "Piano roll"
            onEditStart = { pushUndo() }
            onEdited = { refreshClips(); scheduleSave() }
            onViewportChanged = {
                keyboard.firstNote = lowPitch
                refreshClips()
            }
        }
        rollPanel.addView(pianoRoll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        rollPanel.addView(label("Drag note: move · Right edge: length · Empty ↑↓: scroll · Tap: add / delete", 10f),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(18)))
        editorPanel.addView(rollPanel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        workspace.addView(editorPanel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        mixerPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Flm.bg)
            visibility = View.GONE
        }
        val mixerHeader = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        mixerHeader.addView(label("MIXER  /  MASTER BUS", 11f), LinearLayout.LayoutParams(0, dp(28), 1f))
        mixerHeader.addView(label("Select channel · hold name for devices", 10f),
            LinearLayout.LayoutParams(dp(226), dp(28)))
        mixerPanel.addView(mixerHeader)
        val channels = LinearLayout(this)
        masterChannel = LinearLayout(this)
        channels.addView(masterChannel, LinearLayout.LayoutParams(dp(92), ViewGroup.LayoutParams.MATCH_PARENT))
        mixerStrips = LinearLayout(this)
        mixerScroll = HorizontalScrollView(this).apply {
            isFillViewport = true
            addView(mixerStrips, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        channels.addView(mixerScroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        mixerPanel.addView(channels, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        workspace.addView(mixerPanel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        workspace.addView(sidebar, LinearLayout.LayoutParams(dp(260), ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(workspace, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        keyboard = KeyboardView(this).apply {
            contentDescription = "Piano keyboard"
            firstNote = 60
            noteCount = 12
            onNote = { note, down -> playNote(note, down) }
            visibility = View.GONE
        }
        root.addView(keyboard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)))
        val footer = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Flm.panel)
        }
        arrangeButton = button("TRACKS") {
            if (mixerOpen) setMixerOpen(false) else closeRoll()
        }.apply { contentDescription = "Show arrangement or return from mixer" }
        mixerButton = button("MIXER") { setMixerOpen(!mixerOpen) }
        devicesButton = button("RACK") { toggleDevices() }
        undoButton = button("UNDO") { undo() }.apply {
            contentDescription = "Undo last edit"
            visibility = View.GONE
        }
        footer.addView(arrangeButton, LinearLayout.LayoutParams(dp(72), dp(32)))
        footer.addView(mixerButton, LinearLayout.LayoutParams(dp(72), dp(32)))
        footer.addView(devicesButton, LinearLayout.LayoutParams(dp(56), dp(32)))
        footer.addView(undoButton, LinearLayout.LayoutParams(dp(56), dp(32)))
        quickControls = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        footer.addView(quickControls, LinearLayout.LayoutParams(0, dp(32), 1f))
        footer.addView(label("MASTER", 10f).apply { setOnClickListener { selectMaster() } },
            LinearLayout.LayoutParams(dp(60), dp(32)))
        quickMeter = PeakMeterView(this).apply { contentDescription = "Master output meter" }
        footer.addView(quickMeter, LinearLayout.LayoutParams(dp(68), dp(20)).apply { marginEnd = dp(8) })
        root.addView(footer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36)))
        setContentView(root)
        renderTracks()
    }

    private fun label(value: String, size: Float) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(Flm.textDim)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(6), 0, dp(6), 0)
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
    }

    private fun button(value: String, onClick: () -> Unit) = Button(this).apply {
        text = value
        textSize = 11f
        minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
        setPadding(0, 0, 0, 0)
        setTextColor(Flm.text)
        val shape = GradientDrawable().apply {
            setColor(Flm.panelAlt)
            cornerRadius = dp(4).toFloat()
        }
        background = RippleDrawable(ColorStateList.valueOf(Flm.withAlpha(Flm.text, 36)), shape, shape)
        setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> view.animate().scaleX(0.93f).scaleY(0.93f).setDuration(90).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    view.animate().scaleX(1f).scaleY(1f).setDuration(140).setInterpolator(OvershootInterpolator(1.4f)).start()
            }
            false
        }
        setOnClickListener { onClick() }
    }

    private fun reveal(view: View, dx: Float = 0f, dy: Float = 0f, delay: Long = 0) {
        animTarget[view] = true
        view.animate().cancel()
        view.visibility = View.VISIBLE
        view.alpha = 0f
        view.translationX = dx
        view.translationY = dy
        view.animate().alpha(1f).translationX(0f).translationY(0f)
            .setStartDelay(delay).setDuration(220).setInterpolator(DecelerateInterpolator()).start()
    }

    private fun conceal(view: View, dx: Float = 0f, dy: Float = 0f) {
        animTarget[view] = false
        view.animate().cancel()
        view.animate().alpha(0f).translationX(dx).translationY(dy)
            .setDuration(160).setInterpolator(AccelerateInterpolator())
            .withEndAction {
                if (animTarget[view] == false) {
                    view.visibility = View.GONE
                    view.alpha = 1f
                    view.translationX = 0f
                    view.translationY = 0f
                }
            }.start()
    }

    private fun setPanel(view: View, visible: Boolean, dx: Float = 0f, dy: Float = 0f) {
        if (visible) {
            if (view.visibility != View.VISIBLE || animTarget[view] == false) reveal(view, dx, dy)
        } else if (view.visibility == View.VISIBLE) conceal(view, dx, dy)
    }

    private fun pulse(view: View) {
        view.animate().scaleX(1.07f).scaleY(1.07f).setDuration(90).withEndAction {
            view.animate().scaleX(1f).scaleY(1f).setDuration(160).setInterpolator(OvershootInterpolator(1.6f)).start()
        }.start()
    }

    private fun installedPlugins() = AudioPluginHostHelper.queryAudioPluginServices(this).flatMap { it.plugins }

    private fun pluginName(info: PluginInformation) = when (info.pluginId) {
        "urn:org.androidaudioplugin/samples/aappluginsample/InstrumentSample" -> "Ayumi Synth"
        "urn:org.androidaudioplugin/samples/aappluginsample/EffectSample" -> "Gain / Delay"
        else -> info.displayName
    }

    private fun canEditPlaylist(): Boolean {
        if (loading || stopping) return false
        if (processing) { status.text = "Stop playback to edit the playlist"; return false }
        return true
    }

    private fun placeSource(track: ProjectTrack, lane: Int? = insertLane, step: Int = insertStep): Boolean {
        val current = project ?: return false
        if (current.arrangement.size >= MAX_PLAYLIST_CLIPS) {
            status.text = "Maximum $MAX_PLAYLIST_CLIPS playlist clips"
            return false
        }
        if (track.clip == null && track.audioFile == null) return false
        val row = lane ?: ((current.arrangement.maxOfOrNull { it.lane } ?: -1) + 1).coerceAtMost(PLAYLIST_LANES - 1)
        val item = ArrangementClip(trackId = track.id, lane = row, startStep = step)
        current.arrangement.add(item)
        playlist.selectedClip = item.id
        insertLane = null; insertStep = 0
        refreshClips(); scheduleSave()
        return true
    }

    private fun showPlaylistInsert(lane: Int, step: Int) {
        if (!canEditPlaylist()) return
        insertLane = lane; insertStep = step
        val current = project ?: return
        Sheet(this, "Insert clip", "Track ${lane + 1} · bar ${step / 16 + 1} · step ${step % 16 + 1}").apply {
            row("New instrument", glyph = "+", subtitle = "Built-in synth, drums or AAP plugin") { showTrackMenu() }
            row("Import audio", glyph = "∿", subtitle = "WAV / MP3 / OGG / FLAC") { addAudioTrack() }
            current.tracks.forEach { source ->
                row(source.name, glyph = if (source.isInstrument) "♫" else "∿",
                    subtitle = if (source.isInstrument) "Insert linked pattern" else source.audioName ?: "Choose an audio file") {
                    if (canEditPlaylist()) {
                        if (!source.isInstrument && source.audioFile == null) chooseAudio(source)
                        else {
                            pushUndo()
                            if (source.isInstrument && source.clip == null) source.clip = Clip()
                            placeSource(source, lane, step)
                            selected = current.tracks.indexOf(source)
                            renderTracks()
                        }
                    }
                }
            }
        }.present()
    }

    private fun showPlaylistClipMenu(item: ArrangementClip) {
        if (!canEditPlaylist()) return
        val current = project ?: return
        val source = current.tracks.firstOrNull { it.id == item.trackId } ?: return
        Sheet(this, source.name, "Playlist clip · source stays in the mixer").apply {
            row(if (source.isInstrument) "Edit pattern" else "Open audio channel", glyph = "≋") {
                selectTrack(current.tracks.indexOf(source), source.isInstrument)
                if (!source.isInstrument) { devicesOpen = true; updateWorkspaceVisibility() }
            }
            row("Copy clip", glyph = "+", subtitle = "Place a linked copy after this clip") {
                if (canEditPlaylist()) {
                    pushUndo()
                    val length = if (source.isInstrument) 16 else kotlin.math.ceil(playlist.audioSteps[source.id]?.toDouble() ?: 16.0).toInt()
                    placeSource(source, item.lane, (item.startStep + length).coerceAtMost(PLAYLIST_STEPS - 1))
                }
            }
            row("Remove clip", glyph = "×", subtitle = "Keep the sound and original file") {
                if (canEditPlaylist()) {
                    pushUndo()
                    current.arrangement.removeAll { it.id == item.id }
                    playlist.selectedClip = null
                    refreshClips(); scheduleSave()
                }
            }
        }.present()
    }

    private fun showTrackSheet(index: Int) {
        if (loading || stopping) return
        val current = project ?: return
        val track = current.tracks.getOrNull(index) ?: return
        Sheet(this, "Track ${index + 1}", track.name).apply {
            row("Rename track", glyph = "✎", subtitle = "Give it a name you recognise") { renameTrack(index) }
            row("Duplicate track", glyph = "+", subtitle = "Copy with its instrument and pattern") { duplicateTrack(index) }
            row("Delete track", glyph = "×", color = Flm.danger,
                subtitle = "Remove the track with all its clips") {
                deleteTrack(index)
            }
        }.present()
    }

    private fun deleteTrack(index: Int) {
        val current = project ?: return
        val track = current.tracks.getOrNull(index) ?: return
        val id = current.id
        if (loading || stopping) return
        scope.launch {
            stopTransport()
            current.tracks.removeAt(index)
            current.arrangement.removeAll { it.trackId == track.id }
            if (selected >= current.tracks.size) selected = current.tracks.lastIndex
            rollOpen = false
            clearUndo()
            if (!saveNow()) return@launch
            // The native graph has no node removal API, so rebuild it from the saved project.
            openProject(id)
            status.text = "${track.name} removed"
        }
    }

    private fun showTrackMenu() {
        if (loading || stopping) return
        Sheet(this, "Add track", "What should the new lane record?").apply {
            row("Audio track", glyph = "∿", color = Flm.info, subtitle = "Import a WAV file as a one-shot clip") { addAudioTrack() }
            row("AAP instrument", glyph = "♫", color = Flm.accent, subtitle = "Host an installed plugin APK") { showPluginPicker(true) }
            row("Built-in instrument", glyph = "♫", subtitle = "Sub Bass, Poly Synth and Drum Kit") { showBuiltinPicker() }
            row("Drumkits", glyph = "≋", color = Flm.success, subtitle = "Browse samples or import a ZIP / folder") { showSoundLibrary() }
        }.present()
    }

    private fun showBuiltinPicker() {
        Sheet(this, "Built-in instruments", "Ready to play · no plugin APK needed").apply {
            builtinNames.forEach { (id, name) ->
                row(name, glyph = "♫", subtitle = when (id) {
                    "sub" -> "Deep sine bass with a soft attack"
                    "poly" -> "Polyphonic analog-style keys"
                    else -> "Kick, snare, clap, hats, tom, crash and ride"
                }) { scope.launch { addBuiltin(id) } }
            }
        }.present()
    }

    private fun instrumentReady(track: ProjectTrack) = track.id in devicesById || track.id in builtinIds

    private suspend fun attachBuiltin(track: ProjectTrack) {
        val kind = builtinNames.keys.indexOf(track.builtinId)
        require(kind >= 0) { "Unknown built-in instrument" }
        val p = ensurePlayer()
        val nativeId = p.addBuiltinInstrument(kind)
        require(nativeId >= 0) { "Cannot allocate instrument" }
        builtinIds[track.id] = nativeId
        for ((pitch, name) in track.samples) {
            val file = store.audioFile(project!!, name)
            require(file.length() in 1..MAX_AUDIO_BYTES.toLong()) { "Sample missing or too large" }
            val bytes = withContext(Dispatchers.IO) { file.readBytes() }
            require(p.loadBuiltinSample(nativeId, pitch, bytes, name)) { "Invalid or oversized sample: ${track.sampleNames[pitch] ?: name}" }
        }
    }

    private suspend fun addBuiltin(id: String): ProjectTrack? {
        val current = project ?: return null
        if (loading || stopping) return null
        if (current.tracks.size >= 16) { status.text = "Maximum 16 tracks"; return null }
        stopTransport()
        loading = true
        return try {
            val track = ProjectTrack(name = builtinNames.getValue(id), builtinId = id, drum = id == "drums", clip = Clip())
            attachBuiltin(track)
            current.tracks.add(track)
            placeSource(track)
            selected = current.tracks.lastIndex
            rollOpen = false
            clearUndo()
            applyMix(); renderTracks(); scheduleSave()
            status.text = "${track.name} · double tap its clip to edit notes"
            track
        } catch (e: Exception) {
            status.text = "Instrument: ${e.message}"
            null
        } finally { loading = false }
    }

    private fun showPluginPicker(instrument: Boolean) {
        if (loading || stopping) return
        val choices = installedPlugins().filter {
            it.category?.contains(if (instrument) "instrument" else "effect", true) == true
        }
        if (choices.isEmpty()) {
            status.text = "Install an AAP ${if (instrument) "instrument" else "effect"} APK"
            return
        }
        Sheet(this, if (instrument) "AAP instrument" else "Add MASTER effect", "Installed AAP plugins").apply {
            choices.forEach { info ->
                row(pluginName(info), glyph = if (instrument) "♫" else "≋",
                    color = if (instrument) Flm.accent else Flm.info, subtitle = info.packageName) {
                    scope.launch { addPlugin(info, instrument) }
                }
            }
        }.present()
    }

    private fun ensurePlayer() = player ?: PluginPlayer.create(sampleRate, framesPerCallback, 2).also { player = it }

    private suspend fun attachDevice(id: String, info: PluginInformation, parameters: MutableMap<Int, Double>, instrument: Boolean) {
        client.connectToPluginService(info.packageName)
        val instance = client.instantiateNativePlugin(info)
        val device = try {
            instance.prepare(framesPerCallback, sampleRate, DEFAULT_CONTROL_BYTES_PER_BLOCK)
            val group = (0..15).first { candidate -> devicesById.values.none { it.group == candidate } }
            Device(info, instance, group, parameters).also { device ->
                for (index in 0 until instance.getParameterCount()) {
                    val parameter = instance.getParameter(index)
                    parameters[parameter.id]?.let { saved ->
                        writeParameter(device, index, parameter, saved.coerceIn(parameter.minimumValue, parameter.maximumValue))
                    }
                }
            }
        } catch (e: Exception) {
            instance.destroy()
            throw e
        }
        val p = ensurePlayer()
        if (instrument) p.addPluginToSumBus(instance) else p.addMasterEffect(instance)
        p.setPluginMidiGroups(instance, 1 shl device.group)
        devicesById[id] = device
    }

    private suspend fun addPlugin(info: PluginInformation, instrument: Boolean) {
        val current = project ?: return
        if (loading || stopping) return
        val pluginCount = current.tracks.count { it.pluginId != null } + current.effects.size
        if (pluginCount >= 16 || (instrument && current.tracks.size >= 16) || (!instrument && current.effects.size >= 8)) {
            status.text = "Maximum 16 tracks / plugins and 8 master effects"; return
        }
        loading = true
        val restart = processing
        stopTransport()
        try {
            val pluginId = requireNotNull(info.pluginId) { "Plugin has no ID" }
            if (instrument) {
                val track = ProjectTrack(name = pluginName(info), pluginId = pluginId, clip = Clip())
                attachDevice(track.id, info, track.parameters, true)
                current.tracks.add(track)
                placeSource(track)
                selected = current.tracks.lastIndex
                rollOpen = false
            } else {
                val effect = ProjectEffect(pluginId = pluginId, name = pluginName(info))
                attachDevice(effect.id, info, effect.parameters, false)
                current.effects.add(effect)
                selected = -1
                rollOpen = false
                devicesOpen = true
            }
            clearUndo()
            applyMix(); renderTracks(); scheduleSave()
            status.text = if (instrument) "Double tap the playlist clip to edit notes" else "Master effect · after all tracks"
        } catch (e: Exception) {
            status.text = "Plugin error: ${e.message}"
            Log.e(METER_LOG_TAG, "Plugin load failed", e)
        } finally {
            loading = false
            if (restart && foreground) startTransport()
        }
    }

    private fun addAudioTrack() {
        if (!canEditPlaylist()) return
        val current = project ?: return
        if (current.tracks.size >= 16) { status.text = "Maximum 16 tracks"; return }
        val track = ProjectTrack(name = "Audio ${current.tracks.count { !it.isInstrument } + 1}")
        audioIds[track.id] = ensurePlayer().addAudioTrack()
        current.tracks.add(track)
        selected = current.tracks.lastIndex
        rollOpen = false
        clearUndo()
        applyMix(); renderTracks(); scheduleSave()
        status.text = "Choose WAV / MP3 / OGG / FLAC"
        chooseAudio(track)
    }

    private fun showSoundLibrary(target: ProjectTrack? = null, pitch: Int? = null) {
        if (loading || stopping) return
        stopSamplePreview()
        kitTargetTrack = target?.id
        kitTargetPitch = pitch
        val kits = kitStore.list()
        Sheet(this, "Sounds & drumkits", "Your sample library · originals stay untouched").apply {
            if (target == null) row("Built-in instruments", glyph = "♫", subtitle = "Sub Bass, Poly Synth and Drum Kit") { showBuiltinPicker() }
            row("Import ZIP", glyph = "+", subtitle = "Unpack audio into the library") { chooseKit(PICK_KIT_ZIP) }
            row("Import folder", glyph = "+", subtitle = "Include nested sample folders") { chooseKit(PICK_KIT_FOLDER) }
            kits.forEach { kit ->
                row(kit.name, glyph = "≋", color = Flm.success, subtitle = "${kit.samples.size} samples · tap to browse") { showKitSamples(kit) }
            }
            if (kits.isEmpty()) custom(label("No kits yet · import a ZIP or a sample folder", 12f))
        }.present()
    }

    private fun chooseKit(request: Int) {
        if (loading || stopping) return
        scope.launch {
            stopTransport()
            val intent = if (request == PICK_KIT_FOLDER) Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            else Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "*/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            startActivityForResult(intent, request)
        }
    }

    private fun consumeKitResult() {
        val uri = pendingKitUri ?: return
        val request = pendingKitRequest ?: return
        if (project == null || loading) return
        pendingKitUri = null
        pendingKitRequest = null
        scope.launch {
            stopTransport()
            loading = true
            status.text = "Importing drumkit…"
            try {
                val kit = withContext(Dispatchers.IO) {
                    if (request == PICK_KIT_FOLDER) kitStore.importFolder(uri) else kitStore.importZip(uri)
                }
                status.text = "Imported ${kit.samples.size} samples · ${kit.name}"
                showKitSamples(kit)
            } catch (e: Exception) {
                status.text = "Drumkit import: ${e.message}"
            } finally { loading = false }
        }
    }

    private fun previewSample(file: java.io.File) {
        stopSamplePreview()
        val preview = MediaPlayer()
        samplePreview = preview
        try {
            preview.setDataSource(file.absolutePath)
            preview.setVolume(0.7f, 0.7f)
            preview.setOnPreparedListener { if (samplePreview === it) it.start() }
            preview.setOnCompletionListener { if (samplePreview === it) stopSamplePreview() }
            preview.setOnErrorListener { _, _, _ ->
                stopSamplePreview()
                status.text = "Cannot preview this audio file"
                true
            }
            preview.prepareAsync()
        } catch (e: Exception) {
            stopSamplePreview()
            status.text = "Preview: ${e.message}"
        }
    }

    private fun stopSamplePreview() {
        samplePreview?.release()
        samplePreview = null
    }

    private fun previewButton(file: java.io.File, name: String) = button("▶") { previewSample(file) }.apply {
        contentDescription = "Preview sample · $name"
        layoutParams = LinearLayout.LayoutParams(dp(36), dp(32))
    }

    private fun showKitSamples(kit: Kit) {
        val sheet = Sheet(this, kit.name, "▶ preview · tap a sample to assign a pad")
        sheet.setOnDismissListener { stopSamplePreview() }
        sheet.apply {
            if (kitTargetPitch == null) row("Load kit to pads", glyph = "≋", color = Flm.success,
                subtitle = "Auto-map kick, snare, hats and more") { scope.launch { loadKit(kit) } }
            kit.samples.forEach { sample ->
                row(sample.name, trailing = previewButton(sample.file, sample.name),
                    subtitle = sample.pitch?.let { padName(it) } ?: "Choose a pad") {
                    val pitch = kitTargetPitch
                    if (pitch != null) scope.launch { assignSample(sample, pitch) }
                    else showSamplePads(sample)
                }
            }
        }.present()
    }

    private fun padName(pitch: Int): String {
        val index = PianoRollView.DRUM_PITCHES.indexOf(pitch)
        return if (index >= 0) PianoRollView.DRUM_PADS[index] else "Note $pitch"
    }

    private fun showSamplePads(sample: KitSample) {
        Sheet(this, "Assign ${sample.name}", "Choose a drum pad").apply {
            PianoRollView.DRUM_PITCHES.forEach { pitch ->
                row(padName(pitch), color = Flm.success,
                    subtitle = if (pitch == sample.pitch) "Suggested by filename" else null) {
                    scope.launch { assignSample(sample, pitch) }
                }
            }
        }.present()
    }

    private suspend fun targetDrumTrack(): ProjectTrack? {
        val current = project ?: return null
        val target = current.tracks.firstOrNull { it.id == kitTargetTrack && it.builtinId == "drums" }
            ?: current.tracks.getOrNull(selected)?.takeIf { it.builtinId == "drums" }
            ?: addBuiltin("drums")
        if (target != null) kitTargetTrack = target.id
        return target
    }

    private suspend fun loadPad(track: ProjectTrack, sample: KitSample, pitch: Int) {
        val current = project ?: return
        val file = store.newAudioFile(current, sample.file.extension)
        try {
            withContext(Dispatchers.IO) { sample.file.copyTo(file, overwrite = true) }
            val bytes = withContext(Dispatchers.IO) { file.readBytes() }
            require(ensurePlayer().loadBuiltinSample(builtinIds.getValue(track.id), pitch, bytes, file.name)) {
                "Invalid sample, longer than 20 seconds, or audio memory limit reached"
            }
            track.samples[pitch] = file.name
            track.sampleNames[pitch] = sample.name
        } catch (e: Exception) {
            file.delete()
            throw e
        }
    }

    private fun openDrumTrack(track: ProjectTrack) {
        clearUndo()
        selected = project!!.tracks.indexOf(track)
        track.drum = true
        if (track.clip == null) track.clip = Clip()
        if (project!!.arrangement.none { it.trackId == track.id }) placeSource(track)
        rollOpen = true
        mixerOpen = false
        applyMix(); renderTracks(); scheduleSave()
    }

    private suspend fun assignSample(sample: KitSample, pitch: Int) {
        if (loading || stopping) return
        stopSamplePreview()
        stopTransport()
        val track = targetDrumTrack() ?: return
        loading = true
        try {
            loadPad(track, sample, pitch)
            openDrumTrack(track)
            status.text = "${padName(pitch)} · ${sample.name}"
        } catch (e: Exception) { status.text = "Sample: ${e.message}" }
        finally { loading = false }
    }

    private suspend fun loadKit(kit: Kit) {
        if (loading || stopping) return
        stopSamplePreview()
        stopTransport()
        val track = targetDrumTrack() ?: return
        val mapped = linkedMapOf<Int, KitSample>()
        kit.samples.forEach { sample -> sample.pitch?.let { mapped.putIfAbsent(it, sample) } }
        kit.samples.filter { it.pitch == null }.forEach { sample ->
            PianoRollView.DRUM_PITCHES.firstOrNull { it !in mapped }?.let { mapped[it] = sample }
        }
        loading = true
        var loaded = 0
        val errors = mutableListOf<String>()
        try {
            for ((pitch, sample) in mapped) {
                try { loadPad(track, sample, pitch); loaded++ }
                catch (e: Exception) { errors.add("${sample.name}: ${e.message}") }
            }
            if (loaded > 0) openDrumTrack(track)
            status.text = "${kit.name} · $loaded pads loaded" + if (errors.isEmpty()) "" else " · ${errors.first()}"
        } finally { loading = false }
    }

    private fun builtinCard(track: ProjectTrack, color: Int): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Flm.withAlpha(color, 34))
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        card.addView(label(track.name, 13f).apply { setTextColor(color) })
        card.addView(label(if (track.builtinId == "drums") "8 pads · ${track.samples.size} imported samples" else "Built-in · playable from the piano roll", 11f))
        if (track.builtinId == "drums") card.addView(button("PADS / SAMPLES") { showDrumPads(track) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34)))
        return card
    }

    private fun showDrumPads(track: ProjectTrack) {
        Sheet(this, "Drum pads", "Tap a pad to replace its sound").apply {
            row("Browse / import kit", glyph = "+", color = Flm.success) { showSoundLibrary(track) }
            PianoRollView.DRUM_PITCHES.forEach { pitch ->
                val file = track.samples[pitch]?.let { store.audioFile(project!!, it) }
                row(padName(pitch), subtitle = track.sampleNames[pitch] ?: "Built-in sound",
                    trailing = file?.let { previewButton(it, padName(pitch)) }) { showSoundLibrary(track, pitch) }
            }
            setOnDismissListener { stopSamplePreview() }
        }.present()
    }

    private fun chooseAudio(track: ProjectTrack) {
        if (loading || stopping) return
        scope.launch {
            stopTransport()
            pendingAudioTrack = track.id
            startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "audio/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }, PICK_AUDIO)
        }
    }

    @Deprecated("Deprecated in Android")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_KIT_ZIP || requestCode == PICK_KIT_FOLDER) {
            if (resultCode == RESULT_OK) {
                pendingKitRequest = requestCode
                pendingKitUri = data?.data
                consumeKitResult()
            }
            return
        }
        if (requestCode != PICK_AUDIO) return
        if (resultCode != RESULT_OK) {
            pendingAudioTrack = null; pendingAudioUri = null
            return
        }
        pendingAudioUri = data?.data
        consumeAudioResult()
    }

    private fun consumeAudioResult() {
        val uri = pendingAudioUri ?: return
        val current = project ?: return
        if (loading) return
        val id = pendingAudioTrack
        pendingAudioTrack = null; pendingAudioUri = null
        val track = current.tracks.firstOrNull { it.id == id } ?: return
        scope.launch { importAudio(current, track, uri) }
    }

    private suspend fun importAudio(current: DawProject, track: ProjectTrack, uri: Uri) {
        loading = true
        status.text = "Importing audio…"
        var imported: java.io.File? = null
        try {
            val displayName = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: "audio"
            val extension = displayName.substringAfterLast('.', "").lowercase()
            require(extension in setOf("wav", "mp3", "ogg", "flac")) { "Choose WAV, MP3, OGG or FLAC" }
            val file = store.newAudioFile(current, extension)
            imported = file
            withContext(Dispatchers.IO) {
                contentResolver.openInputStream(uri)?.use { input ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var total = 0
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= MAX_AUDIO_BYTES) { "Maximum audio file size: 32 MB" }
                            output.write(buffer, 0, count)
                        }
                    }
                } ?: error("Cannot open audio file")
            }
            require(ensurePlayer().loadAudioTrack(audioIds.getValue(track.id), file.readBytes(), file.name)) {
                "Invalid audio, longer than 120 seconds, or audio memory limit reached"
            }
            track.audioFile = file.name
            track.audioName = displayName.filterNot { Character.isISOControl(it) }.trim().take(80).ifEmpty { "Audio" }
            audioDurationFrames[track.id] = ensurePlayer().getAudioTrackDurationFrames(audioIds.getValue(track.id))
            if (current.arrangement.none { it.trackId == track.id }) placeSource(track)
            imported = null
            clearUndo()
            renderTracks(); scheduleSave()
            status.text = "Imported $displayName · PLAY from the beginning"
        } catch (e: Exception) {
            imported?.delete()
            status.text = "Audio import: ${e.message}"
            Log.e(METER_LOG_TAG, "Audio import failed", e)
        } finally { loading = false }
    }

    private fun applyMix() {
        val current = project ?: return
        val p = player ?: return
        val anySolo = current.tracks.any { it.soloed }
        current.tracks.forEach { track ->
            val gain = if (!track.muted && (!anySolo || track.soloed)) track.gain else 0f
            devicesById[track.id]?.let { p.setPluginGain(it.instance, gain) }
            audioIds[track.id]?.let { p.setAudioTrackGain(it, gain) }
            builtinIds[track.id]?.let { p.setBuiltinGain(it, gain) }
        }
        p.setMasterGain(if (current.masterMuted) 0f else current.masterGain)
    }

    private fun advancePosition() {
        val frames = player?.transportFrames ?: return
        val bpm = project?.bpm ?: return
        position += (frames - lastFrames) / (sampleRate * 60.0 / bpm / 4.0)
        lastFrames = frames
    }

    private fun changeTempo(delta: Int) {
        val current = project ?: return
        val next = (current.bpm + delta).coerceIn(40, 240)
        if (next == current.bpm) return
        if (processing) {
            scope.launch { stopTransport(); changeTempo(delta) }
            return
        }
        pushUndo()
        current.bpm = next
        bpmLabel.text = "${current.bpm} BPM"
        refreshClips()
        scheduleSave()
    }

    private fun startTransport() {
        // A metronome-only count-in has nothing to play, so the engine may not exist yet.
        val p = player ?: if (metronomeOn) ensurePlayer() else return
        if (processing || stopping || loading || !foreground) return
        val current = project ?: return
        val framesPerStep = sampleRate * 60.0 / current.bpm / 4.0
        for ((trackId, nativeId) in audioIds) {
            val starts = current.arrangement.filter { it.trackId == trackId }
                .map { (it.startStep * framesPerStep).toLong() }.toLongArray()
            if (!p.setAudioTrackPlacements(nativeId, starts)) {
                status.text = "Cannot schedule audio clips"
                return
            }
        }
        position = 0.0; lastFrames = 0L; scheduledStep = 0L
        p.startProcessing()
        processing = true
        transportButton.text = "STOP"
        transportButton.setTextColor(Flm.accent)
        pulse(transportButton)
        sequencerJob = scope.launch {
            var displayedStep = -1L
            while (isActive && processing) {
                advancePosition()
                val step = floor(position).toLong()
                while (scheduledStep <= step) { sequenceStep(scheduledStep); scheduledStep++ }
                if (step != displayedStep) {
                    val sourceId = current.tracks.getOrNull(selected)?.id
                    val activeClip = current.arrangement.firstOrNull {
                        it.trackId == sourceId && step >= it.startStep && step < it.startStep + STEPS_PER_CLIP
                    }
                    pianoRoll.playingStep = activeClip?.let { (step - it.startStep).toInt() } ?: -1
                    playlist.playhead = step.toFloat()
                    positionLabel.text = "${step / STEPS_PER_CLIP + 1}:${"%02d".format(step % STEPS_PER_CLIP + 1)}"
                    displayedStep = step
                }
                delay(4)
            }
        }
        startMetering()
    }

    private fun sequenceStep(step: Long) {
        if (metronomeOn && step % 4L == 0L) {
            soundPool?.play(if (step % 16L == 0L) accentSound else clickSound, 0.9f, 0.9f, 1, 0, 1f)
        }
        pendingOffs.filterValues { it <= step }.keys.toList().forEach { key ->
            sendNote(key.trackId, key.pitch, false)
            pendingOffs.remove(key)
        }
        val current = project ?: return
        val triggered = mutableSetOf<NoteKey>()
        current.arrangement.forEach { item ->
            val localStep = step - item.startStep
            if (localStep !in 0L until STEPS_PER_CLIP.toLong()) return@forEach
            val track = current.tracks.firstOrNull { it.id == item.trackId && it.isInstrument } ?: return@forEach
            track.clip?.notes?.filter { it.step == localStep.toInt() }?.forEach { note ->
                val key = NoteKey(track.id, note.pitch)
                if (liveNotes[note.pitch]?.track != track && triggered.add(key)) {
                    if (pendingOffs.containsKey(key)) sendNote(track.id, note.pitch, false)
                    sendNote(track.id, note.pitch, true)
                    pendingOffs[key] = step + note.lengthSteps
                    Log.i(METER_LOG_TAG, "seq step=$step track=${track.name} note=${note.pitch} length=${note.lengthSteps}")
                }
            }
        }
    }

    private fun sendNote(trackId: String, pitch: Int, down: Boolean) {
        builtinIds[trackId]?.let {
            player?.builtinNote(it, pitch, 0.75f, down)
            return
        }
        val device = devicesById[trackId] ?: return
        val channel = if (down) {
            if (device.noteChannels.containsKey(pitch)) return
            val free = if (device.ayumi) (0..2).firstOrNull { it !in device.noteChannels.values } ?: return else 0
            device.noteChannels[pitch] = free
            free
        } else device.noteChannels.remove(pitch) ?: return
        paramBuffer.clear()
        paramBuffer.putInt(((0x40 or device.group) shl 24) or
            (((if (down) 0x90 else 0x80) or channel) shl 16) or (pitch shl 8))
        paramBuffer.putInt(if (down) NOTE_VELOCITY_16 shl 16 else 0)
        device.instance.addEventUmpInput(paramBuffer, 8)
    }

    private suspend fun toggleTransport() {
        if (loading || stopping) return
        if (processing) stopTransport() else startTransport()
    }

    private suspend fun stopTransport() {
        if (!processing || stopping) return
        stopping = true
        sequencerJob?.cancel(); sequencerJob = null
        advancePosition()
        keyboard.releaseKey()
        liveNotes.keys.toList().forEach { playNote(it, false) }
        pendingOffs.keys.forEach { sendNote(it.trackId, it.pitch, false) }
        pendingOffs.clear()
        delay((framesPerCallback * 2000L / sampleRate).coerceAtLeast(20L) + 10L)
        player?.pauseProcessing()
        processing = false; stopping = false; recording = false
        recordButton.setTextColor(Flm.text)
        transportButton.text = "PLAY"
        transportButton.setTextColor(Flm.text)
        pulse(transportButton)
        stopMetering()
        pianoRoll.playingStep = -1
        playlist.playhead = -1f
        positionLabel.text = "1:01"
    }

    private fun toggleRecording() {
        val track = project?.tracks?.getOrNull(selected) ?: return
        if (loading || stopping || !rollOpen || mixerOpen || !track.isInstrument || !instrumentReady(track)) return
        recording = !recording
        recordButton.setTextColor(if (recording) Flm.danger else Flm.text)
        if (recording) startTransport()
    }

    private fun playNote(note: Int, down: Boolean) {
        if (down) {
            if (stopping || loading) return
            val track = project?.tracks?.getOrNull(selected)?.takeIf { it.isInstrument } ?: return
            if (!instrumentReady(track)) return
            if (!processing) startTransport()
            advancePosition()
            val step = floor(position).toLong()
            val key = NoteKey(track.id, note)
            if (pendingOffs.remove(key) != null) sendNote(track.id, note, false)
            val recorded = if (recording) {
                if (track.clip != null) pushUndo()
                track.clip?.record((step % STEPS_PER_CLIP).toInt(), note)
            } else null
            liveNotes[note] = LiveNote(track, step, recorded)
            sendNote(track.id, note, true)
            refreshClips()
        } else {
            val live = liveNotes.remove(note) ?: return
            if (processing) advancePosition()
            sendNote(live.track.id, note, false)
            live.recorded?.let {
                val length = (floor(position).toLong() - live.start + 1).coerceAtMost(16).toInt()
                live.track.clip?.resize(it, length)
                refreshClips(); scheduleSave()
            }
        }
    }

    private fun shiftOctave(delta: Int) {
        pianoRoll.lowPitch = (pianoRoll.lowPitch + delta).coerceIn(0, 116)
        keyboard.firstNote = pianoRoll.lowPitch
        refreshClips()
    }

    private fun refreshClips() {
        val current = project
        val track = current?.tracks?.getOrNull(selected)
        val drum = track?.drum == true
        pianoRoll.clip = track?.clip
        pianoRoll.drumMode = drum
        pianoRoll.showKeyNames = showKeyNames
        pianoRoll.accentColor = if (selected >= 0) Flm.laneColor(selected) else Flm.accent
        pianoRoll.ghostClips = if (ghostNotes) current?.tracks?.mapNotNull { it.clip }?.filter { it !== track?.clip }
            ?: emptyList() else emptyList()
        playlist.audioSteps = audioDurationFrames.mapValues { (_, frames) ->
            (frames / (sampleRate * 60.0 / (current?.bpm ?: 140) / 4.0)).toFloat().coerceAtLeast(0.1f)
        }
        playlist.project = current
        val noteName = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")[pianoRoll.lowPitch % 12]
        rollLabel.text = "${track?.name ?: "PIANO ROLL"} · ${if (drum) "DRUM STEP" else "Pattern 1"} · $noteName${pianoRoll.lowPitch / 12 - 1}"
        drumButton.setTextColor(if (drum) Flm.accent else Flm.textDim)
        drumButton.isEnabled = track?.builtinId != "drums"
        padsButton.visibility = if (track?.builtinId == "drums") View.VISIBLE else View.GONE
        namesButton.setTextColor(if (showKeyNames) Flm.accent else Flm.textDim)
        ghostButton.setTextColor(if (ghostNotes) Flm.accent else Flm.textDim)
        octaveDown.isEnabled = !drum
        octaveUp.isEnabled = !drum
    }

    private fun updateWorkspaceVisibility() {
        val drum = project?.tracks?.getOrNull(selected)?.drum == true
        setPanel(sidebar, devicesOpen, dx = dp(260).toFloat())
        setPanel(mixerPanel, mixerOpen, dy = dp(48).toFloat())
        setPanel(editorPanel, !mixerOpen, dy = dp(24).toFloat())
        setPanel(rollPanel, rollOpen, dy = dp(24).toFloat())
        setPanel(arrangementPanel, !rollOpen, dy = -dp(12).toFloat())
        setPanel(keyboard, rollOpen && !mixerOpen && !drum, dy = dp(54).toFloat())
        recordButton.isEnabled = rollOpen && !mixerOpen && !drum && project?.tracks?.getOrNull(selected)?.let { instrumentReady(it) } == true
        arrangeButton.text = if (mixerOpen && rollOpen) "PIANO" else "TRACKS"
        arrangeButton.setTextColor(if (!mixerOpen) Flm.accent else Flm.textDim)
        mixerButton.setTextColor(if (mixerOpen) Flm.accent else Flm.textDim)
        devicesButton.setTextColor(if (devicesOpen) Flm.accent else Flm.textDim)
    }

    private fun setMixerOpen(open: Boolean) {
        finishLiveNotes()
        recording = false
        recordButton.setTextColor(Flm.text)
        mixerOpen = open
        updateWorkspaceVisibility()
    }

    private fun toggleDevices() {
        devicesOpen = !devicesOpen
        updateWorkspaceVisibility()
    }

    private fun selectMaster() {
        selected = -1
        closeRoll()
        renderTracks()
    }

    private fun closeRoll() {
        finishLiveNotes()
        rollOpen = false
        recording = false
        recordButton.setTextColor(Flm.text)
        updateWorkspaceVisibility()
    }

    private fun selectTrack(index: Int, openPattern: Boolean = false) {
        if (loading || stopping) return
        keyboard.releaseKey()
        liveNotes.keys.toList().forEach { playNote(it, false) }
        val track = project!!.tracks[index]
        selected = index
        rollOpen = (openPattern || (mixerOpen && rollOpen && track.clip != null)) && track.isInstrument
        if (rollOpen && track.clip == null) { track.clip = Clip(); placeSource(track); scheduleSave() }
        if (track.clip != null && track.clip!!.notes.isNotEmpty()) {
            pianoRoll.lowPitch = (track.clip!!.notes.first().pitch / 12 * 12).coerceIn(24, 96)
            keyboard.firstNote = pianoRoll.lowPitch
        }
        recording = false
        recordButton.setTextColor(Flm.text)
        renderTracks()
    }

    private fun renderTracks() {
        val current = project ?: return
        val scrollX = mixerScroll.scrollX
        rackStack.removeAllViews(); mixerStrips.removeAllViews()
        masterChannel.removeAllViews(); quickControls.removeAllViews()
        stripMeters.clear()
        devicesById.values.forEach { it.knobs.clear() }
        playlist.project = current
        val track = current.tracks.getOrNull(selected)
        if (track == null) {
            rackTitle.text = "MASTER"
            rackTitle.setTextColor(Flm.accent)
            masterFxButton.visibility = View.VISIBLE
            if (current.effects.isEmpty()) rackStack.addView(label("No master effects · + MASTER FX to add", 11f),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(30)))
            current.effects.forEachIndexed { index, effect ->
                val device = devicesById[effect.id]
                if (device != null) rackStack.addView(moduleCard(effect.id, device, Flm.accent, "${index + 1} · ${effect.name}"))
                else rackStack.addView(label("${effect.name} · install its APK", 11f),
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(30)))
            }
            rackStack.addView(outputCard())
        } else {
            val color = Flm.laneColor(selected)
            rackTitle.text = "TRACK ${selected + 1} · ${track.name}"
            rackTitle.setTextColor(color)
            masterFxButton.visibility = View.GONE
            if (track.builtinId != null) {
                rackStack.addView(builtinCard(track, color))
            } else if (track.pluginId != null) {
                val device = devicesById[track.id]
                if (device != null) rackStack.addView(moduleCard(track.id, device, color, pluginName(device.info)))
                else rackStack.addView(label("Plugin unavailable · install its APK", 11f),
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(30)))
            } else {
                rackStack.addView(audioCard(track, color))
            }
            rackStack.addView(button("→ MASTER · ${current.effects.size} FX") { selectMaster() }.apply {
                contentDescription = "Output to master"
                setTextColor(Flm.textDim)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(32)).apply { topMargin = dp(2) })
        }
        masterChannel.addView(masterStrip())
        current.tracks.forEachIndexed { index, item -> mixerStrips.addView(mixerStrip(index, item)) }
        if (current.tracks.isEmpty()) mixerStrips.addView(label("No channels yet · + TRACK to add", 13f).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setOnLongClickListener { showTrackMenu(); true }
        })
        val color = if (track == null) Flm.accent else Flm.laneColor(selected)
        quickControls.addView(label(if (track == null) "MASTER / OUTPUT" else "${selected + 1} / ${track.name}", 11f).apply {
            setTextColor(color)
            setOnClickListener { devicesOpen = true; updateWorkspaceVisibility() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        quickGain = label(gainText(track?.gain ?: current.masterGain), 10f).apply {
            gravity = Gravity.CENTER
            setOnClickListener { setMixerOpen(true) }
        }
        quickControls.addView(quickGain, LinearLayout.LayoutParams(dp(62), ViewGroup.LayoutParams.MATCH_PARENT))
        if (track != null) {
            quickControls.addView(soloButton(track), LinearLayout.LayoutParams(dp(32), dp(28)))
            quickControls.addView(muteButton(track), LinearLayout.LayoutParams(dp(32), dp(28)))
        } else {
            quickControls.addView(masterMuteButton(), LinearLayout.LayoutParams(dp(32), dp(28)))
        }
        mixerScroll.post { mixerScroll.scrollTo(scrollX, 0) }
        updateWorkspaceVisibility()
        transportButton.isEnabled = player != null && (current.tracks.isNotEmpty() || metronomeOn)
        refreshClips()
        updateUndoButton()
    }

    private fun muteButton(track: ProjectTrack) = button("M") {
        pushUndo()
        track.muted = !track.muted
        applyMix(); renderTracks(); scheduleSave()
    }.apply {
        val index = project!!.tracks.indexOf(track)
        contentDescription = "Track ${index + 1} mute"
        isSelected = track.muted
        val shape = GradientDrawable().apply {
            setColor(if (track.muted) Flm.laneColor(index) else Flm.bg)
            cornerRadius = dp(4).toFloat()
        }
        background = RippleDrawable(ColorStateList.valueOf(Flm.withAlpha(Flm.text, 36)), shape, shape)
        setTextColor(if (track.muted) Flm.bg else Flm.textDim)
    }

    private fun soloButton(track: ProjectTrack) = button("S") {
        pushUndo()
        val next = !track.soloed
        project!!.tracks.forEach { it.soloed = false }
        track.soloed = next
        applyMix(); renderTracks(); scheduleSave()
    }.apply {
        val index = project!!.tracks.indexOf(track)
        contentDescription = "Track ${index + 1} solo"
        isSelected = track.soloed
        val shape = GradientDrawable().apply {
            setColor(if (track.soloed) Flm.laneColor(index) else Flm.bg)
            cornerRadius = dp(4).toFloat()
        }
        background = RippleDrawable(ColorStateList.valueOf(Flm.withAlpha(Flm.text, 36)), shape, shape)
        setTextColor(if (track.soloed) Flm.bg else Flm.textDim)
    }

    private fun masterMuteButton() = button("M") {
        pushUndo()
        project!!.masterMuted = !project!!.masterMuted
        applyMix(); renderTracks(); scheduleSave()
    }.apply {
        contentDescription = "Master mute"
        isSelected = project!!.masterMuted
        val shape = GradientDrawable().apply {
            setColor(if (project!!.masterMuted) Flm.accent else Flm.bg)
            cornerRadius = dp(4).toFloat()
        }
        background = RippleDrawable(ColorStateList.valueOf(Flm.withAlpha(Flm.text, 36)), shape, shape)
        setTextColor(if (project!!.masterMuted) Flm.bg else Flm.textDim)
    }

    private fun gainText(gain: Float) = if (gain == 0f) "−∞ dB"
        else String.format(Locale.ROOT, "%.1f dB", 20f * log10(gain))

    private fun channelStrip(index: Int, name: String, color: Int, onSelect: () -> Unit): LinearLayout {
        val strip = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), 0, dp(8), dp(4))
            setBackgroundColor(if (index == selected) Flm.panelAlt else Flm.panel)
            contentDescription = if (index < 0) "Master channel" else "Track ${index + 1} channel"
            layoutParams = LinearLayout.LayoutParams(if (index < 0) ViewGroup.LayoutParams.MATCH_PARENT else dp(84),
                ViewGroup.LayoutParams.MATCH_PARENT).apply { marginEnd = dp(2) }
            setOnClickListener { onSelect() }
        }
        strip.addView(View(this).apply { setBackgroundColor(color) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)))
        strip.addView(label(if (index < 0) name else "${index + 1} · $name", 11f).apply {
            gravity = Gravity.CENTER
            maxLines = 2
            setTextColor(if (index == selected) color else Flm.text)
            contentDescription = if (index < 0) "Select master channel" else "Select track ${index + 1} channel"
            setOnClickListener { onSelect() }
            setOnLongClickListener {
                onSelect(); devicesOpen = true; updateWorkspaceVisibility(); true
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(28)))
        return strip
    }

    private fun masterStrip(): View {
        val current = project!!
        val strip = channelStrip(-1, "MASTER", Flm.accent) { selectMaster() }
        val value = label(gainText(current.masterGain), 10f).apply { gravity = Gravity.CENTER }
        strip.addView(value, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(18)))
        val controls = LinearLayout(this)
        controls.addView(FaderView(this).apply {
            contentDescription = "Master volume"
            fraction = current.masterGain
            onFraction = {
                current.masterGain = it
                value.text = gainText(it)
                if (selected < 0) quickGain.text = value.text
                applyMix(); scheduleSave()
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        meter = PeakMeterView(this).apply { vertical = true; accentColor = Flm.accent }
        controls.addView(meter, LinearLayout.LayoutParams(dp(12), ViewGroup.LayoutParams.MATCH_PARENT).apply {
            marginStart = dp(4)
        })
        strip.addView(controls, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = dp(4); bottomMargin = dp(4)
        })
        strip.addView(masterMuteButton(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(30)))
        strip.addView(button("FX · ${current.effects.size}") {
            selectMaster(); devicesOpen = true; updateWorkspaceVisibility()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(24)).apply { topMargin = dp(4) })
        return strip
    }

    private fun mixerStrip(index: Int, track: ProjectTrack): View {
        val color = Flm.laneColor(index)
        val strip = channelStrip(index, track.name, color) { selectTrack(index) }
        val value = label(gainText(track.gain), 10f).apply { gravity = Gravity.CENTER }
        strip.addView(value, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(18)))
        val controls = LinearLayout(this)
        controls.addView(FaderView(this).apply {
            contentDescription = "Track ${index + 1} volume"
            accentColor = color
            fraction = track.gain
            onFraction = {
                track.gain = it
                value.text = gainText(it)
                if (selected == index) quickGain.text = value.text
                applyMix(); scheduleSave()
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        val peak = PeakMeterView(this).apply { vertical = true; accentColor = color }
        stripMeters.add(track to peak)
        controls.addView(peak, LinearLayout.LayoutParams(dp(12), ViewGroup.LayoutParams.MATCH_PARENT).apply {
            marginStart = dp(4)
        })
        strip.addView(controls, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = dp(4); bottomMargin = dp(4)
        })
        val chips = LinearLayout(this)
        chips.addView(soloButton(track), LinearLayout.LayoutParams(0, dp(30), 1f).apply { marginEnd = dp(3) })
        chips.addView(muteButton(track), LinearLayout.LayoutParams(0, dp(30), 1f))
        strip.addView(chips)
        strip.addView(label(if (track.builtinId == "drums") "DRUMS" else if (track.isInstrument) "SYNTH" else "AUDIO", 9f).apply {
            gravity = Gravity.CENTER
            setTextColor(color)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(24)).apply { topMargin = dp(4) })
        return strip
    }

    private fun moduleCard(id: String, device: Device, color: Int, title: String): View {
        val collapsed = id in collapsedDevices
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Flm.withAlpha(color, 34))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) }
        }
        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            contentDescription = "Module · $title"
            setOnClickListener {
                if (collapsed) collapsedDevices.remove(id) else collapsedDevices.add(id)
                renderTracks()
            }
        }
        header.addView(label(if (collapsed) "▸" else "▾", 12f).apply { setTextColor(color) },
            LinearLayout.LayoutParams(dp(20), dp(30)))
        header.addView(label(title, 12f).apply { setTextColor(color) },
            LinearLayout.LayoutParams(0, dp(30), 1f))
        card.addView(header)
        if (collapsed) return card
        val count = minOf(device.instance.getParameterCount(), if (device.sampleEffect) 4 else MAX_KNOBS_PER_DEVICE)
        if (count == 0) {
            card.addView(label("No parameters", 11f), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(26)))
            return card
        }
        val pages = (count + 2) / 3
        val page = (devicePage[id] ?: 0).coerceIn(0, pages - 1)
        if (pages > 1) {
            val tabs = LinearLayout(this).apply { setPadding(dp(4), 0, dp(4), dp(2)) }
            for (index in 0 until pages) {
                tabs.addView(button("${index + 1}") {
                    devicePage[id] = index
                    renderTracks()
                }.apply {
                    contentDescription = "Parameter page ${index + 1} · $title"
                    setTextColor(if (index == page) Flm.bg else Flm.textDim)
                    setBackgroundColor(if (index == page) color else Flm.panelAlt)
                }, LinearLayout.LayoutParams(dp(26), dp(22)).apply { marginEnd = dp(3) })
            }
            card.addView(tabs)
        }
        val row = LinearLayout(this)
        for (index in page * 3 until minOf(page * 3 + 3, count)) {
            val parameter = device.instance.getParameter(index)
            val value = device.parameters[parameter.id] ?: device.instance.getParameterValue(index)
            val knob = KnobView(this).apply {
                contentDescription = "Parameter ${parameter.name}"
                accentColor = color
                label = parameter.name.replace("Output Volume", "Out").replace("Envelope", "Env").take(12)
                fraction = fractionOf(parameter, value)
                onFraction = {
                    writeParameter(device, index, parameter,
                        parameter.minimumValue + (parameter.maximumValue - parameter.minimumValue) * it)
                    scheduleSave()
                }
            }
            device.knobs.add(index to knob)
            row.addView(knob, LinearLayout.LayoutParams(0, dp(68), 1f))
        }
        card.addView(row)
        return card
    }

    private fun outputCard(): View {
        val current = project!!
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Flm.withAlpha(Flm.accent, 34))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) }
        }
        card.addView(label("OUTPUT", 12f).apply { setTextColor(Flm.accent) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(30)))
        card.addView(KnobView(this).apply {
            contentDescription = "Parameter Master"
            accentColor = Flm.accent
            label = "Master"
            fraction = current.masterGain
            onFraction = {
                current.masterGain = it
                if (selected < 0) quickGain.text = gainText(it)
                applyMix(); scheduleSave()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(68)))
        return card
    }

    private fun audioCard(track: ProjectTrack, color: Int): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Flm.withAlpha(color, 34))
            setPadding(dp(4), dp(4), dp(4), dp(6))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) }
        }
        card.addView(label(track.audioName ?: "AUDIO CLIP", 12f).apply { setTextColor(color) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(28)))
        card.addView(button("IMPORT AUDIO") { chooseAudio(track) }.apply {
            contentDescription = "Import audio · ${track.name}"
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(32)))
        card.addView(label("One-shot playback from start", 10f),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(22)))
        return card
    }

    private fun showRackMenu() {
        Sheet(this, "Rack", "Module cards in the inspector").apply {
            row("Minimize all", glyph = "▸", color = Flm.textDim, subtitle = "Collapse every module card") {
                collapsedDevices.addAll(devicesById.keys)
                renderTracks()
            }
            row("Maximize all", glyph = "▾", color = Flm.accent, subtitle = "Expand every module card") {
                collapsedDevices.clear()
                renderTracks()
            }
        }.present()
    }

    private fun pushUndo() {
        val current = project ?: return
        undoStack.addLast(UndoSnapshot(current.bpm, current.masterGain, current.masterMuted,
            current.tracks.map { track ->
                TrackSnap(track.id, track.gain, track.muted, track.soloed, track.drum,
                    track.clip != null, track.clip?.notes?.toList() ?: emptyList())
            }, current.arrangement.toList()))
        while (undoStack.size > 32) undoStack.removeFirst()
        updateUndoButton()
    }

    private fun undo() {
        if (processing) { scope.launch { stopTransport(); undo() }; return }
        val current = project ?: return
        val snapshot = undoStack.removeLastOrNull() ?: return
        current.arrangement.clear()
        current.arrangement.addAll(snapshot.arrangement)
        current.bpm = snapshot.bpm
        bpmLabel.text = "${current.bpm} BPM"
        current.masterGain = snapshot.masterGain
        current.masterMuted = snapshot.masterMuted
        snapshot.tracks.forEach { snap ->
            val track = current.tracks.firstOrNull { it.id == snap.id } ?: return@forEach
            track.gain = snap.gain
            track.muted = snap.muted
            track.soloed = snap.soloed
            track.drum = snap.drum
            if (!snap.hasClip) track.clip = null
            else {
                val clip = track.clip ?: Clip().also { track.clip = it }
                clip.notes.clear()
                clip.notes.addAll(snap.notes)
            }
        }
        if (rollOpen && current.tracks.getOrNull(selected)?.clip == null) closeRoll()
        applyMix(); renderTracks(); scheduleSave()
        status.text = "Undo · ${undoStack.size} more"
    }

    private fun clearUndo() {
        undoStack.clear()
        updateUndoButton()
    }

    private fun updateUndoButton() {
        if (!::undoButton.isInitialized) return
        if (undoStack.isNotEmpty()) {
            if (undoButton.visibility != View.VISIBLE) reveal(undoButton, dx = -dp(14).toFloat())
        } else {
            undoButton.animate().cancel()
            undoButton.visibility = View.GONE
            undoButton.alpha = 1f
            undoButton.translationX = 0f
        }
    }

    private fun toggleDrumMode() {
        val track = project?.tracks?.getOrNull(selected) ?: return
        if (!track.isInstrument || track.builtinId == "drums") return
        pushUndo()
        track.drum = !track.drum
        refreshClips(); updateWorkspaceVisibility(); scheduleSave()
    }

    private fun showMainMenu() {
        if (loading || stopping) return
        val current = project ?: return
        Sheet(this, "Floton", "${current.name} · ${current.bpm} BPM · ${tracksLabel(current.tracks.size)}").apply {
            row("Project", glyph = "▤", subtitle = "Open, save or rename this session") { showProjectMenu() }
            row("Sound library", glyph = "≋", color = Flm.success, subtitle = "WAV files, drum kits and samples") {
                showSoundLibrary()
            }
            row("Metronome", glyph = "◉", subtitle = if (metronomeOn) "On · click on every beat" else "Off") {
                toggleMetronome()
            }
            row("Theme", glyph = "◐", subtitle = "Colour scheme of the app") { showThemeDialog() }
            row("Gesture hints", glyph = "?", color = Flm.info, subtitle = "What every touch gesture does") {
                showGestureHints()
            }
        }.present()
    }

    private fun showProjectMenu() {
        if (loading || stopping) return
        val current = project ?: return
        Sheet(this, "Project", "${current.name} · ${current.bpm} BPM").apply {
            row("Projects", glyph = "▤", subtitle = "Save this session and open another one") {
                if (!loading && !stopping) scope.launch {
                    stopTransport()
                    if (!saveNow()) return@launch
                    releaseEngine()
                    project = null
                    showProjects()
                }
            }
            row("Save now", glyph = "✓", subtitle = "Autosave already writes after every edit") {
                if (saveNow()) status.text = "Saved · ${project?.name}"
            }
            row("Rename project", glyph = "✎", subtitle = current.name) { renameProject() }
        }.present()
    }

    private fun showGestureHints() {
        Sheet(this, "Gesture hints", "Tracks, clips and notes").apply {
            row("Playlist", glyph = "▤", subtitle = "Tap empty: insert · drag clip: move · double tap: edit · hold clip: menu · hold track name: track menu") { }
            row("Piano roll", glyph = "♫", subtitle = "Tap: add / delete note · drag note: move · right edge: length · empty area ↑↓: scroll") { }
            row("Transport", glyph = "▶", subtitle = "Tap BPM for tap tempo · MENU holds projects, save, sounds, theme and metronome") { }
        }.present()
    }

    private fun showThemeDialog() {
        Sheet(this, "Theme", "Palette for panels, lanes and meters").apply {
            Flm.themes.forEachIndexed { index, theme ->
                row(theme.name, color = theme.accent,
                    subtitle = if (index == Flm.themeIndex) "Current palette" else null,
                    trailing = chips(theme.bg, theme.panelAlt, theme.accent)) { applyUiTheme(index) }
            }
        }.present()
    }

    private fun applyUiTheme(index: Int) {
        if (project == null) return
        Flm.applyTheme(index)
        prefs.edit().putInt("theme", index).apply()
        val lowPitch = pianoRoll.lowPitch
        val firstNote = keyboard.firstNote
        buildUi()
        pianoRoll.lowPitch = lowPitch
        keyboard.firstNote = firstNote
        renderTracks()
        findViewById<View>(android.R.id.content).apply {
            alpha = 0f
            animate().alpha(1f).setDuration(260).setInterpolator(DecelerateInterpolator()).start()
        }
    }

    private fun writeParameter(device: Device, index: Int, parameter: ParameterInformation, value: Double) {
        for (channel in 0 until if (device.ayumi) 3 else 1) {
            val words = UmpHelper.aapUmpSysex8ParameterPlain(parameter.id.toUInt(), parameter.minimumValue,
                parameter.maximumValue, value, group = device.group.toByte(), channel = channel.toByte())
            paramBuffer.clear()
            paramBuffer.asIntBuffer().put(words.toIntArray())
            device.instance.addEventUmpInput(paramBuffer, words.size * 4)
        }
        device.instance.setCachedParameterValue(index, value)
        device.parameters[parameter.id] = value
        Log.i(METER_LOG_TAG, "parameter group=${device.group} id=${parameter.id} value=$value")
    }

    private fun fractionOf(parameter: ParameterInformation, value: Double): Float =
        if (parameter.maximumValue > parameter.minimumValue)
            ((value - parameter.minimumValue) / (parameter.maximumValue - parameter.minimumValue)).toFloat() else 0f

    private fun startMetering() {
        meterJob?.cancel()
        meterJob = scope.launch {
            var ticks = 0
            while (isActive && processing) {
                val p = player ?: break
                val levels = p.peakLevels
                meter.levels = levels
                quickMeter.levels = levels
                stripMeters.forEach { (track, view) ->
                    val peak = devicesById[track.id]?.let { p.getPluginPeak(it.instance) }
                        ?: builtinIds[track.id]?.let { p.getBuiltinPeak(it) }
                        ?: audioIds[track.id]?.let { p.getAudioTrackPeak(it) } ?: 0f
                    view.levels = floatArrayOf(peak, peak)
                }
                // Peak levels are sampled every 50 ms; log roughly once a second instead of
                // flooding logcat with 20 lines per second while the transport runs.
                if (ticks++ % 20 == 0) Log.i(METER_LOG_TAG, "peak ${levels[0]} ${levels[1]}")
                delay(50)
            }
        }
    }

    private fun stopMetering() {
        meterJob?.cancel(); meterJob = null
        if (::meter.isInitialized) meter.levels = floatArrayOf(0f, 0f)
        if (::quickMeter.isInitialized) quickMeter.levels = floatArrayOf(0f, 0f)
        stripMeters.forEach { it.second.levels = floatArrayOf(0f, 0f) }
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch { delay(250); saveNow() }
    }

    private fun saveNow(): Boolean {
        saveJob?.cancel(); saveJob = null
        val current = project ?: return true
        return try { store.save(current); true } catch (e: Exception) {
            if (::status.isInitialized) status.text = "Save failed: ${e.message}"
            Log.e(METER_LOG_TAG, "Project save failed", e)
            false
        }
    }

    private fun releaseEngine() {
        sequencerJob?.cancel(); sequencerJob = null
        stopMetering()
        player?.close(); player = null
        devicesById.values.forEach { it.instance.destroy() }
        devicesById.clear(); audioIds.clear(); builtinIds.clear(); pendingOffs.clear(); liveNotes.clear()
        audioDurationFrames.clear()
        insertLane = null; insertStep = 0
        stopSamplePreview()
        processing = false; stopping = false; recording = false
    }

    @Deprecated("Deprecated in Android")
    override fun onBackPressed() {
        if (loading || stopping) return
        if (project == null) { super.onBackPressed(); return }
        if (devicesOpen) { toggleDevices(); return }
        if (mixerOpen) { setMixerOpen(false); return }
        if (rollOpen) { closeRoll(); return }
        scope.launch {
            stopTransport()
            if (!saveNow()) return@launch
            releaseEngine(); project = null; showProjects()
        }
    }

    private fun finishLiveNotes() {
        if (::keyboard.isInitialized) keyboard.releaseKey()
        liveNotes.keys.toList().forEach { playNote(it, false) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("insertLane", insertLane ?: -1)
        outState.putInt("insertStep", insertStep)
        finishLiveNotes()
        saveNow()
        outState.putString("projectId", project?.id)
        outState.putString("pendingAudioTrack", pendingAudioTrack)
        outState.putString("pendingAudioUri", pendingAudioUri?.toString())
        outState.putString("kitTargetTrack", kitTargetTrack)
        outState.putInt("kitTargetPitch", kitTargetPitch ?: -1)
        outState.putInt("pendingKitRequest", pendingKitRequest ?: -1)
        outState.putString("pendingKitUri", pendingKitUri?.toString())
        outState.putInt("selected", selected)
        outState.putBoolean("rollOpen", rollOpen)
        outState.putBoolean("mixerOpen", mixerOpen)
        outState.putBoolean("devicesOpen", devicesOpen)
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        foreground = true
    }

    override fun onStop() {
        foreground = false
        stopSamplePreview()
        finishLiveNotes()
        saveNow()
        super.onStop()
        scope.launch { stopTransport() }
    }

    override fun onDestroy() {
        finishLiveNotes()
        saveNow()
        scope.cancel()
        releaseEngine()
        soundPool?.release(); soundPool = null
        client.dispose()
        super.onDestroy()
    }
}
