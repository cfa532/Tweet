# Flavor Editing Guide

## Overview
This guide explains how to edit code for specific build variants (flavors) and how to edit common code that affects all variants.

## Build Variant Structure

### Current Variants
- **`full`**: Complete version with upgrade functionality
- **`mini`**: Lightweight version without FFmpeg
- **`play`**: Google Play Store version without upgrade functionality

### Source Set Hierarchy
```
app/src/
├── main/           # Common code (shared by all variants)
├── full/           # Full-specific code
├── mini/           # Mini-specific code
└── play/           # Play-specific code
```

## How to Edit Flavor-Specific Code

### Method 1: Create Flavor-Specific Files (Recommended for Major Changes)

#### Example: Edit TweetActivity.kt for Play Variant Only

1. **Create the directory structure:**
   ```bash
   mkdir -p app/src/play/java/us/fireshare/tweet/
   ```

2. **Copy the common file to the flavor directory:**
   ```bash
   cp app/src/main/java/us/fireshare/tweet/TweetActivity.kt app/src/play/java/us/fireshare/tweet/TweetActivity.kt
   ```

3. **Edit the flavor-specific file:**
   ```bash
   # Edit app/src/play/java/us/fireshare/tweet/TweetActivity.kt
   # Make your changes - this will ONLY affect the play variant
   ```

4. **Build and test:**
   ```bash
   ./gradlew assemblePlayRelease
   ```

#### How It Works:
- **Play Build**: Uses `app/src/play/java/us/fireshare/tweet/TweetActivity.kt`
- **Full/Mini Builds**: Use `app/src/main/java/us/fireshare/tweet/TweetActivity.kt`
- **Result**: Play variant has different behavior, others unchanged

### Method 2: Use BuildConfig Conditional Logic (Recommended for Small Changes)

#### Example: Different Deep Links for Play Variant

1. **Edit the common file:**
   ```kotlin
   // In app/src/main/java/us/fireshare/tweet/viewmodel/TweetViewModel.kt
   val deepLink = if (BuildConfig.IS_PLAY_VERSION) {
       "http://gplay.fireshare.us/tweet/${tweet.mid}/${tweet.authorId}"
   } else {
       "http://${map["domain"]}/tweet/${tweet.mid}/${tweet.authorId}"
   }
   ```

2. **Add BuildConfig import:**
   ```kotlin
   import us.fireshare.tweet.BuildConfig
   ```

3. **Build all variants:**
   ```bash
   ./gradlew assembleFullRelease assembleMiniRelease assemblePlayRelease
   ```

#### How It Works:
- **All Builds**: Use the same file
- **Runtime Behavior**: Different based on `BuildConfig.IS_PLAY_VERSION`
- **Result**: Play variant uses different deep links, others use server domain

## How to Edit Common Code

### Editing Shared Code

#### Example: Add a New Feature to All Variants

1. **Edit the common file:**
   ```kotlin
   // In app/src/main/java/us/fireshare/tweet/SomeClass.kt
   fun newFeature() {
       // This will affect ALL variants
   }
   ```

2. **Build all variants:**
   ```bash
   ./gradlew assembleFullRelease assembleMiniRelease assemblePlayRelease
   ```

#### How It Works:
- **All Builds**: Use the same file from `app/src/main/`
- **Result**: All variants get the new feature

### Editing Resources (Strings, Layouts, etc.)

#### Example: Add a New String Resource

1. **Edit common strings:**
   ```xml
   <!-- In app/src/main/res/values/strings.xml -->
   <string name="new_feature">New Feature</string>
   ```

2. **Add flavor-specific strings:**
   ```xml
   <!-- In app/src/play/res/values/strings.xml -->
   <string name="new_feature">Play Store Feature</string>
   ```

#### How It Works:
- **Play Build**: Uses `app/src/play/res/values/strings.xml`
- **Other Builds**: Use `app/src/main/res/values/strings.xml`

## BuildConfig Flags

### Available Flags
```kotlin
// In app/build.gradle.kts
productFlavors {
    create("full") {
        buildConfigField("Boolean", "IS_MINI_VERSION", "false")
        buildConfigField("Boolean", "IS_PLAY_VERSION", "false")
    }
    
    create("mini") {
        buildConfigField("Boolean", "IS_MINI_VERSION", "true")
        buildConfigField("Boolean", "IS_PLAY_VERSION", "false")
    }
    
    create("play") {
        buildConfigField("Boolean", "IS_MINI_VERSION", "false")
        buildConfigField("Boolean", "IS_PLAY_VERSION", "true")
    }
}
```

### Using BuildConfig in Code
```kotlin
// Check if current build is mini version
if (BuildConfig.IS_MINI_VERSION) {
    // Mini-specific code
}

// Check if current build is play version
if (BuildConfig.IS_PLAY_VERSION) {
    // Play-specific code
}

// Check if current build is full version
if (!BuildConfig.IS_MINI_VERSION && !BuildConfig.IS_PLAY_VERSION) {
    // Full-specific code
}
```

