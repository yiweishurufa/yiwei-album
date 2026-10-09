# 给下一个 AI 的交接说明：一维相册 1.0.2 → 1.0.3

## 0. 你接手的是什么
- 「一维相册」是一个 Android App，用 Kotlin + Jetpack Compose 写（没有用 XML 布局）。包名 `com.hark.shiguang`，minSdk 26，target/compileSdk 34。
- 功能：浏览飞牛 NAS（fnOS）相册和 WebDAV 网盘（123 云盘、阿里云盘 WebDAV、坚果云等）里的照片和视频，提供 AI 整理（人脸、相似照片、重复照片）。「影音」页（原名「影视」）做电影/剧集刮削（TMDB）、弹幕，并用 ExoPlayer 加 libVLC 播放。
- 用户在中国大陆，App 必须**不翻墙也能用**。所有联网刮削源都要能在国内直连。
- 当前代码：git `master` 分支，最新提交 `11b14be`，版本 1.0.2 / versionCode 13。源码包就是 `git archive HEAD`。
- 先读 `HANDOFF.md`（完整历史，最后两节是 1.0.2）和 `NEXT-PLAN.md`，再读本文件。

## 1. 1.0.2 已完成，不要改坏
| 需求 | 实现位置 | 说明 |
|---|---|---|
| 重复/相似照片并入 AI 分析 | `ui/DavGroups.kt`、`ui/Pages.kt`（DiscoverTab / DuplicatesScreen）、`Dav.kt` 的 Analyzer | 重复/相似页面**不再自己扫描**，只读 `lib.meta` 里 Analyzer 算好的 md5/dHash。没分析完时显示「已分析 x/y」和「去 AI 页」按钮（`HomeState.openAiTab()`）。飞牛的 `NasX.repeatCheck(true)` 改为进入 AI 页时每 12 小时最多触发一次 |
| 扫描进度同步 | 新文件 `ScanStatus.kt`；`ScanService.kt`、`ui/Home.kt`、`ui/Screens.kt` | 通知栏、照片页副标题、AI 页整理卡都调用 `ScanStatus.current()` / `ScanStatus.listing(lib)`，数字来源只有一个。通知每 1 秒刷新，空闲约 2 秒后移除 |
| 网络自适应（VPN 下 123 云盘慢） | 新文件 `NetEnv.kt`；接入点在 `App.kt`（`NetEnv.start`）、`data/FnClient.kt`、`DavThumb.kt`、`Dav.kt`、`cloud/Cloud.kt` | 检测 VPN 和系统代理。对每个用到的主机分别测「经 VPN」和「绑定底层 Wi‑Fi/蜂窝」两条路由的延迟；直连更快就用 `NetEnv.dns` + `NetEnv.socketFactory` 把这台主机绑到底层网络。系统不允许绕过 VPN 时，扫描并发 6→12（`Cloud.SCAN_PARALLEL` 现在是 getter），分析并发 4→8，并在「测试连接」结果里给出 `NetEnv.hint(url)` 提示。**新写的 OkHttpClient 一律从 `FnClient.http.newBuilder()` 派生**，这样会自动继承 NetEnv |
| 「影视」改名「影音」 | `ui/Home.kt` 第 99 行 Tab、`ui/MoviesTab.kt` 标题、`ui/Settings.kt` 分组 | 只改了显示文字。内部类名（`Movies`、`MovieLib`、`Route.Movies*`、`MoviesTab`）没改，也**不需要改** |

以上都编译通过，但**还没在真机上验证**（尤其是 NetEnv 在 Clash / v2rayNG / sing-box 下能不能绕过）。

## 2. 你的任务：在「影音」页加入音乐播放器（需求 #4 后半部分）
### 2.1 用户原始需求
- 「影视」改成「影音」（已完成），并加入音乐播放器。
- 能播放**飞牛 NAS** 和 **WebDAV 网盘**里的音乐。
- **自动刮削**：读取文件内嵌标签（标题、歌手、专辑、年份、音轨号、封面、歌词）。缺失时联网补全，补全源必须国内直连可用。
- **多格式**：mp3、flac、ape、wav、m4a（aac/alac）、ogg、opus、wma、dsf/dff（DSD）、aiff、wv。
- 默认功能：后台播放、通知栏/锁屏/蓝牙耳机控制、播放列表、随机/单曲循环/列表循环、按歌曲/专辑/歌手/文件夹浏览、滚动歌词、迷你播放条。
- 音乐库要和照片库、视频库**隔离**：音乐文件夹不进照片/视频页，影视扫描也不收音乐。

