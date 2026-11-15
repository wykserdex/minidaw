package org.androidaudioplugin.minidahost

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.UUID

const val PLAYLIST_STEPS = 4096
const val PLAYLIST_LANES = 64
const val MAX_PLAYLIST_CLIPS = 256

data class ArrangementClip(
    val id: String = UUID.randomUUID().toString(),
    val trackId: String,
    val lane: Int,
    val startStep: Int
)

data class ProjectTrack(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    val pluginId: String? = null,
    var audioFile: String? = null,
    var audioName: String? = null,
    var clip: Clip? = null,
    var gain: Float = 1f,
    var muted: Boolean = false,
    var soloed: Boolean = false,
    var drum: Boolean = false,
    val parameters: MutableMap<Int, Double> = mutableMapOf(),
    val builtinId: String? = null,
    val samples: MutableMap<Int, String> = mutableMapOf(),
    val sampleNames: MutableMap<Int, String> = mutableMapOf()
) {
    val isInstrument: Boolean get() = pluginId != null || builtinId != null
}

data class ProjectEffect(
    val id: String = UUID.randomUUID().toString(),
    val pluginId: String,
    val name: String,
    val parameters: MutableMap<Int, Double> = mutableMapOf()
)

data class DawProject(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    var bpm: Int = 140,
    var masterGain: Float = 1f,
    var masterMuted: Boolean = false,
    val tracks: MutableList<ProjectTrack> = mutableListOf(),
    val effects: MutableList<ProjectEffect> = mutableListOf(),
    val arrangement: MutableList<ArrangementClip> = mutableListOf()
)

class ProjectStore(context: Context) {
    private val filesDirectory = context.filesDir.canonicalFile
    private val uuidPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private val audioExtensions = setOf("wav", "mp3", "ogg", "flac")

    @Synchronized
    fun list(): List<DawProject> {
        val root = checkedChild(filesDirectory, "projects")
        val projects = mutableListOf<Pair<DawProject, Long>>()
        for (directory in root.listFiles().orEmpty()) {
            if (!directory.isDirectory) continue
            try {
                val project = load(directory.name)
                projects.add(project to projectFile(project.id).lastModified())
            } catch (_: Exception) {
                continue
            }
        }
        return projects.sortedByDescending { it.second }.map { it.first }
    }

    @Synchronized
    fun save(project: DawProject) {
        validateProject(project)
        val bytes = encodeProject(project).toString().toByteArray(Charsets.UTF_8)
        val atomicFile = AtomicFile(projectFile(project.id, create = true))
        val output = atomicFile.startWrite()
        try {
            output.write(bytes)
            atomicFile.finishWrite(output)
        } catch (failure: Throwable) {
            atomicFile.failWrite(output)
            throw failure
        }
    }

    @Synchronized
    fun load(id: String): DawProject {
        val atomicFile = AtomicFile(projectFile(id))
        val text = atomicFile.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
        val tokener = JSONTokener(text)
        val json = tokener.nextValue()
        require(json is JSONObject && tokener.nextClean() == '\u0000') { "Invalid project JSON" }
        val project = decodeProject(json)
        require(project.id == id) { "Project ID does not match its directory" }
        validateProject(project)
        return project
    }

    fun audioFile(project: DawProject, storedName: String): File {
        validateAudioFileName(storedName)
        return checkedChild(projectDirectory(project.id), storedName)
    }

    fun newAudioFile(project: DawProject, extension: String): File {
        val normalizedExtension = extension.removePrefix(".").lowercase(Locale.ROOT)
        require(normalizedExtension in audioExtensions) { "Unsupported audio extension" }
        val directory = projectDirectory(project.id, create = true)
        while (true) {
            val file = checkedChild(directory, "${UUID.randomUUID()}.$normalizedExtension")
            if (file.createNewFile()) return file
        }
    }

    private fun checkedChild(directory: File, component: String): File {
        require(component.isNotEmpty() && component != "." && component != ".." &&
            '/' !in component && '\\' !in component && '\u0000' !in component) {
            "Invalid path component"
        }
        val child = File(directory.canonicalFile, component)
        require(child.canonicalFile == child) { "Path must remain within project storage" }
        return child
    }

    private fun projectDirectory(id: String, create: Boolean = false): File {
        validateUuid(id)
        val root = checkedChild(filesDirectory, "projects")
        val directory = checkedChild(root, id)
        if (create && !directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw IOException("Cannot create project directory")
        }
        return directory
    }

