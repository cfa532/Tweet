# Tweet App Documentation Index
**Last Updated:** October 13, 2025

---

## 📚 Documentation Overview

This folder contains all technical documentation for the Tweet distributed social media application. Documents are organized by category for easy navigation.

**Total Documents:** 31 markdown files  
**Last Updated:** October 13, 2025

---

## 🌟 Start Here (New Developers)

### Essential Reading (20 minutes)
1. **[QUICK_START_GUIDE.md](QUICK_START_GUIDE.md)** 🆕 - Get up and running in 5 minutes
2. **[PROJECT_STATUS.md](PROJECT_STATUS.md)** 🆕 - Current project state and overview
3. **[TECHNICAL_ARCHITECTURE.md](TECHNICAL_ARCHITECTURE.md)** 🆕 - Comprehensive architecture guide
4. **[RECENT_CHANGES.md](RECENT_CHANGES.md)** 🆕 - Latest updates (October 2025)

---

## 🚀 Recent Updates (October 2025)

### New Documentation 🆕
- **[QUICK_START_GUIDE.md](QUICK_START_GUIDE.md)** - Developer onboarding guide
- **[PROJECT_STATUS.md](PROJECT_STATUS.md)** - Complete project status
- **[TECHNICAL_ARCHITECTURE.md](TECHNICAL_ARCHITECTURE.md)** - Architecture deep dive
- **[RECENT_CHANGES.md](RECENT_CHANGES.md)** - Recent updates summary
- **[DOCUMENTATION_ORGANIZATION_SUMMARY.md](DOCUMENTATION_ORGANIZATION_SUMMARY.md)** - Organization notes

### Latest Changes
- **[CONNECTION_POOLING_OPTIMIZATION_REPORT.md](CONNECTION_POOLING_OPTIMIZATION_REPORT.md)** - Major performance optimization
- **[VIDEO_MANAGER_CONSOLIDATION_SUMMARY.md](VIDEO_MANAGER_CONSOLIDATION_SUMMARY.md)** - Updated with mute state
- **[LOCAL_VIDEO_PROCESSING_IMPLEMENTATION.md](LOCAL_VIDEO_PROCESSING_IMPLEMENTATION.md)** - Updated with latest config

---

## 📑 Documentation Categories

### 📘 Project Overview & Getting Started

- **[QUICK_START_GUIDE.md](QUICK_START_GUIDE.md)** 🆕
  - Get started in 5 minutes
  - Development environment setup
  - Common tasks and examples
  - Troubleshooting guide
  - Learning path for new developers

- **[PROJECT_STATUS.md](PROJECT_STATUS.md)** 🆕
  - Current project state and metrics
  - Architecture overview
  - Feature list
  - Technical stack
  - Roadmap and future work
  - Quality metrics

- **[TECHNICAL_ARCHITECTURE.md](TECHNICAL_ARCHITECTURE.md)** 🆕
  - Comprehensive architecture guide
  - System diagrams
  - Design patterns
  - Component deep-dives
  - Performance optimizations
  - Threading model

- **[RECENT_CHANGES.md](RECENT_CHANGES.md)** 🆕
  - October 2025 updates summary
  - Connection pooling details
  - Video mute fix
  - HLS segment naming
  - Files modified list

- **[SESSION_SUMMARY_OCT_2025.md](SESSION_SUMMARY_OCT_2025.md)** 🆕
  - Complete session summary
  - All accomplishments
  - Performance metrics
  - Technical achievements

- **[DOCUMENTATION_ORGANIZATION_SUMMARY.md](DOCUMENTATION_ORGANIZATION_SUMMARY.md)** 🆕
  - Documentation reorganization notes
  - Structure explanation

---

## 📑 Documentation Categories

### 🌐 Network & Performance Optimization

#### **Connection Pooling & Distributed Architecture**
- **[CONNECTION_POOLING_OPTIMIZATION_REPORT.md](CONNECTION_POOLING_OPTIMIZATION_REPORT.md)** 🌟 **NEW**
  - Comprehensive connection pooling implementation
  - Node-based client sharing (60-80% memory reduction)
  - OkHttp migration for image downloads
  - Ktor optimization for API calls
  - HproseClientPool for distributed nodes

#### **Image Loading & Caching**
- **[LAZY_LOADING_IMAGE_GRID_IMPROVEMENTS.md](LAZY_LOADING_IMAGE_GRID_IMPROVEMENTS.md)**
  - Lazy loading implementation
  - Grid performance optimization
  - Memory management strategies

---

### 🎥 Video & Media Handling