### 2.2 可以直接复用的现有代码（都在 `app/src/main/java/com/hark/shiguang/`）
- **文件系统抽象**：`Movies.kt` 第 113 行 `interface MovieFs { list(path), readText, bytes(path), playUrl(f), headers(url), canReadSidecars }`，已有实现 `DavFs(accountId)`（WebDAV）和 `FnFs()`（飞牛）。音乐扫描直接用它列目录、读 .lrc、取播放地址和鉴权头，**不要另写一套 WebDAV/飞牛访问代码**。
- **数据源 key**：`Movies.keyOf()`（`Movies.kt` 第 944 行）返回当前源，形如 `dav:<账号id>` 或 `fn:<账号id>`。音乐库也按这个 key 分库。
- **库的写法范本**：`class MovieLib(sourceKey)`（`Movies.kt` 第 591 行）：目录选择 `saveDirs`、JSON 持久化、`version` 计数驱动 Compose 刷新、`MediaIsolation.invalidate(sourceKey)` 做隔离。照着写一个 `MusicLib` 即可。
- **隔离**：`MediaIsolation.kt`。`roots(sourceKey)` 目前只读 `filesDir/{dav|fn}/<id>/movies/config.json` 里的 `dirs`。音乐配置建议存到同级的 `music/config.json`（格式相同），并修改 `roots()` 把两份目录合并，保存后调用 `MediaIsolation.invalidate(key)`，这样照片/视频页就会排除音乐目录。影视扫描（`MovieLib`）也要跳过音乐目录。
- **播放网络**：`ui/MoviePlayer.kt` 第 66 行 `PlayHttp`（目前是 private），包含 OkHttp client（从 `FnClient.http` 派生），以及 123 云盘 302 跳转签名地址的缓存 `resolved`。建议把它提成 `internal` 或单独成文件，音乐复用。注意：302 跳转后的 CDN 地址**不能**再带 WebDAV 鉴权头，MoviePlayer 第 200–215 行已经处理过，照抄。
- **解码**：`app/build.gradle.kts` 已有 `androidx.media3:media3-exoplayer:1.3.1`、`media3-datasource-okhttp:1.3.1`、`org.jellyfin.media3:media3-ffmpeg-decoder:1.3.1+2`（FFmpeg 音频解码），以及 libVLC 3.7.2（`ui/VlcPlayer.kt`，兜底）。需要新增 `androidx.media3:media3-session:1.3.1`（版本必须和现有 media3 保持一致，都是 1.3.1）。
  - ExoPlayer 原生支持 mp3/flac/wav/aac/m4a/ogg/opus。ape、wma、alac 依赖 FFmpeg 扩展，构建时用 `DefaultRenderersFactory(ctx).setExtensionRendererMode(EXTENSION_RENDERER_MODE_PREFER)`（MoviePlayer 里已有写法）。**请实测** jellyfin 这个包是否启用了 ape/wmav2/alac/dsd 解码器；不支持的格式回退到 libVLC 播放，或者在列表里标出「不支持」。
  - DSD（dsf/dff）大概率不支持，至少保证不崩溃并给出提示。
- **进度**：`ScanStatus.kt` 是唯一进度源，音乐扫描的进度也加进 `ScanStatus.current()`，通知栏会自动显示。
- **图片**：Coil 2.6.0（已接入 NetEnv），封面用 Coil 加载本地缓存文件。

### 2.3 建议的实现步骤
1. **数据模型** 新建 `Music.kt`：
   - `data class Track(path, name, size, mtime, title, artist, album, albumArtist, year, trackNo, discNo, durationMs, codec, artPath, lrcPath)`
   - `class MusicLib(sourceKey)`：音乐目录列表、`tracks`、JSON 存到 `filesDir/{dav|fn}/<id>/music/`（见 2.2 隔离一条），带 `version` 计数。
   - `object Music { fun lib(key) }`，写法同 `Movies.lib`。
2. **扫描**：用 `MovieFs.list` 递归遍历用户选的音乐目录，按扩展名过滤（mp3 flac ape wav m4a aac alac ogg opus wma dsf dff aiff aif wv）。增量更新：path + size + mtime 没变就跳过。并发用 `Cloud.SCAN_PARALLEL`。进度写进 ScanStatus。
3. **刮削（读内嵌标签）**：
   - 优先用 HTTP Range 只读文件头 256 KB：ID3v2、FLAC 的 Vorbis comment/PICTURE 块、多数 m4a 的 moov 都在文件头；APEv2 和 ID3v1 在文件尾，需再读尾部 128 KB。
   - 解析：最省事的是 `MediaMetadataRetriever.setDataSource(url, headers)`（可以拿到 title/artist/album/封面/时长，但每首都要建网络连接，很慢）。更好的做法是引入纯 Java 标签库 jaudiotagger（`net.jthink:jaudiotagger`，需确认 Android 兼容性），对 Range 读回的片段解析。可以二者结合：先用库解析片段，失败再用 Retriever。
   - 歌词：同目录同名 `.lrc` 优先，其次是内嵌 USLT / LYRICS 标签。
   - 没有标签时，从文件名推断「歌手 - 歌名」或「01. 歌名」，文件夹名当专辑。
   - 封面缓存到 `cacheDir/music-art/<md5>.jpg`，同目录 cover.jpg / folder.jpg 也认。
