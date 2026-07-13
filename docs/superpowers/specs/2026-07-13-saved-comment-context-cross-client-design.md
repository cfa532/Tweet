# Saved Comment Context for Android and Web

## Goal

Bring Android and Web into parity with iOS for comments and replies that are bookmarked or favorited. A saved comment must retain its immediate parent, render as a quote around an embedded parent tweet, remain highlighted for the viewing user, and never appear as a top-level tweet on the comment writer's profile.

## Shared data contract

- `Tweet.parentTweetId` is optional.
- A new comment stores the tweet it comments on as `parentTweetId`.
- A reply stores the comment it replies to as `parentTweetId`.
- `add_comment` continues to store the child on the immediate parent author's root node.
- The already-updated backend `get_tweet` response supplies `parentTweetId` to both clients.

## Android design

- Extend the Room/serialization `Tweet` model, singleton merge/copy paths, and upload serialization with `parentTweetId`.
- Set the field when creating comments and replies, and route `add_comment` through the immediate parent author's writable service.
- In bookmark/favorite loading, use list membership to set the corresponding viewer flag, load/cache the immediate parent, and pass it to the existing tweet item as embedded quote content.
- Do not save toggle responses under `tweet.authorId` as profile-list membership. Filter legacy cached profile rows whose `parentTweetId` is non-null.
- Add model, loader, and presentation tests where the existing Android test structure permits.

## Web design

- Extend the global `Tweet` interface and comment/reply construction payloads with `parentTweetId`.
- Route `add_comment` through the immediate parent author's writable service.
- When loading bookmark/favorite views, set the corresponding viewer flag from membership, resolve the parent through the store's ordinary tweet read, and render the saved comment using the existing quoted/embedded tweet component path.
- Keep saved comments out of the normal profile tweet collection and filter legacy in-memory/cache entries with `parentTweetId` from top-level profile results.
- Add focused store/component tests using the repository's current test tooling.

## Failure behavior

If the parent cannot be loaded, retain and display the saved comment as a regular row rather than dropping it. Parent retrieval is an ordinary read and must not trigger `refresh_tweet` or other recovery synchronization automatically.

## Verification

- Android model and unit tests plus the app's compile task.
- Web type-check, focused tests, and production build.
- Manual data-flow review confirms replies use their immediate comment parent and saved-list membership controls highlighting.
