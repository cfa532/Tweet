# Tweet App - Quick Start Guide
**Last Updated:** October 10, 2025  
**For:** Developers, Contributors, New Team Members

---

## 🚀 Quick Start (5 Minutes)

### Prerequisites
- ✅ macOS, Linux, or Windows
- ✅ Android Studio (2024+)
- ✅ Java 17 (will auto-download if needed)
- ✅ Android device or emulator (Android 10+)

### Get Running

```bash
# 1. Clone the repository
git clone https://github.com/[your-org]/Tweet.git
cd Tweet

# 2. Open in Android Studio
# (Or use command line below)

# 3. Build and install
./gradlew clean assembleDebug installDebug

# 4. Launch app
adb shell am start -n us.fireshare.tweet.debug/us.fireshare.tweet.TweetActivity
```

**That's it!** The app should now be running on your device. 🎉

---

## 📚 Understanding the Project (15 Minutes)

### Essential Reading Order

1. **[README.md](../README.md)** (2 min)
   - Project overview
   - What is Tweet?
   - Links to documentation

2. **[PROJECT_STATUS.md](PROJECT_STATUS.md)** (5 min)
   - Current state
   - Architecture overview
   - Key features

3. **[TECHNICAL_ARCHITECTURE.md](TECHNICAL_ARCHITECTURE.md)** (8 min)
   - Detailed architecture
   - Design patterns
   - Key components

### Key Concepts

**Distributed Architecture:**
- App connects to multiple nodes (servers)
- Each node serves different users
- Clients are shared per node (not per user) for efficiency

**Connection Pooling:** 🆕
- HproseClientPool manages API clients
- OkHttp handles image downloads
- Massive memory savings (60-80%)

**Media Handling:**
- Videos: ExoPlayer with HLS support
- Images: Progressive loading with compression
- Audio: Standalone audio player

---

## 🛠️ Development Setup

### Step 1: Install Java 17

**Check Current Version:**
```bash
java -version
```

**If Not Java 17:**
```bash
# macOS (using Homebrew)
brew install openjdk@17

# Set JAVA_HOME
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
```

### Step 2: Android SDK Setup

**Required Components:**
- Android SDK Platform 36 (Android 15)
- Android SDK Build-Tools
- NDK 28.0.12433566 (for FFmpeg)
- Android Emulator (optional)

**Install via Android Studio:**
```
Tools → SDK Manager → SDK Platforms → Android 15.0 (API 36)
Tools → SDK Manager → SDK Tools → NDK, CMake
```

### Step 3: Project Configuration

**Local Properties:**
Create `local.properties` if it doesn't exist:
```properties
sdk.dir=/Users/[your-username]/Library/Android/sdk
```

**Gradle Sync:**
- Open project in Android Studio
- Wait for Gradle sync (downloads dependencies)
- Resolve any issues reported

### Step 4: Build Variants

**Debug Build:**
```bash
./gradlew assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk
```

**Release Build:**
```bash
./gradlew assembleRelease
# Output: app/build/outputs/apk/release/app-release.apk
```

---

## 📱 Running the App

### On Physical Device

**1. Enable Developer Options:**
- Settings → About Phone → Tap "Build Number" 7 times
- Settings → Developer Options → Enable "USB Debugging"

**2. Connect Device:**
```bash
adb devices  # Verify device connected
```

**3. Install:**
```bash
./gradlew installDebug
```

### On Emulator

**1. Create AVD:**
- Tools → AVD Manager → Create Virtual Device
- Choose: Pixel 6 Pro (or similar)
- API Level: 34 or higher
- Storage: 4GB+ recommended

**2. Start Emulator:**
```bash
emulator -avd [AVD_NAME]
```

**3. Install:**
```bash
./gradlew installDebug
```

---

## 🔍 Project Structure

### Key Directories

```
Tweet/
├── app/
│   ├── src/main/java/us/fireshare/tweet/
│   │   ├── chat/                    # Chat & messaging
│   │   ├── datamodel/               # Data models, DAOs
│   │   ├── navigation/              # Navigation logic
│   │   ├── network/                 # HproseClientPool 🆕
│   │   ├── profile/                 # User profiles
│   │   ├── service/                 # Background services
│   │   ├── tweet/                   # Tweet screens
│   │   ├── ui/                      # Common UI
│   │   ├── video/                   # Video processing
│   │   ├── viewmodel/               # ViewModels
│   │   ├── widget/                  # Reusable widgets
│   │   ├── HproseInstance.kt        # API hub
│   │   └── TweetApplication.kt      # App class
│   └── build.gradle.kts             # App config
├── docs/                            # Documentation 📚
├── gradle/
│   └── libs.versions.toml           # Dependencies
└── build.gradle.kts                 # Project config
```