#### **Video Player & Playback**
- **[VIDEO_LOADING_ALGORITHM.md](VIDEO_LOADING_ALGORITHM.md)** 🆕
  - Unified video loading and caching strategy
  - Type-based playback strategy (HLS vs Progressive)
  - ExoPlayer integration and caching
  - Aspect ratio handling
  - Error handling and graceful degradation

- **[FULLSCREEN_VIDEO_PLAYER.md](FULLSCREEN_VIDEO_PLAYER.md)** 🆕
  - Full-screen video viewing experience
  - Auto-replay functionality
  - Independent mute state management
  - Efficient player reuse
  - Gesture controls and user interaction

- **[VIDEO_PLAYER_REFACTORING.md](VIDEO_PLAYER_REFACTORING.md)**
  - ExoPlayer architecture
  - Video player lifecycle management
  - Playback state handling

- **[VIDEO_MANAGER_CONSOLIDATION_SUMMARY.md](VIDEO_MANAGER_CONSOLIDATION_SUMMARY.md)**
  - VideoManager singleton pattern
  - Player instance management
  - Memory optimization

- **[VIDEO_LOADING_MANAGER_SUMMARY.md](VIDEO_LOADING_MANAGER_SUMMARY.md)**
  - Video loading orchestration
  - Preloading strategies
  - Visibility tracking

- **[VIDEO_LOADING_FIXES_SUMMARY.md](VIDEO_LOADING_FIXES_SUMMARY.md)**
  - Common video loading issues
  - Bug fixes and solutions
  - Performance improvements

#### **Video Processing & Conversion**
- **[LOCAL_VIDEO_PROCESSING_IMPLEMENTATION.md](LOCAL_VIDEO_PROCESSING_IMPLEMENTATION.md)**
  - FFmpeg integration
  - Local video processing
  - HLS conversion (segment naming: segment000.ts, segment001.ts, etc.)

- **[VIDEO_UPLOAD_STRATEGY_UPDATE.md](VIDEO_UPLOAD_STRATEGY_UPDATE.md)** 🆕
  - Video upload strategy and routing
  - TUS server integration
  - Service availability checking
  - Automatic fallback to IPFS
  - Resolution-based processing

- **[TUS_SERVER_NAMING_UPDATE.md](TUS_SERVER_NAMING_UPDATE.md)** 🌟 **NEW** (Oct 13, 2025)
  - Renamed netDiskUrl to tusServerUrl
  - Updated health check endpoint from `/process-zip/health` to `/health`
  - Added resolveWritableUrl() requirement
  - Server implementation examples

- **[MEDIACODEC_ERROR_RECOVERY.md](MEDIACODEC_ERROR_RECOVERY.md)**
  - MediaCodec error handling
  - Codec compatibility
  - Error recovery strategies

- **[PESREADER_ERROR_RESOLUTION.md](PESREADER_ERROR_RESOLUTION.md)**
  - PES stream parsing issues
  - HLS playback fixes
  - Stream formatting improvements

---

### 💬 Chat & Messaging

- **[CHAT_SESSION_DISPLAY_LOGIC_SUMMARY.md](CHAT_SESSION_DISPLAY_LOGIC_SUMMARY.md)**
  - Chat session management
  - Display logic
  - UI state handling

- **[CHAT_VIDEO_LOADING_OPTIMIZATION.md](CHAT_VIDEO_LOADING_OPTIMIZATION.md)**
  - Video in chat messages
  - Loading optimization
  - Performance tuning

- **[CHAT_VIDEO_DISPLAY_IMPROVEMENT_SUMMARY.md](CHAT_VIDEO_DISPLAY_IMPROVEMENT_SUMMARY.md)**
  - Video display in chat
  - UI improvements
  - User experience enhancements

---

### 🔧 Build & Configuration

- **[BUILD_CONFIGURATION_FIXES_SUMMARY.md](BUILD_CONFIGURATION_FIXES_SUMMARY.md)**
  - Gradle configuration
  - Build settings
  - Dependency management

- **[16KB_PAGE_SIZE_FIX_SUMMARY.md](16KB_PAGE_SIZE_FIX_SUMMARY.md)**
  - Android 16KB page size compatibility
  - NDK configuration
  - Binary packaging fixes

- **[FIREBASE_CRASHLYTICS_FIX_SUMMARY.md](FIREBASE_CRASHLYTICS_FIX_SUMMARY.md)**
  - Firebase Crashlytics integration
  - Crash reporting setup
  - ProGuard configuration

---

### 🎨 UI Features & Components

- **[TWEET_LIST_VIEW.md](TWEET_LIST_VIEW.md)** 🆕
  - Self-contained tweet list component
  - iOS-like infinite scroll
  - Pull-to-refresh functionality
  - Advanced gesture detection
  - Smart debouncing and pagination
  - Profile screen integration

