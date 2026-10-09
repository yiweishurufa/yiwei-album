# 拾光相册 v0.2 architecture contract

Project: /workspace/work/fnalbum (Kotlin, Jetpack Compose, minSdk 26, compileSdk 34, Kotlin 1.9.24-compatible syntax, Compose BOM 2024.06.00, OkHttp 4.12, org.json, coroutines 1.8.1). No new Gradle dependencies unless listed here (allowed extra: none — use OkHttp + org.json + android framework only).

Existing: `data/FnClient.kt` (FnClient.get/post signed calls, Photo, Repo), `ui/*` Compose screens, `App.kt` (Store prefs, Coil ImageLoader with header interceptor), `MainActivity.kt` (Route/Nav).

## File ownership (do NOT edit files you don't own)
- Cloud subagent: `app/src/main/java/com/hark/shiguang/cloud/*.kt` only.
- NAS-extra subagent: `app/src/main/java/com/hark/shiguang/data/FnExtra.kt` only (may READ FnClient.kt; may use FnClient.get/post/http/baseUrl/token/abs and AuthX; if FnClient lacks something, e.g. multipart/DELETE/PUT signed call, implement it inside FnExtra.kt using AuthX.header + FnClient.http).
- Main agent: everything else (UI, themes, Photo model, Store, Nav, build).

## Cloud contract (package com.hark.shiguang.cloud)
```kotlin
enum class CloudKind { WEBDAV, OPENLIST, PAN123, BAIDU }
data class CloudAccount(val id: String, val kind: CloudKind, val title: String,
    val url: String = "", val user: String = "", val pass: String = "",
    val extra: Map<String, String> = emptyMap())   // tokens, client ids, root path...
data class CloudEntry(val name: String, val path: String, val isDir: Boolean, val size: Long,
    val modified: Long /*epoch ms, 0 if unknown*/, val thumbUrl: String?, /* null => use rawUrl for images */
    val isImage: Boolean, val isVideo: Boolean, val id: String = path /* provider file id */)
interface CloudSource {
    val account: CloudAccount
    suspend fun connect(): CloudAccount         // validates login, returns account with refreshed tokens in extra
    suspend fun list(path: String): List<CloudEntry>   // root = "/"
    suspend fun rawUrl(e: CloudEntry): String           // streamable/downloadable URL
    fun headers(url: String): Map<String, String>        // headers needed to GET thumb/raw url (auth, UA, referer)
    suspend fun upload(dir: String, name: String, size: Long, open: () -> java.io.InputStream)
    suspend fun delete(e: CloudEntry)
    suspend fun mkdir(parent: String, name: String)
}
object CloudSources { fun create(a: CloudAccount): CloudSource }
object CloudAccounts {   // persistence; call init(context) from App
    fun init(c: android.content.Context)
    val all: List<CloudAccount>          // snapshot
    fun save(a: CloudAccount); fun remove(id: String)
}
/** Recursive media scan for timeline: walks folders breadth-first, emits media entries, max depth/limit. */
suspend fun CloudSource.scanMedia(root: String = "/", maxItems: Int = 5000, onBatch: (List<CloudEntry>) -> Unit)
```
Providers:
- WEBDAV: PROPFIND Depth 1, Basic auth, PUT upload, DELETE, MKCOL. url = server base incl. path.
- OPENLIST (AList fork): POST /api/auth/login {username,password} -> token; POST /api/fs/list {path,page:1,per_page:0,refresh:false} (header Authorization: token); entries have name,is_dir,size,modified,thumb,sign; raw via POST /api/fs/get -> raw_url; upload PUT /api/fs/put (headers File-Path urlencoded, Authorization, Content-Length); delete POST /api/fs/remove {dir,names}; mkdir POST /api/fs/mkdir {path}. Allow anonymous (empty user).
- PAN123: open platform https://open-api.123pan.com, header Platform: open_platform; token via POST /api/v1/access_token {clientID, clientSecret}; list /api/v2/file/list?parentFileId=..&limit=100 (path field stores fileId chain; use id); download /api/v1/file/download_info?fileId=; delete via trash; upload via its v2 single upload if simple, else throw UnsupportedOperationException("暂不支持").
- BAIDU: xpan open API. Auth = OAuth device code flow with user-supplied AppKey/SecretKey (extra["appKey"], extra["secretKey"]): `suspend fun baiduDeviceCode(appKey): DeviceCode(userCode, verificationUrl, qrcodeUrl, deviceCode, interval, expiresIn)` and `suspend fun baiduPollToken(...)`. list via https://pan.baidu.com/rest/2.0/xpan/file?method=list&dir=&web=1 (thumbs), download via filemetas dlink + access_token, header User-Agent: pan.baidu.com. Refresh token on expiry.

