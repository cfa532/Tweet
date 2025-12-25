package us.fireshare.tweet.network

import hprose.client.HproseClient
import timber.log.Timber
import us.fireshare.tweet.datamodel.HproseService
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Connection pool for Hprose clients in a distributed system.
 * 
 * In the distributed architecture, multiple users are served by the same node/server.
 * This pool manages shared HproseClient instances per node (baseUrl) to:
 * 1. Reduce memory footprint by sharing clients across users on same node
 * 2. Leverage HTTP connection pooling (clients reuse underlying connections)
 * 3. Improve performance by avoiding repeated client creation
 * 4. Properly manage client lifecycle and cleanup
 * 
 * Thread-safe singleton implementation using ConcurrentHashMap and ReadWriteLock.
 */
object HproseClientPool {
    private const val TAG = "HproseClientPool"
    
    // Pool configuration
    private const val DEFAULT_CLIENT_TIMEOUT = 30_000 // 30 seconds for regular operations
    private const val UPLOAD_CLIENT_TIMEOUT = 3_000_000 // 50 minutes for upload operations
    private const val MAX_CLIENTS_PER_TYPE = 50 // Prevent unbounded growth
    private const val CLIENT_CLEANUP_INTERVAL_MS = 300_000L // 5 minutes
    private const val CLIENT_MAX_IDLE_TIME_MS = 600_000L // 10 minutes
    
    /**
     * Client info for tracking usage and cleanup
     */
    private data class ClientInfo(
        val client: HproseClient,
        val service: HproseService,
        var lastAccessTime: Long = System.currentTimeMillis(),
        var referenceCount: Int = 0
    )
    
    // Separate pools for regular and upload clients (upload clients have longer timeouts)
    private val regularClients = ConcurrentHashMap<String, ClientInfo>()
    private val uploadClients = ConcurrentHashMap<String, ClientInfo>()
    
    // Locks for safe cleanup operations
    private val regularLock = ReentrantReadWriteLock()
    private val uploadLock = ReentrantReadWriteLock()
    
    // Track last cleanup time
    private var lastCleanupTime = System.currentTimeMillis()
    
    init {
        Timber.tag(TAG).d("HproseClientPool initialized for distributed node management")
    }
    
    /**
     * Get or create a regular HproseService client for the given baseUrl (node)
     * Multiple users on the same node will share the same client instance
     * 
     * @param baseUrl The base URL of the node/server
     * @return HproseService client for the node, or null if creation fails
     */
    fun getRegularClient(baseUrl: String): HproseService? {
        if (baseUrl.isBlank()) {
            Timber.tag(TAG).w("Cannot create client for blank baseUrl")
            return null
        }
        
        val normalizedUrl = normalizeUrl(baseUrl)
        
        // Try to get existing client first (read lock)
        regularLock.read {
            regularClients[normalizedUrl]?.let { clientInfo ->
                clientInfo.lastAccessTime = System.currentTimeMillis()
                clientInfo.referenceCount++
                Timber.tag(TAG).d("Reusing regular client for node: $normalizedUrl (refs: ${clientInfo.referenceCount})")
                return clientInfo.service
            }
        }
        
        // Create new client if not exists (write lock)
        return regularLock.write {
            // Double-check after acquiring write lock
            regularClients[normalizedUrl]?.let { clientInfo ->
                clientInfo.lastAccessTime = System.currentTimeMillis()
                clientInfo.referenceCount++
                Timber.tag(TAG).d("Reusing regular client (double-check) for node: $normalizedUrl")
                return@write clientInfo.service
            }
            
            // Check pool size limit
            if (regularClients.size >= MAX_CLIENTS_PER_TYPE) {
                Timber.tag(TAG).w("Regular client pool at max capacity (${MAX_CLIENTS_PER_TYPE}), cleaning up...")
                cleanupIdleClients(regularClients, regularLock, "regular")
            }
            
            // Create new client
            try {
                val client = HproseClient.create("$normalizedUrl/webapi/")
                client.timeout = DEFAULT_CLIENT_TIMEOUT
                val service = client.useService(HproseService::class.java)
                
                val clientInfo = ClientInfo(
                    client = client,
                    service = service,
                    referenceCount = 1
                )
                
                regularClients[normalizedUrl] = clientInfo
                Timber.tag(TAG).d("Created new regular client for node: $normalizedUrl (total: ${regularClients.size})")
                
                // Periodic cleanup check
                maybeCleanup()
                
                service
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to create regular Hprose client for node: $normalizedUrl")
                null
            }
        }
    }
    
