package com.drdevrd.translatekeyboard

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

data class TranslationResult(
    val translation: String,
    val transliteration: String,
    val explanation: String
)

/**
 * Calls OpenAI's chat completions endpoint and asks for a strict JSON reply
 * containing the translation, an English-letter transliteration of that
 * translation, and a short explanation of the meaning/grammar.
 */
class OpenAiTranslator(private val apiKey: String) {

    private val executor = Executors.newCachedThreadPool()

    fun translateAsync(
        text: String,
        targetLanguageName: String, // "Hindi" or "Tamil"
        onResult: (TranslationResult?) -> Unit,
        onError: (String) -> Unit
    ) {
        executor.execute {
            try {
                val result = translateSync(text, targetLanguageName)
                onResult(result)
            } catch (e: Exception) {
                onError(e.message ?: "Translation failed")
            }
        }
    }

    private fun translateSync(text: String, targetLanguageName: String): TranslationResult {
        val url = URL("https://api.openai.com/v1/chat/completions")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", "Bearer $apiKey")
        conn.doOutput = true
        conn.connectTimeout = 15000
        conn.readTimeout = 15000

        val systemPrompt = """
            You translate short English text typed on a mobile keyboard into $targetLanguageName.
            Respond with ONLY a compact JSON object, no markdown, no extra text, in this exact shape:
            {"translation":"<text in $targetLanguageName script>","transliteration":"<that translation spelled out in plain English/Latin letters, phonetically>","explanation":"<one short sentence in English explaining the meaning or a useful grammar note>"}
        """.trimIndent()

        val body = JSONObject().apply {
            put("model", "gpt-4o-mini")
            put("temperature", 0.2)
            put("response_format", JSONObject().put("type", "json_object"))
            put("messages", org.json.JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", systemPrompt)
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", text)
                })
            })
        }

        OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }

        val responseCode = conn.responseCode
        val stream = if (responseCode in 200..299) conn.inputStream else conn.errorStream
        val response = BufferedReader(InputStreamReader(stream)).use { it.readText() }

        if (responseCode !in 200..299) {
            throw RuntimeException("OpenAI error ($responseCode): $response")
        }

        val root = JSONObject(response)
        val content = root
            .getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")

        val parsed = JSONObject(content)
        return TranslationResult(
            translation = parsed.optString("translation", ""),
            transliteration = parsed.optString("transliteration", ""),
            explanation = parsed.optString("explanation", "")
        )
    }
}
