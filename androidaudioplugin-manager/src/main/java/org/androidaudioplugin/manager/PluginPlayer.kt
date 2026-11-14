package org.androidaudioplugin.manager

import dev.atsushieno.ktmidi.Ump
import dev.atsushieno.ktmidi.UmpFactory
import dev.atsushieno.ktmidi.toPlatformNativeBytes
import org.androidaudioplugin.ParameterInformation
import org.androidaudioplugin.hosting.NativeRemotePluginInstance
import org.androidaudioplugin.hosting.UmpHelper

class PluginPlayer private constructor(private val native: Long) : AutoCloseable {
    companion object {
        const val sample_audio_filename = "androidaudioplugin_manager_sample_audio.ogg"

        fun create(sampleRate: Int, framesPerCallback: Int, channelCount: Int) =
            PluginPlayer(createNewPluginPlayer(sampleRate, framesPerCallback, channelCount))

        init {
            System.loadLibrary("androidaudioplugin-manager")
        }

        @JvmStatic
        private external fun createNewPluginPlayer(sampleRate: Int, framesPerCallback: Int, channelCount: Int): Long
    }

    override fun close() {
        deletePluginPlayer(native)
    }

    private external fun deletePluginPlayer(native: Long)

    fun setPlugin(plugin: NativeRemotePluginInstance) = setPluginNative(native, plugin.client, plugin.instanceId)

    private external fun setPluginNative(player: Long, nativeClient: Long, instanceId: Int)

    // Appends a plugin after the ones already in the chain; its output reaches the device.
    fun addPlugin(plugin: NativeRemotePluginInstance) = addPluginNative(native, plugin.client, plugin.instanceId)

    private external fun addPluginNative(player: Long, nativeClient: Long, instanceId: Int)

    // Always adds a silence-fed generator to the bus, before the serial master effects.
    fun addPluginToSumBus(plugin: NativeRemotePluginInstance) =
        addPluginToSumBusNative(native, plugin.client, plugin.instanceId)

    private external fun addPluginToSumBusNative(player: Long, nativeClient: Long, instanceId: Int)

    // Serial effects also work before any instrument or audio track has been added.
    fun addMasterEffect(plugin: NativeRemotePluginInstance) =
        addMasterEffectNative(native, plugin.client, plugin.instanceId)

    private external fun addMasterEffectNative(player: Long, nativeClient: Long, instanceId: Int)

    fun setMasterGain(gain: Float) = setMasterGainNative(native, gain)

    private external fun setMasterGainNative(player: Long, gain: Float)

    // IDs are sequential from zero; -1 means the node could not be allocated.
    fun addAudioTrack(): Int = addAudioTrackNative(native)

    private external fun addAudioTrackNative(player: Long): Int

    // One-shot clips restart with the transport. Failed loads preserve the previous clip.
    fun loadAudioTrack(trackId: Int, bytes: ByteArray, filename: String): Boolean =
        loadAudioTrackNative(native, trackId, bytes, filename)

    private external fun loadAudioTrackNative(player: Long, trackId: Int, bytes: ByteArray, filename: String): Boolean

    fun setAudioTrackGain(trackId: Int, gain: Float) = setAudioTrackGainNative(native, trackId, gain)

    private external fun setAudioTrackGainNative(player: Long, trackId: Int, gain: Float)

    // Last-block post-gain peak before the track is mixed into the bus.
    fun getAudioTrackPeak(trackId: Int): Float = getAudioTrackPeakNative(native, trackId)

    private external fun getAudioTrackPeakNative(player: Long, trackId: Int): Float

    // Feeds only the UMP groups set in the mask to the plugin node that hosts it.
    // 0xFFFF is the broadcast, 0 mutes the node's MIDI input.
    fun setPluginMidiGroups(plugin: NativeRemotePluginInstance, groupMask: Int) =
        setPluginMidiGroupsNative(native, plugin.client, plugin.instanceId, groupMask)

    private external fun setPluginMidiGroupsNative(player: Long, nativeClient: Long, instanceId: Int, groupMask: Int)

