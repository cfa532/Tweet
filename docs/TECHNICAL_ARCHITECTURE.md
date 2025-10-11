# Tweet App - Technical Architecture Guide
**Last Updated:** October 10, 2025  
**Version:** 38 (Build 64)

---

## 📋 Table of Contents

1. [Overview](#overview)
2. [System Architecture](#system-architecture)
3. [Network Layer](#network-layer)
4. [Data Layer](#data-layer)
5. [Media Layer](#media-layer)
6. [UI Layer](#ui-layer)
7. [Key Design Patterns](#key-design-patterns)
8. [Performance Optimizations](#performance-optimizations)
9. [Threading Model](#threading-model)
10. [Error Handling](#error-handling)

---

## Overview

Tweet is a distributed Web3 social media application built with modern Android architecture components. The app connects to multiple decentralized nodes, with each node serving a subset of users.

### Core Principles
- **Distributed:** Multi-node architecture with no single point of failure
- **Efficient:** Connection pooling and resource sharing
- **Reactive:** Flow-based state management
- **Modern:** Jetpack Compose UI with Material Design 3
- **Scalable:** Designed to handle growing user base

---

## System Architecture

### High-Level Architecture

```
┌───────────────────────────────────────────────────────────────┐
│                        Android Application                     │
├───────────────────────────────────────────────────────────────┤
│  Presentation Layer (Jetpack Compose)                         │
│  ┌─────────────┬─────────────┬─────────────┬─────────────┐   │
│  │ TweetFeed   │ TweetDetail │ ChatScreen  │ UserProfile │   │
│  │   Screen    │   Screen    │             │   Screen    │   │
│  └─────────────┴─────────────┴─────────────┴─────────────┘   │
├───────────────────────────────────────────────────────────────┤
│  ViewModel Layer (State Management)                           │
│  ┌─────────────┬─────────────┬─────────────┬─────────────┐   │
│  │TweetFeed VM │ Tweet VM    │  Chat VM    │  User VM    │   │
│  └─────────────┴─────────────┴─────────────┴─────────────┘   │
├───────────────────────────────────────────────────────────────┤
│  Repository Layer (Business Logic)                            │
│  ┌─────────────────────────────────────────────────────────┐  │
│  │            HproseInstance (Singleton)                    │  │
│  │  - API Communication        - User Management           │  │
│  │  - Tweet Operations         - Media Handling            │  │
│  └─────────────────────────────────────────────────────────┘  │
├───────────────────────────────────────────────────────────────┤
│  Network Layer                                                 │
│  ┌─────────────┬─────────────┬─────────────┬─────────────┐   │
│  │ HproseClient│  OkHttp     │   Ktor      │  Firebase   │   │
│  │    Pool 🆕  │(Images) 🆕  │(Uploads)    │(Analytics)  │   │
│  └─────────────┴─────────────┴─────────────┴─────────────┘   │
├───────────────────────────────────────────────────────────────┤
│  Data Layer                                                    │
│  ┌─────────────┬─────────────┬─────────────────────────────┐ │
│  │ Room DB     │ SharedPrefs │  Cache Manager              │ │
│  │ (Local)     │ (Settings)  │  (Tweet/Image/Video)        │ │
│  └─────────────┴─────────────┴─────────────────────────────┘ │
├───────────────────────────────────────────────────────────────┤
│  Media Layer                                                   │
│  ┌─────────────┬─────────────┬─────────────────────────────┐ │
│  │VideoManager │ImageCache   │ LocalHLSConverter           │ │
│  │(ExoPlayer)  │Manager      │ (FFmpeg)                    │ │
│  └─────────────┴─────────────┴─────────────────────────────┘ │
├───────────────────────────────────────────────────────────────┤
│  Platform Layer                                                │
│  ┌─────────────┬─────────────┬─────────────┬─────────────┐   │
│  │  CameraX    │ WorkManager │  Coroutines │    Hilt     │   │
│  └─────────────┴─────────────┴─────────────┴─────────────┘   │
└───────────────────────────────────────────────────────────────┘
                              ↓↑
┌───────────────────────────────────────────────────────────────┐
│              Distributed Node Network (Leither OS)            │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐       │
│  │ Node A   │ │ Node B   │ │ Node C   │ │ Node D   │  ...  │
│  │10 users  │ │15 users  │ │8 users   │ │12 users  │       │
│  └──────────┘ └──────────┘ └──────────┘ └──────────┘       │
└───────────────────────────────────────────────────────────────┘
```

---

## Network Layer

### HproseClientPool (NEW - October 2025) 🌟

**Purpose:** Efficient client sharing across users on the same node

**Key Features:**
- Per-node client pooling (not per-user)
- Separate pools for regular (5min) and upload (50min) operations
- Automatic reference counting
- Idle client cleanup (10min timeout)
- Thread-safe concurrent access

**Implementation:**
```kotlin
object HproseClientPool {
    private val regularClients = ConcurrentHashMap<String, ClientInfo>()
    private val uploadClients = ConcurrentHashMap<String, ClientInfo>()
    
    fun getRegularClient(baseUrl: String): HproseService?
    fun getUploadClient(baseUrl: String): HproseService?
    fun releaseClient(baseUrl: String, isUploadClient: Boolean)
    fun getPoolStats(): PoolStats
}
```

**Performance Impact:**
- **Memory:** 60-80% reduction (shared clients)
- **Creation Overhead:** 90% reduction (cache hits)
- **Scalability:** 10x improvement

**See:** [CONNECTION_POOLING_OPTIMIZATION_REPORT.md](CONNECTION_POOLING_OPTIMIZATION_REPORT.md)

---

### OkHttpClient (NEW - October 2025) 🌟

**Purpose:** Professional-grade HTTP connection pooling for image downloads

**Configuration:**
```kotlin
private val okHttpClient = OkHttpClient.Builder()
    .connectionPool(ConnectionPool(
        maxIdleConnections = 16,
        keepAliveDuration = 5,
        timeUnit = TimeUnit.MINUTES
    ))
    .connectTimeout(5_000, TimeUnit.MILLISECONDS)
    .readTimeout(15_000, TimeUnit.MILLISECONDS)
    .retryOnConnectionFailure(true)
    .build()
```

**Benefits:**
- HTTP/2 support with multiplexing
- Automatic connection reuse
- Built-in retry logic
- 30-50% faster image loading

---

### Ktor HttpClient (Enhanced - October 2025)

**Purpose:** File upload and backend communication

**Configuration:**
```kotlin
HttpClient(CIO) {
    engine {
        maxConnectionsCount = 1000 // Total across all nodes
    }
    install(HttpTimeout) {
        requestTimeoutMillis = 3_000_000  // 50 minutes
        connectTimeoutMillis = 60_000     // 1 minute
        socketTimeoutMillis = 300_000     // 5 minutes
    }
}
```

---

### Hprose RPC

**Purpose:** Primary API communication protocol

**Usage:**
```kotlin
// Via User object (uses pool automatically)
val service = user.hproseService
service?.runMApp<Map<String, Any>>(entry, params)

// Direct access to pool
val service = HproseClientPool.getRegularClient(baseUrl)
```

---

## Data Layer

### Room Databases

#### TweetCacheDatabase (Version 11)
**Tables:**
- `CachedTweet` - Tweet cache with 30-day TTL
- `CachedUser` - User profile cache
- `BlacklistEntry` - Blocked content

**Configuration:**
```kotlin
Room.databaseBuilder(context, TweetCacheDatabase::class.java, "tweet_cache_database")
    .fallbackToDestructiveMigration(false)
    .addMigrations(MIGRATION_10_11)
    .build()
```

**Access Pattern:**
- Singleton instance via `getInstance(context)`
- Automatic migrations configured
- DAOs injected via Hilt

#### ChatDatabase
**Tables:**
- `ChatMessage` - Message history
- `ChatSession` - Active conversations

**Configuration:**
```kotlin
Room.databaseBuilder(context, ChatDatabase::class.java, "chat_database")
    .fallbackToDestructiveMigration(true) // Destructive migration
    .build()
```

---

### Cache Management

#### TweetCacheManager
**Purpose:** Centralized tweet caching

**Features:**
- 30-day automatic cleanup
- Efficient query methods
- Blacklist integration

#### ImageCacheManager (Updated October 2025)
**Purpose:** Image downloading and caching

**Features:**
- 150MB memory cache
- OkHttp connection pooling 🆕
- Progressive loading support
- 16 concurrent downloads
- Automatic compression (80% JPEG quality)

**Key Methods:**
```kotlin
suspend fun loadImage(context, imageUrl, mid, isVisible): Bitmap?
suspend fun loadOriginalImage(context, imageUrl, mid): Bitmap?
suspend fun loadImageProgressive(context, imageUrl, mid, onProgress): Bitmap?
```

---

## Media Layer

### VideoManager

**Purpose:** Centralized video player management

**Responsibilities:**
- ExoPlayer instance lifecycle
- Visibility-based loading
- Memory management
- Full-screen support
- Preloading strategy

**Key Features:**
- Player pooling (reuse instances)
- 2GB video cache
- Automatic cleanup
- Thread-safe operations

**Configuration:**
```kotlin
const val CACHE_SIZE_BYTES = 2000L * 1024 * 1024  // 2GB
const val PRELOAD_AHEAD_COUNT = 3
const val MAX_CONCURRENT_PRELOADS = 3
```

---

### LocalHLSConverter

**Purpose:** Local video processing to HLS format

**Features:**
- Multi-resolution (720p + 480p)
- Smart codec selection (COPY vs libx264)
- Automatic fallback
- Aspect ratio preservation
- Standard segment naming (segment000.ts, segment001.ts, etc.)

**Processing:**
```kotlin
suspend fun convertToHLS(inputUri, outputDir, fileName): HLSConversionResult
```

**Output Structure:**
```
output/
├── master.m3u8
├── 720/
│   ├── playlist.m3u8
│   └── segment000.ts, segment001.ts, ...
└── 480/
    ├── playlist.m3u8
    └── segment000.ts, segment001.ts, ...
```

---

## UI Layer

### Jetpack Compose Architecture

**Navigation:**
- NavController-based navigation
- Bottom navigation bar
- Deep linking support

**Key Screens:**
```kotlin
├── TweetFeedScreen           // Main feed
├── TweetDetailScreen         // Tweet detail with comments
├── UserProfileScreen         // User profiles
├── ChatScreen                // Messaging
├── MediaBrowser              // Full-screen media viewer
├── CameraXComposable         // Camera capture
└── SystemSettings            // App settings
```

### State Management Pattern

```kotlin
// ViewModel
class TweetViewModel @Inject constructor() : ViewModel() {
    private val _tweetState = MutableStateFlow<Tweet>(initialTweet)
    val tweetState: StateFlow<Tweet> = _tweetState.asStateFlow()
    
    fun loadTweet() { viewModelScope.launch { ... } }
}

// Compose Screen
@Composable
fun TweetDetailScreen(viewModel: TweetViewModel) {
    val tweet by viewModel.tweetState.collectAsState()
    
    LaunchedEffect(Unit) {
        viewModel.loadTweet()
    }
    
    // UI renders based on tweet state
}
```

---

## Key Design Patterns

### 1. Singleton Pattern
**Used For:** Managers and global state

```kotlin
object VideoManager { ... }
object ImageCacheManager { ... }
object HproseInstance { ... }
object HproseClientPool { ... } // NEW
```

**Benefits:**
- Single source of truth
- Global access
- Memory efficient

---

### 2. Repository Pattern
**Used For:** Data access abstraction

```kotlin
class ChatRepository(private val chatMessageDao: ChatMessageDao) {
    suspend fun getMessages(): List<ChatMessage> = chatMessageDao.getAllMessages()
    suspend fun insertMessage(message: ChatMessage) = chatMessageDao.insert(message)
}
```

**Benefits:**
- Separates data access from business logic
- Easy to test
- Flexible data sources

---

### 3. MVVM Pattern
**Used For:** UI state management

```
View (Compose) ←→ ViewModel ←→ Repository ←→ Data Source
```

**Benefits:**
- Clear separation of concerns
- Testable business logic
- Lifecycle awareness

---

### 4. Dependency Injection (Hilt)
**Used For:** Object creation and dependency management

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun provideChatDatabase(@ApplicationContext context: Context): ChatDatabase {
        return Room.databaseBuilder(context, ChatDatabase::class.java, "chat_database")
            .build()
    }
}
```

---

### 5. Object Pool Pattern (NEW) 🌟
**Used For:** Connection management in distributed architecture

```kotlin
object HproseClientPool {
    private val regularClients = ConcurrentHashMap<String, ClientInfo>()
    
    fun getRegularClient(baseUrl: String): HproseService? {
        // Reuse existing or create new client
        // Multiple users on same node share one client
    }
}
```

**Benefits:**
- Massive memory savings
- Connection reuse
- Automatic lifecycle management

---

## Performance Optimizations

### 1. Connection Pooling (October 2025) 🆕

**Optimization:** Node-based client sharing

**Impact:**
- 60-80% memory reduction for API clients
- 90% reduction in client creation overhead
- 4x improvement in connection reuse
- 30-50% faster image downloads

**Implementation:**
- `HproseClientPool` for API clients
- `OkHttpClient` for image downloads
- Enhanced `Ktor` configuration

---

### 2. Lazy Loading

**Images:**
- Load only visible images
- Progressive loading support
- 16 concurrent downloads

**Videos:**
- Visibility-based loading
- Preload 3 tweets ahead
- Automatic cleanup of invisible videos

---

### 3. Memory Management

**Image Cache:**
- 150MB memory limit
- LRU eviction policy
- Automatic cleanup on memory pressure

**Video Cache:**
- 2GB disk cache (ExoPlayer)
- Automatic eviction
- System memory warning integration

---

### 4. Database Optimization

**Indexing:**
```kotlin
@Entity(indices = [Index(value = ["uid"])])
data class CachedTweet(...)
```

**Connection Pooling:**
- Room automatic pooling (default: 4 connections)
- Singleton database instances
- Efficient query patterns

---

## Threading Model

### Coroutine Dispatchers

```kotlin
// UI operations
Dispatchers.Main

// Network/Database operations
Dispatchers.IO

// CPU-intensive operations  
Dispatchers.Default

// Application-level scope
TweetApplication.applicationScope = CoroutineScope(SupervisorJob() + IO)
```

### Thread Safety

**ConcurrentHashMap:**
- All pools and caches use ConcurrentHashMap
- Thread-safe without explicit synchronization

**Locks:**
- ReentrantReadWriteLock for HproseClientPool
- Synchronized blocks for critical sections

**Atomic Operations:**
- AtomicInteger for counters
- Thread-safe state updates

---

## Error Handling

### Network Errors

```kotlin
try {
    val response = user.hproseService?.runMApp<Map<String, Any>>(entry, params)
    if (response?.get("success") != true) {
        // Handle API error
        return emptyList()
    }
    // Process response
} catch (e: Exception) {
    Timber.e("API call failed", e)
    // Return cached data or empty result
}
```

### Retry Logic

**OkHttp:**
- Automatic retry on connection failure
- Configurable timeout

**Manual Retry:**
```kotlin
var retryCount = 0
while (retryCount < MAX_RETRIES) {
    try {
        return performOperation()
    } catch (e: Exception) {
        retryCount++
        if (retryCount >= MAX_RETRIES) throw e
        delay(1000 * retryCount) // Exponential backoff
    }
}
```

---

## Distributed Architecture Details

### Node Management

**Multi-Node Support:**
- Each user associated with a node (baseUrl)
- Dynamic node URL resolution
- Automatic failover to writable nodes

**Client Sharing:**
```
Example: 100 users across 10 nodes

Before Optimization:
- 100 users × 2 clients = 200 client instances
- Memory: 400MB

After Optimization (with HproseClientPool):
- 10 nodes × 2 clients = 20 client instances  
- Memory: 40MB
- 90% memory reduction! 🎉
```

---

### Node Communication

**Request Flow:**
```
User Action
  ↓
ViewModel
  ↓
HproseInstance
  ↓
User.hproseService (gets from HproseClientPool)
  ↓
HproseClientPool.getRegularClient(baseUrl)
  ↓
[Cache Hit] Reuse existing client → Node
[Cache Miss] Create new client → Node
```

---

## Key Components Deep Dive

### HproseInstance

**Purpose:** Central API hub for all backend operations

**Key Methods:**
```kotlin
// Tweet operations
suspend fun getTweetFeed(user, pageNumber, pageSize): List<Tweet?>
suspend fun getTweetsByUser(user, pageNumber, pageSize): List<Tweet?>
suspend fun fetchTweet(tweetId, authorId): Tweet?
suspend fun uploadTweet(context, content, attachments): Tweet?

// User operations
suspend fun getUser(userId, baseUrl): User?
suspend fun updateUser(user): Boolean

// Media operations
suspend fun uploadToIPFS(context, uri, fileType): MimeiFileType?
fun getMediaUrl(mimeiId, baseUrl): Uri
```

**Characteristics:**
- Singleton object
- Coroutine-based async operations
- Comprehensive error handling
- Timber logging throughout

---

### VideoManager

**Purpose:** Unified video player management

**Key Responsibilities:**
1. ExoPlayer instance pooling
2. Visibility-based loading
3. Preloading strategy
4. Memory management
5. Full-screen support

**Player Lifecycle:**
```
Create → Prepare → Play → Pause → Stop → Release
         ↑__________________|  (Reuse)
```

**Visibility Management:**
```kotlin
VideoManager.markVideoVisible(videoMid)     // Start loading
VideoManager.markVideoNotVisible(videoMid)  // Pause/cleanup
```

---

### ImageCacheManager (Updated October 2025)

**Purpose:** Image downloading, caching, and optimization

**Three-Tier Caching:**
1. **Memory Cache:** 150MB LRU cache
2. **Disk Cache:** App cache directory
3. **Network:** OkHttp with connection pooling 🆕

**Download Pipeline:**
```
Request Image
  ↓
Memory Cache? → Yes → Return
  ↓ No
Disk Cache? → Yes → Load & Cache → Return
  ↓ No
Download (OkHttp) → Compress → Cache → Return
```

---

## UI Components Architecture

### Composable Hierarchy

```
TweetFeedScreen
  └─ LazyColumn
      └─ TweetItem (multiple)
          ├─ TweetHeader
          ├─ TweetBody
          │   └─ MediaItemView
          │       ├─ ImagePreview
          │       ├─ VideoPreview (uses global mute)
          │       └─ AudioPlayer
          └─ TweetFooter

TweetDetailScreen
  └─ LazyColumn
      ├─ TweetDetailBody
      │   └─ MediaItemView (useIndependentVideoMute = true) 🆕
      │       └─ VideoPreview (unmuted, independent)
      └─ CommentItem (multiple)
```

---

### State Management

**Unidirectional Data Flow:**
```
User Action
  ↓
UI Event
  ↓
ViewModel.method()
  ↓
Repository/HproseInstance
  ↓
StateFlow.emit(newState)
  ↓
UI Recomposition
```

---

## Video Mute State Architecture (NEW - October 2025) 🌟

### Context-Aware Mute Behavior

**Implementation:**
```kotlin
@Composable
fun VideoPreview(
    ...
    useIndependentMuteState: Boolean = false
) {
    var isMuted by remember { 
        mutableStateOf(
            if (useIndependentMuteState) false 
            else preferenceHelper.getSpeakerMute()
        )
    }
    
    LaunchedEffect(isMuted) {
        exoPlayer.volume = if (isMuted) 0f else 1f
        if (!useIndependentMuteState) {
            preferenceHelper.setSpeakerMute(isMuted) // Only update global if not independent
        }
    }
}
```

**Usage:**
```kotlin
// In feeds (follows global mute)
MediaItemView(
    ...
    useIndependentVideoMute = false // Default
)

// In TweetDetailView (unmuted, independent)
MediaItemView(
    ...
    useIndependentVideoMute = true
)
```

**Behavior:**
- **Feeds:** All videos synchronized with global mute preference
- **Detail View:** Videos start unmuted, changes don't affect global
- **Full Screen:** Videos always unmuted, independent of all other contexts

---

## Dependency Injection Structure

### Hilt Modules

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton
    fun provideChatDatabase(@ApplicationContext context: Context): ChatDatabase
    
    @Provides @Singleton  
    fun provideTweetFeedViewModel(): TweetFeedViewModel
    
    @Provides
    fun provideChatRepository(dao: ChatMessageDao): ChatRepository
}
```

**Component Hierarchy:**
```
SingletonComponent (Application lifetime)
  ├─ Databases
  ├─ Global ViewModels
  └─ Repositories

ActivityComponent (Activity lifetime)
  └─ Activity-specific dependencies

ViewModelComponent (ViewModel lifetime)
  └─ ViewModel dependencies
```

---

## Background Processing

### WorkManager Tasks

**CleanUpWorker:**
- **Frequency:** Daily
- **Purpose:** Delete old cached tweets (>30 days)

**MessageCheckWorker:**
- **Frequency:** 15 minutes
- **Purpose:** Check for new messages
- **Constraint:** Network connected

**Configuration:**
```kotlin
val cleanUpRequest = PeriodicWorkRequestBuilder<CleanUpWorker>(1, TimeUnit.DAYS).build()
val messageCheckRequest = PeriodicWorkRequestBuilder<MessageCheckWorker>(15, TimeUnit.MINUTES)
    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
    .build()
```

---

## Security Architecture

### Data Protection
- **Local Storage:** App-private cache directories
- **Database:** SQLite with potential encryption
- **Network:** HTTPS support for secure nodes

### Authentication
- Session-based authentication
- Token management in HproseService
- Automatic token refresh

### Content Filtering
- **BlackList:** Centralized content blocking
- **User Blocking:** Per-user blacklist
- **Automatic Cleanup:** Failed content removal

---

## Performance Monitoring

### Built-in Metrics

**HproseClientPool:**
```kotlin
val stats = HproseClientPool.getPoolStats()
// Returns: client counts, references, ages
```

**VideoManager:**
```kotlin
VideoManager.getCachedVideoCount()
VideoManager.getActiveVideoCount()
VideoManager.getVideoActiveCount(videoMid)
```

**ImageCache:**
- Memory usage tracking
- Cache hit/miss rates
- Download statistics

### Logging Strategy

**Timber Tags:**
```kotlin
Timber.tag("HproseClientPool").d("Message")
Timber.tag("getTweetFeed").d("Message")
Timber.tag("VideoManager").d("Message")
```

**Log Levels:**
- Debug: Normal operations
- Warning: Recoverable issues
- Error: Failures requiring attention

---

## Memory Management Strategy

### Automatic Cleanup

**System Memory Warnings:**
```kotlin
override fun onTrimMemory(level: Int) {
    when (level) {
        TRIM_MEMORY_RUNNING_CRITICAL -> {
            ImageCacheManager.clearMemoryCache()
            VideoManager.cleanupInactivePlayers()
        }
    }
}
```

### Manual Cleanup

**Scheduled:**
- Daily tweet cleanup (>30 days)
- Periodic video player cleanup
- Idle client cleanup (10min)

**On-Demand:**
- User logout: Clear all caches
- Low memory: Aggressive cleanup
- App restart: Fresh start

---

## Testing Strategy

### Unit Tests
- ViewModel logic
- Repository operations
- Data transformations
- Utility functions

### Integration Tests
- Database operations
- API communication
- Cache management

### Manual Testing
- UI flows
- Video playback
- Image loading
- Multi-device testing

---

## Configuration Reference

### Build Configuration

```kotlin
android {
    compileSdk = 36
    minSdk = 29
    targetSdk = 36
    
    defaultConfig {
        applicationId = "us.fireshare.tweet"
        versionCode = 64
        versionName = "38"
    }
}

kotlin {
    jvmToolchain(17)
}
```

### Network Timeouts

```kotlin
// OkHttp (Images)
connectTimeout = 5_000ms
readTimeout = 15_000ms

// Ktor (Uploads)
connectTimeout = 60_000ms
requestTimeout = 3_000_000ms

// Hprose (API)
regular = 300_000ms (5 min)
upload = 3_000_000ms (50 min)
```

---

## Scalability Considerations

### Current Capacity
- **Users per Node:** 100s
- **Total Nodes:** Unlimited
- **Concurrent Connections:** 1000
- **Cache Size:** 2GB video + 150MB images

### Growth Strategy
1. **Horizontal Scaling:** Add more nodes as users grow
2. **Connection Pooling:** Efficiency improves with more users per node
3. **Cache Management:** Automatic cleanup prevents unbounded growth
4. **Load Balancing:** Distribute users across nodes

---

## Best Practices

### Code Style
- Kotlin coroutines for async
- Flow for reactive streams
- Compose for all UI
- Hilt for DI
- Timber for logging

### Resource Management
- Always use `withContext` for IO operations
- Proper lifecycle awareness
- Clean up in `DisposableEffect`
- Release media players when done

### Error Handling
- Try-catch around network calls
- Graceful degradation
- User-friendly error messages
- Comprehensive logging

---

## Related Documentation

- **[INDEX.md](INDEX.md)** - Documentation index
- **[PROJECT_STATUS.md](PROJECT_STATUS.md)** - Current status
- **[CONNECTION_POOLING_OPTIMIZATION_REPORT.md](CONNECTION_POOLING_OPTIMIZATION_REPORT.md)** - Optimization details
- **[VIDEO_MANAGER_CONSOLIDATION_SUMMARY.md](VIDEO_MANAGER_CONSOLIDATION_SUMMARY.md)** - Video architecture
- **[LOCAL_VIDEO_PROCESSING_IMPLEMENTATION.md](LOCAL_VIDEO_PROCESSING_IMPLEMENTATION.md)** - Video processing

---

**Document Version:** 1.0  
**Last Updated:** October 10, 2025  
**Status:** ✅ Complete

---

**End of Technical Architecture Guide**


