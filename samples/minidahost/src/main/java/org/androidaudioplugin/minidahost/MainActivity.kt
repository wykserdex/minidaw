package org.androidaudioplugin.minidahost

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor

private const val DEFAULT_CONTROL_BYTES_PER_BLOCK = 0x10000
private const val NOTE_VELOCITY_16 = 12000
private const val MAX_KNOBS_PER_DEVICE = 8
private const val METER_LOG_TAG = "MiniDAHost"
private const val PICK_AUDIO = 1
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val client by lazy { AudioPluginClientBase(applicationContext) }
    private val store by lazy { ProjectStore(this) }
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
    private var player: PluginPlayer? = null
    private var processing = false
    private var stopping = false
    private var loading = false
    private var foreground = false
    private var selected = -1
    private var selectedEffect: String? = null
    private var rollOpen = false
    private var recording = false
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
    private val clipPreviews = mutableListOf<ClipPreviewView>()
    private val stripMeters = mutableListOf<Pair<ProjectTrack, PeakMeterView>>()
    private val paramBuffer = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder())

    private lateinit var status: TextView
    private lateinit var transportButton: Button
    private lateinit var recordButton: Button
    private lateinit var bpmLabel: TextView
    private lateinit var positionLabel: TextView
    private lateinit var rollLabel: TextView
    private lateinit var lanes: LinearLayout
    private lateinit var devicePanel: LinearLayout
    private lateinit var mixerStrips: LinearLayout
    private lateinit var arrangementPanel: LinearLayout
    private lateinit var rollPanel: LinearLayout
    private lateinit var meter: PeakMeterView
    private lateinit var pianoRoll: PianoRollView
    private lateinit var keyboard: KeyboardView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.hide()
        pendingAudioTrack = savedInstanceState?.getString("pendingAudioTrack")
        pendingAudioUri = savedInstanceState?.getString("pendingAudioUri")?.let { Uri.parse(it) }
        showProjects()
        savedInstanceState?.getString("projectId")?.let { id ->
            scope.launch {
                openProject(id)
                val index = savedInstanceState.getInt("selected", -1)
                if (index in project?.tracks.orEmpty().indices)
                    selectTrack(index, savedInstanceState.getBoolean("rollOpen") && project!!.tracks[index].clip != null)
                consumeAudioResult()
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
        header.addView(label("MiniDAW  /  Projects", 20f).apply { setTextColor(Flm.text) },
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
                list.addView(button("${saved.name}   ·   ${saved.tracks.size} tracks   ·   ${saved.bpm} BPM") {
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

    private fun newProjectDialog() {
        val input = EditText(this).apply {
            setSingleLine()
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setText("Untitled ${store.list().size + 1}")
            selectAll()
            filters = arrayOf(android.text.InputFilter.LengthFilter(80))
        }
        val dialog = AlertDialog.Builder(this).setTitle("New empty project").setView(input)
            .setPositiveButton("CREATE", null).setNegativeButton(android.R.string.cancel, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text.toString().trim()
                if (name.isEmpty()) { input.error = "Enter a project name"; return@setOnClickListener }
                val created = DawProject(name = name)
                try {
                    store.save(created)
                    dialog.dismiss()
                    scope.launch { openProject(created.id) }
                } catch (e: Exception) {
                    input.error = "Cannot save: ${e.message}"
                }
            }
        }
        dialog.show()
    }

    private suspend fun openProject(id: String) {
        if (loading || stopping) return
        val saved = try { store.load(id) } catch (e: Exception) {
            AlertDialog.Builder(this).setMessage("Cannot open project: ${e.message}")
                .setPositiveButton(android.R.string.ok, null).show()
            return
        }
        stopTransport()
        if (!saveNow()) return
        releaseEngine()
        project = saved
        selected = -1
        selectedEffect = null
        rollOpen = false
        buildUi()
        loading = true
        status.text = "Opening ${saved.name}…"
        val problems = mutableListOf<String>()
        val installed = installedPlugins()
        try {
            saved.tracks.forEach { track ->
                if (track.pluginId == null) {
                    val nativeId = ensurePlayer().addAudioTrack()
                    audioIds[track.id] = nativeId
                    track.audioFile?.let { name ->
                        try {
                            val file = store.audioFile(saved, name)
                            require(file.length() in 1..MAX_AUDIO_BYTES.toLong()) { "Audio missing or too large" }
                            require(ensurePlayer().loadAudioTrack(nativeId, file.readBytes(), name)) { "Unsupported or oversized audio" }
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
        bar.addView(button("PROJECTS") {
            if (!loading && !stopping) scope.launch {
                stopTransport()
                if (!saveNow()) return@launch
                releaseEngine()
                project = null
                showProjects()
            }
        }, LinearLayout.LayoutParams(dp(82), dp(34)))
        bar.addView(button("SAVE") { if (saveNow()) status.text = "Saved · ${project?.name}" },
            LinearLayout.LayoutParams(dp(50), dp(34)))
        transportButton = button("PLAY") { scope.launch { toggleTransport() } }
        recordButton = button("REC") { toggleRecording() }
        bar.addView(transportButton, LinearLayout.LayoutParams(dp(58), dp(34)))
        bar.addView(recordButton, LinearLayout.LayoutParams(dp(46), dp(34)))
        bar.addView(button("−") { changeTempo(-5) }, LinearLayout.LayoutParams(dp(30), dp(34)))
        bpmLabel = label("${project!!.bpm} BPM", 12f)
        bar.addView(bpmLabel, LinearLayout.LayoutParams(dp(74), dp(34)))
        bar.addView(button("+") { changeTempo(5) }, LinearLayout.LayoutParams(dp(30), dp(34)))
        positionLabel = label("1:01", 12f)
        bar.addView(positionLabel, LinearLayout.LayoutParams(dp(56), dp(34)))
        status = label("${project!!.name} · autosave", 11f)
        bar.addView(status, LinearLayout.LayoutParams(0, dp(34), 1f))
        bar.addView(button("+ TRACK") { showTrackMenu() }, LinearLayout.LayoutParams(dp(74), dp(34)))
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)))

        val workspace = LinearLayout(this)
        val sidebar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Flm.panel)
        }
        val deviceHeader = LinearLayout(this)
        deviceHeader.addView(label("DEVICES / FX", 11f), LinearLayout.LayoutParams(0, dp(32), 1f))
        deviceHeader.addView(button("+ FX") { showPluginPicker(false) }, LinearLayout.LayoutParams(dp(54), dp(28)))
        sidebar.addView(deviceHeader)
        sidebar.addView(button("MASTER · effects after all tracks") {
            selected = -1; selectedEffect = null; closeRoll(); renderTracks()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(30)))
        devicePanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sidebar.addView(ScrollView(this).apply { addView(devicePanel) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        workspace.addView(sidebar, LinearLayout.LayoutParams(dp(228), ViewGroup.LayoutParams.MATCH_PARENT))

        val editor = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        arrangementPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val arrangementHeader = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        arrangementHeader.addView(label("ARRANGEMENT  /  1 BAR PATTERNS", 11f),
            LinearLayout.LayoutParams(0, dp(28), 1f))
        arrangementHeader.addView(label("1       2       3       4", 11f), LinearLayout.LayoutParams(dp(190), dp(28)))
        arrangementPanel.addView(arrangementHeader)
        lanes = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            contentDescription = "Arrangement workspace"
            setOnLongClickListener { showTrackMenu(); true }
        }
        arrangementPanel.addView(ScrollView(this).apply {
            isFillViewport = true
            setOnLongClickListener { showTrackMenu(); true }
            addView(lanes)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        editor.addView(arrangementPanel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        rollPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val rollHeader = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        rollLabel = label("PIANO ROLL", 11f)
        rollHeader.addView(rollLabel, LinearLayout.LayoutParams(0, dp(28), 1f))
        rollHeader.addView(button("−8") { shiftOctave(-12) }, LinearLayout.LayoutParams(dp(36), dp(28)))
        rollHeader.addView(button("+8") { shiftOctave(12) }, LinearLayout.LayoutParams(dp(36), dp(28)))
        rollHeader.addView(button("ERASE") {
            project!!.tracks.getOrNull(selected)?.clip?.notes?.clear()
            refreshClips(); scheduleSave()
        }, LinearLayout.LayoutParams(dp(54), dp(28)))
        rollHeader.addView(button("CLOSE") { closeRoll() }, LinearLayout.LayoutParams(dp(54), dp(28)))
        rollPanel.addView(rollHeader)
        pianoRoll = PianoRollView(this).apply {
            contentDescription = "Piano roll"
            onEdited = { refreshClips(); scheduleSave() }
        }
        rollPanel.addView(pianoRoll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        rollPanel.addView(label("Tap: add / delete   ·   Drag: note length   ·   REC: keyboard", 10f),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(18)))
        editor.addView(rollPanel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.4f))
        workspace.addView(editor, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        root.addView(workspace, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        mixerStrips = LinearLayout(this)
        root.addView(HorizontalScrollView(this).apply {
            setBackgroundColor(Flm.panel)
            addView(mixerStrips)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(96)))
        keyboard = KeyboardView(this).apply {
            contentDescription = "Piano keyboard"
            firstNote = 60
            noteCount = 12
            onNote = { note, down -> playNote(note, down) }
        }
        root.addView(keyboard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)))
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
        setBackgroundColor(Flm.panelAlt)
        setOnClickListener { onClick() }
    }

    private fun installedPlugins() = AudioPluginHostHelper.queryAudioPluginServices(this).flatMap { it.plugins }

    private fun pluginName(info: PluginInformation) = when (info.pluginId) {
        "urn:org.androidaudioplugin/samples/aappluginsample/InstrumentSample" -> "Ayumi Synth"
        "urn:org.androidaudioplugin/samples/aappluginsample/EffectSample" -> "Gain / Delay"
        else -> info.displayName
    }

    private fun showTrackMenu() {
        if (loading || stopping) return
        AlertDialog.Builder(this).setTitle("Add track")
            .setItems(arrayOf("Audio track", "AAP instrument")) { _, index ->
                if (index == 0) addAudioTrack() else showPluginPicker(true)
            }.setNegativeButton(android.R.string.cancel, null).show()
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
        AlertDialog.Builder(this).setTitle(if (instrument) "AAP instrument" else "Add MASTER effect")
            .setItems(choices.map { pluginName(it) }.toTypedArray()) { _, index ->
                scope.launch { addPlugin(choices[index], instrument) }
            }.setNegativeButton(android.R.string.cancel, null).show()
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
                val track = ProjectTrack(name = pluginName(info), pluginId = pluginId)
                attachDevice(track.id, info, track.parameters, true)
                current.tracks.add(track)
                selected = current.tracks.lastIndex
                selectedEffect = null
                rollOpen = false
            } else {
                val effect = ProjectEffect(pluginId = pluginId, name = pluginName(info))
                attachDevice(effect.id, info, effect.parameters, false)
                current.effects.add(effect)
                selected = -1
                selectedEffect = effect.id
                rollOpen = false
            }
            applyMix(); renderTracks(); scheduleSave()
            status.text = if (instrument) "Empty track · tap its lane to create a pattern" else "Master effect · after all tracks"
        } catch (e: Exception) {
            status.text = "Plugin error: ${e.message}"
            Log.e(METER_LOG_TAG, "Plugin load failed", e)
        } finally {
            loading = false
            if (restart && foreground) startTransport()
        }
    }

    private fun addAudioTrack() {
        val current = project ?: return
        if (current.tracks.size >= 16) { status.text = "Maximum 16 tracks"; return }
        val track = ProjectTrack(name = "Audio ${current.tracks.count { it.pluginId == null } + 1}")
        audioIds[track.id] = ensurePlayer().addAudioTrack()
        current.tracks.add(track)
        selected = current.tracks.lastIndex
        selectedEffect = null
        rollOpen = false
        applyMix(); renderTracks(); scheduleSave()
        status.text = "Empty audio track · tap its lane to import WAV / MP3 / OGG / FLAC"
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
            imported = null
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
        if (processing) advancePosition()
        current.bpm = (current.bpm + delta).coerceIn(40, 240)
        bpmLabel.text = "${current.bpm} BPM"
        scheduleSave()
    }

    private fun startTransport() {
        val p = player ?: return
        if (processing || stopping || loading || !foreground) return
        position = 0.0; lastFrames = 0L; scheduledStep = 0L
        p.startProcessing()
        processing = true
        transportButton.text = "STOP"
        sequencerJob = scope.launch {
            var displayedStep = -1L
            while (isActive && processing) {
                advancePosition()
                val step = floor(position).toLong()
                while (scheduledStep <= step) { sequenceStep(scheduledStep); scheduledStep++ }
                if (step != displayedStep) {
                    pianoRoll.playingStep = (step % STEPS_PER_CLIP).toInt()
                    clipPreviews.forEach { it.playingStep = pianoRoll.playingStep }
                    positionLabel.text = "${step / STEPS_PER_CLIP + 1}:${"%02d".format(step % STEPS_PER_CLIP + 1)}"
                    displayedStep = step
                }
                delay(4)
            }
        }
        startMetering()
    }

    private fun sequenceStep(step: Long) {
        pendingOffs.filterValues { it <= step }.keys.toList().forEach { key ->
            sendNote(key.trackId, key.pitch, false)
            pendingOffs.remove(key)
        }
        project?.tracks?.filter { it.pluginId != null }?.forEach { track ->
            track.clip?.notes?.filter { it.step == (step % STEPS_PER_CLIP).toInt() }?.forEach { note ->
                val key = NoteKey(track.id, note.pitch)
                if (liveNotes[note.pitch]?.track != track) {
                    if (pendingOffs.containsKey(key)) sendNote(track.id, note.pitch, false)
                    sendNote(track.id, note.pitch, true)
                    pendingOffs[key] = step + note.lengthSteps
                    Log.i(METER_LOG_TAG, "seq step=$step track=${track.name} note=${note.pitch} length=${note.lengthSteps}")
                }
            }
        }
    }

    private fun sendNote(trackId: String, pitch: Int, down: Boolean) {
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
        stopMetering()
        pianoRoll.playingStep = -1
        clipPreviews.forEach { it.playingStep = -1 }
        positionLabel.text = "1:01"
    }

    private fun toggleRecording() {
        val track = project?.tracks?.getOrNull(selected) ?: return
        if (loading || stopping || !rollOpen || track.pluginId == null || devicesById[track.id] == null) return
        recording = !recording
        recordButton.setTextColor(if (recording) Color.rgb(230, 120, 120) else Flm.text)
        if (recording) startTransport()
    }

    private fun playNote(note: Int, down: Boolean) {
        if (down) {
            if (stopping || loading) return
            val track = project?.tracks?.getOrNull(selected)?.takeIf { it.pluginId != null } ?: return
            if (devicesById[track.id] == null) return
            if (!processing) startTransport()
            advancePosition()
            val step = floor(position).toLong()
            val key = NoteKey(track.id, note)
            if (pendingOffs.remove(key) != null) sendNote(track.id, note, false)
            val recorded = if (recording) track.clip?.record((step % STEPS_PER_CLIP).toInt(), note) else null
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
        pianoRoll.lowPitch = (pianoRoll.lowPitch + delta).coerceIn(24, 96)
        keyboard.firstNote = pianoRoll.lowPitch
        refreshClips()
    }

    private fun refreshClips() {
        val track = project?.tracks?.getOrNull(selected)
        pianoRoll.clip = track?.clip
        clipPreviews.forEach { it.invalidate() }
        rollLabel.text = "${track?.name ?: "PIANO ROLL"} · Pattern 1 · C${pianoRoll.lowPitch / 12 - 1}"
    }

    private fun closeRoll() {
        keyboard.releaseKey()
        rollOpen = false
        recording = false
        recordButton.setTextColor(Flm.text)
        rollPanel.visibility = View.GONE
        arrangementPanel.visibility = View.VISIBLE
        keyboard.visibility = View.GONE
        recordButton.isEnabled = false
    }

    private fun selectTrack(index: Int, openPattern: Boolean = false) {
        if (loading || stopping) return
        keyboard.releaseKey()
        liveNotes.keys.toList().forEach { playNote(it, false) }
        val track = project!!.tracks[index]
        selected = index
        selectedEffect = null
        rollOpen = openPattern && track.pluginId != null
        if (rollOpen && track.clip == null) { track.clip = Clip(); scheduleSave() }
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
        lanes.removeAllViews(); devicePanel.removeAllViews(); mixerStrips.removeAllViews()
        clipPreviews.clear(); stripMeters.clear()
        devicesById.values.forEach { it.knobs.clear() }
        current.tracks.forEachIndexed { index, track ->
            lanes.addView(laneView(index, track))
        }
        lanes.addView(label(if (current.tracks.isEmpty()) "Empty project · hold here to add a track" else "Hold empty space to add a track", 13f).apply {
            contentDescription = "Add track by long press"
            gravity = Gravity.CENTER
            minHeight = dp(70)
            setOnLongClickListener { showTrackMenu(); true }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        })
        devicePanel.addView(label("MASTER INSERTS", 10f), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(26)))
        if (current.effects.isEmpty()) devicePanel.addView(label("No effects · + FX to add", 11f))
        current.effects.forEach { effect ->
            devicePanel.addView(button(effect.name) {
                selected = -1; selectedEffect = effect.id; closeRoll(); renderTracks()
            }.apply { setTextColor(if (selectedEffect == effect.id) Flm.accent else Flm.text) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(32)))
        }
        val track = current.tracks.getOrNull(selected)
        val deviceId = selectedEffect ?: track?.id
        devicesById[deviceId]?.let { devicePanel.addView(deviceCard(it)) }
        if (deviceId != null && devicesById[deviceId] == null && track?.pluginId != null)
            devicePanel.addView(label("Plugin unavailable · install its APK", 11f))
        if (track?.pluginId == null && track != null) {
            devicePanel.addView(label(track.name, 13f))
            devicePanel.addView(button("IMPORT AUDIO") { chooseAudio(track) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34)))
            devicePanel.addView(label("One-shot playback from start", 10f))
        }
        mixerStrips.addView(masterStrip())
        current.tracks.forEachIndexed { index, item -> mixerStrips.addView(mixerStrip(index, item)) }
        rollPanel.visibility = if (rollOpen) View.VISIBLE else View.GONE
        arrangementPanel.visibility = if (rollOpen) View.GONE else View.VISIBLE
        keyboard.visibility = if (rollOpen) View.VISIBLE else View.GONE
        recordButton.isEnabled = rollOpen && track?.id in devicesById
        transportButton.isEnabled = player != null && current.tracks.isNotEmpty()
        refreshClips()
    }

    private fun laneView(index: Int, track: ProjectTrack): View {
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(if (index == selected) Flm.panelAlt else Flm.panel)
            setPadding(dp(4), dp(3), dp(4), dp(3))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { bottomMargin = dp(3) }
            setOnLongClickListener { showTrackMenu(); true }
        }
        row.addView(View(this).apply { setBackgroundColor(Flm.laneColor(index)) },
            LinearLayout.LayoutParams(dp(3), ViewGroup.LayoutParams.MATCH_PARENT))
        row.addView(label("${index + 1}  ${track.name}", 12f).apply {
            setOnClickListener { selectTrack(index) }
        }, LinearLayout.LayoutParams(dp(138), ViewGroup.LayoutParams.MATCH_PARENT))
        val lane: View = if (track.pluginId != null && track.clip != null) {
            ClipPreviewView(this).apply {
                clip = track.clip
                contentDescription = "Pattern 1 · ${track.name}"
                setOnClickListener { selectTrack(index, true) }
                clipPreviews.add(this)
            }
        } else label(if (track.pluginId != null) "+ Create pattern" else track.audioName ?: "+ Import audio", 12f).apply {
            contentDescription = if (track.pluginId != null) "Create pattern · ${track.name}" else "Audio clip · ${track.name}"
            setOnClickListener {
                selectTrack(index, track.pluginId != null)
                if (track.pluginId == null) chooseAudio(track)
            }
        }
        lane.setOnLongClickListener { showTrackMenu(); true }
        row.addView(lane, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        row.addView(soloButton(track), LinearLayout.LayoutParams(dp(28), dp(28)))
        row.addView(muteButton(track), LinearLayout.LayoutParams(dp(28), dp(28)))
        return row
    }

    private fun muteButton(track: ProjectTrack) = button("M") {
        track.muted = !track.muted
        applyMix(); renderTracks(); scheduleSave()
    }.apply { setTextColor(if (track.muted) Flm.accent else Flm.textDim) }

    private fun soloButton(track: ProjectTrack) = button("S") {
        val next = !track.soloed
        project!!.tracks.forEach { it.soloed = false }
        track.soloed = next
        applyMix(); renderTracks(); scheduleSave()
    }.apply { setTextColor(if (track.soloed) Flm.accent else Flm.textDim) }

    private fun masterStrip(): View {
        val current = project!!
        val strip = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(2), dp(8), dp(2))
            setBackgroundColor(Flm.panelAlt)
            layoutParams = LinearLayout.LayoutParams(dp(142), ViewGroup.LayoutParams.MATCH_PARENT)
        }
        strip.addView(label("MASTER", 11f).apply { setTextColor(Flm.accent) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(22)))
        val controls = LinearLayout(this)
        meter = PeakMeterView(this)
        controls.addView(meter, LinearLayout.LayoutParams(dp(44), dp(62)))
        controls.addView(FaderView(this).apply {
            contentDescription = "Master volume"
            fraction = current.masterGain
            onFraction = { current.masterGain = it; applyMix(); scheduleSave() }
        }, LinearLayout.LayoutParams(dp(42), dp(62)))
        controls.addView(button("M") {
            current.masterMuted = !current.masterMuted
            applyMix(); renderTracks(); scheduleSave()
        }.apply {
            contentDescription = "Master mute"
            setTextColor(if (current.masterMuted) Flm.accent else Flm.textDim)
        }, LinearLayout.LayoutParams(dp(30), dp(28)))
        strip.addView(controls)
        return strip
    }

    private fun mixerStrip(index: Int, track: ProjectTrack): View {
        val strip = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(2), dp(8), dp(2))
            layoutParams = LinearLayout.LayoutParams(dp(138), ViewGroup.LayoutParams.MATCH_PARENT)
        }
        strip.addView(label("${index + 1}  ${track.name}", 10f).apply { setOnClickListener { selectTrack(index) } },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(22)))
        val controls = LinearLayout(this)
        val peak = PeakMeterView(this)
        stripMeters.add(track to peak)
        controls.addView(peak, LinearLayout.LayoutParams(dp(30), dp(62)))
        controls.addView(FaderView(this).apply {
            contentDescription = "Track ${index + 1} volume"
            fraction = track.gain
            onFraction = { track.gain = it; applyMix(); scheduleSave() }
        }, LinearLayout.LayoutParams(dp(38), dp(62)))
        val chips = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        chips.addView(soloButton(track), LinearLayout.LayoutParams(dp(32), dp(28)))
        chips.addView(muteButton(track), LinearLayout.LayoutParams(dp(32), dp(28)))
        controls.addView(chips)
        strip.addView(controls)
        return strip
    }

    private fun deviceCard(device: Device): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(6), dp(4), dp(6))
        }
        card.addView(label(pluginName(device.info), 13f).apply { setTextColor(Flm.text) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(24)))
        val count = minOf(device.instance.getParameterCount(), if (device.sampleEffect) 4 else MAX_KNOBS_PER_DEVICE)
        if (count == 0) card.addView(label("No parameters", 11f))
        for (first in 0 until count step 3) {
            val row = LinearLayout(this)
            for (index in first until minOf(first + 3, count)) {
                val parameter = device.instance.getParameter(index)
                val value = device.parameters[parameter.id] ?: device.instance.getParameterValue(index)
                val knob = KnobView(this).apply {
                    contentDescription = "Parameter ${parameter.name}"
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
        }
        return card
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
            while (isActive && processing) {
                val p = player ?: break
                val levels = p.peakLevels
                meter.levels = levels
                stripMeters.forEach { (track, view) ->
                    val peak = devicesById[track.id]?.let { p.getPluginPeak(it.instance) }
                        ?: audioIds[track.id]?.let { p.getAudioTrackPeak(it) } ?: 0f
                    view.levels = floatArrayOf(peak, peak)
                }
                Log.i(METER_LOG_TAG, "peak ${levels[0]} ${levels[1]}")
                delay(50)
            }
        }
    }

    private fun stopMetering() {
        meterJob?.cancel(); meterJob = null
        if (::meter.isInitialized) meter.levels = floatArrayOf(0f, 0f)
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
        devicesById.clear(); audioIds.clear(); pendingOffs.clear(); liveNotes.clear()
        processing = false; stopping = false; recording = false
    }

    @Deprecated("Deprecated in Android")
    override fun onBackPressed() {
        if (loading || stopping) return
        if (project == null) { super.onBackPressed(); return }
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
        finishLiveNotes()
        saveNow()
        outState.putString("projectId", project?.id)
        outState.putString("pendingAudioTrack", pendingAudioTrack)
        outState.putString("pendingAudioUri", pendingAudioUri?.toString())
        outState.putInt("selected", selected)
        outState.putBoolean("rollOpen", rollOpen)
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        foreground = true
    }

    override fun onStop() {
        foreground = false
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
        client.dispose()
        super.onDestroy()
    }
}
