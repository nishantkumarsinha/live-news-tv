# Feed guide

The TV app draws its screen from one file, `channels.json`, plus one small logo per channel.
It does not know how those were made. You can supply them in either of two ways.

## Option 1: no server, push files with adb

1. Build and install the app (see the README).
2. Set `FEED_URL` to an empty string in `app/build.gradle.kts` so the app does not fetch a list
   from anywhere else.
3. Push your list and logos:

```
adb connect <tv-ip>:5555
adb shell mkdir -p /sdcard/Android/data/com.nishvik.livenews/files/icons
adb push channels.json /sdcard/Android/data/com.nishvik.livenews/files/channels.json
adb push my_logo.jpg /sdcard/Android/data/com.nishvik.livenews/files/icons/
```

Start from [`channels.sample.json`](channels.sample.json).

## Option 2: your own Cloudflare Workers KV feed

The `cloudflare/` folder holds a small Worker that serves the files from a KV namespace.
Everything in it uses placeholders. Keep your real account id, namespace id and API token
out of the repository.

1. Create a namespace: `npx wrangler kv namespace create livenews-feed`
2. Copy `cloudflare/wrangler.toml` to `cloudflare/wrangler.local.toml` (git ignores this name)
   and put your namespace id in it. Change or remove the `[[routes]]` block to suit your domain.
3. Deploy: `npx wrangler deploy -c cloudflare/wrangler.local.toml`
4. Upload files with the keys below, for example:

```
npx wrangler kv key put --binding=FEED "test/channels.json" --path ./channels.json -c cloudflare/wrangler.local.toml --remote
npx wrangler kv key put --binding=FEED "icons/my_logo.jpg" --path ./my_logo.jpg -c cloudflare/wrangler.local.toml --remote
```

5. Set `FEED_URL` for each flavor in `app/build.gradle.kts` to your address followed by `/test` or `/prod`.

Wrangler flags are documented at https://developers.cloudflare.com/workers/wrangler/commands/kv
Check `npx wrangler kv key put --help` for your version.

### Keys the Worker serves

| Path | Content |
|---|---|
| `/test/channels.json` and `/prod/channels.json` | The channel list. Two folders so a test TV can run ahead of the production TV. |
| `/icons/<name>.jpg` | One icon per channel (an illustration or your own image). Name uses lowercase letters, digits and underscores. Each file is read up to 300 KB. |

Anything else returns 404. In the Worker in this repository, reads are open. The author's own feed is locked with a Cloudflare WAF rule that blocks any request without a private `X-Feed-Key` header, so it only answers the author's builds. You can do the same for yours: build from source with `feedKey` set in a local keystore.properties file, and add a WAF rule that blocks requests lacking that header. The Worker adds an
ETag, so an unchanged list costs a tiny 304 reply, and caches at the edge for 60 seconds.

## What the app does with the feed

- Checks at most every 5 minutes while the screen is showing.
- Downloads the logos a new list needs first, then writes the list atomically.
- Replaces the local list only when the new `updated_epoch` is newer, so a list pushed by adb
  is not overwritten by an older feed.
- If a download fails, the list already on the TV stays in use.
- Shows "List not updated since ..." after 3 hours without an update.

## channels.json fields

| Field | Meaning |
|---|---|
| `updated_epoch`, `updated` | When the list was made. The epoch decides which list is newer. |
| `contact` | Text shown on the empty headlines screen. |
| `weather` | `temp`, `desc`, `icon`, `day` (1 or 0), `ts`, `city`. Shown top right. |
| `channels[].group` | `hindi`, `english`, `business` or `world`. Sets the row. |
| `channels[].name`, `region` | Label under the logo. `region` is an optional suffix such as UK. |
| `channels[].id` | YouTube video id of the live stream. Empty means the channel is not live. |
| `channels[].watching` | Live viewers. |
| `channels[].since` | Unix time the stream started. Shown as "live for ...". |
| `channels[].icon` | Logo file name in `icons/`. |
| `channels[].news[]` | Headlines: `t` title, `d` description, `ts` unix time. |
| `channels[].news_src` | Source label shown over the headlines. |

## Picking the right stream (done before the file is written)

A channel can run several live streams at once, for example the 24x7 feed and programme
streams. The collector that writes `channels.json` keeps the candidates with at least 20
percent of the top viewer count (at most 4), picks the oldest by start time, and falls back
to the most viewed. A short list of manual overrides can pin a channel to a fixed stream.
Because this is re-run every hour, a channel that starts a new stream is picked up without
an app update. The collector is not part of this repository.
