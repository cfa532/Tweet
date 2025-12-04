# Deep Link Implementation

## Overview
Deep link support has been implemented and tested for the Tweet app. The implementation allows users to open specific tweets directly via URLs.

## Implementation Details

### 1. AndroidManifest Configuration
- **Intent Filter**: Configured to handle deep links with `android:autoVerify="true"`
- **URL Pattern**: `http://fireshare.uk/tweet/{tweetId}/{authorId}` and `https://fireshare.uk/tweet/{tweetId}/{authorId}`
- **Launch Mode**: Added `android:launchMode="singleTop"` to ensure `onNewIntent()` is called when app is already running

**Location**: `app/src/main/AndroidManifest.xml`

```xml
<intent-filter android:autoVerify="true">
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <category android:name="android.intent.category.BROWSABLE" />
    <data android:scheme="http" />
    <data android:scheme="https" />
    <data android:host="fireshare.uk" />
    <data android:pathPattern="/tweet/.*" />
</intent-filter>
```

### 2. TweetActivity Changes
- **onNewIntent()**: Added to handle deep links when app is already running
- **Intent Tracking**: Added `currentIntent` state in `ActivityViewModel` to track the current intent
- **Initial Intent Handling**: Properly handles deep links on app startup

**Key Changes**:
- `onNewIntent()` method to handle deep links when app is running
- `handleIntent()` helper method to process deep link intents
- Intent state management via `ActivityViewModel.currentIntent`

### 3. Navigation Graph Updates
- **Deep Link Parsing**: Improved parsing with better error handling and logging
- **Navigation Handling**: Properly navigates to deep link destination on initial load and when app is running
- **Logging**: Added comprehensive logging for debugging

**Key Features**:
- `parseDeepLink()` function with validation and error handling
- Proper handling of initial load vs. runtime deep links
- Navigation with back stack management

### 4. Deep Link Route
- **Route Definition**: `NavTweet.DeepLink(tweetId: MimeiId, authorId: MimeiId)`
- **Screen**: Opens `TweetDetailScreen` with the specified tweet


## Real-World Usage

### How to Create and Share Deep Links

Deep links can be created and shared in various ways:

#### 1. **Sharing from Within the App**
When users share a tweet from the app, generate a deep link URL:
```
http://fireshare.uk/tweet/{tweetId}/{authorId}
```

**Example**:
```
http://fireshare.uk/tweet/WLCTRAPIWM8whdqSa7WtWlCCIMV/iFG4GC9r0fF22jYBCkuPThybzwO
```

#### 2. **Sharing via Web/Email/Social Media**
- Post the deep link URL on websites, forums, or social media
- Send via email or messaging apps
- Include in blog posts or articles
- Share in QR codes

#### 3. **Opening Deep Links**

**From Browser**:
- User clicks a link like `http://fireshare.uk/tweet/...` in Chrome, Firefox, etc.
- Android shows a dialog: "Open with Tweet app" or "Open in app"
- User selects the app → Opens directly to the tweet

**From Other Apps**:
- Links in messaging apps (WhatsApp, Telegram, etc.)
- Links in email clients
- Links in social media apps
- Any app that can handle URLs

**From QR Codes**:
- Generate QR code containing the deep link URL
- User scans with camera app → Opens Tweet app directly

**From Notifications**:
- Push notifications can include deep link URLs
- Tapping notification opens the specific tweet

### URL Format

Deep links follow this format:
```
http://fireshare.uk/tweet/{tweetId}/{authorId}
https://fireshare.uk/tweet/{tweetId}/{authorId}
```

**Components**:
- `fireshare.uk` - Domain (configured in AndroidManifest)
- `/tweet/` - Path prefix
- `{tweetId}` - Unique identifier for the tweet
- `{authorId}` - Unique identifier for the tweet author

**Example Valid Links**:
```
http://fireshare.uk/tweet/WLCTRAPIWM8whdqSa7WtWlCCIMV/iFG4GC9r0fF22jYBCkuPThybzwO
https://fireshare.uk/tweet/agvvgWJmmXtji5FLTt768Plu3He/uCQDqhZgCGw3zpXtNSWo9Ftyn7Q
```

### How It Works

1. **User clicks link** → Android system intercepts the URL
2. **System checks** → Finds Tweet app registered for `fireshare.uk/tweet/*`
3. **App opens** → If app not running, launches it; if running, brings to foreground
4. **Deep link parsed** → App extracts `tweetId` and `authorId` from URL
5. **Navigation** → App navigates to `TweetDetailScreen` with the tweet
6. **Data fetched** → App fetches tweet data and author information from server

### Testing

#### Manual Testing via ADB

1. **Test with app not running**:
```bash
adb shell am start -a android.intent.action.VIEW \
  -d "http://fireshare.uk/tweet/WLCTRAPIWM8whdqSa7WtWlCCIMV/iFG4GC9r0fF22jYBCkuPThybzwO" \
  us.fireshare.tweet.debug
```

2. **Test with app already running**:
   - Open the app first
   - Then run the same command above
   - The app should navigate to the tweet detail screen

3. **View logs**:
```bash
adb logcat -s DeepLink:TweetActivity:TweetViewModel:D
```

#### Using Test Script

A test script is available at `TEST_DEEP_LINK.sh`:

```bash
./TEST_DEEP_LINK.sh
```

The script will:
- Check if a device is connected
- Provide example commands
- Optionally run a test with user-provided tweet/author IDs

#### Testing in Browser