4. **联网补全（可在设置里关闭）**：国内直连可用的源要先实测。可以考虑网易云音乐公开搜索接口、QQ 音乐、LRCLIB（境外，可能慢），都是非官方接口，要做好失败容错，不能阻塞播放。只补缺失的字段，不覆盖文件里已有的标签。
5. **播放服务**：新建 `MusicService : MediaSessionService`，内部一个 ExoPlayer（音频属性 `C.USAGE_MEDIA` / `AUDIO_CONTENT_TYPE_MUSIC`，`handleAudioFocus = true`，`setHandleAudioBecomingNoisy(true)`）。数据源用 PlayHttp 的 `OkHttpDataSource`，按 url 设置鉴权头。`AndroidManifest.xml` 需要加：
   - `<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK"/>`
   - `<service android:name=".MusicService" android:exported="true" android:foregroundServiceType="mediaPlayback">`，加上 intent-filter `androidx.media3.session.MediaSessionService`。
   - UI 侧用 `MediaController` 连接这个服务。不要和影视的 MoviePlayer 共用 ExoPlayer。播放视频时暂停音乐。
6. **界面**（Compose，风格照 `ui/MoviesTab.kt`、颜色/字号用 `ui/Theme.kt` 的 `C.*`、组件用 `ui/UiKit.kt`）：
   - `MoviesTab` 顶部加分段切换：电影剧集 | 音乐。
   - 音乐首页：歌曲 / 专辑 / 歌手 / 文件夹 四个子页，加搜索和「全部播放 / 随机播放」。
   - 迷你播放条：显示在底部导航上方（`ui/Home.kt` 的底栏），展示封面、标题、播放/暂停、下一首。
   - 全屏播放页：大封面、进度条、上一首/下一首、播放模式（列表循环 / 单曲循环 / 随机）、播放列表抽屉、滚动歌词（解析 LRC 时间轴，高亮当前行）。
   - 设置「影音」分组里加：音乐文件夹选择、联网补全开关、重新刮削。
   - 新页面路由加到 `Route`（`sealed class Route` 定义在 `MainActivity.kt`），用 `Nav.push(...)` 跳转。
7. **版本**：`app/build.gradle.kts` 改为 versionName `1.0.3` / versionCode `14`。

### 2.4 验收清单
- 飞牛和 WebDAV（123 云盘）各放一个音乐文件夹，能扫出来并显示标题、歌手、专辑、封面。
- mp3、flac、m4a 能正常播放；ape、wma 能播放，或者有明确的「不支持」提示，不能崩溃。
- 锁屏/通知栏能暂停和切歌；切到后台播放 30 分钟不中断。
- 音乐文件夹不出现在照片页和视频页。
- 照片、影视、AI 原有功能不受影响。

## 3. 构建环境和交付流程（重要，这台机器有坑）
- 工具在 `/workspace/tools/`：JDK17、Gradle 8.7、Android SDK 34、aarch64 版 aapt2。环境变量：`source /workspace/tools/env.sh`。
- 只编译检查：`cd /workspace/work/fnalbum && /workspace/tools/compile.sh :app:compileReleaseKotlin -Pkotlin.compiler.execution.strategy=in-process`，错误看 `grep "^e: " /workspace/tools/compile.log`。
- 打正式包：`bash /workspace/tools/build.sh -x extractReleaseVersionControlInfo -Pkotlin.compiler.execution.strategy=in-process --max-workers=2`，大约 3–4 分钟，产物是 `/workspace/work/out/app-release.apk`。这个命令要放到后台跑，前台 120 秒会超时。
- 机器是 2 核 8 GB、没有 swap：**必须**加 `in-process` 和 `--max-workers=2`，否则 Gradle/Kotlin 守护进程会被 OOM 杀掉。**不要用 `pkill`**，会把 shell 会话一起杀掉。
- `build.sh` 会改写 `local.properties` 和 `gradle.properties` 的 aapt2 路径，这是正常的。
- 交付前核对：
  1. `aapt2 dump badging app-release.apk | head -1`，确认版本号。
  2. `PATH=/workspace/tools/jdk17/bin:$PATH /workspace/tools/android-sdk/build-tools/34.0.0/apksigner verify --print-certs app-release.apk`，证书 SHA-256 必须是 `04812323e88a940c72008ab5c5ba171ab8c094376d366627501b55ef3f0bc4c3`。
  3. 源码包：`git archive --format=zip -o YiWei-1.0.3-src.zip HEAD`，并确认里面没有 `.jks`、`keystore.properties`、`local.properties`。
- 签名：`keystore.properties` + `yiwei-release.jks`（不在 git 里）。如果在新机器上丢失，用户手里有 `YiWei-signing-key.zip`，**向用户要，绝对不能换新签名**（否则用户无法覆盖安装）。

## 4. 用户的工作习惯
- 用中文交流。
- 用户说「等我说完一起做」时，只记录需求、不动代码；说「说完了 / 先做这些」才开始做，并且**所有需求完成后一次性打包交付**（APK + 源码包），不要做一点发一点。
- 不要自己上传 GitHub，除非用户明确要求；签名文件永远不能进 GitHub。
- 每完成一项就 `git commit`，提交信息写清楚「版本 #需求号 做了什么」。交付时更新 `HANDOFF.md` 和 `NEXT-PLAN.md`。
