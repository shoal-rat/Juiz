package app.juiz.core.providers

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import okio.BufferedSource
import java.io.IOException
import java.util.concurrent.TimeUnit

object Http {
    val JSON = "application/json; charset=utf-8".toMediaType()

    /** 共享连接池：同一供应商的连续请求复用连接，省去握手时间。 */
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}

class HttpStatusException(val code: Int, val body: String) : IOException("HTTP $code: ${body.take(300)}")

/**
 * 在当前协程里执行一个可取消的阻塞请求：协程被取消（例如对方打断）时立即 cancel 网络调用。
 */
suspend fun <T> Call.executeCancellable(block: suspend (Response) -> T): T {
    val handle = currentCoroutineContext().job.invokeOnCompletion { cancel() }
    try {
        execute().use { resp ->
            if (!resp.isSuccessful) throw HttpStatusException(resp.code, resp.body?.string().orEmpty())
            return block(resp)
        }
    } catch (e: IOException) {
        currentCoroutineContext().ensureActive()
        throw e
    } finally {
        handle.dispose()
    }
}

/** 逐条读取 Server-Sent Events。返回 false 可提前结束。 */
suspend fun readSse(source: BufferedSource, onEvent: suspend (event: String?, data: String) -> Boolean) {
    var event: String? = null
    val data = StringBuilder()
    while (true) {
        currentCoroutineContext().ensureActive()
        val line = try {
            source.readUtf8Line()
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            throw e
        } ?: break
        when {
            line.isEmpty() -> {
                if (data.isNotEmpty()) {
                    val keep = onEvent(event, data.toString())
                    data.setLength(0)
                    event = null
                    if (!keep) return
                }
            }
            line.startsWith(":") -> Unit
            line.startsWith("event:") -> event = line.removePrefix("event:").trim()
            line.startsWith("data:") -> {
                if (data.isNotEmpty()) data.append('\n')
                data.append(line.removePrefix("data:").trimStart())
            }
        }
    }
    if (data.isNotEmpty()) onEvent(event, data.toString())
}

fun rethrowIfCancelled(e: Throwable) {
    if (e is CancellationException) throw e
}