### Important Files

**Start Here:**
- `TweetApplication.kt` - App initialization
- `HproseInstance.kt` - API operations
- `TweetFeedScreen.kt` - Main feed UI
- `VideoManager.kt` - Video management
- `ImageCacheManager.kt` - Image handling

---

## 🎯 Common Tasks

### Adding a New Feature

**1. Create Data Model (if needed):**
```kotlin
// In datamodel/
@Parcelize
data class MyFeature(
    val id: String,
    val name: String
) : Parcelable
```

**2. Add API Methods:**
```kotlin
// In HproseInstance.kt
suspend fun getMyFeature(id: String): MyFeature? {
    val params = mapOf("id" to id)
    return user.hproseService?.runMApp<Map<String, Any>>("get_feature", params)
        ?.let { MyFeature.from(it) }
}
```

**3. Create ViewModel:**
```kotlin
@HiltViewModel
class MyFeatureViewModel @Inject constructor() : ViewModel() {
    private val _state = MutableStateFlow<MyFeature?>(null)
    val state = _state.asStateFlow()
    
    fun load() = viewModelScope.launch {
        _state.value = HproseInstance.getMyFeature(id)
    }
}
```

**4. Create UI:**
```kotlin
@Composable
fun MyFeatureScreen(viewModel: MyFeatureViewModel = hiltViewModel()) {
    val feature by viewModel.state.collectAsState()
    
    LaunchedEffect(Unit) {
        viewModel.load()
    }
    
    // UI code
}
```

---

### Debugging

**View Logs:**
```bash
# All app logs
adb logcat | grep "us.fireshare.tweet"

# Specific component
adb logcat | grep "HproseClientPool"
adb logcat | grep "getTweetFeed"
adb logcat | grep "VideoManager"
```

**Check Pool Stats:**
```kotlin
// Add to any ViewModel or Activity
Timber.d(HproseClientPool.getPoolStats().toString())
```

**Monitor Memory:**
```bash
adb shell dumpsys meminfo us.fireshare.tweet.debug
```

---

### Making Changes

**1. Create Feature Branch:**
```bash
git checkout -b feature/my-feature
```

**2. Make Changes:**
- Edit code in Android Studio
- Follow existing patterns
- Add Timber logging

**3. Test:**
```bash
# Build
./gradlew assembleDebug

# Run tests
./gradlew test

# Install and test manually
./gradlew installDebug
```

**4. Commit:**
```bash
git add .
git commit -m "Add: My feature description"
```

---

## 🐛 Troubleshooting

### Build Fails with Java Error

**Problem:** `java.lang.IllegalArgumentException: 25`

**Solution:**
```bash
# Install Java 17
brew install openjdk@17

# Verify
java -version  # Should show 17.x.x
```

### Gradle Sync Fails

**Problem:** Dependencies not resolving

**Solution:**
```bash
# Clear Gradle cache
./gradlew clean
./gradlew --stop

# Remove .gradle folder
rm -rf .gradle

# Sync again
./gradlew build
```

### App Crashes on Launch

**Check Logs:**
```bash
adb logcat | grep -E "AndroidRuntime|FATAL"
```

**Common Causes:**
1. Missing Firebase config (`google-services.json`)
2. Network permissions
3. Database migration issues

**Solutions:**
- Ensure `google-services.json` exists
- Uninstall and reinstall app
- Check logcat for specific errors

### Videos Not Playing

**Check:**
1. FFmpeg libraries included (`libs/` folder)
2. Network connectivity
3. Video URL format
4. VideoManager logs

```bash
adb logcat | grep "VideoManager"
```

### Images Not Loading

**Check:**
1. OkHttp dependency added
2. Network permissions
3. Image URL format
4. ImageCacheManager logs

```bash
adb logcat | grep "ImageCacheManager"
```

---

## 📖 Learning Path

### Week 1: Understanding

**Day 1-2:** Project Overview
- [ ] Read README.md
- [ ] Review PROJECT_STATUS.md
- [ ] Build and run the app
- [ ] Explore main screens