    private fun projectFile(id: String, create: Boolean = false): File {
        val directory = projectDirectory(id, create)
        checkedChild(directory, "project.json.bak")
        checkedChild(directory, "project.json.new")
        return checkedChild(directory, "project.json")
    }

    private fun validateUuid(id: String) {
        require(uuidPattern.matches(id)) { "Invalid UUID" }
    }

    private fun displayName(value: String): String {
        val name = value.trim()
        require(name.length in 1..80 && name.none { Character.isISOControl(it) }) {
            "Name must contain 1 to 80 printable characters"
        }
        return name
    }

    private fun validatePluginId(id: String) {
        require(id.isNotEmpty() && id == id.trim() && id.none { Character.isISOControl(it) }) {
            "Invalid plugin ID"
        }
    }

    private fun validateAudioFileName(name: String) {
        val separator = name.lastIndexOf('.')
        require(separator > 0 && name.substring(separator + 1) in audioExtensions) {
            "Invalid stored audio filename"
        }
        validateUuid(name.substring(0, separator))
    }

    private fun validateGain(gain: Float) {
        require(gain.isFinite() && gain in 0f..1f) { "Gain must be finite and between 0 and 1" }
    }

    private fun validateProject(project: DawProject) {
        validateUuid(project.id)
        displayName(project.name)
        require(project.bpm in 40..240) { "BPM must be between 40 and 240" }
        validateGain(project.masterGain)
        require(project.tracks.size <= 16) { "Too many tracks" }
        require(project.effects.size <= 8) { "Too many effects" }
        require(project.tracks.count { it.pluginId != null } + project.effects.size <= 16) {
            "Too many plugins"
        }
        val ids = mutableSetOf<String>()
        for (track in project.tracks) {
            validateUuid(track.id)
            require(ids.add(track.id)) { "Duplicate track or effect ID" }
            displayName(track.name)
            track.pluginId?.let { validatePluginId(it) }
            track.builtinId?.let {
                require(it in setOf("sub", "poly", "drums")) { "Unknown built-in instrument" }
            }
            require(track.pluginId == null || track.builtinId == null) {
                "A track cannot contain both a plugin and a built-in instrument"
            }
            require(!track.isInstrument || (track.audioFile == null && track.audioName == null)) {
                "An instrument track cannot contain an audio clip"
            }
            track.audioFile?.let { validateAudioFileName(it) }
            track.audioName?.let { displayName(it) }
            require(track.samples.size <= 128 && track.sampleNames.size <= 128) { "Too many pads" }
            for ((pitch, file) in track.samples) {
                require(pitch in 0..127) { "Invalid pad pitch" }
                validateAudioFileName(file)
            }
            for ((pitch, name) in track.sampleNames) {
                require(pitch in 0..127 && pitch in track.samples) { "Sample name has no assigned pad" }
                displayName(name)
            }
            require(track.isInstrument || (track.samples.isEmpty() && track.sampleNames.isEmpty())) {
                "An audio track cannot contain pad samples"
            }
            validateGain(track.gain)
            require(track.parameters.values.all { it.isFinite() }) { "Non-finite parameter value" }
            track.clip?.let { clip ->
                require(clip.notes.size <= 2048) { "Too many clip notes" }
                for (note in clip.notes) {
                    require(note.step in 0 until STEPS_PER_CLIP && note.pitch in 0..127 &&
                        note.lengthSteps in 1..(STEPS_PER_CLIP - note.step)) {
                        "Invalid clip note"
                    }
                }
            }
        }
        for (effect in project.effects) {
            validateUuid(effect.id)
            require(ids.add(effect.id)) { "Duplicate track or effect ID" }
            validatePluginId(effect.pluginId)
            displayName(effect.name)
            require(effect.parameters.values.all { it.isFinite() }) { "Non-finite parameter value" }
        }
        require(project.arrangement.size <= MAX_PLAYLIST_CLIPS) { "Too many playlist clips" }
        val placementIds = mutableSetOf<String>()
        val tracksById = project.tracks.associateBy { it.id }
        for (placement in project.arrangement) {
            validateUuid(placement.id)
            require(placementIds.add(placement.id)) { "Duplicate playlist clip ID" }
            require(placement.lane in 0 until PLAYLIST_LANES) { "Invalid playlist lane" }
            require(placement.startStep in 0 until PLAYLIST_STEPS) { "Invalid playlist start step" }
            val track = tracksById[placement.trackId]
            require(track != null) { "Playlist clip references an unknown track" }
            require(track.clip != null || track.audioFile != null) { "Playlist source has no clip or audio file" }
        }
    }