- **[BADGE_IMPLEMENTATION_SUMMARY.md](BADGE_IMPLEMENTATION_SUMMARY.md)**
  - Launcher badge implementation
  - Notification badges
  - Badge state management

- **[BADGE_FORMATTING_IMPLEMENTATION.md](BADGE_FORMATTING_IMPLEMENTATION.md)**
  - Badge visual formatting
  - UI guidelines
  - Cross-platform support

- **[NIGHT_MODE_OPTIONAL_IMPLEMENTATION.md](NIGHT_MODE_OPTIONAL_IMPLEMENTATION.md)**
  - Dark mode support
  - Theme switching
  - Auto-battery mode

---

### 🗂️ File & Data Management

- **[FILE_TYPE_DETECTION_IMPLEMENTATION.md](FILE_TYPE_DETECTION_IMPLEMENTATION.md)**
  - File type detection logic
  - MIME type handling
  - Media classification

- **[FILE_TYPE_DETECTION_SUMMARY.md](FILE_TYPE_DETECTION_SUMMARY.md)**
  - File type detection summary
  - Implementation details
  - Edge cases

---

### 🧹 Maintenance & Cleanup

- **[CLEANUP_SUMMARY.md](CLEANUP_SUMMARY.md)**
  - Code cleanup operations
  - Deprecated code removal
  - Refactoring notes

- **[UNUSED_CODE_ANALYSIS.md](UNUSED_CODE_ANALYSIS.md)**
  - Unused code identification
  - Potential removal candidates
  - Technical debt tracking

---

## 🏗️ Architecture Overview

### **Distributed Architecture**
The Tweet app is a distributed social media application that connects to multiple nodes/servers:
- Each node serves data for a subset of users
- HproseClientPool manages shared clients per node
- Connection pooling optimizes multi-node communication
- Efficient resource sharing across users on same node

### **Key Components**

1. **Network Layer**
   - `HproseClientPool`: Node-based client sharing
   - `OkHttpClient`: Image download optimization
   - `Ktor HttpClient`: API communication

2. **Media Layer**
   - `VideoManager`: Video player lifecycle
   - `ImageCacheManager`: Image caching and loading
   - `LocalHLSConverter`: Local video processing

3. **Data Layer**
   - `Room Database`: Local caching (ChatDatabase, TweetCacheDatabase)
   - `TweetCacheManager`: Tweet data management
   - `BlackList`: Content filtering

4. **UI Layer**
   - `TweetFeedViewModel`: Feed management
   - `TweetDetailView`: Tweet detail display
   - `MediaBrowser`: Full-screen media viewing

---

## 🔍 Finding Information

### By Feature
- **Video Issues?** → See Video & Media Handling section
- **Network Issues?** → See Network & Performance Optimization
- **Build Issues?** → See Build & Configuration
- **UI Issues?** → See UI Features & Components

### By Date
- **Latest (Oct 2025):** Connection Pooling, Video Mute Fix
- **Recent:** Video loading optimizations, Chat improvements
- **Older:** Initial implementations and configurations

---

## 📝 Documentation Standards

### Document Types
1. **SUMMARY.md** - Brief overview of changes
2. **IMPLEMENTATION.md** - Detailed implementation guide
3. **REPORT.md** - Comprehensive analysis with metrics

### Common Sections
- Overview/Summary
- Implementation Details
- Code Examples
- Testing Notes
- Known Issues
- Future Work

---

## 🛠️ Development Resources

### Quick Links
- **Root README:** `../README.md`
- **Build Config:** `../app/build.gradle.kts`
- **Dependencies:** `../gradle/libs.versions.toml`

### Key Files
- **Main Application:** `app/src/main/java/us/fireshare/tweet/TweetApplication.kt`
- **Network Layer:** `app/src/main/java/us/fireshare/tweet/HproseInstance.kt`
- **Connection Pool:** `app/src/main/java/us/fireshare/tweet/network/HproseClientPool.kt`
- **Video Manager:** `app/src/main/java/us/fireshare/tweet/widget/VideoManager.kt`
- **Image Cache:** `app/src/main/java/us/fireshare/tweet/widget/ImageCacheManager.kt`

---

## 🏷️ Tags & Keywords

**Performance:** Connection Pooling, Image Loading, Video Loading, Memory Management  
**Network:** HproseClientPool, OkHttp, Ktor, Distributed Architecture  
**Media:** Video Player, HLS, Image Cache, Audio Player  
**Database:** Room, TweetCache, ChatDatabase  
**UI:** Compose, MediaBrowser, TweetDetail, Feed  

---

**Total Documents:** 31 markdown files  
**Last Review:** October 13, 2025  
**Maintainer:** Development Team

