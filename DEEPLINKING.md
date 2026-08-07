# Deep Linking & Share-URL Configuration

How dtweet.com deep links work across Android, iOS, and the web, and which
URL each share action must produce. This is the cross-platform spec; the iOS
repo (`~/Documents/GitHub/Tweet-iOS`) carries the same document, and the
Cloudflare Worker lives there under `cloudflare/dtweet-worker/`.

## Domains & infrastructure

| Host | Role |
|---|---|
| `dtweet.com` | Deep-link domain and browser fallback host (Cloudflare Worker). Serves `/.well-known/assetlinks.json` and `/.well-known/apple-app-site-association` over https; browser navigation is redirected to TweetWeb. |
| `dl.dtweet.com` | Public gateway alias served by the same Worker. Kept for compatibility; new shared links should use `dtweet.com`. |
| check_upgrade domain | Whatever domain the backend returns from `check_upgrade` (`HproseInstance.kt`). |

The web stack (Leither + TweetWeb) is **http-only**: TweetWeb hardcodes
`http://` for its `/webapi/` RPC and talks directly to provider-node IPs.
https exists solely where Google/Apple require it (the well-known files).

## Who opens the link

- **App installed**: Android App Links (`autoVerify` intent filter for host
  `dtweet.com`, both http and https schemes) open the app directly.
- **No app**: the Worker redirects the browser to TweetWeb. The fragment is
  retained by the browser because it is never sent in the HTTP request.

## Share-URL policy (which button produces which URL)

| Share action | URL format | Rationale |
|---|---|---|
| **Tweet action-bar share buttons** | `http://dtweet.com/#tweet/{mid}/{authorId}` (fragment-form share URL) | Opens the app when installed; web fallback via Worker, in feed, detail, comment, media, and fullscreen contexts |
| **Dropdown menu → share, feed and detail view alike** | `http://{check_upgrade domain}/#tweet/{mid}/{authorId}` | Backend-controlled domain using TweetWeb's external share-link format, with `dtweet.com` as fallback |

`ShareLinkStyle.PROVIDER_IP` —
`http://{author public IPv4}/entry?aid={appIdHash}&ver=last#/tweet/{mid}/{authorId}`
— is no longer selected by any share action. It remains implemented as the
DNS-free format; see the `PROVIDER_IP` KDoc in `TweetViewModel.kt` for how the
URL is composed and which addresses the validator rejects.

The `#` in `domain/#tweet/...` is TweetWeb's external share-link delimiter; it
does not mean TweetWeb uses Vue `createWebHashHistory()` for domain navigation.
Normal domain navigation stays in history mode. Only the separate
`provider/entry?...#/tweet/...` form uses the IP-entry hash-routing contract.

Comment shares append `?fromComment=true&parentTweetId={mid}&parentAuthorId={mid}`
(inside the hash fragment for the provider-IP format).

## Android implementation map

- `app/build.gradle.kts` — release builds set `publicDeepLinkHost=dtweet.com`
  and `appLinkAutoVerify=true`; debug builds set
  `publicDeepLinkHost=debug.dtweet.invalid` and `appLinkAutoVerify=false` so
  the debug app cannot steal production `dtweet.com` links.
- `app/src/main/AndroidManifest.xml` — app-link intent filter with
  `${publicDeepLinkHost}`, root path `/` for fragment-form tweet links, and path
  patterns `/tweet/.*`, `/user/.*`, `/profile/.*`. Release resolves that host
  to `dtweet.com`; debug resolves it to `debug.dtweet.invalid`.
- `app/src/main/java/us/fireshare/tweet/HproseInstance.kt` — `check_upgrade`
  supplies the share domain for the feed dropdown menu.
- `viewmodel/TweetViewModel.kt` — `ShareLinkStyle` enum + `shareTweet`;
  the default and all action bars use `DEEPLINK`; feed dropdowns pass
  `WEB_DOMAIN`, while the detail dropdown passes `PROVIDER_IP`.

## Verification identities (served by the Worker in `assetlinks.json`)

Package `us.fireshare.tweet`; fingerprints:

- Google Play App Signing cert (`8A:D4:2F:4C…`) — Play Store installs
- Local upload key `tweet_keystore.jks` (`42:B9:90:AF…`) — sideloaded release
  builds (both `play` and `full` flavors share the applicationId)

The release `assetlinks.json` entry for `dtweet.com` must include both
relations:

- `delegate_permission/common.handle_all_urls`
- `delegate_permission/common.get_login_creds`

`common.get_login_creds` is required for Google Play credential sharing.

Do not include `us.fireshare.tweet.debug` or the debug keystore fingerprint in
the public `dtweet.com` `assetlinks.json`. If both release and debug are
verified for the same host, Android may open the debug app for production
links.

Do not keep `fireshare.uk` in Android app-link filters. `dtweet.com` is the
public app-link host now.

Verify on a device: `adb shell pm get-app-links us.fireshare.tweet`
(dtweet.com should show `verified`).
