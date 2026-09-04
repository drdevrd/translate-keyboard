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

data class ReverseResult(
    val englishTranslation: String,
    val detectedLanguage: String
)

/**
 * Calls OpenAI's chat completions endpoint for two directions:
 *  - translateAsync: English typed on the keyboard -> Hindi/Tamil, with a
 *    transliteration and a word-by-word gloss written in the target language.
 *  - reverseTranslateAsync: a Hindi/Tamil/Hinglish message (e.g. copied from
 *    an incoming chat bubble) -> plain English, so a reply is easy to write.
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
                onResult(translateSync(text, targetLanguageName))
            } catch (e: Exception) {
                onError(e.message ?: "Translation failed")
            }
        }
    }

    fun reverseTranslateAsync(
        text: String,
        onResult: (ReverseResult?) -> Unit,
        onError: (String) -> Unit
    ) {
        executor.execute {
            try {
                onResult(reverseSync(text))
            } catch (e: Exception) {
                onError(e.message ?: "Translation failed")
            }
        }
    }

    private fun translateSync(text: String, targetLanguageName: String): TranslationResult {
        val systemPrompt = """
            You translate short English text typed on a mobile keyboard into $targetLanguageName.
            Respond with ONLY a compact JSON object, no markdown, no extra text, in this exact shape:
            {"translation":"<text in $targetLanguageName script>","transliteration":"<that translation spelled out in plain English/Latin letters, phonetically>","explanation":"<a short word-by-word gloss WRITTEN IN $targetLanguageName script, in the form 'word1 = meaning1, word2 = meaning2', breaking down which word means what>"}
        """.trimIndent()

        val content = callOpenAi(systemPrompt, text)
        val parsed = JSONObject(content)
        return TranslationResult(
            translation = parsed.optString("translation", ""),
            transliteration = parsed.optString("transliteration", ""),
            explanation = parsed.optString("explanation", "")
        )
    }

    private fun reverseSync(text: String): ReverseResult {
        val systemPrompt = """
            The user will paste a short message they received, written in Hindi, Tamil, or Hinglish/Tanglish (Latin-letter phonetic spelling).
            Translate it into natural, simple English so the user can understand it and reply easily.
            Respond with ONLY a compact JSON object, no markdown, no extra text, in this exact shape:
            {"english":"<the message in plain English>","detected_language":"<Hindi, Tamil, Hinglish, Tanglish, or English>"}
        """.trimIndent()

        val content = callOpenAi(systemPrompt, text)
        val parsed = JSONObject(content)
        return ReverseResult(
            englishTranslation = parsed.optString("english", ""),
            detectedLanguage = parsed.optString("detected_language", "")
        )
    }

    private fun callOpenAi(systemPrompt: String, userText: String): String {
        val url = URL("https://api.openai.com/v1/chat/completions")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", "Bearer $apiKey")
        conn.doOutput = true
        conn.connectTimeout = 15000
        conn.readTimeout = 15000

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
                    put("content", userText)
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
        return root
            .getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")
    }
}
