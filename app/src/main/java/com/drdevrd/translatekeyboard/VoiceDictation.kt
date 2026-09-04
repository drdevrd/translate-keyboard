package com.drdevrd.translatekeyboard

import android.media.MediaRecorder
import java.io.File
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Records short voice notes to a temp file and sends them to OpenAI's
 * audio transcription endpoint (Whisper) for speech-to-text.
 */
class VoiceDictation(private val outputDir: File) {

    private var recorder: MediaRecorder? = null
    private var currentFile: File? = null

    val isRecording: Boolean
        get() = recorder != null

    fun startRecording(): Boolean {
        return try {
            val file = File(outputDir, "dictation_${UUID.randomUUID()}.m4a")
            val r = MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setOutputFile(file.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            currentFile = file
            true
        } catch (e: Exception) {
            recorder = null
            currentFile = null
            false
        }
    }

    /** Stops recording and returns the recorded file, or null if nothing was recording. */
    fun stopRecording(): File? {
        val r = recorder ?: return null
        return try {
            r.stop()
            r.release()
            recorder = null
            currentFile
        } catch (e: Exception) {
            recorder = null
            null
        }
    }
}

class WhisperTranscriber(private val apiKey: String) {

    private val executor = Executors.newCachedThreadPool()

    fun transcribeAsync(
        audioFile: File,
        onResult: (String?) -> Unit,
        onError: (String) -> Unit
    ) {
        executor.execute {
            try {
                val text = transcribeSync(audioFile)
                onResult(text)
            } catch (e: Exception) {
                onError(e.message ?: "Transcription failed")
            } finally {
                audioFile.delete()
            }
        }
    }

    private fun transcribeSync(audioFile: File): String {
        val boundary = "----TranslateKeyboardBoundary${UUID.randomUUID()}"
        val url = URL("https://api.openai.com/v1/audio/transcriptions")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Authorization", "Bearer $apiKey")
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        conn.doOutput = true
        conn.connectTimeout = 20000
        conn.readTimeout = 30000

        conn.outputStream.use { out ->
            writeFormField(out, boundary, "model", "whisper-1")
            writeFileField(out, boundary, "file", audioFile)
            out.write("--$boundary--\r\n".toByteArray())
        }

        val responseCode = conn.responseCode
        val stream = if (responseCode in 200..299) conn.inputStream else conn.errorStream
        val response = stream.bufferedReader().use { it.readText() }

        if (responseCode !in 200..299) {
            throw RuntimeException("OpenAI error ($responseCode): $response")
        }

        val root = org.json.JSONObject(response)
        return root.optString("text", "")
    }

    private fun writeFormField(out: OutputStream, boundary: String, name: String, value: String) {
        out.write("--$boundary\r\n".toByteArray())
        out.write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray())
        out.write("$value\r\n".toByteArray())
    }

    private fun writeFileField(out: OutputStream, boundary: String, name: String, file: File) {
        out.write("--$boundary\r\n".toByteArray())
        out.write(
            "Content-Disposition: form-data; name=\"$name\"; filename=\"${file.name}\"\r\n".toByteArray()
        )
        out.write("Content-Type: audio/m4a\r\n\r\n".toByteArray())
        file.inputStream().use { it.copyTo(out) }
        out.write("\r\n".toByteArray())
    }
}