    /**
     * Get or create an upload HproseService client for the given baseUrl (node)
     * Upload clients have extended timeouts for long-running upload operations
     * 
     * @param baseUrl The base URL of the node/server
     * @return HproseService client for uploads, or null if creation fails
     */
    fun getUploadClient(baseUrl: String): HproseService? {
        if (baseUrl.isBlank()) {
            Timber.tag(TAG).w("Cannot create upload client for blank baseUrl")
            return null
        }
        
        val normalizedUrl = normalizeUrl(baseUrl)
        
        // Try to get existing client first (read lock)
        uploadLock.read {
            uploadClients[normalizedUrl]?.let { clientInfo ->
                clientInfo.lastAccessTime = System.currentTimeMillis()
                clientInfo.referenceCount++
                Timber.tag(TAG).d("Reusing upload client for node: $normalizedUrl (refs: ${clientInfo.referenceCount})")
                return clientInfo.service
            }
        }
        
        // Create new client if not exists (write lock)
        return uploadLock.write {
            // Double-check after acquiring write lock
            uploadClients[normalizedUrl]?.let { clientInfo ->
                clientInfo.lastAccessTime = System.currentTimeMillis()
                clientInfo.referenceCount++
                Timber.tag(TAG).d("Reusing upload client (double-check) for node: $normalizedUrl")
                return@write clientInfo.service
            }
            
            // Check pool size limit
            if (uploadClients.size >= MAX_CLIENTS_PER_TYPE) {
                Timber.tag(TAG).w("Upload client pool at max capacity (${MAX_CLIENTS_PER_TYPE}), cleaning up...")
                cleanupIdleClients(uploadClients, uploadLock, "upload")
            }
            
            // Create new client
            try {
                val client = HproseClient.create("$normalizedUrl/webapi/")
                client.timeout = UPLOAD_CLIENT_TIMEOUT
                val service = client.useService(HproseService::class.java)
                
                val clientInfo = ClientInfo(
                    client = client,
                    service = service,
                    referenceCount = 1
                )
                
                uploadClients[normalizedUrl] = clientInfo
                Timber.tag(TAG).d("Created new upload client for node: $normalizedUrl (total: ${uploadClients.size})")
                
                // Periodic cleanup check
                maybeCleanup()
                
                service
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to create upload Hprose client for node: $normalizedUrl")
                null
            }
        }
    }
    
    /**
     * Release a client reference (decrement reference count)
     * Clients with zero references are candidates for cleanup
     * 
     * @param baseUrl The base URL of the node/server
     * @param isUploadClient Whether this is an upload client (default: false)
     */
    fun releaseClient(baseUrl: String, isUploadClient: Boolean = false) {
        val normalizedUrl = normalizeUrl(baseUrl)
        
        if (isUploadClient) {
            uploadLock.write {
                uploadClients[normalizedUrl]?.let { clientInfo ->
                    clientInfo.referenceCount = maxOf(0, clientInfo.referenceCount - 1)
                    Timber.tag(TAG).d("Released upload client for node: $normalizedUrl (refs: ${clientInfo.referenceCount})")
                }
            }
        } else {
            regularLock.write {
                regularClients[normalizedUrl]?.let { clientInfo ->
                    clientInfo.referenceCount = maxOf(0, clientInfo.referenceCount - 1)
                    Timber.tag(TAG).d("Released regular client for node: $normalizedUrl (refs: ${clientInfo.referenceCount})")
                }
            }
        }
    }
    
    /**
     * Clear a specific client from the pool (e.g., when node URL changes)
     * 
     * @param baseUrl The base URL of the node/server to clear
     */
    fun clearClient(baseUrl: String) {
        val normalizedUrl = normalizeUrl(baseUrl)
        
        regularLock.write {
            regularClients.remove(normalizedUrl)?.let {
                Timber.tag(TAG).d("Cleared regular client for node: $normalizedUrl")
            }
        }
        
        uploadLock.write {
            uploadClients.remove(normalizedUrl)?.let {
                Timber.tag(TAG).d("Cleared upload client for node: $normalizedUrl")
            }
        }
    }

    /**
     * Normalize URL for consistent cache key generation
     * Keep the protocol (http:// or https://) as HproseClient needs it
     */
    private fun normalizeUrl(url: String): String {
        return url.trim().removeSuffix("/")
    }
    
    /**
     * Perform periodic cleanup check
     */
    private fun maybeCleanup() {
        val now = System.currentTimeMillis()
        if (now - lastCleanupTime > CLIENT_CLEANUP_INTERVAL_MS) {
            lastCleanupTime = now
            
            // Clean both pools
            cleanupIdleClients(regularClients, regularLock, "regular")
            cleanupIdleClients(uploadClients, uploadLock, "upload")
        }
    }
    
    /**
     * Clean up idle clients that haven't been accessed recently and have no references
     */
    private fun cleanupIdleClients(
        clientMap: ConcurrentHashMap<String, ClientInfo>,
        lock: ReentrantReadWriteLock,
        type: String
    ) {
        lock.write {
            val now = System.currentTimeMillis()
            val toRemove = mutableListOf<String>()
            
            clientMap.forEach { (url, clientInfo) ->
                val idleTime = now - clientInfo.lastAccessTime
                if (clientInfo.referenceCount == 0 && idleTime > CLIENT_MAX_IDLE_TIME_MS) {
                    toRemove.add(url)
                }
            }
            
            toRemove.forEach { url ->
                clientMap.remove(url)
            }
            
            if (toRemove.isNotEmpty()) {
                Timber.tag(TAG).d("Cleaned up ${toRemove.size} idle $type clients (remaining: ${clientMap.size})")
            }
        }
    }

}


