package us.fireshare.tweet.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import us.fireshare.tweet.HproseInstance

@AndroidEntryPoint
class MessageCheckService : Service() {
    
    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "message_check_service"
        private const val CHECK_INTERVAL = 15 * 60 * 1000L // 15 minutes in milliseconds
        
        fun startService(context: Context) {
            val intent = Intent(context, MessageCheckService::class.java)
            context.startForegroundService(intent)
        }
        
        fun stopService(context: Context) {
            val intent = Intent(context, MessageCheckService::class.java)
            context.stopService(intent)
        }
    }
    
    // BadgeStateManager is now a singleton object, no injection needed
    
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var checkJob: Job? = null
    
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Timber.tag("MessageCheckService").d("Service created")
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.tag("MessageCheckService").d("Service started")
        
        // Start foreground service immediately (required for Android 12+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                createNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                createNotification(),
                0
            )
        }
        
        startMessageChecking()
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        super.onDestroy()
        checkJob?.cancel()
        Timber.tag("MessageCheckService").d("Service destroyed")
    }
    
    private fun startMessageChecking() {
        checkJob?.cancel()
        checkJob = serviceScope.launch {
            while (true) {
                try {
                    Timber.tag("MessageCheckService").d("Checking for new messages...")
                    val newMessages = HproseInstance.checkNewMessages()
                    
                    if (newMessages != null && newMessages.isNotEmpty()) {
                        Timber.tag("MessageCheckService").d("Found ${newMessages.size} new messages")
                        BadgeStateManager.updateBadgeCount(newMessages.size)
                    } else {
                        Timber.tag("MessageCheckService").d("No new messages found")
                    }
                    
                    delay(CHECK_INTERVAL)
                } catch (e: Exception) {
                    Timber.tag("MessageCheckService").e(e, "Error checking messages")
                    delay(CHECK_INTERVAL) // Still wait before retrying
                }
            }
        }
    }
    
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Message Check Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Background service for checking new messages"
            setShowBadge(false)
        }

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }
    
    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Message Check Service")
            .setContentText("Checking for new messages every 15 minutes")
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setOngoing(true)
            .build()
    }
} 