## Practical Examples

### Example 1: Add Play-Specific Feature

#### Scenario: Add a "Rate App" button only for Play variant

**Method 1: Flavor-Specific File**
```bash
# Create play-specific activity
mkdir -p app/src/play/java/us/fireshare/tweet/
cp app/src/main/java/us/fireshare/tweet/MainActivity.kt app/src/play/java/us/fireshare/tweet/MainActivity.kt

# Edit app/src/play/java/us/fireshare/tweet/MainActivity.kt
# Add rate app button code
```

**Method 2: BuildConfig Conditional**
```kotlin
// In app/src/main/java/us/fireshare/tweet/MainActivity.kt
if (BuildConfig.IS_PLAY_VERSION) {
    // Show rate app button
    showRateAppButton()
}
```

### Example 2: Different API Endpoints

#### Scenario: Use different API endpoints for different variants

```kotlin
// In app/src/main/java/us/fireshare/tweet/ApiClient.kt
val baseUrl = when {
    BuildConfig.IS_PLAY_VERSION -> "https://api.play.fireshare.us"
    BuildConfig.IS_MINI_VERSION -> "https://api.mini.fireshare.us"
    else -> "https://api.fireshare.us"
}
```

### Example 3: Different Dependencies

#### Scenario: Add analytics only for Play variant

```kotlin
// In app/build.gradle.kts
dependencies {
    // Common dependencies
    implementation("com.example:common-lib:1.0.0")
    
    // Play-specific dependencies
    "playImplementation"("com.google.firebase:firebase-analytics:21.0.0")
}
```

## Best Practices

### When to Use Flavor-Specific Files
- **Major structural changes** (different activities, fragments)
- **Completely different behavior** (no upgrade system)
- **Different dependencies** (different libraries)
- **Different resources** (different layouts, strings)

### When to Use BuildConfig Conditionals
- **Small behavioral differences** (different URLs, flags)
- **Feature toggles** (enable/disable features)
- **Configuration differences** (different settings)
- **Minor UI changes** (different text, colors)

### File Organization
```
app/src/
├── main/                    # Common code
│   ├── java/               # Shared Java/Kotlin files
│   ├── res/                # Shared resources
│   └── AndroidManifest.xml # Base manifest
├── full/                   # Full-specific overrides
│   ├── java/               # Full-specific Java/Kotlin files
│   └── res/                # Full-specific resources
├── mini/                   # Mini-specific overrides
│   ├── java/               # Mini-specific Java/Kotlin files
│   └── res/                # Mini-specific resources
└── play/                   # Play-specific overrides
    ├── java/               # Play-specific Java/Kotlin files
    ├── res/                # Play-specific resources
    └── AndroidManifest.xml # Play-specific manifest
```

## Testing Your Changes

### Test Individual Variants
```bash
# Test full variant
./gradlew assembleFullRelease

# Test mini variant
./gradlew assembleMiniRelease

# Test play variant
./gradlew assemblePlayRelease
```

### Test All Variants
```bash
# Build all variants
./gradlew assembleFullRelease assembleMiniRelease assemblePlayRelease
```

### Verify Changes
```bash
# Check if your changes are in the correct variant
# Install and test each variant to ensure:
# 1. Your changes appear in the intended variant
# 2. Other variants are not affected
# 3. All variants build successfully
```

## Troubleshooting

### Common Issues

#### 1. Redeclaration Errors
```
e: Redeclaration: class MyClass
```
**Solution**: This is normal when using flavor-specific files. The build system correctly detects both versions.

#### 2. BuildConfig Not Found
```
e: Unresolved reference 'BuildConfig'
```
**Solution**: Add import:
```kotlin
import us.fireshare.tweet.BuildConfig
```

#### 3. Changes Not Appearing
**Solution**: 
1. Check if you're editing the correct file
2. Ensure you're building the correct variant
3. Clean and rebuild: `./gradlew clean assemblePlayRelease`

#### 4. Wrong Variant Affected
**Solution**:
1. Check file location (main vs flavor-specific)
2. Verify BuildConfig conditions
3. Test each variant individually

## Summary

| Change Type | Method | Example |
|-------------|--------|---------|
| **Major structural changes** | Flavor-specific files | Different activities |
| **Small behavioral differences** | BuildConfig conditionals | Different URLs |
| **Different dependencies** | Flavor-specific files | Different libraries |
| **Feature toggles** | BuildConfig conditionals | Enable/disable features |
| **Different resources** | Flavor-specific files | Different layouts |
| **Configuration differences** | BuildConfig conditionals | Different settings |

---

**Remember**: 
- **Flavor-specific files** override common files
- **BuildConfig conditionals** provide runtime differences
- **Test each variant** to ensure changes work as expected
- **Common code changes** affect all variants