    // Linear audio gain; zero mutes audio without suppressing MIDI or note-offs.
    fun setPluginGain(plugin: NativeRemotePluginInstance, gain: Float) =
        setPluginGainNative(native, plugin.client, plugin.instanceId, gain)

    private external fun setPluginGainNative(player: Long, nativeClient: Long, instanceId: Int, gain: Float)

    // Last-block post-gain peak across the plugin's output channels, before summing.
    fun getPluginPeak(plugin: NativeRemotePluginInstance): Float =
        getPluginPeakNative(native, plugin.client, plugin.instanceId)

    private external fun getPluginPeakNative(player: Long, nativeClient: Long, instanceId: Int): Float

    // Peak of the most recently processed block, left then right.
    val peakLevels: FloatArray get() = getPeakLevelsNative(native)

    private external fun getPeakLevelsNative(player: Long): FloatArray

    // Audio-clock position in frames; reset when the transport starts, not a wall clock.
    val transportFrames: Long get() = getTransportFramesNative(native)

    private external fun getTransportFramesNative(player: Long): Long

    fun loadAudioResource(bytes: ByteArray, filename: String) =
        loadAudioResourceNative(native, bytes, filename)

    private external fun loadAudioResourceNative(player: Long, bytes: ByteArray, filename: String)

    fun startProcessing() = startProcessingNative(native)

    private external fun startProcessingNative(native: Long)

    fun pauseProcessing() = pauseProcessingNative(native)

    private external fun pauseProcessingNative(native: Long)

    fun playPreloadedAudio() = playPreloadedAudioNative(native)

    private external fun playPreloadedAudioNative(native: Long)

    fun enableAudioRecorder() = enableAudioRecorderNative(native)

    private external fun enableAudioRecorderNative(native: Long)

    // MIDI events

    fun addMidiEvent(ump: Long) = addMidiEvent(Ump(ump))

    fun addMidiEvent(ump: Ump) = addMidiEvents(ump.toPlatformNativeBytes())

    fun addMidiEvents(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset, timestampInNanoseconds: Long = 0) =
        addMidiEventsNative(native, bytes, offset, length, timestampInNanoseconds)

    private external fun addMidiEventsNative(native: Long, bytes: ByteArray, offset: Int = 0, length: Int, timestampInNanoseconds: Long)

    fun setParameterValue(parameter: ParameterInformation, value: Double) {
        val ints = UmpHelper.aapUmpSysex8ParameterPlain(parameter.id.toUInt(), parameter.minimumValue, parameter.maximumValue, value)
        val umps = ints.filterIndexed { i, _ -> i % 4 == 0 }.flatMapIndexed { i, v ->
            Ump(v, ints[i * 4 + 1], ints[i * 4 + 2], ints[i * 4 + 3]).toPlatformNativeBytes().asList() }
        addMidiEvents(umps.toByteArray())
    }

    fun processPitchBend(note: Int, value: Float) {
        assert(value in -1.0..1.0)
        val ump =
            if (note < 0) UmpFactory.midi2PitchBend(0, 0, (0x1_0000_0000 * value).toLong())
            else UmpFactory.midi2PerNotePitchBend(0, 0, note, (0x1_0000_0000 * value).toLong())
        addMidiEvent(ump)
    }

    fun processPressure(note: Int, value: Float) {
        assert(value in -1.0..1.0)
        val ump = Ump(UmpFactory.midi2PAf(0, 0, note, (0x1_0000_0000 * value).toLong()))
        addMidiEvent(ump)
    }

    fun setNoteState(note: Int, velocity16: Int, isNoteOn: Boolean, group: Int = 0) {
        val ump =
            if (isNoteOn) Ump(UmpFactory.midi2NoteOn(group, group, note, 0, velocity16, 0))
            else Ump(UmpFactory.midi2NoteOff(group, group, note, 0, velocity16, 0))
        addMidiEvent(ump)
    }

    // non-MIDI events
    fun setPresetIndex(index: Int) = setPresetIndexNative(native, index)

    private external fun setPresetIndexNative(native: Long, index: Int)

    fun readMidiOutput(data: ByteArray): Int = readMidiOutputNative(native, data)

    private external fun readMidiOutputNative(native: Long, data: ByteArray): Int
}
