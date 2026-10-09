# 一维相册（com.hark.shiguang）交接说明 + 「影视」功能方案

## 一、项目现状（v1.0.7）
- Kotlin + Jetpack Compose 单 Activity；无 ViewModel，全局状态是 Compose `mutableStateOf` 单例（`HomeState`、`SearchState`、`Dav`、`NasAccounts` 等）。
- 构建：Gradle 8.7 + AGP，`gradle assembleRelease`（项目里没有 gradlew，需自备 Gradle 8.7 + JDK 17 + Android SDK 34）（若卡在 extractReleaseVersionControlInfo，加 `-x extractReleaseVersionControlInfo`）。版本号只改 `versionName/versionCode`，「关于」页读 `BuildConfig.VERSION_NAME`。**每次出包都要同步 +1**（下一版 1.0.8 / versionCode 8）。
- 已有依赖：media3 exoplayer/ui/okhttp 1.3.1（视频播放）、Coil、OkHttp、TensorFlow Lite 2.14、ML Kit face-detection 16.1.7。ABI 只打 arm64-v8a、armeabi-v7a。
- 两类「来源」：飞牛 NAS 账号（`NasAccounts`，API 在 `data/FnClient.kt`、`data/FnExtra.kt`，`Repo` 封装）和 WebDAV 账号（`Dav` + `DavLib`，`cloud/WebDavSource.kt`）。
  - 左上角来源胶囊 `TopSwitch()`（`ui/Home.kt`），`switchSource()` 全 App 切换；`HomeState.stash/restore` 保存每个飞牛账号的已加载状态。
  - 每个 WebDAV 账号的缓存都在 `filesDir/dav/<accountId>/`（文件清单 manifest、meta、faces/）。

### 文件地图
| 文件 | 作用 |
|---|---|
| `ui/Home.kt` | 底部导航、来源胶囊、照片页（飞牛/WebDAV）、相册、AI 页（WebDAV 版 `DavDiscoverTab`）、人脸设置弹窗 |
| `ui/Extra.kt` | `VideosTab(dav)` 视频页 |
| `ui/Viewer.kt` | 大图 / 视频播放（ExoPlayer） |
| `ui/Screens.kt` | `HomeState`、`SearchState`、搜索页 |
| `ui/DavGroups.kt` | 重复/相似扫描进度 + 分组结果 |
| `Dav.kt` | `DavLib`（扫描、meta、地点、重复/相似、`aiSearch` 概念 AND 打分）、`Analyzer`（本地分析） |
| `Ai.kt` | 大模型配置、`AiClient.classify/concepts`、`AiRunner` |
| `Faces.kt` | WebDAV 人脸：ML Kit 检测 → 对齐 → MobileFaceNet 192 维 → 贪心聚类；参数 0.65 / 5 张 / 0.36 |
| `cloud/WebDavSource.kt` | PROPFIND 列目录、`allBytes()`、带认证的 URL |
| `MainActivity.kt` | `Route` 路由表 + `Nav` |

### 底部导航（当前）
`照片 / 视频 / AI / 设置`。内部 `HomeState.tab`：0 照片时间线、1 视频、2 相册、3 AI、4 设置。相册没有底部按钮，在照片页标题上方的「时间线 | 相册」切换里（`PhotoModeSwitch()`），tab==2 时底部仍高亮「照片」。照片页已不显示视频。

### 已知没做 / 注意
- `DavGroupsScreen` 删除 WebDAV 文件是直接删除，不可恢复。
- 人脸识别未在真机充分验证：对齐旋转方向、0.36 阈值在 MobileFaceNet 上的效果需实测。
- 飞牛时间线是分页拉取，照片页过滤视频是在客户端做的。

---

## 二、「影视」功能方案（待实现）

### 1. 入口
- 把底部「视频」改为「影视」（tab 1）。页面上方是海报墙（电影 / 剧集），下方或二级切换「全部视频」保留原来的 `VideosTab` 列表。底部最终：`照片 / 影视 / AI / 设置`。
- 来源跟随左上角来源胶囊：当前是飞牛就看飞牛的影视库，是 WebDAV 就看该 WebDAV 账号的影视库。

### 2. 影视库目录设置（用户明确要求）
- 影视文件夹和相册往往不在同一个目录，所以**每个来源单独设置「影视目录」**，可以设多个，和相册扫描目录互不影响。
- WebDAV：在影视页右上角「⚙ 媒体库」→ 用现有 PROPFIND 目录浏览器选一个或多个文件夹，并设置类型：电影 / 剧集 / 自动。保存到 `filesDir/dav/<accountId>/movies/config.json`。
- 飞牛：同样能选 NAS 路径（用现有文件浏览接口）；如果用户已经在用「飞牛影视」，可以研究直接读它的已刮削数据（可选，优先级低）。
- 首次进影视页、没有设置目录时，显示空状态 + 「选择影视文件夹」按钮。