**Day 3-4:** Architecture
- [ ] Study TECHNICAL_ARCHITECTURE.md
- [ ] Review HproseInstance.kt
- [ ] Understand VideoManager.kt
- [ ] Review data models

**Day 5:** Recent Changes
- [ ] Read RECENT_CHANGES.md
- [ ] Review CONNECTION_POOLING report
- [ ] Understand new optimizations

---

### Week 2: Contributing

**Day 1-2:** Code Exploration
- [ ] Browse tweet/ package (UI screens)
- [ ] Review viewmodel/ package
- [ ] Study widget/ package (reusable components)

**Day 3-4:** Make Small Changes
- [ ] Fix a simple UI bug
- [ ] Add a small feature
- [ ] Write unit test

**Day 5:** Integration
- [ ] Make larger changes
- [ ] Test thoroughly
- [ ] Update documentation

---

### Week 3: Mastery

**Day 1-2:** Deep Dive
- [ ] Study network layer (HproseClientPool)
- [ ] Understand video processing
- [ ] Review database schema

**Day 3-5:** Major Feature
- [ ] Design new feature
- [ ] Implement with proper patterns
- [ ] Write comprehensive tests
- [ ] Document changes

---

## 🎓 Key Concepts Explained

### Distributed Architecture

**What is a Node?**
- A server running Leither OS
- Serves data for specific users
- Has unique baseUrl (e.g., `http://125.229.161.122:8080`)

**Why Connection Pooling?**
- Multiple users on same node share one client
- Reduces memory from 200 clients to 20 clients (for 100 users on 10 nodes)
- Connections are reused, reducing network overhead

---

### Hprose RPC

**What is Hprose?**
- RPC framework for remote procedure calls
- Similar to REST but more efficient
- Supports multiple protocols (HTTP, WebSocket, TCP)

**How We Use It:**
```kotlin
// API call
val response = user.hproseService?.runMApp<Map<String, Any>>(
    entry = "get_tweet_feed",
    params = mapOf("pn" to 0, "ps" to 10)
)
```

---

### HLS Video Format

**What is HLS?**
- HTTP Live Streaming
- Adaptive bitrate streaming
- Supported by ExoPlayer

**Why Multi-Resolution?**
- 720p for good quality
- 480p for slower networks
- Player switches automatically

**Segment Naming:**
- `segment000.ts`, `segment001.ts`, etc.
- Standard web streaming convention

---

### Jetpack Compose

**Declarative UI:**
```kotlin
@Composable
fun Greeting(name: String) {
    Text("Hello, $name!")
}
```

**State Management:**
```kotlin
var count by remember { mutableStateOf(0) }
Button(onClick = { count++ }) {
    Text("Clicked $count times")
}
```

---

## 🔗 Useful Resources

