package com.feelyeon.nasviewer

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class SynoException(message: String) : Exception(message)

/**
 * Talks to a Synology DSM's File Station WebAPI directly over plain HTTP(S) calls.
 * Unlike a browser/WebView fetch, OkHttp requests here are not subject to CORS, so
 * no reverse-proxy/same-origin setup on the NAS is required (unlike the earlier PWA).
 */
object SynologyApi {
    // Same generous timeouts as the Glide/OkHttp image loader (NasViewerApp) — this
    // NAS is reached over a bandwidth-limited connection, so even non-image
    // downloads (epub/text files) can take a while.
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    @Volatile
    var sid: String? = null
        private set

    // Serializes login/re-login so that several concurrent session failures (e.g. a
    // batch of thumbnail loads all hitting an expired session at once) don't each fire
    // their own login() call — a fresh DSM login invalidates other sessions (error 107),
    // so overlapping forced re-logins can cascade into repeated failures.
    private val loginMutex = Mutex()

    private fun errorMessage(code: Int?): String = when (code) {
        400 -> "계정 또는 비밀번호가 올바르지 않습니다."
        401 -> "계정이 존재하지 않습니다."
        402 -> "비밀번호가 올바르지 않습니다."
        403 -> "2단계 인증 코드가 필요합니다. 이 앱은 2단계 인증을 지원하지 않습니다 — 전용 계정을 만들어 사용해주세요."
        404 -> "2단계 인증 코드가 올바르지 않습니다."
        406 -> "2단계 인증 등록이 필요합니다."
        407 -> "이 IP/위치에서의 접근이 허용되지 않았습니다."
        408 -> "비밀번호가 만료되었습니다. DSM 웹사이트(브라우저)에 이 계정으로 로그인해서 새 비밀번호를 설정한 뒤 다시 시도해주세요."
        409 -> "비밀번호가 만료되었습니다. DSM 웹사이트(브라우저)에 이 계정으로 로그인해서 새 비밀번호를 설정한 뒤 다시 시도해주세요."
        410 -> "비밀번호를 변경해야 합니다. DSM 웹사이트(브라우저)에 이 계정으로 로그인해서 새 비밀번호를 설정한 뒤 다시 시도해주세요."
        119 -> "세션이 만료되었습니다."
        105 -> "권한이 없습니다. File Station 사용 권한을 확인해주세요."
        106 -> "세션이 시간초과되었습니다."
        107 -> "다른 곳에서 로그인되어 세션이 종료되었습니다."
        null -> "네트워크 오류가 발생했습니다."
        else -> "NAS 오류 (코드 $code)"
    }

    private fun isSessionError(code: Int?) = code == 105 || code == 106 || code == 107 || code == 119

    private fun buildUrl(base: String, path: String, params: Map<String, String>): String {
        val builder = "$base$path".toHttpUrl().newBuilder()
        params.forEach { (k, v) -> builder.addQueryParameter(k, v) }
        return builder.build().toString()
    }

