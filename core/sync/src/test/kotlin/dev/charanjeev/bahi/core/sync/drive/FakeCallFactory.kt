package dev.charanjeev.bahi.core.sync.drive

import okhttp3.Call
import okhttp3.Callback
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Timeout

/**
 * Stands in for [okhttp3.OkHttpClient] -- the network boundary [DriveApi]
 * actually depends on is [Call.Factory], not the concrete client, exactly so
 * a test can hand it this instead (that class's own doc explains why). Every
 * request [DriveApi] issues is recorded in [requests] so a test can assert on
 * shape (URL, method, headers, body) without a real server; [handler] is what
 * scripts the response, the same "test supplies the scenario" shape
 * `FakeDriveAuthorization` already uses for a different seam.
 */
class FakeCallFactory(private val handler: (Request) -> Response) : Call.Factory {

    val requests = mutableListOf<Request>()

    override fun newCall(request: Request): Call {
        requests += request
        return FakeCall(request, handler(request))
    }

    private class FakeCall(private val request: Request, private val response: Response) : Call {
        override fun request() = request
        override fun execute() = response
        override fun enqueue(responseCallback: Callback) = throw UnsupportedOperationException("DriveApi only ever calls execute()")
        override fun cancel() = throw UnsupportedOperationException("not exercised by DriveApi")
        override fun isExecuted() = false
        override fun isCanceled() = false
        override fun timeout(): Timeout = Timeout.NONE
        override fun clone(): Call = throw UnsupportedOperationException("not exercised by DriveApi")
        override fun addEventListener(eventListener: okhttp3.EventListener) = throw UnsupportedOperationException("not exercised by DriveApi")
        override fun <T : Any> tag(type: kotlin.reflect.KClass<T>): T? = null
        override fun <T> tag(type: Class<out T>): T? = null
        override fun <T : Any> tag(type: kotlin.reflect.KClass<T>, computeIfAbsent: () -> T): T = computeIfAbsent()
        override fun <T : Any> tag(type: Class<T>, computeIfAbsent: () -> T): T = computeIfAbsent()
    }
}

fun okResponse(request: Request, body: String) = Response.Builder()
    .request(request)
    .protocol(Protocol.HTTP_1_1)
    .code(200)
    .message("OK")
    .body(body.toResponseBody())
    .build()

fun okResponse(request: Request, body: ByteArray) = Response.Builder()
    .request(request)
    .protocol(Protocol.HTTP_1_1)
    .code(200)
    .message("OK")
    .body(body.toResponseBody())
    .build()

fun errorResponse(request: Request, code: Int, body: String = "") = Response.Builder()
    .request(request)
    .protocol(Protocol.HTTP_1_1)
    .code(code)
    .message("error")
    .body(body.toResponseBody())
    .build()