    private fun encodeProject(project: DawProject): JSONObject {
        val tracks = JSONArray()
        for (track in project.tracks) {
            tracks.put(JSONObject()
                .put("id", track.id)
                .put("name", displayName(track.name))
                .put("pluginId", track.pluginId ?: JSONObject.NULL)
                .put("builtinId", track.builtinId ?: JSONObject.NULL)
                .put("samples", encodePadStrings(track.samples))
                .put("sampleNames", encodePadStrings(track.sampleNames, names = true))
                .put("audioFile", track.audioFile ?: JSONObject.NULL)
                .put("audioName", track.audioName?.let { displayName(it) } ?: JSONObject.NULL)
                .put("clip", track.clip?.let { encodeClip(it) } ?: JSONObject.NULL)
                .put("gain", track.gain.toDouble())
                .put("muted", track.muted)
                .put("soloed", track.soloed)
                .put("drum", track.drum)
                .put("parameters", encodeParameters(track.parameters)))
        }
        val effects = JSONArray()
        for (effect in project.effects) {
            effects.put(JSONObject()
                .put("id", effect.id)
                .put("pluginId", effect.pluginId)
                .put("name", displayName(effect.name))
                .put("parameters", encodeParameters(effect.parameters)))
        }
        val arrangement = JSONArray()
        for (placement in project.arrangement) {
            arrangement.put(JSONObject()
                .put("id", placement.id)
                .put("trackId", placement.trackId)
                .put("lane", placement.lane)
                .put("startStep", placement.startStep))
        }
        return JSONObject()
            .put("version", 1)
            .put("id", project.id)
            .put("name", displayName(project.name))
            .put("bpm", project.bpm)
            .put("masterGain", project.masterGain.toDouble())
            .put("masterMuted", project.masterMuted)
            .put("tracks", tracks)
            .put("effects", effects)
            .put("arrangement", arrangement)
    }

    private fun encodeClip(clip: Clip): JSONObject {
        val notes = JSONArray()
        for (note in clip.notes) {
            notes.put(JSONObject()
                .put("step", note.step)
                .put("pitch", note.pitch)
                .put("lengthSteps", note.lengthSteps))
        }
        return JSONObject().put("notes", notes)
    }

    private fun encodeParameters(parameters: Map<Int, Double>): JSONObject {
        val json = JSONObject()
        for ((id, value) in parameters.toSortedMap()) json.put(id.toString(), value)
        return json
    }

    private fun encodePadStrings(values: Map<Int, String>, names: Boolean = false): JSONObject {
        val json = JSONObject()
        for ((pitch, value) in values.toSortedMap()) {
            json.put(pitch.toString(), if (names) displayName(value) else value)
        }
        return json
    }

    private fun decodeProject(json: JSONObject): DawProject {
        require(integer(json, "version") == 1) { "Unsupported project version" }
        val trackArray = array(json, "tracks")
        val effectArray = array(json, "effects")
        require(trackArray.length() <= 16 && effectArray.length() <= 8) { "Too many tracks or effects" }
        val tracks = mutableListOf<ProjectTrack>()
        for (index in 0 until trackArray.length()) {
            val track = trackArray.get(index)
            require(track is JSONObject) { "Invalid track" }
            tracks.add(ProjectTrack(
                id = string(track, "id"),
                name = displayName(string(track, "name")),
                pluginId = nullableString(track, "pluginId"),
                audioFile = nullableString(track, "audioFile"),
                audioName = nullableString(track, "audioName")?.let { displayName(it) },
                clip = decodeClip(track),
                gain = gain(track, "gain"),
                muted = boolean(track, "muted"),
                soloed = boolean(track, "soloed"),
                drum = track.optBoolean("drum", false),
                parameters = decodeParameters(track),
                builtinId = nullableString(track, "builtinId"),
                samples = decodePadStrings(track, "samples"),
                sampleNames = decodePadStrings(track, "sampleNames", names = true)
            ))
        }
        val effects = mutableListOf<ProjectEffect>()
        for (index in 0 until effectArray.length()) {
            val effect = effectArray.get(index)
            require(effect is JSONObject) { "Invalid effect" }
            effects.add(ProjectEffect(
                id = string(effect, "id"),
                pluginId = string(effect, "pluginId"),
                name = displayName(string(effect, "name")),
                parameters = decodeParameters(effect)
            ))
        }
        val arrangement = mutableListOf<ArrangementClip>()
        if (json.has("arrangement")) {
            val placements = array(json, "arrangement")
            require(placements.length() <= MAX_PLAYLIST_CLIPS) { "Too many playlist clips" }
            for (index in 0 until placements.length()) {
                val placement = placements.get(index)
                require(placement is JSONObject) { "Invalid playlist clip" }
                arrangement.add(ArrangementClip(
                    id = string(placement, "id"),
                    trackId = string(placement, "trackId"),
                    lane = integer(placement, "lane"),
                    startStep = integer(placement, "startStep")
                ))
            }
        } else {
            for ((index, track) in tracks.withIndex()) {
                if (track.clip != null || track.audioFile != null) {
                    arrangement.add(ArrangementClip(trackId = track.id, lane = index, startStep = 0))
                }
            }
        }
        return DawProject(
            id = string(json, "id"),
            name = displayName(string(json, "name")),
            bpm = integer(json, "bpm"),
            masterGain = gain(json, "masterGain"),
            masterMuted = boolean(json, "masterMuted"),
            tracks = tracks,
            effects = effects,
            arrangement = arrangement
        )
    }