## NAS extra contract (package com.hark.shiguang.data, file FnExtra.kt, object NasX)
Derive endpoints from the official web bundle at /workspace/work/fnos-re/p/*.js and the reference app /workspace/work/fmphoto-ref/entry/src/main/ets/network/fnHttpClient/*.ets (PolyForm Noncommercial: read for endpoints/params only, write our own code). Return existing models (Photo, Album) where possible; add new data classes in FnExtra.kt.
Needed (suspend funs on object NasX):
- timeline granularity helpers: `suspend fun monthSummary(): List<MonthCount>` (year, month, count, coverPhoto?) and `yearSummary()` derived from Repo.timeline().
- delete(ids) to recycle bin; recycleList(offset, limit) -> List<Photo>; recycleRestore(ids); recycleDeleteForever(ids); recycleClear()
- download URL for original (fn: originalDownloadUrl(p: Photo): String + headers) ; live-photo motion video URL if any.
- upload(file name, size, mime, InputStream provider, folder/target) with progress callback (whatever official upload endpoint is, incl. chunking if required); list upload target folders if needed.
- album ops: createAlbum(name), addToAlbum(albumId, ids), removeFromAlbum, renameAlbum, deleteAlbum.
- smart categories: list categories (scene/object classes with cover + count) and category photos (offset,limit).
- duplicates / similar: repeat photo groups list + similarSearch(photoId).
- map: map photo points/clusters list (lat,lng,count,cover) and photos in a bbox.
- folders: folder tree browse (list subfolders + photos in folder).
- search filters: whatever filter options /search endpoints take (date range, type, person, location).
- batch collect(ids,on).
Every function: real endpoint + params as seen in sources; mark any guessed one with `// UNVERIFIED`.

## Coordination notes (main agent, 23:47)
- App renamed by user to 「一维相册」 (manifest label done). Remaining 拾光 strings in Kotlin (login title, disclaimer, Diag header, Transfers folder Pictures/一维相册, deviceName, HomeHeaders fallback) should become 一维相册 / 一维 by whoever owns those files.
- Launcher icon chosen: "timeline + amber dot" — res/drawable/ic_bg.xml, ic_fg.xml, ic_mono.xml already updated. Login screen logo should match: charcoal rounded square, off-white line, amber (#E3A13B) dot right of center.
- Source will be pushed to the user's GitHub repo (name pending); never commit shiguang.jks or local.properties.
- 23:50 NOTE from a second main-agent run: I detected concurrent edits (Viewer.kt uses Actions.*). I wrote ui/Settings.kt (SettingsScreen, SettingsCode QR via zxing, Section/NavRow/ToggleRow/InputRow helpers), updated AndroidManifest (share-in SEND/SEND_MULTIPLE filters, BackupJob service, FileProvider ${applicationId}.files + res/xml/files.xml, media/notification permissions), added zxing core 3.5.3 to build.gradle.kts (versionCode 3, 0.2.0), renamed all 拾光相册 strings to 一维相册. My Viewer.kt patch referenced `Ops.*` (delete/download/share/openSimilar/addToAlbum/downloadLiveVideo), MoreSheet, AlbumPicker, ConfirmDialog, toast, HomeState.dirty, Route.Viewer(slideshow) — reconcile with your Actions.* naming. I am now STOPPING all UI edits; you own the UI.
