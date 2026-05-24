package com.openmate.core.data.sync

import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Test

class SyncLogStoreTest {

    @Test
    fun append_keepsOnlyLatest200Entries_andFormatsFinalText() {
        val store = SyncLogStore()

        repeat(205) { index ->
            store.append(
                SyncLogEntry(
                    id = index.toLong(),
                    timestamp = 1_700_000_000_000L + index,
                    level = SyncLogLevel.Info,
                    category = SyncLogCategory.Sync,
                    sessionId = if (index % 2 == 0) "ses_current" else null,
                    message = "增量消息处理 seq=${1000 + index} bytes=${128 + index} seq=${1000 + index} trace=inc-1",
                )
            )
        }

        val entries = store.entries.value

        assertThat(entries.size).isEqualTo(200)
        assertThat(entries.first().id).isEqualTo(5L)
        assertThat(entries.last().renderedText).contains("INFO [Sync] 增量消息处理")
        assertThat(entries.last().message).contains("trace=inc-1")
        assertThat(entries.last().message).contains("bytes=")
    }

    @Test
    fun append_fromMultipleThreads_keepsLatest200WithoutDroppingSize() {
        val store = SyncLogStore()
        val executor = Executors.newFixedThreadPool(8)
        val taskCount = 1000
        val ready = CountDownLatch(taskCount)
        val start = CountDownLatch(1)
        val done = CountDownLatch(taskCount)

        repeat(taskCount) { taskIndex ->
            executor.execute {
                ready.countDown()
                start.await(5, TimeUnit.SECONDS)
                store.append(
                    SyncLogEntry(
                        id = taskIndex.toLong(),
                        timestamp = taskIndex.toLong(),
                        level = SyncLogLevel.Info,
                        category = SyncLogCategory.Sync,
                        sessionId = null,
                        message = "t$taskIndex e$taskIndex",
                    )
                )
                done.countDown()
            }
        }

        ready.await(5, TimeUnit.SECONDS)
        start.countDown()
        done.await(5, TimeUnit.SECONDS)
        executor.shutdown()

        assertThat(store.entries.value.size).isEqualTo(200)
        assertThat(store.entries.value.map { it.id }.distinct().size).isEqualTo(200)
    }
}
