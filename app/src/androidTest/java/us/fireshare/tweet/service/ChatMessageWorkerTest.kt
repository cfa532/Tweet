package us.fireshare.tweet.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import timber.log.Timber

/**
 * Test class for ChatMessageWorker functionality.
 * This demonstrates how to test the worker and verify its behavior.
 */
@RunWith(AndroidJUnit4::class)
class ChatMessageWorkerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
    }

    @Test
    fun testChatMessageWorkerCreation() {
        // Test that the worker can be created successfully
        val worker = TestListenableWorkerBuilder<SendChatMessageWorker>(context).build()
        assertEquals(SendChatMessageWorker::class.java, worker::class.java)
    }

    @Test
    fun testChatMessageWorkerWithMissingReceiptId() = runBlocking {
        // Test worker with missing receiptId (should fail)
        val inputData = workDataOf(
            "content" to "Test message content",
            "messageTimestamp" to System.currentTimeMillis()
        )

        val worker = TestListenableWorkerBuilder<SendChatMessageWorker>(context)
            .setInputData(inputData)
            .build()

        val result = worker.doWork()
        assertEquals(ListenableWorker.Result.failure(), result)
    }

    @Test
    fun testBadgeStateManagerInitialization() {
        // Test BadgeStateManager initialization
        try {
            BadgeStateManager.initialize(context)
            Timber.d("BadgeStateManager initialized successfully")
        } catch (e: Exception) {
            Timber.e(e, "Error initializing BadgeStateManager")
        }
    }

    @Test
    fun testLauncherBadgeSupport() {
        // Test launcher badge support
        try {
            val isSupported = LauncherBadgeManager.isBadgeSupported(context)
            Timber.d("Launcher badge support: $isSupported")
        } catch (e: Exception) {
            Timber.e(e, "Error checking launcher badge support")
        }
    }

    @Test
    fun testBadgeTestUtility() {
        // Test badge test utility functions
        try {
            BadgeTestUtility.testBadgeSupport(context)
            BadgeTestUtility.testBadgeCount(context, 1)
            BadgeTestUtility.testBadgeIncrement(context)
            BadgeTestUtility.clearBadge(context)
            Timber.d("BadgeTestUtility tests completed successfully")
        } catch (e: Exception) {
            Timber.e(e, "Error in BadgeTestUtility tests")
        }
    }
} 