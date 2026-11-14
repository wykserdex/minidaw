package org.androidaudioplugin.minidahost

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioManager
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.androidaudioplugin.ParameterInformation
import org.androidaudioplugin.PluginInformation
import org.androidaudioplugin.hosting.AudioPluginClientBase
import org.androidaudioplugin.hosting.AudioPluginHostHelper
import org.androidaudioplugin.hosting.NativeRemotePluginInstance
import org.androidaudioplugin.manager.PluginPlayer
import java.util.concurrent.CopyOnWriteArrayList

private const val DEFAULT_CONTROL_BYTES_PER_BLOCK = 0x10000
private const val NOTE_VELOCITY_16 = 12000
private const val MAX_KNOBS_PER_DEVICE = 8
private const val EXTRA_AUTOSTART = "autostart"
private const val EXTRA_NOTE = "note"
private const val EXTRA_NOTE_MS = "note_ms"
private const val EXTRA_FPC = "fpc"
private const val EXTRA_CHAIN = "chain"
private const val EXTRA_GROUP = "group"
private const val EXTRA_PLAY_GROUP = "play_group"
private const val EXTRA_NOTE2 = "note2"
private const val EXTRA_PLAY_GROUP2 = "play_group2"
private const val EXTRA_DUPLICATE = "duplicate"
private const val METER_LOG_TAG = "MiniDAHost"
private const val METER_INTERVAL_MS = 50L

class MainActivity : Activity() {

    private class Track(val info: PluginInformation, val instance: NativeRemotePluginInstance) {
        var group: Int = 0
        var instrument = false
        var muted = false
        var soloed = false
        val knobs = mutableListOf<KnobView>()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val client by lazy { AudioPluginClientBase(applicationContext) }
    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private val sampleRate by lazy {
        audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toInt() ?: 48000
    }
    private var fpcOverride = 0
    private val framesPerCallback by lazy {
        if (fpcOverride > 0) fpcOverride
        else audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toInt() ?: 96
    }

    private var plugins: List<PluginInformation> = emptyList()
    private val tracks = CopyOnWriteArrayList<Track>()
    private var player: PluginPlayer? = null
    private var processing = false
    private var selected = 0
    private var meterJob: Job? = null

