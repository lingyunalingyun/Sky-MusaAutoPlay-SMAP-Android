package com.smap.android.cloud

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

data class CloudSheet(
    val id: Int,
    val title: String,
    val artist: String,
    val transcribedBy: String,
    val bpm: Int,
    val difficulty: Int,
    val downloads: Int,
    val likes: Int,
    val uploader: String,
    val uploadTime: String,
    val downloadUrl: String,
    val coverUrl: String
)

data class CloudListResult(val total: Int, val pages: Int, val items: List<CloudSheet>)
data class CloudUser(val id: Int, val username: String, val mid: String, val avatar: String?)

class CloudApi(context: Context) {
    private val prefs = context.getSharedPreferences("cloud_auth", Context.MODE_PRIVATE)
    private val coverCache = ConcurrentHashMap<String, ByteArray>()
    val user: CloudUser?
        get() {
            val mid = prefs.getString("mid", null) ?: return null
            return CloudUser(prefs.getInt("id", 0), prefs.getString("username", "").orEmpty(), mid, prefs.getString("avatar", null))
        }

    fun login(username: String, password: String): Result<CloudUser> = runCatching {
        val body = JSONObject().put("username", username).put("password", password).toString().toByteArray()
        val response = request("$BASE/api/game_login.php", "POST", body, "application/json")
        val root = JSONObject(response.toString(Charsets.UTF_8))
        if (!root.optBoolean("success")) error(root.optString("error", "登录失败"))
        val json = root.getJSONObject("user")
        val loggedIn = CloudUser(json.getInt("id"), json.getString("username"), json.getString("mid"), json.optString("avatar").takeIf { it.isNotBlank() })
        prefs.edit().putInt("id", loggedIn.id).putString("username", loggedIn.username)
            .putString("mid", loggedIn.mid).putString("avatar", loggedIn.avatar).apply()
        loggedIn
    }

    fun logout() { prefs.edit().clear().apply() }

    fun list(query: String, sort: String, difficulty: Int, page: Int, perPage: Int = 20): Result<CloudListResult> = runCatching {
        var url = "$BASE/api/sheets/list.php?per_page=$perPage&page=$page&sort=$sort"
        if (query.isNotBlank()) url += "&q=${encode(query.trim())}"
        if (difficulty in 1..5) url += "&difficulty=$difficulty"
        val root = JSONObject(request(url).toString(Charsets.UTF_8))
        if (root.optString("status") != "ok") error(root.optString("msg", "加载失败"))
        val array = root.optJSONArray("items")
        val items = buildList {
            if (array != null) for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(CloudSheet(
                    item.optInt("id"), item.optString("title"), item.optString("artist"), item.optString("transcribed_by"),
                    item.optInt("bpm"), item.optInt("difficulty"), item.optInt("downloads"), item.optInt("likes"),
                    item.optString("uploader"), item.optString("created_at"), item.optString("download_url"), item.optString("cover_url")
                ))
            }
        }
        CloudListResult(root.optInt("total"), root.optInt("pages").coerceAtLeast(1), items)
    }

    fun download(sheet: CloudSheet): Result<ByteArray> = runCatching {
        request(if (sheet.downloadUrl.startsWith("http")) sheet.downloadUrl else BASE + sheet.downloadUrl)
    }

    fun cover(sheet: CloudSheet): ByteArray? {
        if (sheet.coverUrl.isBlank()) return null
        return coverCache[sheet.coverUrl] ?: runCatching { request(sheet.coverUrl) }.getOrNull()?.also { coverCache[sheet.coverUrl] = it }
    }

    private fun request(url: String, method: String = "GET", body: ByteArray? = null, contentType: String? = null): ByteArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.requestMethod = method
        if (body != null) {
            connection.doOutput = true
            contentType?.let { connection.setRequestProperty("Content-Type", it) }
            connection.outputStream.use { it.write(body) }
        }
        val code = connection.responseCode
        val bytes = (if (code in 200..299) connection.inputStream else connection.errorStream)?.use { it.readBytes() } ?: byteArrayOf()
        connection.disconnect()
        if (code !in 200..299) error("服务器错误 HTTP $code")
        return bytes
    }

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")

    companion object { const val BASE = "http://musetreehouse.com" }
}
