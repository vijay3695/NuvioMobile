package com.nuvio.app.features.downloads

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request

internal val downloadHttpClient = OkHttpClient.Builder()
    .connectTimeout(60, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .writeTimeout(60, TimeUnit.SECONDS)
    .followRedirects(true)
    .followSslRedirects(true)
    .build()

internal class DownloadHttpException(val statusCode: Int, detail: String? = null) : IOException(
    buildString {
        append("Download failed: HTTP $statusCode")
        detail?.takeIf { it.isNotBlank() }?.let { append(" — ").append(it.take(500)) }
    },
)

internal class DownloadStorageException(
    val requiredBytes: Long,
    val availableBytes: Long,
) : IOException(
    "Not enough storage space. Need ${requiredBytes} bytes, but only ${availableBytes} bytes are available.",
)

internal suspend fun transferAndroidDownload(
    item: DownloadItem,
    directory: File,
    validator: String?,
    client: OkHttpClient = downloadHttpClient,
    onHeaders: (totalBytes: Long?, validator: String?) -> Unit,
    onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    availableStorageBytes: () -> Long = { directory.usableSpace },
): File = coroutineScope {
    val activeCall = AtomicReference<Call?>()
    val transfer = async(Dispatchers.IO) {
        try {
            require(File(item.fileName).name == item.fileName && item.fileName.isNotBlank())
            check(directory.isDirectory || directory.mkdirs()) { "Cannot create downloads directory" }
            val partial = File(directory, "${item.fileName}.part")
            var offset = partial.takeIf(File::isFile)?.length() ?: 0L
            // Many direct HTTP media hosts omit ETag and Last-Modified. Restarting at
            // byte zero on every transient failure makes large files unable to recover.
            // Attempt a Range resume without If-Range in that case, then require a valid
            // Content-Range and (when known) the same total length before appending. A 200
            // response naturally truncates the partial and starts a fresh full transfer.
            val resumeWithoutValidator = offset > 0L && validator == null
            val estimatedTotalBytes = item.totalBytes?.takeIf { it > 0L }
            val estimatedRemainingBytes = estimatedTotalBytes?.let { (it - offset).coerceAtLeast(0L) }
            if (estimatedRemainingBytes != null) {
                val available = availableStorageBytes()
                if (available < estimatedRemainingBytes) {
                    throw DownloadStorageException(estimatedRemainingBytes, available)
                }
            }
            var restarted = false
            var restartedForUnknownRange = false
            var restartedAfterRangeBadRequest = false

            while (true) {
                ensureActive()
                val request = Request.Builder().url(item.sourceUrl).apply {
                    item.sourceHeaders.forEach { (key, value) ->
                        if (!key.equals("Range", true) && !key.equals("If-Range", true) &&
                            !key.equals("Accept-Encoding", true)) header(key, value)
                    }
                    header("Accept-Encoding", "identity")
                    if (offset > 0L) {
                        header("Range", "bytes=$offset-")
                        validator?.let { header("If-Range", it) }
                    }
                }.build()
                val call = client.newCall(request)
                activeCall.set(call)
                ensureActive()
                call.execute().use { response ->
                    if (response.code == 416 && offset > 0L && !restarted) {
                        offset = 0L
                        restarted = true
                        estimatedTotalBytes?.let { required ->
                            val available = availableStorageBytes()
                            if (available < required) {
                                throw DownloadStorageException(required, available)
                            }
                        }
                        return@use
                    }
                    if (response.code == 400 && offset > 0L && !restartedAfterRangeBadRequest) {
                        // Some media hosts reject Range/If-Range after a pause or URL refresh.
                        // Retry once from byte zero; a second 400 is surfaced with the server body.
                        offset = 0L
                        restartedAfterRangeBadRequest = true
                        return@use
                    }
                    if (!response.isSuccessful) {
                        val detail = runCatching { response.peekBody(2_048L).string().trim() }.getOrNull()
                        throw DownloadHttpException(response.code, detail)
                    }
                    val range = response.header("Content-Range")?.let(::parseDownloadContentRange)
                    val resumed = response.code == 206
                    if (resumed && (range == null || range.start != offset)) {
                        throw IOException("Server returned an invalid download range")
                    }
                    if (resumed && resumeWithoutValidator && estimatedTotalBytes != null &&
                        range?.totalBytes != estimatedTotalBytes) {
                        // Without a validator we only append when the server confirms the
                        // previously observed representation length. Otherwise preserve the
                        // partial and retry from zero rather than mixing media versions.
                        if (!restartedForUnknownRange) {
                            offset = 0L
                            restartedForUnknownRange = true
                            return@use
                        }
                        throw IOException("The resumed source size changed; refusing to append mismatched bytes")
                    }
                    if (resumed && range?.totalBytes == null) {
                        // A range with an unknown total cannot prove that this is the end of the media.
                        // If we were resuming, retry once from byte zero without Range.
                        if (offset > 0L && !restartedForUnknownRange) {
                            offset = 0L
                            restartedForUnknownRange = true
                            return@use
                        }
                        throw IOException("Server did not provide a verifiable total size for the partial response")
                    }
                    val startingBytes = if (resumed) offset else 0L
                    val body = response.body ?: throw IOException("Download response is empty")
                    val contentLength = body.contentLength().takeIf { it >= 0L }
                    if (resumed && contentLength != null && range != null &&
                        contentLength != range.responseBytes) {
                        throw IOException("Download response length does not match its byte range")
                    }
                    val totalBytes = range?.totalBytes ?: contentLength?.let { startingBytes + it } ?: estimatedTotalBytes
                    val responseValidator = response.header("ETag")?.takeUnless { it.startsWith("W/") }
                        ?: response.header("Last-Modified")
                    val resolvedTotalBytes = totalBytes ?: estimatedTotalBytes
                    val requiredRemainingBytes = resolvedTotalBytes?.let { (it - startingBytes).coerceAtLeast(0L) }
                    if (requiredRemainingBytes != null) {
                        val available = availableStorageBytes()
                        if (available < requiredRemainingBytes) {
                            throw DownloadStorageException(requiredRemainingBytes, available)
                        }
                    }
                    onHeaders(totalBytes, responseValidator)
                    var downloaded = startingBytes
                    onProgress(downloaded, totalBytes)
                    body.byteStream().use { input ->
                        FileOutputStream(partial, resumed && offset > 0L).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                ensureActive()
                                val size = input.read(buffer)
                                if (size == -1) break
                                val requiredBytes = totalBytes?.let {
                                    (it - downloaded).coerceAtLeast(size.toLong())
                                } ?: size.toLong()
                                val available = availableStorageBytes()
                                if (available < requiredBytes) {
                                    throw DownloadStorageException(requiredBytes, available)
                                }
                                output.write(buffer, 0, size)
                                downloaded += size
                                onProgress(downloaded, totalBytes)
                            }
                            output.fd.sync()
                        }
                    }
                    ensureActive()
                    if (resumed && range != null && downloaded - startingBytes != range.responseBytes) {
                        throw IOException("Download ended before the requested byte range was complete")
                    }
                    if (totalBytes != null && downloaded != totalBytes) {
                        throw IOException("Download ended before all bytes were received")
                    }
                    return@async partial
                }
            }
            @Suppress("UNREACHABLE_CODE")
            partial
        } catch (error: Exception) {
            // Closing a cancelled HTTP call throws IOException from its blocking read.
            ensureActive()
            throw error
        }
    }
    try {
        transfer.await()
    } finally {
        activeCall.get()?.cancel()
    }
}

internal data class DownloadContentRange(
    val start: Long,
    val end: Long,
    val totalBytes: Long?,
) {
    val responseBytes: Long
        get() = end - start + 1L
}

internal fun parseDownloadContentRange(value: String): DownloadContentRange? {
    val match = Regex("bytes (\\d+)-(\\d+)/(\\d+|\\*)").matchEntire(value.trim()) ?: return null
    val start = match.groupValues[1].toLongOrNull() ?: return null
    val end = match.groupValues[2].toLongOrNull() ?: return null
    val total = match.groupValues[3].toLongOrNull()
    if (end < start || end - start == Long.MAX_VALUE || (total != null && end >= total)) return null
    return DownloadContentRange(start, end, total)
}