    private lateinit var status: TextView
    private lateinit var transportButton: Button
    private lateinit var lanes: LinearLayout
    private lateinit var devices: LinearLayout
    private lateinit var lanesScroll: ScrollView
    private lateinit var devicesScroll: ScrollView
    private lateinit var meter: PeakMeterView
    private lateinit var keyboard: KeyboardView

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The default theme's bar paints over the top 72dp of our own UI in landscape.
        actionBar?.hide()
        fpcOverride = intent.getIntExtra(EXTRA_FPC, 0)
        buildUi()
        refreshPluginList()
        status.text = "output ${sampleRate} Hz / ${framesPerCallback} fpc; " +
                "${plugins.size} AAP plugins found"
        autoRun()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Flm.bg)
            fitsSystemWindows = true
        }

        val title = TextView(this).apply {
            text = "MiniDAW"
            setTextColor(Flm.accent)
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(16), dp(8), dp(16), dp(4))
        }
        status = TextView(this).apply {
            setTextColor(Flm.textDim)
            textSize = 12f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(8), dp(10), dp(16), dp(6))
        }
        root.addView(horizontalRow(title, status))

        lanes = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        devices = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        lanesScroll = scrolled(lanes)
        devicesScroll = scrolled(devices)
        val main = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        main.addView(lanesScroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.05f))
        main.addView(devicesScroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        root.addView(main, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        meter = PeakMeterView(this)
        meter.label = "MASTER"
        root.addView(meter, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(30)))

        transportButton = flmButton("PLAY", enabled = false) { toggleTransport() }
        val addButton = flmButton("ADD") { showPluginPicker() }
        val clearButton = flmButton("CLEAR") { releaseAll() }
        val transport = horizontalRow(transportButton, addButton, clearButton)
        transport.setBackgroundColor(Flm.panel)
        root.addView(transport, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT))

        keyboard = KeyboardView(this).apply {
            onNote = { note, down -> playNote(note, down) }
        }
        root.addView(keyboard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(110)))

        setContentView(root)
    }

    private fun horizontalRow(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        views.forEach { addView(it, LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
    }

    private fun scrolled(content: LinearLayout) = ScrollView(this).apply {
        setBackgroundColor(Flm.bg)
        addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    //---------------- model

    private fun refreshPluginList() {
        plugins = AudioPluginHostHelper.queryAudioPluginServices(this).flatMap { it.plugins }
    }

    private fun chainIndices(): List<Int> {
        val byCategory: (String) -> Int = { category ->
            plugins.indexOfFirst { it.category?.contains(category, true) == true }
        }
        val instrument = byCategory("instrument")
        // Two instances of one instrument make the summing bus measurable: a note on both groups
        // has to double the peak instead of leaving it flat.
        val doubled = instrument >= 0 && intent.getBooleanExtra(EXTRA_DUPLICATE, false)
        return listOf(instrument, if (doubled) instrument else -1, byCategory("effect"))
            .filter { it >= 0 }
            .ifEmpty { listOf(0) }
    }

    private fun showPluginPicker() {
        if (plugins.isEmpty()) {
            status.text = "no AAP plugins found"
            return
        }
        val labels = plugins.map { "${it.displayName}  [${it.category ?: "?"}]" }.toTypedArray()
        val picked = BooleanArray(plugins.size)
        AlertDialog.Builder(this)
            .setTitle("AAP plugins")
            .setMultiChoiceItems(labels, picked) { _, which, checked -> picked[which] = checked }
            .setPositiveButton("ADD") { _, _ ->
                scope.launch { addTracks(picked.indices.filter { picked[it] }.map { plugins[it] }) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // The first plugin heads the graph; later instruments join the summing bus and later effects
    // are appended in series, so the summed instruments are what gets processed.
    private suspend fun addTracks(infos: List<PluginInformation>) {
        if (infos.isEmpty()) return
        for (info in infos) {
            client.connectToPluginService(info.packageName)
            val instance = client.instantiateNativePlugin(info)
            instance.prepare(framesPerCallback, sampleRate, DEFAULT_CONTROL_BYTES_PER_BLOCK)
            val isInstrument = info.category?.contains("instrument", true) == true
            val current = player
            player = if (current == null)
                PluginPlayer.create(sampleRate, framesPerCallback, 2).apply { setPlugin(instance) }
            else if (isInstrument)
                current.apply { addPluginToSumBus(instance) }
            else
                current.apply { addPlugin(instance) }
            tracks += Track(info, instance).apply {
                group = tracks.size
                instrument = isInstrument
            }
        }
        applyRouting()
        transportButton.isEnabled = true
        renderTracks()
        status.text = "chain: " + tracks.joinToString(" -> ") { it.info.displayName }
    }

    private fun playNote(note: Int, down: Boolean) {
        val track = tracks.getOrNull(selected) ?: return
        player?.setNoteState(note, NOTE_VELOCITY_16, down, track.group)
    }

    // A muted or unsoloed track gets an empty group mask, so its node receives no note at all.
    private fun applyRouting() {
        val anySolo = tracks.any { it.soloed }
        tracks.forEach { track ->
            val mask = when {
                track.muted || (anySolo && !track.soloed) -> 0
                else -> 1 shl track.group
            }
            player?.setPluginMidiGroups(track.instance, mask)
            Log.i(METER_LOG_TAG, "routing ${track.info.displayName} group=${track.group} mask=$mask")
        }
    }

    private fun toggleTransport() {
        val p = player ?: return
        if (processing) {
            p.pauseProcessing()
            stopMetering()
            transportButton.text = "PLAY"
        } else {
            p.startProcessing()
            transportButton.text = "STOP"
            startMetering()
        }
        processing = !processing
    }

    private fun releaseAll() {
        stopMetering()
        player?.close() // its nodes reference the instances, so it goes first
        player = null
        processing = false
        transportButton.isEnabled = false
        transportButton.text = "PLAY"
        tracks.forEach {
            it.instance.deactivate()
            it.instance.destroy()
        }
        tracks.clear()
        selected = 0
        renderTracks()
        status.text = "cleared"
    }

    //---------------- view

    private var renderedTrackCount = -1

    private fun renderTracks() {
        lanes.removeAllViews()
        devices.removeAllViews()
        tracks.forEachIndexed { index, track ->
            lanes.addView(laneView(index, track))
            devices.addView(deviceCard(track))
        }
        if (tracks.isEmpty()) {
            lanes.addView(dimLabel("no tracks - press ADD"))
            devices.addView(dimLabel("device panel"))
        }
        // Tapping S/M/Solo rebuilds the same roster, which would otherwise throw the reader back
        // to the top mid-scroll; only a changed track count is a real roster edit.
        if (tracks.size != renderedTrackCount) {
            renderedTrackCount = tracks.size
            lanesScroll.post {
                lanesScroll.scrollTo(0, 0)
                devicesScroll.scrollTo(0, 0)
            }
        }
    }

    private fun dimLabel(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(Flm.textDim)
        textSize = 13f
        setPadding(dp(16), dp(16), dp(16), dp(16))
    }

    private fun laneView(index: Int, track: Track): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(if (index == selected) Flm.panelAlt else Flm.panel)
            setPadding(dp(4), dp(8), dp(8), dp(8))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        row.addView(View(this).apply {
            setBackgroundColor(Flm.laneColor(index))
            layoutParams = LinearLayout.LayoutParams(dp(6), dp(42))
        })
        row.addView(TextView(this).apply {
            text = "${track.info.displayName}  C${track.group}"
            setTextColor(Flm.text)
            textSize = 12f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(8), 0, dp(8), 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(chip("S", track.soloed) {
            tracks.forEach { it.soloed = false }
            track.soloed = !track.soloed
            applyRouting()
            renderTracks()
        })
        row.addView(chip("M", track.muted) {
            track.muted = !track.muted
            applyRouting()
            renderTracks()
        })
        row.setOnClickListener {
            selected = index
            renderTracks()
        }
        return row
    }

    private fun flmButton(label: String, enabled: Boolean = true,
                          onClick: () -> Unit) = Button(this).apply {
        text = label
        isEnabled = enabled
        setTextColor(Flm.text)
        setBackgroundColor(Flm.panelAlt)
        setOnClickListener { onClick() }
    }

    private fun chip(text: String, active: Boolean, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        minWidth = 0
        setPadding(dp(10), 0, dp(10), 0)
        textSize = 11f
        setTextColor(if (active) Flm.accent else Flm.textDim)
        setBackgroundColor(Flm.bg)
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(38))
        setOnClickListener { onClick() }
    }

    private fun deviceCard(track: Track): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Flm.panel)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(6), dp(6), dp(6), 0) }
        }
        card.addView(TextView(this).apply {
            text = track.info.displayName
            setTextColor(Flm.accent)
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
        })
        val count = minOf(track.instance.getParameterCount(), MAX_KNOBS_PER_DEVICE)
        if (count == 0)
            card.addView(dimLabel("no parameters"))
        else {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            track.knobs.clear()
            for (index in 0 until count) {
                val parameter = track.instance.getParameter(index)
                val knob = KnobView(this).apply {
                    label = parameter.name.take(9)
                    fraction = fractionOf(parameter, track.instance.getParameterValue(index))
                    onFraction = { fraction ->
                        val value = parameter.minimumValue +
                                (parameter.maximumValue - parameter.minimumValue) * fraction
                        track.instance.setCachedParameterValue(index, value)
                        player?.setParameterValue(parameter, value)
                    }
                }
                track.knobs += knob
                row.addView(knob, LinearLayout.LayoutParams(0, dp(74), 1f))
            }
            card.addView(row)
        }
        return card
    }

    private fun fractionOf(parameter: ParameterInformation, value: Double): Float =
        if (parameter.maximumValue > parameter.minimumValue)
            ((value - parameter.minimumValue) /
                    (parameter.maximumValue - parameter.minimumValue)).toFloat() else 0f

    private fun startMetering() {
        meterJob?.cancel()
        meterJob = scope.launch {
            while (isActive && processing) {
                val levels = player?.peakLevels ?: break
                meter.levels = levels
                meter.label = "MASTER  L ${"%.3f".format(levels[0])}  R ${"%.3f".format(levels[1])}"
                Log.i(METER_LOG_TAG, "peak ${levels[0]} ${levels[1]}")
                refreshKnobs()
                delay(METER_INTERVAL_MS)
            }
        }
    }

    // The plugin owns the value; the host only mirrors it back into the arc.
    private fun refreshKnobs() {
        tracks.forEach { track ->
            val count = minOf(track.instance.getParameterCount(), MAX_KNOBS_PER_DEVICE)
            for (index in 0 until count) {
                val knob = track.knobs.getOrNull(index) ?: continue
                val parameter = track.instance.getParameter(index)
                knob.fraction = fractionOf(parameter, track.instance.getParameterValue(index))
            }
        }
    }

    private fun stopMetering() {
        meterJob?.cancel()
        meterJob = null
        meter.levels = floatArrayOf(0f, 0f)
        meter.label = "MASTER  stopped"
    }

    //---------------- measurement entry

    // Loads a chain and plays one note without touching the screen, so a routing change can be
    // measured from logcat instead of judged by ear.
    private fun autoRun() {
        if (!intent.getBooleanExtra(EXTRA_AUTOSTART, false)) return
        scope.launch {
            if (plugins.isEmpty()) {
                status.text = "autostart: no AAP plugins found"
                return@launch
            }
            addTracks(chainIndices().map { plugins[it] })
            val group = intent.getIntExtra(EXTRA_GROUP, -1)
            if (group >= 0 && tracks.isNotEmpty()) {
                tracks[0].group = group
                applyRouting()
            }
            if (!processing) toggleTransport()
            val note = intent.getIntExtra(EXTRA_NOTE, -1)
            if (note >= 0) {
                val playGroup = intent.getIntExtra(EXTRA_PLAY_GROUP, tracks[0].group)
                val note2 = intent.getIntExtra(EXTRA_NOTE2, -1)
                val playGroup2 = intent.getIntExtra(EXTRA_PLAY_GROUP2, 1)
                delay(700)
                Log.i(METER_LOG_TAG, "note $note group $playGroup")
                player?.setNoteState(note, NOTE_VELOCITY_16, true, playGroup)
                if (note2 >= 0) {
                    Log.i(METER_LOG_TAG, "note $note2 group $playGroup2")
                    player?.setNoteState(note2, NOTE_VELOCITY_16, true, playGroup2)
                }
                delay(intent.getIntExtra(EXTRA_NOTE_MS, 1500).toLong())
                player?.setNoteState(note, NOTE_VELOCITY_16, false, playGroup)
                if (note2 >= 0)
                    player?.setNoteState(note2, NOTE_VELOCITY_16, false, playGroup2)
                status.text = "autostart: note $note on group $playGroup"
            }
        }
    }

    override fun onDestroy() {
        releaseAll()
        client.dispose()
        super.onDestroy()
    }
}
