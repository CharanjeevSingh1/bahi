package dev.charanjeev.bahi.core.sync.drive

import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MultipartReader
import okhttp3.Request
import okhttp3.Response
import okio.Buffer

@Serializable
private data class FakeFileMetadata(val name: String, val appProperties: Map<String, String> = emptyMap())

/**
 * A minimal in-process stand-in for Drive's `appDataFolder`, used by
 * [DriveTransportTest] and [DriveCompactorTest] -- [DriveApiTest] already
 * covers request shape in isolation, so this is behaviour, not verification:
 * it actually stores whatever `create` uploads and answers `list`/`get`
 * against that, the same "fakes, not mocks" convention as the rest of this
 * repo's tests, applied to the one seam ([okhttp3.Call.Factory]) that made
 * writing one possible here.
 *
 * [clock] stamps each file's `createdTime` at the moment it's created --
 * [DriveCompactor]'s grace period and staleness checks are both ages, and a
 * test asserting on either needs to move time forward without a real
 * `Thread.sleep`, the same reason [DriveCompactor] itself takes an injectable
 * clock rather than calling [Instant.now] directly.
 */
class InMemoryFakeDrive(private val clock: () -> Instant = Instant::now) {

    private data class StoredFile(val id: String, val name: String, val appProperties: Map<String, String>, val content: ByteArray, val createdTime: Instant)

    private val files = mutableListOf<StoredFile>()
    private var nextId = 1
    private val json = Json { ignoreUnknownKeys = true }

    fun handle(request: Request): Response = when {
        request.method == "GET" && request.url.encodedPath == "/drive/v3/files" -> list(request)
        request.method == "GET" -> get(request)
        request.method == "POST" -> create(request)
        request.method == "DELETE" -> delete(request)
        else -> errorResponse(request, 400, "unsupported by InMemoryFakeDrive")
    }

    private fun list(request: Request): Response {
        val query = request.url.queryParameter("q").orEmpty()
        val match = Regex("key='([^']+)' and value='([^']+)'").find(query)
        val matching = if (match != null) {
            val (key, value) = match.destructured
            files.filter { it.appProperties[key] == value }
        } else {
            files
        }
        val filesJson = matching.joinToString(",") { file ->
            val propsJson = file.appProperties.entries.joinToString(",") { (k, v) -> "\"$k\":\"$v\"" }
            """{"id":"${file.id}","name":"${file.name}","appProperties":{$propsJson},"createdTime":"${file.createdTime}"}"""
        }
        return okResponse(request, """{"files":[$filesJson]}""")
    }

    private fun get(request: Request): Response {
        val id = request.url.pathSegments.last()
        val file = files.firstOrNull { it.id == id } ?: return errorResponse(request, 404, "no such file")
        return okResponse(request, file.content)
    }

    private fun create(request: Request): Response {
        val body = request.body ?: return errorResponse(request, 400, "no body")
        val boundary = body.contentType()?.parameter("boundary") ?: return errorResponse(request, 400, "no boundary")
        val buffer = Buffer().also { body.writeTo(it) }
        val reader = MultipartReader(buffer, boundary)
        val metadata = json.decodeFromString<FakeFileMetadata>(reader.nextPart()!!.body.readUtf8())
        val content = reader.nextPart()!!.body.readByteArray()
        reader.close()

        val id = "fake-file-${nextId++}"
        files += StoredFile(id, metadata.name, metadata.appProperties, content, clock())
        return okResponse(request, """{"id":"$id","name":"${metadata.name}","appProperties":{}}""")
    }

    private fun delete(request: Request): Response {
        val id = request.url.pathSegments.last()
        files.removeAll { it.id == id }
        return okResponse(request, "")
    }
}
