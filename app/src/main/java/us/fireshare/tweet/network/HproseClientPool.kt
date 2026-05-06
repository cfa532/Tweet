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
 * 5. Support concurrent requests with multiple clients per URL (matching iOS: 8 clients/URL)
 * 
 * Thread-safe singleton implementation using ConcurrentHashMap and ReadWriteLock.
 */
object HproseClientPool {
    private const val TAG = "HproseClientPool"
    
    // Pool configuration
    private const val DEFAULT_CLIENT_TIMEOUT = 30_000 // 30 seconds for regular operations
    private const val WRITABLE_CLIENT_TIMEOUT = 3_000_000 // 50 minutes for write operations (uploads, long-running mutations)
    private const val CLIENTS_PER_URL = 8 // Number of clients per URL (matches iOS)
    private const val MAX_URLS = 50 // Maximum number of different URLs to track
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
    
    /**
     * Pool of clients for a single URL
     */
    private data class ClientPool(
        val clients: MutableList<ClientInfo> = mutableListOf(),
        var nextClientIndex: Int = 0 // Round-robin index
    )
    
    // Separate pools for read and write clients (write clients have longer timeouts
    // for long-running mutations like file uploads). Each URL has a pool of up to
    // CLIENTS_PER_URL clients.
    private val regularClients = ConcurrentHashMap<String, ClientPool>()
    private val writableClients = ConcurrentHashMap<String, ClientPool>()
    
    // Locks for safe cleanup operations
    private val regularLock = ReentrantReadWriteLock()
    private val writableLock = ReentrantReadWriteLock()
    
    // Track last cleanup time
    private var lastCleanupTime = System.currentTimeMillis()
    
    init {
        Timber.tag(TAG).d("HproseClientPool initialized for distributed node management")
    }
    