    private suspend fun getJson(url: String): JSONObject = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw SynoException("네트워크 오류 (HTTP ${resp.code})")
            val bodyStr = resp.body?.string() ?: throw SynoException("빈 응답")
            JSONObject(bodyStr)
        }
    }

    suspend fun loginFromPrefs(context: Context): String =
        login(Prefs.baseUrl(context), Prefs.account(context), Prefs.password(context))

    suspend fun login(base: String, account: String, passwd: String): String {
        val url = buildUrl(
            base, "/webapi/auth.cgi", mapOf(
                "api" to "SYNO.API.Auth",
                "version" to "6",
                "method" to "login",
                "account" to account,
                "passwd" to passwd,
                "session" to "FileStation",
                "format" to "sid"
            )
        )
        val json = getJson(url)
        if (!json.optBoolean("success")) {
            val code = if (json.has("error")) json.optJSONObject("error")?.optInt("code") else null
            throw SynoException(errorMessage(code))
        }
        val newSid = json.getJSONObject("data").getString("sid")
        sid = newSid
        return newSid
    }

    suspend fun ensureLoggedIn(context: Context, force: Boolean = false): Boolean {
        if (sid != null && !force) return true
        // Remember what we think is the stale sid so that, once we get the lock, we can
        // tell whether another caller already refreshed past it — if so, skip our own
        // redundant (and disruptive) login instead of piling onto theirs.
        val knownStaleSid = if (force) sid else null
        return loginMutex.withLock {
            if (sid != null && (!force || sid !== knownStaleSid)) return@withLock true
            if (!Prefs.hasAccount(context)) return@withLock false
            try {
                loginFromPrefs(context)
                true
            } catch (e: Exception) {
                false
            }
        }
    }

    suspend fun listFolder(context: Context, path: String): List<FileItem> {
        if (sid == null && !ensureLoggedIn(context)) throw SynoException("로그인이 필요합니다.")
        return listFolderInternal(context, path, retried = false)
    }

    // path == "/" is our sentinel for "top-level NAS shares", which File Station
    // exposes via a distinct API method (list_share) rather than list+folder_path — a
    // plain folder_path of "/" is not a valid argument to "list" itself.
    private suspend fun listFolderInternal(context: Context, path: String, retried: Boolean): List<FileItem> {
        val isRoot = path == "/"
        val url = buildUrl(
            Prefs.baseUrl(context), "/webapi/entry.cgi",
            if (isRoot) mapOf(
                "api" to "SYNO.FileStation.List",
                "version" to "2",
                "method" to "list_share",
                "additional" to "[\"size\",\"time\"]",
                "_sid" to (sid ?: "")
            ) else mapOf(
                "api" to "SYNO.FileStation.List",
                "version" to "2",
                "method" to "list",
                "folder_path" to path,
                "additional" to "[\"size\",\"time\"]",
                "_sid" to (sid ?: "")
            )
        )
        val json = getJson(url)
        if (!json.optBoolean("success")) {
            val code = if (json.has("error")) json.optJSONObject("error")?.optInt("code") else null
            if (!retried && isSessionError(code) && ensureLoggedIn(context, force = true)) {
                return listFolderInternal(context, path, retried = true)
            }
            throw SynoException(errorMessage(code))
        }
        val data = json.getJSONObject("data")
        val files = data.getJSONArray(if (isRoot) "shares" else "files")
        val result = ArrayList<FileItem>(files.length())
        for (i in 0 until files.length()) {
            val f = files.getJSONObject(i)
            val additional = f.optJSONObject("additional")
            val size = additional?.optLong("size", -1) ?: -1
            val mtime = additional?.optJSONObject("time")?.optLong("mtime", -1) ?: -1
            result.add(
                FileItem(
                    name = f.getString("name"),
                    path = f.getString("path"),
                    isDir = f.getBoolean("isdir"),
                    size = size,
                    mtime = mtime
                )
            )
        }
        return result
    }

    fun thumbUrl(context: Context, path: String): String = buildUrl(
        Prefs.baseUrl(context), "/webapi/entry.cgi", mapOf(
            "api" to "SYNO.FileStation.Thumb",
            "version" to "2",
            "method" to "get",
            "path" to path,
            "size" to "medium",
            "_sid" to (sid ?: "")
        )
    )

    fun downloadUrl(context: Context, path: String): String = buildUrl(
        Prefs.baseUrl(context), "/webapi/entry.cgi", mapOf(
            "api" to "SYNO.FileStation.Download",
            "version" to "2",
            "method" to "download",
            "path" to path,
            "mode" to "open",
            "_sid" to (sid ?: "")
        )
    )

    fun logout() {
        sid = null
    }

    // Streams the file straight to disk instead of buffering the whole thing as a
    // ByteArray, which would otherwise hold the full response buffer and the ByteArray
    // both in memory at once. Downloads to a ".tmp" sibling and renames atomically so a
    // crash or killed process mid-download never leaves a corrupt file at destFile.
    suspend fun downloadToFile(context: Context, path: String, destFile: File): File {
        if (sid == null && !ensureLoggedIn(context)) throw SynoException("로그인이 필요합니다.")
        return downloadToFileInternal(context, path, destFile, retried = false)
    }

    private suspend fun downloadToFileInternal(context: Context, path: String, destFile: File, retried: Boolean): File =
        withContext(Dispatchers.IO) {
            val url = buildUrl(
                Prefs.baseUrl(context), "/webapi/entry.cgi", mapOf(
                    "api" to "SYNO.FileStation.Download",
                    "version" to "2",
                    "method" to "download",
                    "path" to path,
                    "mode" to "open",
                    "_sid" to (sid ?: "")
                )
            )
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { resp ->
                val contentType = resp.header("Content-Type") ?: ""
                if (!resp.isSuccessful || contentType.startsWith("application/json")) {
                    val bodyStr = resp.body?.string() ?: ""
                    val code = try {
                        JSONObject(bodyStr).optJSONObject("error")?.optInt("code")
                    } catch (e: Exception) {
                        null
                    }
                    if (!retried && isSessionError(code) && ensureLoggedIn(context, force = true)) {
                        return@withContext downloadToFileInternal(context, path, destFile, retried = true)
                    }
                    throw SynoException(if (code != null) errorMessage(code) else "네트워크 오류 (HTTP ${resp.code})")
                }
                val body = resp.body ?: throw SynoException("빈 응답")
                val tmpFile = File(destFile.parentFile, "${destFile.name}.tmp")
                body.byteStream().use { input ->
                    tmpFile.outputStream().use { output -> input.copyTo(output) }
                }
                if (!tmpFile.renameTo(destFile)) {
                    tmpFile.delete()
                    throw SynoException("캐시 파일을 저장하지 못했습니다.")
                }
                destFile
            }
        }
}

private val IMAGE_EXT_RE = Regex("(?i)\\.(jpe?g|png|gif|webp|bmp)$")
fun FileItem.isImage(): Boolean = !isDir && IMAGE_EXT_RE.containsMatchIn(name)
fun FileItem.isEpub(): Boolean = !isDir && name.endsWith(".epub", ignoreCase = true)
fun FileItem.isTextDoc(): Boolean = !isDir && name.endsWith(".txt", ignoreCase = true)
fun FileItem.isPdf(): Boolean = !isDir && name.endsWith(".pdf", ignoreCase = true)
