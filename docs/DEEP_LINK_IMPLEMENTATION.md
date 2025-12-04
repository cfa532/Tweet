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

## URL Format

Deep links follow this format:
```
http://fireshare.uk/tweet/{tweetId}/{authorId}
https://fireshare.uk/tweet/{tweetId}/{authorId}
```

**Example**:
```
http://fireshare.uk/tweet/agvvgWJmmXtji5FLTt768Plu3He/uCQDqhZgCGw3zpXtNSWo9Ftyn7Q
```

## Testing

### Manual Testing

1. **Test with app not running**:
```bash
adb shell am start -a android.intent.action.VIEW \
  -d "http://fireshare.uk/tweet/TWEET_ID/AUTHOR_ID" \
  us.fireshare.tweet.debug
```

2. **Test with app already running**:
   - Open the app first
   - Then run the same command above
   - The app should navigate to the tweet detail screen

3. **View logs**:
```bash
adb logcat -s DeepLink:TweetActivity:D
```

### Using Test Script

A test script is available at `TEST_DEEP_LINK.sh`:

```bash
./TEST_DEEP_LINK.sh
```

The script will:
- Check if a device is connected
- Provide example commands
- Optionally run a test with user-provided tweet/author IDs

## Logging

Deep link events are logged with the `DeepLink` tag. Key log messages:

- `"Parsing deep link: {uri}"` - When parsing starts
- `"Successfully parsed deep link: tweetId={id}, authorId={id}"` - On successful parse
- `"Navigating to deep link: tweetId={id}, authorId={id}"` - When navigating
- `"Invalid deep link format: ..."` - On parsing errors

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

