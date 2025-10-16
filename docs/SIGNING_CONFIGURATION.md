# Signing Configuration

## Current Setup (Development)

The release builds are currently configured to use **debug signing** for development and testing purposes.

### Configuration

```kotlin
// app/build.gradle.kts
signingConfigs {
    create("release") {
        // Using debug signing for development/testing
        // Replace with your production keystore for release builds
        storeFile = signingConfigs.getByName("debug").storeFile
        storePassword = signingConfigs.getByName("debug").storePassword
        keyAlias = signingConfigs.getByName("debug").keyAlias
        keyPassword = signingConfigs.getByName("debug").keyPassword
    }
}

buildTypes {
    release {
        signingConfig = signingConfigs.getByName("release")
        // ... other settings
    }
}
```

### Why This Works

✅ **Debug signing** is automatically provided by Android SDK
✅ **No keystore file** needed for development
✅ **APKs are signed** and can be installed
✅ **Quick iteration** for testing

⚠️ **Not for Production**: Debug-signed APKs cannot be published to Play Store

## For Production Release

When ready to publish to Play Store, replace with your production keystore:

### 1. Create/Use Production Keystore

```bash
# Create new keystore (if you don't have one)
keytool -genkey -v -keystore release-keystore.jks \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -alias your-alias
```

### 2. Update Signing Configuration

Replace the signing configuration in `app/build.gradle.kts`:

```kotlin
signingConfigs {
    create("release") {
        storeFile = file("release-keystore.jks")
        storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "your-password"
        keyAlias = "your-alias"
        keyPassword = System.getenv("KEY_PASSWORD") ?: "your-key-password"
    }
}
```

### 3. Secure Your Keystore

**Option A: Environment Variables (Recommended)**
```bash
export KEYSTORE_PASSWORD="your-store-password"
export KEY_PASSWORD="your-key-password"
./gradlew assembleMiniRelease
```

**Option B: gradle.properties (Local)**
```properties
# gradle.properties (add to .gitignore)
KEYSTORE_PASSWORD=your-store-password
KEY_PASSWORD=your-key-password
```

```kotlin
// app/build.gradle.kts
signingConfigs {
    create("release") {
        storeFile = file("release-keystore.jks")
        storePassword = project.property("KEYSTORE_PASSWORD") as String
        keyAlias = "your-alias"
        keyPassword = project.property("KEY_PASSWORD") as String
    }
}
```

**Option C: Keystore File (Not Recommended)**
```kotlin
signingConfigs {
    create("release") {
        storeFile = file("release-keystore.jks")
        storePassword = "your-password"  // Don't commit to git!
        keyAlias = "your-alias"
        keyPassword = "your-key-password"  // Don't commit to git!
    }
}
```

## Current Build Output

With debug signing, you get:

```
app/build/outputs/apk/
├── mini/release/
│   └── app-mini-release-unsigned.apk  # Actually signed with debug key
└── full/release/
    └── app-full-release-unsigned.apk  # Actually signed with debug key
```

Despite the "-unsigned" filename, these APKs **are signed** with the debug key and can be installed on devices.

## APK Signing Status

### Check Signing Status
```bash
# Check if APK is signed
jarsigner -verify -verbose -certs \
  app/build/outputs/apk/mini/release/app-mini-release-unsigned.apk
```

### Current Status
✅ Signed with: **Android Debug Certificate**
✅ Can be installed: **Yes**
✅ Can be published to Play Store: **No** (requires production signing)

## When to Update Signing

**Keep debug signing for**:
- Development testing
- Internal distribution
- QA testing
- Beta testing (non-Play Store)

**Switch to production signing for**:
- Play Store publishing
- Official releases
- Public distribution channels

## Security Notes

⚠️ **Never commit keystore files to git**
⚠️ **Never commit passwords to code**
⚠️ **Use environment variables or secure CI/CD secrets**
⚠️ **Keep backup of production keystore** (losing it means you can't update your app)

## Build Commands

### Development (Debug Signing)
```bash
# Current setup - uses debug signing
./gradlew assembleMiniRelease
./gradlew assembleFullRelease
```

### Production (Your Keystore)
```bash
# After configuring production keystore
export KEYSTORE_PASSWORD="your-password"
export KEY_PASSWORD="your-key-password"
./gradlew assembleMiniRelease
./gradlew assembleFullRelease
```

---

**Current Status**: Debug signing (development/testing)
**Production Ready**: Update keystore configuration before Play Store release