    /**
     * Get or create a regular HproseService client for the given baseUrl (node)
     * Uses round-robin to distribute requests across up to CLIENTS_PER_URL clients
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
            regularClients[normalizedUrl]?.let { pool ->
                if (pool.clients.isNotEmpty()) {
                    // Round-robin selection
                    val clientInfo = pool.clients[pool.nextClientIndex % pool.clients.size]
                    pool.nextClientIndex = (pool.nextClientIndex + 1) % pool.clients.size
                    
                    clientInfo.lastAccessTime = System.currentTimeMillis()
                    clientInfo.referenceCount++
                    Timber.tag(TAG).d("Reusing regular client for node: $normalizedUrl (client ${pool.nextClientIndex}/${pool.clients.size}, refs: ${clientInfo.referenceCount})")
                    return clientInfo.service
                }
            }
        }
        
        // Create new client if pool doesn't exist or isn't full (write lock)
        return regularLock.write {
            val pool = regularClients.getOrPut(normalizedUrl) { ClientPool() }
            
            // If pool already has clients, use round-robin
            if (pool.clients.isNotEmpty()) {
                val clientInfo = pool.clients[pool.nextClientIndex % pool.clients.size]
                pool.nextClientIndex = (pool.nextClientIndex + 1) % pool.clients.size
                
                clientInfo.lastAccessTime = System.currentTimeMillis()
                clientInfo.referenceCount++
                Timber.tag(TAG).d("Reusing regular client (double-check) for node: $normalizedUrl")
                return@write clientInfo.service
            }
            
            // Check URL limit
            if (regularClients.size >= MAX_URLS) {
                Timber.tag(TAG).w("Regular client pool at max URLs ($MAX_URLS), cleaning up...")
                cleanupIdleClients(regularClients, regularLock, "regular")
            }
            
            // Create new client (up to CLIENTS_PER_URL per URL)
            if (pool.clients.size < CLIENTS_PER_URL) {
                try {
                    val client = HproseClient.create("$normalizedUrl/webapi/")
                    client.timeout = DEFAULT_CLIENT_TIMEOUT
                    val service = client.useService(HproseService::class.java)
                    
                    val clientInfo = ClientInfo(
                        client = client,
                        service = service,
                        referenceCount = 1
                    )
                    
                    pool.clients.add(clientInfo)
                    Timber.tag(TAG).d("Created new regular client for node: $normalizedUrl (${pool.clients.size}/$CLIENTS_PER_URL clients)")
                    
                    // Periodic cleanup check
                    maybeCleanup()
                    
                    service
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Failed to create regular Hprose client for node: $normalizedUrl")
                    null
                }
            } else {
                // Pool is full, use round-robin
                val clientInfo = pool.clients[pool.nextClientIndex % pool.clients.size]
                pool.nextClientIndex = (pool.nextClientIndex + 1) % pool.clients.size
                
                clientInfo.lastAccessTime = System.currentTimeMillis()
                clientInfo.referenceCount++
                Timber.tag(TAG).d("Pool full, reusing regular client for node: $normalizedUrl")
                clientInfo.service
            }
        }
    }
    
    /**
     * Get or create a writable HproseService client for the given baseUrl (node).
     * Writable clients target the user's writable host (hostIds[0]) and have
     * extended timeouts for long-running mutations like file uploads. Uses
     * round-robin to distribute requests across up to CLIENTS_PER_URL clients.
     *
     * @param baseUrl The base URL of the writable node/server
     * @return HproseService client for writes, or null if creation fails
     */
    fun getWritableClient(baseUrl: String): HproseService? {
        if (baseUrl.isBlank()) {
            Timber.tag(TAG).w("Cannot create writable client for blank baseUrl")
            return null
        }

        val normalizedUrl = normalizeUrl(baseUrl)

        // Try to get existing client first (read lock)
        writableLock.read {
            writableClients[normalizedUrl]?.let { pool ->
                if (pool.clients.isNotEmpty()) {
                    // Round-robin selection
                    val clientInfo = pool.clients[pool.nextClientIndex % pool.clients.size]
                    pool.nextClientIndex = (pool.nextClientIndex + 1) % pool.clients.size

                    clientInfo.lastAccessTime = System.currentTimeMillis()
                    clientInfo.referenceCount++
                    Timber.tag(TAG).d("Reusing writable client for node: $normalizedUrl (client ${pool.nextClientIndex}/${pool.clients.size}, refs: ${clientInfo.referenceCount})")
                    return clientInfo.service
                }
            }
        }

        // Create new client if pool doesn't exist or isn't full (write lock)
        return writableLock.write {
            val pool = writableClients.getOrPut(normalizedUrl) { ClientPool() }

            // If pool already has clients, use round-robin
            if (pool.clients.isNotEmpty()) {
                val clientInfo = pool.clients[pool.nextClientIndex % pool.clients.size]
                pool.nextClientIndex = (pool.nextClientIndex + 1) % pool.clients.size

                clientInfo.lastAccessTime = System.currentTimeMillis()
                clientInfo.referenceCount++
                Timber.tag(TAG).d("Reusing writable client (double-check) for node: $normalizedUrl")
                return@write clientInfo.service
            }

            // Check URL limit
            if (writableClients.size >= MAX_URLS) {
                Timber.tag(TAG).w("Writable client pool at max URLs ($MAX_URLS), cleaning up...")
                cleanupIdleClients(writableClients, writableLock, "writable")
            }

            // Create new client (up to CLIENTS_PER_URL per URL)
            if (pool.clients.size < CLIENTS_PER_URL) {
                try {
                    val client = HproseClient.create("$normalizedUrl/webapi/")
                    client.timeout = WRITABLE_CLIENT_TIMEOUT
                    val service = client.useService(HproseService::class.java)

                    val clientInfo = ClientInfo(
                        client = client,
                        service = service,
                        referenceCount = 1
                    )

                    pool.clients.add(clientInfo)
                    Timber.tag(TAG).d("Created new writable client for node: $normalizedUrl (${pool.clients.size}/$CLIENTS_PER_URL clients)")

                    // Periodic cleanup check
                    maybeCleanup()

                    service
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Failed to create writable Hprose client for node: $normalizedUrl")
                    null
                }
            } else {
                // Pool is full, use round-robin
                val clientInfo = pool.clients[pool.nextClientIndex % pool.clients.size]
                pool.nextClientIndex = (pool.nextClientIndex + 1) % pool.clients.size

                clientInfo.lastAccessTime = System.currentTimeMillis()
                clientInfo.referenceCount++
                Timber.tag(TAG).d("Pool full, reusing writable client for node: $normalizedUrl")
                clientInfo.service
            }
        }
    }
    
