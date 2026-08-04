package com.pockettavern.app.data.girlfriend

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType

object GeminiVisionClient {

    data class Config(
        val apiKey: String = "",
        val model: String = "gemini-2.5-flash"
    )

    private val client by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    suspend fun describe(imageFile: File, config: Config): String = withContext(Dispatchers.IO) {
        if (config.apiKey.isBlank() || !imageFile.exists()) return@withContext ""
        val bytes = imageFile.readBytes()
        val mime = when (imageFile.extension.lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            else -> "image/png"
        }
        describeBase64(Base64.encodeToString(bytes, Base64.NO_WRAP), mime, config)
    }

    suspend fun describeDataUrl(dataUrl: String, config: Config): String = withContext(Dispatchers.IO) {
        if (config.apiKey.isBlank() || !dataUrl.startsWith("data:")) return@withContext ""
        val mime = dataUrl.substringAfter("data:").substringBefore(";")
        val b64 = dataUrl.substringAfter("base64,")
        describeBase64(b64, mime, config)
    }

    private suspend fun describeBase64(b64: String, mime: String, config: Config): String {
        return try {
            val prompt = "请用中文简短描述这张图片的内容，不超过80字。"
            val body = "{\"contents\":[{\"parts\":[" +
                "{\"text\":\"${prompt.replace("\"", "\\\"")}\"}," +
                "{\"inline_data\":{\"mime_type\":\"$mime\",\"data\":\"$b64\"}}]}]}"
            val url = "https://generativelanguage.googleapis.com/v1beta/models/${config.model}:generateContent" +
                "?key=${config.apiKey}"
            val req = okhttp3.Request.Builder()
                .url(url)
                .post(body.toRequestBody("application/json"))
                .addHeader("Content-Type", "application/json")
                .build()
            val resp = client.newCall(req).execute()
            val respBody = resp.body?.string() ?: ""
            resp.close()
            val j = Json { ignoreUnknownKeys = true; isLenient = true }
            val obj = j.parseToJsonElement(respBody).jsonObject
            val text = obj["candidates"]?.jsonArray?.getOrNull(0)?.jsonObject
                ?.get("content")?.jsonObject
                ?.get("parts")?.jsonArray?.getOrNull(0)?.jsonObject
                ?.get("text")?.jsonPrimitive?.contentOrNull?.trim() ?: ""
            text
        } catch (_: Exception) { "" }
    }

    private fun String.toRequestBody(mediaType: String): okhttp3.RequestBody =
        okhttp3.RequestBody.create(mediaType.toMediaType(), this)
}