    private fun decodeClip(track: JSONObject): Clip? {
        val value = track.opt("clip")
        if (value == null || value === JSONObject.NULL) return null
        require(value is JSONObject) { "Invalid clip" }
        val notes = array(value, "notes")
        require(notes.length() <= 2048) { "Too many clip notes" }
        return Clip().apply {
            for (index in 0 until notes.length()) {
                val note = notes.get(index)
                require(note is JSONObject) { "Invalid clip note" }
                this.notes.add(ClipNote(
                    step = integer(note, "step"),
                    pitch = integer(note, "pitch"),
                    lengthSteps = integer(note, "lengthSteps")
                ))
            }
        }
    }

    private fun decodeParameters(owner: JSONObject): MutableMap<Int, Double> {
        val json = owner.get("parameters")
        require(json is JSONObject) { "Invalid parameters" }
        val parameters = mutableMapOf<Int, Double>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val id = key.toIntOrNull()
            require(id != null && id.toString() == key) { "Invalid parameter ID" }
            parameters[id] = number(json, key)
        }
        return parameters
    }

    private fun decodePadStrings(owner: JSONObject, key: String, names: Boolean = false): MutableMap<Int, String> {
        if (!owner.has(key)) return mutableMapOf()
        val json = owner.get(key)
        require(json is JSONObject && json.length() <= 128) { "Invalid pad map: $key" }
        val values = mutableMapOf<Int, String>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val pitchKey = keys.next()
            val pitch = pitchKey.toIntOrNull()
            require(pitch != null && pitch in 0..127 && pitch.toString() == pitchKey) { "Invalid pad pitch" }
            val value = string(json, pitchKey)
            values[pitch] = if (names) displayName(value) else value.also { validateAudioFileName(it) }
        }
        return values
    }

    private fun string(json: JSONObject, key: String): String {
        val value = json.get(key)
        require(value is String) { "Invalid string: $key" }
        return value
    }

    private fun nullableString(json: JSONObject, key: String): String? {
        val value = json.opt(key)
        if (value == null || value === JSONObject.NULL) return null
        require(value is String) { "Invalid string: $key" }
        return value
    }

    private fun boolean(json: JSONObject, key: String): Boolean {
        val value = json.get(key)
        require(value is Boolean) { "Invalid boolean: $key" }
        return value
    }

    private fun array(json: JSONObject, key: String): JSONArray {
        val value = json.get(key)
        require(value is JSONArray) { "Invalid array: $key" }
        return value
    }

    private fun number(json: JSONObject, key: String): Double {
        val value = json.get(key)
        require(value is Number) { "Invalid number: $key" }
        val number = value.toDouble()
        require(number.isFinite()) { "Non-finite number: $key" }
        return number
    }

    private fun integer(json: JSONObject, key: String): Int {
        val value = number(json, key)
        require(value >= Int.MIN_VALUE.toDouble() && value <= Int.MAX_VALUE.toDouble() &&
            value % 1.0 == 0.0) { "Invalid integer: $key" }
        return value.toInt()
    }

    private fun gain(json: JSONObject, key: String): Float {
        val value = number(json, key)
        require(value in 0.0..1.0) { "Gain must be between 0 and 1: $key" }
        return value.toFloat()
    }
}
