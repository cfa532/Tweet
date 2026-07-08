# Deep Linking & Share-URL Configuration

How dtweet.com deep links work across Android, iOS, and the web, and which
URL each share action must produce. This is the cross-platform spec; the iOS
repo (`~/Documents/GitHub/Tweet-iOS`) carries the same document, and the
Cloudflare Worker lives there under `cloudflare/dtweet-worker/`.

## Domains & infrastructure

| Host | Role |
|---|---|
| `dtweet.com` | Deep-link domain (Cloudflare Worker). Serves `/.well-known/assetlinks.json` and `/.well-known/apple-app-site-association` over https; redirects browser page loads `302 → http://dl.dtweet.com/<path>`. |
| `dl.dtweet.com` | Http-only web app (Cloudflare-proxied DNS → ksbox nginx :80 → Leither :8080, Leither domain-routes it to the TweetWeb app). Https requests are answered `301 → http` so browsers' forced upgrades fall back. |
| check_upgrade domain | Whatever domain the backend returns from `check_upgrade` (`HproseInstance.kt`). |

The web stack (Leither + TweetWeb) is **http-only**: TweetWeb hardcodes
`http://` for its `/webapi/` RPC and talks directly to provider-node IPs.
https exists solely where Google/Apple require it (the well-known files).

## Who opens the link

- **App installed**: Android App Links (`autoVerify` intent filter for host
  `dtweet.com`, both http and https schemes) open the app directly.
- **No app**: the browser follows the Worker redirect and renders the tweet
  on `http://dl.dtweet.com/...`. Chrome may show a one-time
  "connection is not secure — Continue" interstitial on the https→http
  downgrade.

## Share-URL policy (which button produces which URL)

| Share action | URL format | Rationale |
|---|---|---|
| **Feed share button** (plain tweet rows only) | `http://dtweet.com/tweet/{mid}/{authorId}` (standard deep-link format) | Opens the app when installed; web fallback via Worker |
| **Detail-view share button** | `http://{check_upgrade domain}/tweet/{mid}/{authorId}` | Backend-controlled domain, independent of dtweet.com |
| **Detail-view dropdown menu → share** | `http://{author provider IP}/entry?aid={appIdHash}&ver=last#/tweet/{mid}/{authorId}` | Works with a bare node IP, no DNS/domain needed |
| **Everything else** (comment rows, fullscreen player, media browser) | check_upgrade domain — same as the detail-view share button | Unchanged legacy behavior |

Comment shares append `?fromComment=true&parentTweetId={mid}&parentAuthorId={mid}`
(inside the hash fragment for the provider-IP format).

## Android implementation map

- `app/src/main/AndroidManifest.xml` — `autoVerify` intent filter with hosts
  `fireshare.uk` + `dtweet.com` and path patterns `/tweet/.*`, `/user/.*`,
  `/profile/.*`.
- `app/src/main/java/us/fireshare/tweet/HproseInstance.kt` — `check_upgrade`
  supplies the share domain for the detail-view share button.
- `viewmodel/TweetViewModel.kt` — `ShareLinkStyle` enum + `shareTweet`;
  default is `WEB_DOMAIN`; feed rows (`TweetItem.kt`, `TweetItemBody.kt`)
  pass `DEEPLINK`, the dropdown (`TweetDropdownMenuItems.kt`) passes
  `PROVIDER_IP`.

## Verification identities (served by the Worker in `assetlinks.json`)

Packages `us.fireshare.tweet` / `us.fireshare.tweet.debug`; fingerprints:

- Google Play App Signing cert (`8A:D4:2F:4C…`) — Play Store installs
- Local upload key `tweet_keystore.jks` (`42:B9:90:AF…`) — sideloaded release
  builds (both `play` and `full` flavors share the applicationId)
- `debug-keystore/debug.keystore` (`90:9D:FF:B9…`) — debug builds

Verify on a device: `adb shell pm get-app-links us.fireshare.tweet`
(dtweet.com should show `verified`).
