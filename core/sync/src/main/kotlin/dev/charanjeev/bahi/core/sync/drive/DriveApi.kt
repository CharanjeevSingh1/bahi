package dev.charanjeev.bahi.core.sync.drive

import java.net.URLEncoder
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private const val FILES_URL = "https://www.googleapis.com/drive/v3/files"
private const val UPLOAD_URL = "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart"
private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
private val OCTET_STREAM = "application/octet-stream".toMediaType()
private val json = Json { ignoreUnknownKeys = true }

@Serializable
internal data class DriveFile(val id: String, val name: String, val appProperties: Map<String, String> = emptyMap())

@Serializable
private data class DriveFileList(val files: List<DriveFile> = emptyList(), val nextPageToken: String? = null)

@Serializable
private data class DriveFileMetadata(val name: String, val parents: List<String>, val appProperties: Map<String, String>)

/**
 * The four Drive v3 REST calls [DriveTransport] needs, all scoped to
 * `appDataFolder` (docs/sync-design.md §8.6: `drive.appdata` is the only
 * scope this app ever requests) -- kept separate from [DriveTransport] so
 * the SyncTransport-shaped logic (encrypt, decrypt, apply a cursor) doesn't
 * have to know these are HTTP calls at all. [callFactory] is
 * [okhttp3.Call.Factory] rather than a concrete [okhttp3.OkHttpClient] so a
 * test can supply a hand-written fake that never touches the network --
 * `FakeCallFactory` in this module's tests, matching the "fakes, not mocks"
 * convention for exactly the interface OkHttp itself already publishes.
 * [accessToken] is asked fresh on every call rather than cached here: token
 * lifetime and refresh are [dev.charanjeev.bahi.core.sync.oauth.
 * DriveAuthorization]'s job (slice 9d), not this class's to second-guess.
 */
internal class DriveApi(
    private val callFactory: Call.Factory,
    private val accessToken: suspend () -> String,
) {

    /**
     * Every file tagged `appProperties[key] == value`, across as many pages
     * as Drive returns (§8.3: listing carries no documented consistency SLA,
     * but pagination is a separate, unrelated fact this always has to
     * honour regardless).
     */
    suspend fun list(key: String, value: String): List<DriveFile> =
        listInternal("appProperties has { key='$key' and value='$value' } and trashed=false")

    /**
     * Every file in `appDataFolder`, tagged or not. No production caller --
     * this exists for `DriveTransportContractTest` (docs/sync-design.md
     * §10.5), which wipes the throwaway account clean before each test so a
     * previous run's leftover files can't make "pull returns nothing from a
     * transport nothing has been pushed to" false on a real, persistent
     * account.
     */
    suspend fun listAll(): List<DriveFile> = listInternal("trashed=false")

    private suspend fun listInternal(query: String): List<DriveFile> {
        val files = mutableListOf<DriveFile>()
        var pageToken: String? = null
        do {
            val url = buildString {
                append(FILES_URL)
                append("?spaces=appDataFolder")
                append("&q=").append(encode(query))
                append("&fields=").append(encode("nextPageToken,files(id,name,appProperties)"))
                append("&pageSize=1000")
                pageToken?.let { append("&pageToken=").append(encode(it)) }
            }
            val page = json.decodeFromString<DriveFileList>(executeText(Request.Builder().url(url)))
            files += page.files
            pageToken = page.nextPageToken
        } while (pageToken != null)
        return files
    }

    /** The raw bytes of one file's content. */
    suspend fun get(fileId: String): ByteArray = executeBytes(Request.Builder().url("$FILES_URL/$fileId?alt=media"))

    /** Creates a new, immutable file under `appDataFolder`. Returns its id -- files are never updated, only created and (later, by compaction) deleted. */
    suspend fun create(name: String, appProperties: Map<String, String>, content: ByteArray): String {
        val metadata = json.encodeToString(DriveFileMetadata(name, listOf("appDataFolder"), appProperties))
        val body = MultipartBody.Builder()
            .setType("multipart/related".toMediaType())
            .addPart(metadata.toRequestBody(JSON_MEDIA_TYPE))
            .addPart(content.toRequestBody(OCTET_STREAM))
            .build()
        val response = executeText(Request.Builder().url(UPLOAD_URL).post(body))
        return json.decodeFromString<DriveFile>(response).id
    }

    /** Permanently removes a file. No caller in this repo yet -- compaction (slice 9f) is the one this exists for. */
    suspend fun delete(fileId: String) {
        executeBytes(Request.Builder().url("$FILES_URL/$fileId").delete())
    }

    private suspend fun executeText(request: Request.Builder): String = executeBytes(request).decodeToString()

    private suspend fun executeBytes(request: Request.Builder): ByteArray {
        val authorized = request.header("Authorization", "Bearer ${accessToken()}").build()
        callFactory.newCall(authorized).execute().use { response ->
            val bytes = response.body?.bytes() ?: ByteArray(0)
            if (!response.isSuccessful) {
                throw DriveTransportException(
                    "Drive API returned ${response.code} for ${authorized.method} ${authorized.url.encodedPath}: " +
                        bytes.decodeToString().take(200),
                    retryable = response.code == 429 || response.code in 500..599,
                )
            }
            return bytes
        }
    }

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
}
