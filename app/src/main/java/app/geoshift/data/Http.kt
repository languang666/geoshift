package app.geoshift.data

import app.geoshift.PortalStore
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * GET for the keyless open map services. Returns the body of a 2xx reply (or of [allowStatus]).
 * Errors are PortalExceptions with Chinese messages.
 */
internal fun httpText(url: String, allowStatus: Int = -1, maxBytes: Int = 8 * 1024 * 1024): String {
    val connection = try {
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 15000
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", "Geoshift/${PortalStore.VERSION} (Android; app.geoshift; +https://github.com/languang666/geoshift)")
            setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.5")
        }
    } catch (e: Exception) {
        throw PortalException("网络请求失败（${e.javaClass.simpleName}）。")
    }
    try {
        val code = try { connection.responseCode } catch (e: Exception) {
            throw PortalException("连接失败（${e.javaClass.simpleName}），请检查网络后重试。")
        }
        if (code !in 200..299 && code != allowStatus) {
            throw PortalException(if (code == 429) "请求太频繁，请稍后再试。" else "服务暂时不可用（HTTP $code），请稍后重试。")
        }
        val stream = (if (code in 200..299) connection.inputStream else connection.errorStream)
            ?: throw PortalException("服务没有返回内容（HTTP $code）。")
        stream.use {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val n = try { it.read(buffer) } catch (e: Exception) {
                    throw PortalException("读取响应失败（${e.javaClass.simpleName}），请重试。")
                }
                if (n < 0) break
                total += n
                if (total > maxBytes) throw PortalException("响应过大，请缩小范围后重试。")
                out.write(buffer, 0, n)
            }
            return out.toString("UTF-8")
        }
    } finally {
        connection.disconnect()
    }
}
