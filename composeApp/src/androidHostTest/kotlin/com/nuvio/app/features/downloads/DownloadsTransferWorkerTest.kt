package com.nuvio.app.features.downloads

import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.ForegroundInfo
import androidx.work.ListenableWorker
import androidx.work.impl.utils.futures.SettableFuture
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.nuvio.app.core.build.AppFeaturePolicy
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34])
class DownloadsTransferWorkerTest {
    @Test
    fun completesDownloadsWithTheDistributionForegroundPolicy(): Unit = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val scheduler = DownloadsPlatformDownloader.scheduler(context)
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("complete video"))
            val id = UUID.randomUUID().toString()
            val item = downloadItem(server.url("/video").toString(), id).copy(fileName = "$id.mkv")
            val transfer = scheduler.store.begin(item)
            val promotions = mutableListOf<ForegroundInfo>()
            val worker = TestListenableWorkerBuilder<DownloadsTransferWorker>(context)
                .setInputData(workDataOf(
                    AndroidDownloadScheduler.FILE_NAME to item.fileName,
                    AndroidDownloadScheduler.GENERATION to transfer.generation,
                ))
                .setForegroundUpdater { _, _, info ->
                    promotions += info
                    SettableFuture.create<Void>().apply { set(null) }
                }
                .build()

            assertEquals(ListenableWorker.Result.success(), worker.doWork())

            val completed = assertNotNull(scheduler.store.get(item.fileName)).item
            assertEquals(DownloadStatus.Completed, completed.status)
            assertEquals("complete video", File(scheduler.directory, item.fileName).readText())
            assertEquals(14L, completed.downloadedBytes)
            assertEquals("Bearer test", server.takeRequest().getHeader("Authorization"))
            assertEquals(if (AppFeaturePolicy.downloadForegroundServiceEnabled) 1 else 0, promotions.size)
            promotions.singleOrNull()?.let { info ->
                assertEquals(DownloadsLiveStatusPlatform.notificationId(item.id), info.notificationId)
                assertEquals(
                    if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
                    info.foregroundServiceType,
                )
            }
        }
    }

    @Test
    fun persistsTransientRetryAcrossWorkerExecutionsWithoutPausing(): Unit = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val scheduler = DownloadsPlatformDownloader.scheduler(context)
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(MockResponse().setBody("recovered media"))
            val id = UUID.randomUUID().toString()
            val item = downloadItem(server.url("/retry").toString(), id).copy(fileName = "$id.mkv")
            val transfer = scheduler.store.begin(item)
            val input = workDataOf(
                AndroidDownloadScheduler.FILE_NAME to item.fileName,
                AndroidDownloadScheduler.GENERATION to transfer.generation,
            )

            fun worker() = TestListenableWorkerBuilder<DownloadsTransferWorker>(context)
                .setInputData(input)
                .setForegroundUpdater { _, _, _ ->
                    SettableFuture.create<Void>().apply { set(null) }
                }
                .build()

            assertEquals(ListenableWorker.Result.retry(), worker().doWork())
            assertEquals(1, assertNotNull(scheduler.store.get(item.fileName)).retryCount)
            assertEquals(DownloadStatus.Downloading, scheduler.store.get(item.fileName)?.item?.status)

            assertEquals(ListenableWorker.Result.retry(), worker().doWork())
            assertEquals(2, assertNotNull(scheduler.store.get(item.fileName)).retryCount)
            assertEquals(DownloadStatus.Downloading, scheduler.store.get(item.fileName)?.item?.status)

            assertEquals(ListenableWorker.Result.success(), worker().doWork())
            assertEquals(3, server.requestCount)
            val saved = assertNotNull(scheduler.store.get(item.fileName))
            assertEquals(DownloadStatus.Completed, saved.item.status)
            assertEquals(0, saved.retryCount)
            assertEquals("recovered media", File(scheduler.directory, item.fileName).readText())
        }
    }

    @Test
    fun workerResumesDirectHttpPartialWithoutValidatorAndCompletesAutomatically(): Unit = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val scheduler = DownloadsPlatformDownloader.scheduler(context)
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes 5-11/12")
                .setBody("world!!"))
            val id = UUID.randomUUID().toString()
            val item = downloadItem(server.url("/direct-media").toString(), id).copy(
                fileName = "$id.mkv",
                status = DownloadStatus.Downloading,
                downloadedBytes = 5L,
                totalBytes = 12L,
            )
            check(scheduler.directory.mkdirs() || scheduler.directory.isDirectory)
            File(scheduler.directory, "${item.fileName}.part").writeText("hello")
            val transfer = scheduler.store.begin(item)
            val worker = TestListenableWorkerBuilder<DownloadsTransferWorker>(context)
                .setInputData(workDataOf(
                    AndroidDownloadScheduler.FILE_NAME to item.fileName,
                    AndroidDownloadScheduler.GENERATION to transfer.generation,
                ))
                .setForegroundUpdater { _, _, _ ->
                    SettableFuture.create<Void>().apply { set(null) }
                }
                .build()

            assertEquals(ListenableWorker.Result.success(), worker.doWork())

            val saved = assertNotNull(scheduler.store.get(item.fileName))
            assertEquals(DownloadStatus.Completed, saved.item.status)
            assertEquals("helloworld!!", File(scheduler.directory, item.fileName).readText())
            assertEquals("bytes=5-", server.takeRequest().getHeader("Range"))
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun retryPolicyAcceptsTemporaryNotFoundAndServerErrorsWithoutManualResume() {
        assertEquals(true, shouldRetryAndroidDownload(DownloadHttpException(404), 0))
        assertEquals(true, shouldRetryAndroidDownload(DownloadHttpException(503), 1))
        assertEquals(false, shouldRetryAndroidDownload(DownloadHttpException(404), MAX_ANDROID_DOWNLOAD_RETRIES))
    }
}