### Official Documentation
- [Kotlin Coroutines](https://kotlinlang.org/docs/coroutines-overview.html)
- [Jetpack Compose](https://developer.android.com/jetpack/compose)
- [Android Architecture](https://developer.android.com/topic/architecture)
- [ExoPlayer](https://developer.android.com/media/media3/exoplayer)

### Internal Documentation
- [INDEX.md](INDEX.md) - Complete doc index
- [TECHNICAL_ARCHITECTURE.md](TECHNICAL_ARCHITECTURE.md) - Architecture deep dive
- [RECENT_CHANGES.md](RECENT_CHANGES.md) - Latest updates

### Tools
- [Android Debug Bridge (adb)](https://developer.android.com/tools/adb)
- [Gradle Build Tool](https://gradle.org/)
- [Git Version Control](https://git-scm.com/)

---

## ⚡ Pro Tips

### Development Workflow

**Fast Iteration:**
```bash
# Quick build and install
./gradlew installDebug && adb shell am start -n us.fireshare.tweet.debug/us.fireshare.tweet.TweetActivity
```

**Watch Logs:**
```bash
# In separate terminal
adb logcat | grep -E "MyTag|Error|Exception"
```

**Clear App Data:**
```bash
adb shell pm clear us.fireshare.tweet.debug
```

### Code Navigation

**Find Usage:**
- Cmd+Click (Mac) / Ctrl+Click (Windows/Linux)
- Cmd+B to go to declaration
- Cmd+Option+B to go to implementation

**Search:**
- Cmd+Shift+F: Find in files
- Cmd+O: Find class
- Cmd+Shift+O: Find file

### Debugging

**Breakpoints:**
- Click line number gutter
- Debug with: Run → Debug 'app'

**Logcat Filtering:**
- Use package name filter: `us.fireshare.tweet`
- Use tag filter for specific components
- Use level filter (Error, Warn, Info, Debug)

---

## 🎯 Your First Contribution

### Easy Tasks (1-2 hours)

1. **Fix a typo in UI strings**
   - File: `app/src/main/res/values/strings.xml`
   - Build and test
   - Submit PR

2. **Add a log statement**
   - Find interesting code path
   - Add: `Timber.tag("MyFeature").d("Message")`
   - Verify log appears

3. **Update documentation**
   - Fix outdated info
   - Add clarifications
   - Improve formatting

### Medium Tasks (1 day)

1. **Add a new UI component**
   - Create Composable function
   - Use existing patterns
   - Add to appropriate screen

2. **Optimize a function**
   - Find inefficient code
   - Improve performance
   - Measure improvement

3. **Fix a known issue**
   - Check issue tracker
   - Fix and test
   - Update docs

### Advanced Tasks (2-3 days)

1. **Add a new feature**
   - Design data model
   - Implement API layer
   - Create ViewModel
   - Build UI
   - Write tests
   - Document

2. **Refactor a component**
   - Identify code smells
   - Plan refactoring
   - Implement with tests
   - Verify no regressions

3. **Performance optimization**
   - Profile app
   - Identify bottleneck
   - Implement optimization
   - Measure improvement
   - Document results

---

## 💡 Code Examples

### Making an API Call

```kotlin
// In ViewModel
fun loadTweets() = viewModelScope.launch {
    try {
        val tweets = HproseInstance.getTweetFeed(
            user = HproseInstance.appUser,
            pageNumber = 0,
            pageSize = 10
        )
        _tweetsState.value = tweets
    } catch (e: Exception) {
        Timber.e(e, "Failed to load tweets")
        _errorState.value = e.message
    }
}
```

### Loading an Image

```kotlin
// In Composable
LaunchedEffect(imageId) {
    val bitmap = ImageCacheManager.loadImage(
        context = context,
        imageUrl = imageUrl,
        mid = imageId,
        isVisible = true
    )
    bitmap?.let { loadedBitmap = it }
}
```

### Playing a Video

```kotlin
// In Composable
VideoPreview(
    url = videoUrl,
    modifier = Modifier.fillMaxWidth(),
    index = 0,
    autoPlay = true,
    inPreviewGrid = false,
    callback = { /* handle click */ },
    videoMid = videoId,
    videoType = MediaType.HLS_VIDEO,
    useIndependentMuteState = false // Use global mute
)
```

### Using Connection Pool

```kotlin
// Automatic via User object
val service = user.hproseService // Gets from pool automatically

// Direct access (rare)
val service = HproseClientPool.getRegularClient(baseUrl)

// Check pool stats
val stats = HproseClientPool.getPoolStats()
Timber.d("Pool stats: $stats")
```

---

## 🧪 Testing

### Running Tests

**All Tests:**
```bash
./gradlew test
```

**Specific Test:**
```bash
./gradlew test --tests "SpecificTestClass"
```

**Android Tests:**
```bash
./gradlew connectedAndroidTest
```

### Writing Tests

**Unit Test Example:**
```kotlin
class MyViewModelTest {
    @Test
    fun `load tweets should update state`() = runTest {
        val viewModel = MyViewModel()
        viewModel.loadTweets()
        
        val tweets = viewModel.tweetsState.value
        assertTrue(tweets.isNotEmpty())
    }
}
```

---

## 🔐 Configuration

### Firebase Setup

**File:** `app/google-services.json`
- Required for Firebase services
- Get from Firebase Console
- Already configured for project

### API Configuration

**Debug Build:**
```kotlin
buildConfigField("String", "BASE_URL", "\"twbe.fireshare.uk\"")
buildConfigField("String", "APP_ID", "\"d4lRyhABgqOnqY4bURSm_T-4FZ4\"")
```

**Release Build:**
```kotlin
buildConfigField("String", "BASE_URL", "\"tweet.fireshare.uk\"")
buildConfigField("String", "APP_ID", "\"heWgeGkeBX2gaENbIBS_Iy1mdTS\"")
```

---

## 📞 Getting Help

### Check Documentation
1. [INDEX.md](INDEX.md) - Find relevant docs
2. [TECHNICAL_ARCHITECTURE.md](TECHNICAL_ARCHITECTURE.md) - Architecture details
3. [PROJECT_STATUS.md](PROJECT_STATUS.md) - Current state

### Common Issues
- See PROJECT_STATUS.md → Known Issues section
- Check RECENT_CHANGES.md for recent bug fixes

### Code Search
```bash
# Find all usages of a function
grep -r "functionName" app/src/

# Find specific pattern
grep -r "VideoManager\." app/src/ | grep "\.kt:"
```

---

## ✅ Development Checklist

### Before Coding
- [ ] Pull latest from main branch
- [ ] Read related documentation
- [ ] Understand existing patterns
- [ ] Create feature branch

### While Coding
- [ ] Follow existing code style
- [ ] Add Timber logging
- [ ] Handle errors gracefully
- [ ] Write comments for complex logic
- [ ] Use dependency injection (Hilt)

### After Coding
- [ ] Build successful (zero errors)
- [ ] Test on real device
- [ ] Check logcat for issues
- [ ] Update documentation
- [ ] Add entry to RECENT_CHANGES.md (if significant)
- [ ] Create PR with clear description

---

## 🎉 Success Indicators

**You're Ready When:**
- ✅ App builds successfully
- ✅ Can navigate all main screens
- ✅ Understand connection pooling concept
- ✅ Can find code for specific features
- ✅ Can make small changes confidently

**Keep Learning:**
- Read code regularly
- Try making changes
- Ask questions
- Review documentation
- Study design patterns

---

## 🚦 Next Steps

### After Quick Start

**Immediate:**
1. ✅ App running successfully
2. Read [TECHNICAL_ARCHITECTURE.md](TECHNICAL_ARCHITECTURE.md)
3. Explore codebase in Android Studio
4. Make a small change and test

**This Week:**
1. Read all documentation in [INDEX.md](INDEX.md)
2. Understand key components
3. Fix a small bug or add small feature
4. Review code with team

**This Month:**
1. Master one subsystem (Video, Network, or UI)
2. Contribute meaningful features
3. Help with code reviews
4. Improve documentation

---

## 📊 Project Statistics

### Current State
- **Lines of Code:** ~35,000+
- **Kotlin Files:** ~100
- **Compose Screens:** ~15
- **ViewModels:** ~10
- **Background Workers:** 2
- **Documentation Files:** 27

### Build Times
- **Clean Build:** ~40-60 seconds
- **Incremental Build:** ~5-10 seconds
- **Install:** ~5-10 seconds

### App Size
- **Debug APK:** ~85MB
- **Release APK:** ~80MB (minified)
- **Installed Size:** ~120MB

---

## 🎓 Additional Resources

### Kotlin
- [Kotlin Documentation](https://kotlinlang.org/docs/home.html)
- [Kotlin Coroutines Guide](https://kotlinlang.org/docs/coroutines-guide.html)

### Android
- [Android Developers](https://developer.android.com/)
- [Jetpack Compose](https://developer.android.com/jetpack/compose)
- [Material Design 3](https://m3.material.io/)

### Libraries
- [Hilt](https://dagger.dev/hilt/)
- [Room](https://developer.android.com/training/data-storage/room)
- [OkHttp](https://square.github.io/okhttp/)
- [ExoPlayer](https://developer.android.com/media/media3/exoplayer)

---

## 🌟 Best Practices

### Code Quality
- Use meaningful variable names
- Keep functions small (<50 lines)
- Add comments for complex logic
- Follow existing patterns
- Use Timber for logging (never println)

### Performance
- Use `remember` in Composables
- Avoid recomposition triggers
- Use `LaunchedEffect` with proper keys
- Profile before optimizing
- Measure improvements

### Testing
- Write unit tests for ViewModels
- Test edge cases
- Use real devices for integration testing
- Check memory leaks (LeakCanary if needed)

---

**Welcome to the Tweet development team!** 🎉

For questions or clarifications, refer to the comprehensive documentation in the `docs/` folder or reach out to the team.

---

**Quick Start Guide Version:** 1.0  
**Status:** ✅ Complete  
**Feedback:** Welcome!

---

**End of Quick Start Guide**