### 3. 扫描
- 递归列出影视目录，后缀：mkv mp4 avi mov ts m2ts rmvb wmv flv webm iso(只列不播)。同时收集同目录的 `.nfo`、`poster.jpg / folder.jpg / <名>-poster.jpg`、`fanart.jpg`、字幕 `.srt .ass .ssa`。
- 文件名解析：
  - 电影：`名称 (2019)`、`名称.2019.1080p.BluRay...` → 标题 + 年份；去掉分辨率、编码、发布组等噪声词。
  - 剧集：`S01E02`、`s1e2`、`第1季第2集`、`EP02`、`[02]`；剧名取上级文件夹（`剧名/Season 1/`）。
- 扫描结果缓存为 `movies/library.json`（按来源独立），增量：只处理新文件。显示进度条（同重复扫描的样式）。

### 4. 刮削
- 优先级：本地 NFO（Kodi/Jellyfin 格式，读 title/year/plot/rating/tmdbid/thumb）＞本地海报图片＞TMDB。
- TMDB：用户在 设置 → 影视 → 填 TMDB API Key（v3 key 或 v4 Read Access Token 都支持），存 `Store`。
  - 搜索：`GET https://api.themoviedb.org/3/search/movie?query=&year=&language=zh-CN`，剧集用 `/search/tv`；详情 `/movie/{id}`、`/tv/{id}`、`/tv/{id}/season/{n}`（集标题、集截图）；演员 `credits`。
  - 图片：`https://image.tmdb.org/t/p/w342{poster_path}`（海报墙）、`w780{backdrop_path}`（详情页背景）。
  - 选取：标题相似度 + 年份匹配度打分，取最高；低于阈值标「未匹配」，详情页可手动搜索改正（「修正匹配」）。
  - 限速：并发 ≤4，失败重试 2 次；结果写入 `library.json`，海报下载到 `movies/img/` 本地缓存。
  - 国内网络可能连不上 TMDB：设置里提供「TMDB 代理地址」（替换 api.themoviedb.org / image.tmdb.org 的 base URL），连不上时提示。
- 可选：大模型辅助解析很乱的文件名（复用 `AiClient`），只在正则失败时调用。

### 5. 界面
- 海报墙：三列 2:3 海报卡（标题 + 年份 + 评分角标），顶部分段「电影 | 剧集 | 全部视频」，排序：最近添加 / 名称 / 年份 / 评分。顶部一行「继续观看」横滑（有播放进度的）。
- 详情页：背景大图 + 海报 + 标题、年份、时长、评分、类型、简介、演员横滑；剧集显示季切换 + 集列表（集截图、集标题、进度条）。按钮：播放 / 继续播放、选择版本（同一部多个文件时）、修正匹配。
- 播放：复用 Viewer 里的 ExoPlayer（`media3-datasource-okhttp` 带 WebDAV 认证头；飞牛用现有直链/转码地址）。新增：外挂字幕（同名 srt/ass，media3 `SubtitleConfiguration`）、音轨 / 字幕轨选择、倍速、记忆播放进度（每来源 `movies/progress.json`，看到 >90% 算看完）、横屏全屏、剧集自动下一集。
- 骨架屏：海报墙加载时用灰色 2:3 占位。

### 6. 数据结构（建议）
```kotlin
data class MovieItem(
  val id: String,              // 来源内稳定 id（路径 hash）
  val kind: String,            // movie / tv
  val title: String, val year: Int?, val tmdbId: Int?,
  val poster: String?, val backdrop: String?, val overview: String?, val rating: Float?,
  val genres: List<String>, val files: List<String>,   // 电影：多个版本
  val seasons: Map<Int, List<Episode>>,                 // 剧集
  val addedAt: Long, val matched: Boolean,
)
data class Episode(val season: Int, val episode: Int, val path: String, val title: String?, val still: String?)
```
新建 `Movies.kt`（扫描 / 解析 / 刮削 / 缓存，按 `sourceKey = "fn:<id>" / "dav:<id>"` 一份 `MovieLib`）、`ui/MoviesTab.kt`、`ui/MovieDetail.kt`、在 `MainActivity.Route` 加 `MovieDetail(id)`、`MovieLibrarySettings`。

### 7. 验收
- 设置影视目录后能扫描并显示进度；有 NFO 的直接出海报，没有的走 TMDB。
- 切换来源后影视库、进度、设置各自独立，切回来立刻恢复。
- WebDAV mkv 能播放、能加载外挂字幕、能记住进度。
- 版本号升到 1.0.8，「关于」页同步。
