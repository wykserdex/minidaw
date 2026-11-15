package org.androidaudioplugin.minidahost

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipInputStream

data class Kit(val id: String, val name: String, val samples: List<KitSample>)

data class KitSample(val id: String, val name: String, val file: File, val pitch: Int?)

/**
 * App-private, reusable copies of kits. All methods do synchronous IO: call them off the UI thread.
 * Returned files belong to the library; copy a selected file to ProjectStore.newAudioFile() before
 * assigning it to a project. Neither import method writes to, deletes, or retains access to its source.
 */
class DrumKitStore(context: Context) {
    private val resolver = context.contentResolver
    private val filesDirectory = context.filesDir.canonicalFile

    @Synchronized
    fun list(): List<Kit> {
        val root = checkedChild(filesDirectory, "drumkits")
        if (!root.exists()) return emptyList()
        require(root.isDirectory) { "Invalid drumkit library directory" }
        val kits = mutableListOf<Kit>()
        for (directory in root.listFiles().orEmpty()) {
            if (!UUID_PATTERN.matches(directory.name)) continue // Includes unpublished staging areas.
            try {
                kits.add(readKit(checkedChild(root, directory.name)))
            } catch (_: Exception) {
                // Incomplete, corrupt, or symlinked kits are not exposed to preview/assignment callers.
            }
        }
        return kits.sortedWith(compareBy<Kit> { it.name.lowercase(Locale.ROOT) }.thenBy { it.id })
    }

