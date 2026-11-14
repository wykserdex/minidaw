package org.androidaudioplugin.minidahost

const val STEPS_PER_CLIP = 16

data class ClipNote(val step: Int, val pitch: Int, val lengthSteps: Int = 1)

class Clip {
    val notes = mutableListOf<ClipNote>()

    fun noteAt(step: Int, pitch: Int) = notes.firstOrNull {
        it.pitch == pitch && step >= it.step && step < it.step + it.lengthSteps
    }

    fun record(step: Int, pitch: Int): ClipNote {
        notes.removeAll { it.step == step && it.pitch == pitch }
        return ClipNote(step, pitch).also { notes.add(it) }
    }

    fun resize(note: ClipNote, length: Int): ClipNote {
        val next = note.copy(lengthSteps = length.coerceIn(1, STEPS_PER_CLIP - note.step))
        val index = notes.indexOf(note)
        if (index >= 0) notes[index] = next
        return next
    }
}
