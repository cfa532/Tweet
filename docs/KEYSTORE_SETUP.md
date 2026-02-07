# Keystore Setup Guide

## Quick Setup

### Step 1: Copy Your Keystore File

Copy your keystore file to the project root directory:

```bash
cp /path/to/your-keystore.jks /Users/cfa532/Documents/GitHub/Tweet/
```

### Step 2: Create keystore.properties

Create a file named `keystore.properties` in the project root:

```bash
cd /Users/cfa532/Documents/GitHub/Tweet
nano keystore.properties
```

Add this content (replace with your actual values):

```properties
KEYSTORE_FILE=your-keystore.jks
KEYSTORE_PASSWORD=your_keystore_password
KEY_ALIAS=your_key_alias
KEY_PASSWORD=your_key_password
```

Save and exit (Ctrl+X, then Y, then Enter)

### Step 3: Add to .gitignore

Make sure keystore files are NOT committed to git:

```bash
echo "keystore.properties" >> .gitignore
echo "*.jks" >> .gitignore
echo "*.keystore" >> .gitignore
```

### Step 4: Build

Now both mini and full versions will use your keystore:

```bash
# Mini version (10 MB, signed with your keystore)
./gradlew assembleMiniRelease

# Full version (54 MB, signed with your keystore)
./gradlew assembleFullRelease
```

## Configuration Details

### How It Works

The `app/build.gradle.kts` now:

1. **Loads** `keystore.properties` file
2. **Reads** your keystore credentials
3. **Applies** to release builds (both mini and full)
4. **Fallback** to debug signing if keystore.properties doesn't exist

### Build Configuration

```kotlin
// Load keystore properties
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = java.util.Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(java.io.FileInputStream(keystorePropertiesFile))
}

signingConfigs {
    create("release") {
        if (keystorePropertiesFile.exists()) {
            // Use your keystore
            storeFile = file(keystoreProperties["KEYSTORE_FILE"])
            storePassword = keystoreProperties["KEYSTORE_PASSWORD"]
            keyAlias = keystoreProperties["KEY_ALIAS"]
            keyPassword = keystoreProperties["KEY_PASSWORD"]
        } else {
            // Fallback to debug signing
            storeFile = signingConfigs.getByName("debug").storeFile
            // ...
        }
    }
}

buildTypes {
    release {
        signingConfig = signingConfigs.getByName("release")
        // ...
    }
}
```

## File Structure

```
/Users/cfa532/Documents/GitHub/Tweet/
├── keystore.properties          # Your credentials (NEVER commit!)
├── your-keystore.jks           # Your keystore file (NEVER commit!)
├── app/
│   └── build.gradle.kts        # Reads keystore.properties
└── .gitignore                  # Excludes keystore files
```

## Example keystore.properties

```properties
KEYSTORE_FILE=fireshare-release.jks
KEYSTORE_PASSWORD=MySecurePassword123
KEY_ALIAS=fireshare
KEY_PASSWORD=MyKeyPassword456
```

## Security Best Practices

### ✅ DO
- Keep keystore file in project root
- Use `keystore.properties` for credentials
- Add to `.gitignore`
- Backup keystore file securely
- Use strong passwords

### ❌ DON'T
- Commit keystore to git
- Commit passwords to git
- Share keystore publicly
- Hard-code passwords in build files
- Lose your keystore (can't update app!)

## Verify Signing

Check if APK is signed with your keystore:

```bash
# Check mini version
jarsigner -verify -verbose -certs \
  app/build/outputs/apk/mini/release/app-mini-release.apk

# Check full version
jarsigner -verify -verbose -certs \
  app/build/outputs/apk/full/release/app-full-release.apk
```

Should show your certificate details, not "Android Debug".

## Build Output

After building with your keystore:

```
app/build/outputs/apk/
├── mini/release/
│   └── app-mini-release.apk     # Signed with YOUR keystore
└── full/release/
    └── app-full-release.apk     # Signed with YOUR keystore
```

Both APKs ready for Play Store or distribution!

## Troubleshooting

### "Keystore file not found"
- Check `KEYSTORE_FILE` path in keystore.properties
- Path is relative to project root
- Use just filename if keystore is in root: `my-keystore.jks`

### "Invalid keystore password"
- Verify password in keystore.properties
- Check for extra spaces or quotes
- Password is case-sensitive

### "Key alias not found"
- Verify alias name matches keystore
- List aliases: `keytool -list -v -keystore your-keystore.jks`

### Build still uses debug signing
- Check `keystore.properties` exists in project root
- Verify file format (no extra spaces, correct property names)
- Check logs: `./gradlew assembleMiniRelease --info | grep keystore`

## CI/CD Setup (Optional)

For automated builds, use environment variables:

```bash
export KEYSTORE_FILE="fireshare-release.jks"
export KEYSTORE_PASSWORD="your_password"
export KEY_ALIAS="your_alias"
export KEY_PASSWORD="your_key_password"

./gradlew assembleMiniRelease assembleFullRelease
```

---

**Ready to build**: Just create `keystore.properties` with your credentials!