    @Synchronized
    fun importZip(uri: Uri): Kit {
        require(uri.scheme == "content") { "Choose a content URI for a ZIP archive" }
        val name = printableName(sourceDisplayName(uri).substringBeforeLast('.'), "Drum kit")
        return publish(name) { stage, samples, budget ->
            val source = resolver.openInputStream(uri) ?: throw IOException("Cannot open archive")
            source.use { raw ->
                val encoded = LimitedInputStream(raw, MAX_TOTAL_BYTES)
                ZipInputStream(encoded.buffered()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        budget.scan()
                        val parts = zipPath(entry.name, entry.isDirectory)
                        require(entry.size <= MAX_FILE_BYTES) { "Archive entry is too large" }
                        val filename = parts.last()
                        val extension = audioExtension(filename)
                        if (!entry.isDirectory && extension != null) {
                            addSample(stage, samples, budget, filename, extension, zip)
                        } else {
                            // Drain ignored entries too: ZIP bombs must not hide in non-audio content.
                            copyBounded(zip, null, budget)
                        }
                        zip.closeEntry()
                    }
                    // ZipInputStream stops at the central directory. Count the remaining encoded
                    // bytes, including arbitrary trailers, rather than trusting a reported URI size.
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val count = encoded.read(buffer)
                        if (count < 0) break
                        if (count == 0) throw IOException("Archive stream made no progress")
                    }
                }
            }
        }
    }

    @Synchronized
    fun importFolder(uri: Uri): Kit {
        require(uri.scheme == "content" && DocumentsContract.isTreeUri(uri)) {
            "Choose a folder with the Storage Access Framework"
        }
        val rootId = if (DocumentsContract.isDocumentUri(contextForDocuments, uri)) {
            DocumentsContract.getDocumentId(uri)
        } else {
            DocumentsContract.getTreeDocumentId(uri)
        }
        val rootUri = DocumentsContract.buildDocumentUriUsingTree(uri, rootId)
        val root = resolver.query(rootUri, DOCUMENT_COLUMNS, null, null, null)?.use { cursor ->
            require(cursor.moveToFirst()) { "Folder is unavailable" }
            val document = document(cursor)
            require(document.id == rootId && !cursor.moveToNext()) { "Invalid folder document" }
            document
        } ?: throw IOException("Cannot read folder")
        require(root.mime == DocumentsContract.Document.MIME_TYPE_DIR) { "Selected document is not a folder" }
        require(root.flags and DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT == 0) { "Virtual folders are unsupported" }
        return publish(printableName(root.name, "Drum kit")) { stage, samples, budget ->
            val visited = mutableSetOf(root.id)
            importChildren(uri, root.id, 0, visited, stage, samples, budget)
        }
    }

    private val contextForDocuments = context.applicationContext ?: context

    private fun importChildren(
        tree: Uri,
        parentId: String,
        depth: Int,
        visited: MutableSet<String>,
        stage: File,
        samples: MutableList<KitSample>,
        budget: Budget
    ) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val cursor = resolver.query(children, DOCUMENT_COLUMNS, null, null, null)
            ?: throw IOException("Cannot enumerate folder")
        cursor.use {
            while (it.moveToNext()) {
                budget.scan()
                val child = document(it)
                safeComponent(child.name)
                require(visited.add(child.id)) { "Repeated document or folder cycle" }
                if (child.mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    require(depth < MAX_DEPTH) { "Folder nesting is too deep" }
                    require(child.flags and DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT == 0) {
                        "Virtual folders are unsupported"
                    }
                    importChildren(tree, child.id, depth + 1, visited, stage, samples, budget)
                } else {
                    val extension = audioExtension(child.name) ?: continue
                    require(child.flags and DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT == 0) {
                        "Virtual audio documents are unsupported"
                    }
                    require(child.size == null || child.size <= MAX_FILE_BYTES) { "Audio file is too large" }
                    require(samples.size < MAX_AUDIO_FILES) { "Too many audio files" }
                    val documentUri = DocumentsContract.buildDocumentUriUsingTree(tree, child.id)
                    val source = resolver.openInputStream(documentUri) ?: throw IOException("Cannot open audio document")
                    source.use { input -> addSample(stage, samples, budget, child.name, extension, input) }
                }
            }
        }
    }

    private fun publish(name: String, copySources: (File, MutableList<KitSample>, Budget) -> Unit): Kit {
        val root = checkedChild(filesDirectory, "drumkits")
        if (!root.isDirectory && !root.mkdir() && !root.isDirectory) {
            throw IOException("Cannot create drumkit library")
        }
        val id = UUID.randomUUID().toString()
        val destination = checkedChild(root, id)
        val stage = checkedChild(root, ".import-$id")
        require(!destination.exists() && stage.mkdir()) { "Cannot create a private import staging area" }
        val samples = mutableListOf<KitSample>()
        val budget = Budget()
        var published = false
        try {
            copySources(stage, samples, budget)
            require(samples.isNotEmpty()) { "No supported audio files were found" }
            val kit = Kit(id, name, samples.map { it.copy(file = checkedChild(destination, it.file.name)) })
            val metadata = encodeKit(kit).toString().toByteArray(Charsets.UTF_8)
            require(metadata.size.toLong() <= MAX_MANIFEST_BYTES) { "Kit metadata is too large" }
            val atomic = manifest(stage)
            val output = atomic.startWrite()
            try {
                output.write(metadata)
                atomic.finishWrite(output)
            } catch (failure: Throwable) {
                atomic.failWrite(output)
                throw failure
            }
            // Audio is complete and the manifest is written last. Same-directory rename publishes
            // the entire kit at once; readers only consider UUID directories with a valid manifest.
            require(!destination.exists()) { "Kit already exists" }
            if (!stage.renameTo(destination)) throw IOException("Cannot publish drumkit")
            published = true
            return kit
        } finally {
            if (!published) cleanupStage(stage, budget.ownedFiles)
        }
    }

    private fun addSample(
        stage: File,
        samples: MutableList<KitSample>,
        budget: Budget,
        filename: String,
        extension: String,
        input: InputStream
    ) {
        require(samples.size < MAX_AUDIO_FILES) { "Too many audio files" }
        val id = UUID.randomUUID().toString()
        val file = checkedChild(stage, "$id.$extension")
        if (!file.createNewFile()) throw IOException("Cannot create sample copy")
        budget.ownedFiles.add(file.name)
        // Source names and ZIP attributes never dictate paths or file types on disk. Even a ZIP
        // symlink entry is copied as bytes into a fresh regular UUID file, never materialized as a link.
        FileOutputStream(file).use { output ->
            require(copyBounded(input, output, budget) > 0L) { "Audio file is empty" }
            output.fd.sync()
        }
        samples.add(KitSample(id, printableName(filename.substringBeforeLast('.'), "Sample"), file, inferPitch(filename)))
    }

    private fun copyBounded(input: InputStream, output: FileOutputStream?, budget: Budget): Long {
        val buffer = ByteArray(BUFFER_SIZE)
        var fileBytes = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) throw IOException("Source stream made no progress")
            fileBytes += count
            budget.bytes += count
            require(fileBytes <= MAX_FILE_BYTES) { "Expanded file exceeds 16 MiB" }
            require(budget.bytes <= MAX_TOTAL_BYTES) { "Imported content exceeds 128 MiB" }
            output?.write(buffer, 0, count)
        }
        return fileBytes
    }

    private fun readKit(directory: File): Kit {
        require(UUID_PATTERN.matches(directory.name) && directory.isDirectory) { "Invalid kit directory" }
        val text = manifest(directory).openRead().use { input ->
            LimitedInputStream(input, MAX_MANIFEST_BYTES).use { it.readBytes().toString(Charsets.UTF_8) }
        }
        val tokener = JSONTokener(text)
        val json = tokener.nextValue()
        require(json is JSONObject && tokener.nextClean() == '\u0000') { "Invalid kit metadata" }
        require(integer(json, "version") == 1 && string(json, "id") == directory.name) { "Invalid kit identity" }
        val name = checkedName(string(json, "name"))
        val array = json.get("samples")
        require(array is JSONArray && array.length() in 1..MAX_AUDIO_FILES) { "Invalid kit samples" }
        val ids = mutableSetOf<String>()
        var totalBytes = 0L
        val samples = mutableListOf<KitSample>()
        for (index in 0 until array.length()) {
            val sample = array.get(index)
            require(sample is JSONObject) { "Invalid kit sample" }
            val id = string(sample, "id")
            require(UUID_PATTERN.matches(id) && ids.add(id)) { "Invalid or duplicate sample ID" }
            val filename = string(sample, "file")
            val extension = audioExtension(filename)
            require(extension != null && filename == "$id.$extension") { "Invalid stored sample filename" }
            val file = checkedChild(directory, filename)
            require(file.isFile && file.length() in 1..MAX_FILE_BYTES) { "Missing or oversized sample file" }
            totalBytes += file.length()
            require(totalBytes <= MAX_TOTAL_BYTES) { "Kit is too large" }
            val pitch = if (!sample.has("pitch") || sample.isNull("pitch")) null else integer(sample, "pitch")
            require(pitch == null || pitch in 0..127) { "Invalid sample pitch" }
            samples.add(KitSample(id, checkedName(string(sample, "name")), file, pitch))
        }
        return Kit(directory.name, name, samples)
    }

    private fun encodeKit(kit: Kit): JSONObject {
        val samples = JSONArray()
        for (sample in kit.samples) {
            samples.put(JSONObject()
                .put("id", sample.id)
                .put("name", checkedName(sample.name))
                .put("file", sample.file.name)
                .put("pitch", sample.pitch ?: JSONObject.NULL))
        }
        return JSONObject().put("version", 1).put("id", kit.id)
            .put("name", checkedName(kit.name)).put("samples", samples)
    }

    private fun manifest(directory: File): AtomicFile {
        checkedChild(directory, "kit.json.bak")
        checkedChild(directory, "kit.json.new")
        return AtomicFile(checkedChild(directory, "kit.json"))
    }

    private fun cleanupStage(stage: File, ownedFiles: Set<String>) {
        // No recursive delete and no search for stale imports: only this invocation's new files.
        try {
            if (stage.canonicalFile != stage || !stage.isDirectory) return
            for (name in ownedFiles + setOf("kit.json", "kit.json.bak", "kit.json.new")) {
                try {
                    checkedChild(stage, name).delete()
                } catch (_: Exception) {
                    // Never follow a substituted symlink during cleanup.
                }
            }
            stage.delete() // Fails harmlessly if unexpected files appeared.
        } catch (_: Exception) {
            // Cleanup must not hide the original import failure.
        }
    }

    private fun checkedChild(directory: File, component: String): File {
        require(directory.canonicalFile == directory) { "Storage directory must not be a symlink" }
        safeComponent(component)
        val child = File(directory, component)
        require(child.canonicalFile == child) { "Path must remain within drumkit storage" }
        return child
    }

    private fun zipPath(path: String, directory: Boolean): List<String> {
        require(path.isNotEmpty() && path.length <= 2048 && !path.startsWith('/') && '\\' !in path) {
            "Unsafe archive path"
        }
        val parts = (if (directory) path.removeSuffix("/") else path).split('/')
        for (part in parts) safeComponent(part)
        val folderDepth = if (directory) parts.size else parts.size - 1
        require(folderDepth <= MAX_DEPTH) { "Archive nesting is too deep" }
        return parts
    }

    private fun safeComponent(value: String) {
        require(value.isNotEmpty() && value != "." && value != ".." &&
            '/' !in value && '\\' !in value && ':' !in value && value.none { Character.isISOControl(it) }) {
            "Unsafe filename or path component"
        }
    }

    private fun audioExtension(filename: String): String? {
        val separator = filename.lastIndexOf('.')
        if (separator <= 0) return null
        return filename.substring(separator + 1).lowercase(Locale.ROOT).takeIf { it in AUDIO_EXTENSIONS }
    }

    private fun inferPitch(filename: String): Int? {
        val name = filename.substringBeforeLast('.').lowercase(Locale.ROOT)
        val candidates = PITCH_PATTERNS.filter { (_, pattern) -> pattern.containsMatchIn(name) }.keys
        return candidates.singleOrNull() // Never resolve ambiguous names by ordering or overwrite a pad.
    }

    private fun printableName(value: String, fallback: String): String {
        var name = value.filterNot { Character.isISOControl(it) }.trim().take(80).trim()
        if (name.isNotEmpty() && Character.isHighSurrogate(name.last())) name = name.dropLast(1).trim()
        return name.ifEmpty { fallback }
    }

    private fun checkedName(value: String): String {
        require(value == value.trim() && value.length in 1..80 && value.none { Character.isISOControl(it) }) {
            "Name must contain 1 to 80 printable characters"
        }
        return value
    }

    private val sourceDisplayFallback = "Drum kit"

    private fun sourceDisplayName(uri: Uri): String {
        return resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) cursor.getString(index) else null
        } ?: sourceDisplayFallback
    }

    private data class Document(val id: String, val name: String, val mime: String, val size: Long?, val flags: Int)

    private fun document(cursor: Cursor): Document {
        fun text(column: String): String {
            val index = cursor.getColumnIndexOrThrow(column)
            require(!cursor.isNull(index)) { "Missing document metadata" }
            return cursor.getString(index)
        }
        val id = text(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
        require(id.isNotEmpty() && id.length <= 4096 && '\u0000' !in id) { "Invalid document ID" }
        val sizeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
        val flagsIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS)
        val size = if (sizeIndex < 0 || cursor.isNull(sizeIndex)) null else cursor.getLong(sizeIndex).takeIf { it >= 0 }
        val flags = if (flagsIndex < 0 || cursor.isNull(flagsIndex)) 0 else cursor.getInt(flagsIndex)
        return Document(id, text(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            text(DocumentsContract.Document.COLUMN_MIME_TYPE), size, flags)
    }

    private fun string(json: JSONObject, key: String): String {
        val value = json.get(key)
        require(value is String) { "Invalid string: $key" }
        return value
    }

    private fun integer(json: JSONObject, key: String): Int {
        val value = json.get(key)
        require(value is Number) { "Invalid integer: $key" }
        val number = value.toDouble()
        require(number.isFinite() && number >= Int.MIN_VALUE.toDouble() && number <= Int.MAX_VALUE.toDouble() &&
            number % 1.0 == 0.0) { "Invalid integer: $key" }
        return number.toInt()
    }

    private class Budget {
        var entries = 0
        var bytes = 0L
        val ownedFiles = mutableSetOf<String>()
        fun scan() {
            require(++entries <= MAX_ENTRIES) { "Too many scanned entries" }
        }
    }

    private class LimitedInputStream(private val source: InputStream, private val limit: Long) : InputStream() {
        private var bytes = 0L
        override fun read(): Int {
            val value = source.read()
            if (value >= 0 && ++bytes > limit) throw IOException("Input exceeds its byte limit")
            return value
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            val allowed = minOf(length.toLong(), limit - bytes + 1).toInt()
            val count = source.read(buffer, offset, allowed)
            if (count > 0) {
                bytes += count
                if (bytes > limit) throw IOException("Input exceeds its byte limit")
            }
            return count
        }
        override fun close() = source.close()
    }

    private companion object {
        const val MAX_AUDIO_FILES = 256
        const val MAX_ENTRIES = 2000
        const val MAX_DEPTH = 6
        const val MAX_FILE_BYTES = 16L * 1024 * 1024
        const val MAX_TOTAL_BYTES = 128L * 1024 * 1024
        const val MAX_MANIFEST_BYTES = 256L * 1024
        const val BUFFER_SIZE = 16 * 1024
        val UUID_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        val AUDIO_EXTENSIONS = setOf("wav", "mp3", "ogg", "flac")
        val DOCUMENT_COLUMNS = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_FLAGS)
        val PITCH_PATTERNS = mapOf(
            36 to Regex("(?:^|[^a-z])kick(?:[^a-z]|$)"),
            38 to Regex("(?:^|[^a-z])snare(?:[^a-z]|$)"),
            39 to Regex("(?:^|[^a-z])clap(?:[^a-z]|$)"),
            42 to Regex("(?:^|[^a-z])(?:closed[ _-]*(?:hi[ _-]*)?hat|(?:hi[ _-]*)?hat[ _-]*closed)(?:[^a-z]|$)"),
            46 to Regex("(?:^|[^a-z])(?:open[ _-]*(?:hi[ _-]*)?hat|(?:hi[ _-]*)?hat[ _-]*open)(?:[^a-z]|$)"),
            45 to Regex("(?:^|[^a-z])tom(?:[^a-z]|$)"),
            49 to Regex("(?:^|[^a-z])crash(?:[^a-z]|$)"),
            51 to Regex("(?:^|[^a-z])ride(?:[^a-z]|$)")
        )
    }
}
