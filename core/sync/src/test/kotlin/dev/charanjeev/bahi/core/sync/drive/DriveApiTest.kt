package dev.charanjeev.bahi.core.sync.drive

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.Request
import org.junit.Test

class DriveApiTest {

    private fun api(handler: (Request) -> okhttp3.Response) = DriveApi(FakeCallFactory(handler)) { "a-token" }

    @Test
    fun `every request carries the access token as a bearer header`() = runTest {
        var seenAuth: String? = null
        val api = api { request ->
            seenAuth = request.header("Authorization")
            okResponse(request, """{"files":[]}""")
        }

        api.list("kind", "ops")

        assertThat(seenAuth).isEqualTo("Bearer a-token")
    }

    @Test
    fun `list scopes to appDataFolder and filters by the given appProperty`() = runTest {
        var seenUrl: String? = null
        val api = api { request ->
            seenUrl = request.url.toString()
            okResponse(request, """{"files":[]}""")
        }

        api.list("deviceId", "device-a")

        assertThat(seenUrl).contains("spaces=appDataFolder")
        assertThat(seenUrl).contains("q=")
        // URL-encoded query still has to name the key and value it was asked to filter on.
        assertThat(java.net.URLDecoder.decode(seenUrl, "UTF-8")).contains("key='deviceId'")
        assertThat(java.net.URLDecoder.decode(seenUrl, "UTF-8")).contains("value='device-a'")
    }

    @Test
    fun `list follows nextPageToken until it is absent`() = runTest {
        var page = 0
        val api = api { request ->
            page++
            when (page) {
                1 -> {
                    assertThat(request.url.toString()).doesNotContain("pageToken=")
                    okResponse(request, """{"files":[{"id":"1","name":"a","appProperties":{}}],"nextPageToken":"tok2"}""")
                }
                else -> {
                    assertThat(request.url.toString()).contains("pageToken=tok2")
                    okResponse(request, """{"files":[{"id":"2","name":"b","appProperties":{}}]}""")
                }
            }
        }

        val files = api.list("kind", "ops")

        assertThat(files.map { it.id }).containsExactly("1", "2").inOrder()
    }

    @Test
    fun `get downloads a files raw content`() = runTest {
        val api = api { request ->
            assertThat(request.url.toString()).isEqualTo("https://www.googleapis.com/drive/v3/files/file-1?alt=media")
            okResponse(request, "raw-bytes")
        }

        assertThat(api.get("file-1").decodeToString()).isEqualTo("raw-bytes")
    }

    @Test
    fun `create uploads a multipart request and returns the new files id`() = runTest {
        var seenBody: String? = null
        val api = api { request ->
            assertThat(request.url.toString()).isEqualTo("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart")
            assertThat(request.method).isEqualTo("POST")
            val buffer = okio.Buffer()
            request.body!!.writeTo(buffer)
            seenBody = buffer.readUtf8()
            okResponse(request, """{"id":"new-file-id","name":"ops-a-1.json","appProperties":{}}""")
        }

        val id = api.create("ops-a-1.json", mapOf("deviceId" to "a"), "content".encodeToByteArray())

        assertThat(id).isEqualTo("new-file-id")
        assertThat(seenBody).contains("\"name\":\"ops-a-1.json\"")
        assertThat(seenBody).contains("\"parents\":[\"appDataFolder\"]")
        assertThat(seenBody).contains("\"deviceId\":\"a\"")
        assertThat(seenBody).contains("content")
    }

    @Test
    fun `delete issues a DELETE against the files id`() = runTest {
        val api = api { request ->
            assertThat(request.method).isEqualTo("DELETE")
            assertThat(request.url.toString()).isEqualTo("https://www.googleapis.com/drive/v3/files/file-1")
            okResponse(request, "")
        }

        api.delete("file-1")
    }

    @Test
    fun `a 429 is classified retryable`() = runTest {
        val api = api { request -> errorResponse(request, 429) }

        val failure = runCatching { api.get("file-1") }.exceptionOrNull() as? DriveTransportException
        assertThat(failure?.retryable).isTrue()
    }

    @Test
    fun `a 500 is classified retryable`() = runTest {
        val api = api { request -> errorResponse(request, 503) }

        val failure = runCatching { api.get("file-1") }.exceptionOrNull() as? DriveTransportException
        assertThat(failure?.retryable).isTrue()
    }

    @Test
    fun `a 403 is classified not retryable`() = runTest {
        val api = api { request -> errorResponse(request, 403, "quota exceeded") }

        val failure = runCatching { api.get("file-1") }.exceptionOrNull() as? DriveTransportException
        assertThat(failure?.retryable).isFalse()
    }

    @Test
    fun `a 404 is classified not retryable`() = runTest {
        val api = api { request -> errorResponse(request, 404) }

        val failure = runCatching { api.get("file-1") }.exceptionOrNull() as? DriveTransportException
        assertThat(failure?.retryable).isFalse()
    }
}
