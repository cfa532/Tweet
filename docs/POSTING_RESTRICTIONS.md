# Posting Restrictions

The mini-specific posting restriction has been removed with the mini build variant.

## Current Rule

For full/play builds, non-guest users who have more than 10 tweets must configure a node before posting more content:

```kotlin
if (!appUser.isGuest() && appUser.tweetCount > 10 && appUser.cloudDrivePort == 0) {
    showNodeRequiredDialog = true
}
```

Guests are still handled by the existing guest warning flow.

## Relevant Code

- `app/src/main/java/us/fireshare/tweet/tweet/ComposeTweetScreen.kt`
- `app/src/main/java/us/fireshare/tweet/navigation/BottomNavigationBar.kt`

## Test

```bash
./gradlew compileFullDebugSources
./gradlew compilePlayDebugSources
```
