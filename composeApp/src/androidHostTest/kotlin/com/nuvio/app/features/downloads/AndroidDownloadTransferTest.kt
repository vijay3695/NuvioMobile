package com.nuvio.app.features.downloads

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidDownloadTransferTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun knownMediaSizeIsCheckedBeforeTheNetworkRequest(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("media bytes"))
            val item = downloadItem(server.url("/video").toString()).copy(totalBytes = 10_000L)
            val directory = temporary.newFolder()

            val error = assertFailsWith<DownloadStorageException> {
                transferAndroidDownload(item, directory, null,
                    onHeaders = { _, _ -> }, onProgress = { _, _ -> },
                    availableStorageBytes = { 512L })
            }

            assertEquals(10_000L, error.requiredBytes)
            assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
        }
    }

    @Test
    fun resumesPartialFileWithRangeAndValidator(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 5-10/11")
                .setHeader("ETag", "\"version-1\"").setBody(" world"))
            val directory = temporary.newFolder()
            File(directory, "video.mkv.part").writeText("hello")

            val output = transferAndroidDownload(downloadItem(server.url("/video").toString()), directory,
                "\"version-1\"", onHeaders = { _, _ -> }, onProgress = { _, _ -> })

            assertEquals("hello world", output.readText())
            val request = server.takeRequest()
            assertEquals("bytes=5-", request.getHeader("Range"))
            assertEquals("\"version-1\"", request.getHeader("If-Range"))
            assertEquals("identity", request.getHeader("Accept-Encoding"))
            assertEquals("Bearer test", request.getHeader("Authorization"))
        }
    }

    @Test
    fun ignoredRangeRestartsInsteadOfAppending(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("new file"))
            val directory = temporary.newFolder()
            File(directory, "video.mkv.part").writeText("old prefix")
            val output = transferAndroidDownload(downloadItem(server.url("/video").toString()), directory,
                null, onHeaders = { _, _ -> }, onProgress = { _, _ -> })
            assertEquals("new file", output.readText())
        }
    }

    @Test
    fun partialWithoutValidatorRestartsFromTheBeginning(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("complete replacement"))
            val directory = temporary.newFolder()
            File(directory, "video.mkv.part").writeText("stale bytes")

            val output = transferAndroidDownload(downloadItem(server.url("/video").toString()), directory,
                null, onHeaders = { _, _ -> }, onProgress = { _, _ -> })

            assertEquals("complete replacement", output.readText())
            assertNull(server.takeRequest().getHeader("Range"))
        }
    }

    @Test
    fun unsatisfiedRangeRetriesFromZero(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(416))
            server.enqueue(MockResponse().setBody("replacement"))
            val directory = temporary.newFolder()
            File(directory, "video.mkv.part").writeText("old prefix")
            val output = transferAndroidDownload(downloadItem(server.url("/video").toString()), directory,
                "\"stable\"", onHeaders = { _, _ -> }, onProgress = { _, _ -> })
            assertEquals("replacement", output.readText())
            assertEquals("bytes=10-", server.takeRequest().getHeader("Range"))
            assertNull(server.takeRequest().getHeader("Range"))
        }
    }

    @Test
    fun invalidResumeRechecksSpaceForTheFullRestart(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(416))
            val directory = temporary.newFolder()
            File(directory, "video.mkv.part").writeText("123456789")
            val item = downloadItem(server.url("/video").toString()).copy(totalBytes = 10L)

            assertFailsWith<DownloadStorageException> {
                transferAndroidDownload(item, directory, "\"stable\"",
                    onHeaders = { _, _ -> }, onProgress = { _, _ -> },
                    availableStorageBytes = { 1L })
            }
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun mismatchedRangeDoesNotCorruptThePartialFile(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 2-4/5").setBody("bad"))
            val directory = temporary.newFolder()
            val partial = File(directory, "video.mkv.part").apply { writeText("hello") }
            assertFailsWith<IOException> {
                transferAndroidDownload(downloadItem(server.url("/video").toString()), directory,
                    null, onHeaders = { _, _ -> }, onProgress = { _, _ -> })
            }
            assertEquals("hello", partial.readText())
        }
    }

    @Test
    fun partialContentRangeWithoutKnownTotalMustStillBeComplete(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes 5-10/*")
                .setChunkedBody("short", 5))
            val directory = temporary.newFolder()
            val partial = File(directory, "video.mkv.part").apply { writeText("hello") }

            assertFailsWith<IOException> {
                transferAndroidDownload(downloadItem(server.url("/video").toString()), directory,
                    null, onHeaders = { _, _ -> }, onProgress = { _, _ -> })
            }
            assertEquals("hello", partial.readText())
            assertFalse(File(directory, "video.mkv").exists())
        }
    }

    @Test
    fun unknownTotalForResumedRangeRestartsFromZero() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes 6-11/*").setBody("partial"))
            server.enqueue(MockResponse().setBody("complete media"))
            val directory = temporary.newFolder()
            File(directory, "video.mkv.part").writeText("prefix")

            val output = transferAndroidDownload(downloadItem(server.url("/video").toString()), directory,
                "\"stable\"", onHeaders = { _, _ -> }, onProgress = { _, _ -> })

            assertEquals("complete media", output.readText())
            assertEquals("bytes=6-", server.takeRequest().getHeader("Range"))
            assertNull(server.takeRequest().getHeader("Range"))
        }
    }

    @Test
    fun storageIsRecheckedBeforeWritingEachChunk() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("media data"))
            val directory = temporary.newFolder()
            var checks = 0

            assertFailsWith<DownloadStorageException> {
                transferAndroidDownload(downloadItem(server.url("/video").toString()), directory,
                    null, onHeaders = { _, _ -> }, onProgress = { _, _ -> },
                    availableStorageBytes = {
                        checks++
                        if (checks == 1) 1_000L else 0L
                    })
            }

            assertEquals(2, checks)
            assertEquals(0L, File(directory, "video.mkv.part").length())
            assertFalse(File(directory, "video.mkv").exists())
        }
    }

    @Test
    fun contentRangeRejectsOverflowAndInvalidTotals() {
        assertNull(parseDownloadContentRange("bytes 0-9223372036854775807/*"))
        assertNull(parseDownloadContentRange("bytes 5-10/10"))
        assertEquals(6L, parseDownloadContentRange("bytes 5-10/*")?.responseBytes)
    }

    @Test
    fun truncatedResponseNeverBecomesCompletedFile(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("abcdefghij").setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
            val directory = temporary.newFolder()
            assertFailsWith<IOException> {
                transferAndroidDownload(downloadItem(server.url("/video").toString()), directory,
                    null, onHeaders = { _, _ -> }, onProgress = { _, _ -> })
            }
            assertFalse(File(directory, "video.mkv").exists())
            assertTrue(File(directory, "video.mkv.part").length() < 10)
        }
    }

    @Test
    fun cancellationClosesBlockedSocketPromptlyAndKeepsPartialBytes(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("abcdefghij").throttleBody(1, 10, TimeUnit.SECONDS))
            val directory = temporary.newFolder()
            val task = async(Dispatchers.Default) {
                transferAndroidDownload(downloadItem(server.url("/video").toString()), directory,
                    null, onHeaders = { _, _ -> }, onProgress = { _, _ -> })
            }
            withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) }
            withTimeout(5_000) {
                while (File(directory, "video.mkv.part").length() == 0L) delay(10)
            }
            withTimeout(2_000) { task.cancelAndJoin() }
            assertEquals(1L, File(directory, "video.mkv.part").length())
            assertFalse(File(directory, "video.mkv").exists())
        }
    }

    @Test
    fun retryPolicyDistinguishesTransientAndPermanentFailures() {
        assertTrue(shouldRetryAndroidDownload(IOException("connection lost"), 0))
        assertTrue(shouldRetryAndroidDownload(DownloadHttpException(503), 0))
        assertTrue(shouldRetryAndroidDownload(DownloadHttpException(429), 0))
        assertFalse(shouldRetryAndroidDownload(DownloadHttpException(403), 0))
        assertFalse(shouldRetryAndroidDownload(DownloadStorageException(100L, 10L), 0))
        assertFalse(shouldRetryAndroidDownload(IOException("connection lost"), 4))
    }

    @Test
    fun validRangeThatStopsBeforeTheFileEndIsNotMarkedComplete(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes 5-7/11")
                .setBody("end"))
            val directory = temporary.newFolder()
            val partial = File(directory, "video.mkv.part").apply { writeText("start") }

            assertFailsWith<IOException> {
                transferAndroidDownload(downloadItem(server.url("/video").toString()), directory,
                    "\"stable\"", onHeaders = { _, _ -> }, onProgress = { _, _ -> })
            }

            assertEquals("startend", partial.readText())
            assertFalse(File(directory, "video.mkv").exists())
        }
    }
}

internal fun downloadItem(url: String = "https://example.com/video.mkv", id: String = "test-download") = DownloadItem(
    id = id,
    contentType = "movie",
    parentMetaId = "test-movie",
    parentMetaType = "movie",
    videoId = "test-movie",
    title = "Download test",
    streamTitle = "Test video",
    providerName = "Test",
    sourceUrl = url,
    sourceHeaders = mapOf("Authorization" to "Bearer test"),
    fileName = "video.mkv",
    status = DownloadStatus.Downloading,
    createdAtEpochMs = 1L,
    updatedAtEpochMs = 1L,
)