    /**
     * Release a client reference (decrement reference count).
     * Clients with zero references are candidates for cleanup.
     *
     * @param baseUrl The base URL of the node/server
     * @param isWritableClient Whether this is a writable-pool client (default: false → regular pool)
     */
    fun releaseClient(baseUrl: String, isWritableClient: Boolean = false) {
        val normalizedUrl = normalizeUrl(baseUrl)

        if (isWritableClient) {
            writableLock.write {
                writableClients[normalizedUrl]?.let { pool ->
                    // Decrement reference count for all clients in pool
                    pool.clients.forEach { clientInfo ->
                        clientInfo.referenceCount = maxOf(0, clientInfo.referenceCount - 1)
                    }
                    Timber.tag(TAG).d("Released writable client pool for node: $normalizedUrl")
                }
            }
        } else {
            regularLock.write {
                regularClients[normalizedUrl]?.let { pool ->
                    // Decrement reference count for all clients in pool
                    pool.clients.forEach { clientInfo ->
                        clientInfo.referenceCount = maxOf(0, clientInfo.referenceCount - 1)
                    }
                    Timber.tag(TAG).d("Released regular client pool for node: $normalizedUrl")
                }
            }
        }
    }
    
    /**
     * Clear a specific client pool from the pool (e.g., when node URL changes)
     * 
     * @param baseUrl The base URL of the node/server to clear
     */
    fun clearClient(baseUrl: String) {
        val normalizedUrl = normalizeUrl(baseUrl)
        
        regularLock.write {
            regularClients.remove(normalizedUrl)?.let { pool ->
                Timber.tag(TAG).d("Cleared regular client pool for node: $normalizedUrl (${pool.clients.size} clients)")
            }
        }
        
        writableLock.write {
            writableClients.remove(normalizedUrl)?.let { pool ->
                Timber.tag(TAG).d("Cleared writable client pool for node: $normalizedUrl (${pool.clients.size} clients)")
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
            cleanupIdleClients(writableClients, writableLock, "writable")
        }
    }
    
    /**
     * Clean up idle clients that haven't been accessed recently and have no references
     */
    private fun cleanupIdleClients(
        clientMap: ConcurrentHashMap<String, ClientPool>,
        lock: ReentrantReadWriteLock,
        type: String
    ) {
        lock.write {
            val now = System.currentTimeMillis()
            val toRemove = mutableListOf<String>()
            var totalClientsRemoved = 0
            
            clientMap.forEach { (url, pool) ->
                // Remove idle clients from the pool
                val initialSize = pool.clients.size
                pool.clients.removeIf { clientInfo ->
                    val idleTime = now - clientInfo.lastAccessTime
                    clientInfo.referenceCount == 0 && idleTime > CLIENT_MAX_IDLE_TIME_MS
                }
                
                val removedCount = initialSize - pool.clients.size
                totalClientsRemoved += removedCount
                
                // If all clients in the pool are gone, remove the URL entry
                if (pool.clients.isEmpty()) {
                    toRemove.add(url)
                } else if (removedCount > 0) {
                    // Reset round-robin index if we removed clients
                    pool.nextClientIndex = 0
                }
            }
            
            toRemove.forEach { url ->
                clientMap.remove(url)
            }
            
            if (totalClientsRemoved > 0 || toRemove.isNotEmpty()) {
                Timber.tag(TAG).d("Cleaned up $totalClientsRemoved idle $type clients and ${toRemove.size} empty pools (remaining URLs: ${clientMap.size})")
            }
        }
    }

}