1. Open Chrome/Firefox on Android device
2. Navigate to: `http://fireshare.uk/tweet/WLCTRAPIWM8whdqSa7WtWlCCIMV/iFG4GC9r0fF22jYBCkuPThybzwO`
3. System should prompt to open in Tweet app
4. Select "Tweet" → App opens to the tweet

## Use Cases

### 1. **Social Media Sharing**
- User shares a tweet link on Twitter/X, Facebook, etc.
- Followers click the link → Opens directly in Tweet app

### 2. **Email/Newsletter**
- Include tweet links in newsletters or emails
- Recipients click → Opens tweet in app

### 3. **Website Integration**
- Embed tweet links on websites or blogs
- Visitors click → Opens tweet in app (if installed)

### 4. **Cross-App Sharing**
- Share tweet links in messaging apps
- Recipients click → Opens tweet in app

### 5. **QR Codes**
- Generate QR code with deep link URL
- Users scan → Opens tweet in app

### 6. **Push Notifications**
- Send notifications with deep link URLs
- User taps notification → Opens specific tweet

## Important Notes

### Invalid Users
- If a deep link contains an invalid `authorId`, the app will detect a redirect loop
- This is expected behavior - invalid users cause the server to return the same IP:port
- The app gracefully handles this by stopping retries

### App Not Installed - Current Behavior

**What Happens**:
1. User clicks deep link URL (e.g., `http://fireshare.uk/tweet/...`)
2. Android system checks for apps that can handle this URL
3. **If app is not installed**: 
   - URL opens in browser (Chrome, Firefox, etc.)
   - Browser attempts to load the URL
   - **Current limitation**: No web page exists at this URL, so user sees an error or blank page

**User Experience**:
- ❌ User sees browser error or blank page
- ❌ No way to view the tweet
- ❌ No prompt to install the app

### Recommended Solution: Web Fallback Page

To provide a better experience when the app is not installed, implement a web fallback:

#### Option 1: Web Page with Tweet Display
Create a web page at `http://fireshare.uk/tweet/{tweetId}/{authorId}` that:
- Displays the tweet content (text, images, etc.)
- Shows a "Download App" button linking to Play Store
- Provides basic tweet viewing functionality

**Implementation**:
- Server-side web page that fetches and displays tweet data
- Responsive design for mobile browsers
- "Open in App" button (only shows if app is installed)

#### Option 2: Redirect to App Store
Create a simple web page that:
- Immediately redirects to Google Play Store
- Or shows a landing page with app download link

**Example**:
```html
<!-- Redirect to Play Store -->
<meta http-equiv="refresh" content="0; url=https://play.google.com/store/apps/details?id=us.fireshare.tweet">
```

#### Option 3: Smart Link Service
Use a service like Branch.io or Firebase Dynamic Links that:
- Detects if app is installed
- Opens app if installed, web page if not
- Provides analytics and attribution

### App Already Running
- If app is already running, deep links will:
  - Bring app to foreground
  - Navigate to the tweet (clearing back stack to root)
  - Update the current screen

### Best Practices

1. **Always provide a web fallback** - Users without the app should see something useful
2. **Include app store link** - Make it easy for users to install the app
3. **Use HTTPS** - More secure and required for App Links verification
4. **Test both scenarios** - Test with app installed and not installed
5. **Consider universal links** - iOS uses different mechanism, may need separate handling

## Logging

Deep link events are logged with the `DeepLink` tag. Key log messages:

- `"Invalid deep link format: ..."` - On parsing errors (warnings only)
- Error logs for failed user/tweet fetches

## Error Handling

The implementation includes comprehensive error handling:

1. **Invalid URL Format**: Logs warning and ignores invalid URLs
2. **Missing Segments**: Validates that all required path segments are present
3. **Blank Values**: Checks that tweetId and authorId are not blank
4. **Null Intent**: Safely handles null intents

## Known Limitations

1. **Domain Verification**: App Links verification (`android:autoVerify="true"`) requires:
   - A `.well-known/assetlinks.json` file on the server
   - HTTPS support (or HTTP for localhost testing)
   - Proper domain configuration

2. **Play Variant**: The Play variant uses `gplay.fireshare.us` for sharing links, but deep link receiving still uses `fireshare.uk` (as configured in AndroidManifest).

3. **No Web Fallback**: Currently, if the app is not installed, clicking a deep link opens in browser but there's no web page to display the tweet. **Recommendation**: Implement a web fallback page (see "App Not Installed" section above).

## Future Enhancements

Potential improvements:
1. Support for additional deep link patterns (user profiles, search, etc.)
2. Deep link analytics/tracking
3. Fallback handling for invalid or expired tweets
4. Custom URL schemes (e.g., `tweet://tweet/{id}/{authorId}`)

## Files Modified

1. `app/src/main/AndroidManifest.xml` - Added intent filter and launch mode
2. `app/src/main/java/us/fireshare/tweet/TweetActivity.kt` - Added `onNewIntent()` handling
3. `app/src/main/java/us/fireshare/tweet/navigation/TweetNavGraph.kt` - Improved deep link parsing and navigation
4. `TEST_DEEP_LINK.sh` - Test script for manual testing

## Verification Checklist

- [x] Deep links work when app is not running
- [x] Deep links work when app is already running
- [x] Proper error handling for invalid URLs
- [x] Logging for debugging
- [x] HTTPS support added
- [x] Launch mode configured correctly
- [x] Navigation properly handles back stack

