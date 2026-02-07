# Quick Keystore Setup - 3 Steps

## Step 1: Copy Keystore to Project Root

```bash
# Copy your keystore file to the project directory
cp /path/to/your-keystore.jks /Users/cfa532/Documents/GitHub/Tweet/
```

## Step 2: Create keystore.properties

Create file: `/Users/cfa532/Documents/GitHub/Tweet/keystore.properties`

```properties
KEYSTORE_FILE=your-keystore.jks
KEYSTORE_PASSWORD=your_keystore_password
KEY_ALIAS=your_key_alias
KEY_PASSWORD=your_key_password
```

Replace:
- `your-keystore.jks` - with your actual keystore filename
- `your_keystore_password` - with your keystore password
- `your_key_alias` - with your key alias
- `your_key_password` - with your key password

**Example**:
```properties
KEYSTORE_FILE=fireshare-release.jks
KEYSTORE_PASSWORD=MyStorePass123
KEY_ALIAS=fireshare
KEY_PASSWORD=MyKeyPass456
```

## Step 3: Build

```bash
# Build mini version (signed with your keystore)
./gradlew assembleMiniRelease

# Build full version (signed with your keystore)
./gradlew assembleFullRelease
```

Done! Both APKs are now signed with your production keystore.

## Output Files

```
app/build/outputs/apk/
├── mini/release/app-mini-release.apk     # Signed with YOUR keystore
└── full/release/app-full-release.apk     # Signed with YOUR keystore
```

## Security

✅ Keystore files added to `.gitignore`
✅ Won't be committed to git
✅ Safe to use your production keystore

## If You Don't Have keystore.properties

No problem! The build will automatically use debug signing (for testing):

```bash
./gradlew assembleMiniRelease  # Uses debug signing
```

---

**Next**: Create `keystore.properties`, then rebuild for production-signed APKs

