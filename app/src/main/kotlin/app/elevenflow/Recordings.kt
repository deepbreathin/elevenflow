package app.elevenflow

import android.content.Context
import java.io.File
import java.io.RandomAccessFile

/** Every utterance is saved as a WAV in app storage, so a failed one can be transcribed later. */
object Recordings {
    fun dir(ctx: Context) = File(ctx.filesDir, "recordings").apply { mkdirs() }

    fun newFile(ctx: Context, id: Long) = File(dir(ctx), "$id.wav")

    fun writeWav(f: File, pcm: ByteArray, sampleRate: Int) {
        RandomAccessFile(f, "rw").use { out ->
            out.setLength(0)
            fun le32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
            fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
            out.write("RIFF".toByteArray()); out.write(le32(36 + pcm.size)); out.write("WAVE".toByteArray())
            out.write("fmt ".toByteArray()); out.write(le32(16)); out.write(le16(1)); out.write(le16(1))
            out.write(le32(sampleRate)); out.write(le32(sampleRate * 2)); out.write(le16(2)); out.write(le16(16))
            out.write("data".toByteArray()); out.write(le32(pcm.size)); out.write(pcm)
        }
    }

    /** Delete any recording no history entry points at. */
    fun prune(ctx: Context, keep: Set<String>) {
        dir(ctx).listFiles()?.forEach { if (it.absolutePath !in keep) it.delete() }
    }
}
