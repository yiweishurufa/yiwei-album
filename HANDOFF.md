# 一维相册 · 交接文档（给接手的 AI）

时间：2026-10-09 00:05（北京时间）。上一位 AI 额度将尽，以下是全部状态。请先读本文件，再读 ARCH.md。

## 1. 产品目标
Android 原生（Kotlin + Jetpack Compose）飞牛 fnOS 相册客户端，名字「一维相册」。
- 包名 / applicationId：`com.hark.shiguang`（保持不变，否则无法覆盖安装旧版）
- 版本：versionCode 3 / 0.2.0（build.gradle.kts 已改）
- 签名：`shiguang.jks`，storePassword/keyPassword/alias 均为 `shiguang`。**不要把 jks 推到公开仓库**。
- 已发布过 0.1.0、0.1.1（基础登录、时间线、相册、人物、地点、AI 搜索、收藏、查看器）。
- 参考项目：github.com/jonas-pi/FMphoto（鸿蒙版，PolyForm Noncommercial 许可）。**只借鉴接口和功能思路，不要复制代码**。

## 2. 用户已拍板的需求（不要再问）
- 四套主题全保留并可切换：暗房 DARKROOM（深色、琥珀）、冷矿 MINERAL（浅色）、电影 CINEMA（深色）、手账 JOURNAL（浅色纸感）；支持跟随系统深浅色（浅/深各选一套）。
- Logo：时间线 + 琥珀色瞬间点（已做成矢量 adaptive icon：res/drawable/ic_bg.xml、ic_fg.xml、ic_mono.xml）。
- FMphoto 全部功能：日/月/年视图 + 双指缩放切换、右侧时间快速拖动条、缩略图→大图一镜到底、长按滑动多选、批量收藏/下载/删除/加入相册、下载原图/实况、上传与手机相册自动备份、传输队列、回收站（恢复/彻底删除）、重复/相似照片、智能分类、地图、文件夹浏览、幻灯片、搜索过滤、系统分享导入、内外网自动切换、诊断日志、设置同步（二维码/设置码/WebDAV）。
- 第三方来源：OpenList、WebDAV、123 云盘、百度网盘，统一在一个 App 里看。
- 设计规则：Android 原生感，克制配色，不要满屏渐变/玻璃拟态/小字/卡片套卡片。
- 用户要求「一次性做完再发」，不要零碎发半成品。

## 3. 构建
- 环境脚本在 `tools/`：`setup.sh`（JDK17 + Android SDK 34 + Gradle 8.7，aarch64 机器用替换过的 aapt2）、`build.sh`、`env.sh`。普通 x86 电脑直接用 Android Studio 或 `gradle assembleRelease` 即可，不需要这些脚本。
- 依赖：Compose BOM 2024.06.00、Kotlin 1.9.24、compose compiler 1.5.14、coil 2.6、media3 1.3.1、okhttp 4.12、security-crypto、zxing core 3.5.3。
- **当前构建失败，39 个 Kotlin 编译错误，清单见 BUILD_ERRORS.txt**。工具链本身已验证可出签名 APK。

## 4. 代码地图（app/src/main/java/com/hark/shiguang/）
| 文件 | 状态 |
|---|---|
| App.kt | Store（加密偏好：url/lanUrl/wanUrl/autoSwitch/theme/followSystem/columns/slideSec/slideShuffle/backupOn…、exportSettings/importSettings）、ImageAuth（按 URL 给 Coil/播放器加头）。完成 |
| MainActivity.kt | Route 全部路由已声明 + 分享导入 handleShare + 主题化状态栏。引用了尚未编写的界面（见第 5 节） |
| Diag.kt | 诊断日志（脱敏、导出）。完成 |
| Transfers.kt | 传输队列（上传/下载，存到 Pictures/一维相册）。完成，需接 UI |
| data/FnClient.kt | 飞牛 API 基础（登录、authx 签名、Photo 模型含 source/cloudPath/key/isCloud） |
| data/FnExtra.kt | **NasX 高级接口层已写好**：月/年汇总、删除、回收站、下载原图、实况视频、上传、目录、相册增删改、智能分类、重复/相似、地图、文件夹、搜索过滤、批量收藏 |
| data/Endpoint.kt | 内外网探测与自动切换。第 19 行 `by mutableStateOf` 缺 import（`androidx.compose.runtime.getValue/setValue`） |
| cloud/*.kt | 网盘层完成并在 JVM 上编译通过；OpenList 已对 demo.oplist.org 实测；WebDAV/123/百度未用真实账号测。接口见 ARCH.md 和各文件注释（`// UNVERIFIED` 标注了未确认点） |
| ui/Theme.kt | 四套主题、ThemeState、C 调色板。完成 |
| ui/Timeline.kt | 日/月/年、双指切换、长按滑动多选、快速滚动条 |
| ui/HomeHeaders.kt | 四种首页头部 + 回忆/去年今日；引用了不存在的 openAlbum/openPerson |
| ui/Screens.kt | 登录、首页、底部导航、相册/人物等；引用了不存在的 SelectionBar；373 行重载歧义 |
| ui/Settings.kt | 设置页完成（主题卡片、连接、来源、备份、整理入口、幻灯片、二维码设置同步、诊断、退出），提供 Section/NavRow/ToggleRow/InputRow |
| ui/Viewer.kt | 查看器：一镜到底(Hero)、幻灯片、分享/下载/删除/更多。**被两次并行修改弄乱了**：同时引用 `Actions.*` 和 `Ops.*`、`playing` 与 `slideshow` 两套变量。建议统一成一个 `ui/Ops.kt` |

## 5. 下一步（按顺序）
1. 新建 `ui/Ops.kt`：`object Ops { download(ctx, photos), share(ctx, photos), suspend delete(photos), suspend addToAlbum(album, photos), openSimilar(p), downloadLiveVideo(ctx, p) }`，飞牛照片走 NasX，云照片走 CloudSources；把 Viewer.kt 里的 `Actions.` 全换成 `Ops.`；再补 `toast(ctx,msg)`、`ConfirmDialog(title, msg, okText, onDismiss, onOk)`、`AlbumPicker(onDismiss, onPick)`；删掉 Viewer 里重复的 `ConfirmDelete`/`MoreSheet`/`slideshow` 变量，只留一套。
2. 新建缺失界面：CloudHomeScreen（账户列表 + 添加四种来源；百度用设备码登录 baiduDeviceCode/baiduWaitToken）、CloudBrowseScreen、CloudTimelineScreen（scanMedia 汇成时间线）、BackupScreen + `BackupScheduler`/`BackupJob`（JobScheduler，Manifest 已声明 .BackupJob）、TransfersScreen、SmartScreen、DuplicatesScreen、FoldersScreen、MapScreen（无地图 SDK 时可先做按地点分组列表 + 点开 geo: intent）、RecycleScreen、UploadScreen、SelectionBar（多选底栏：收藏/下载/加入相册/删除）、openAlbum/openPerson。
3. 修 Endpoint.kt import、Screens.kt:373 歧义。
4. `bash tools/build.sh` 直到通过，装机自测；APK 命名 `YiWei-0.2.0.apk`。
5. GitHub：先写 `.gitignore`（排除 *.jks、local.properties、build/、.gradle/）。用户的 GitHub 连接账号是 fuckhw-bot，但还没把仓库授权给应用，仓库名待用户确认。

## 6. 用户信息
- 中文沟通，北京时间。设备 Android。飞牛 NAS 地址由用户在 App 登录页填写，代码里不写死。

## 7. 进度更新（2026-10-09 00:22）
- 第 5 节 1–4 已完成：ui/Ops.kt（Ops/CloudPhotos/toast/ConfirmDialog/InputDialog/AlbumPicker/SelectionBar/openAlbum/openPerson）、ui/Pages.kt（全部缺失界面）、Backup.kt（BackupScheduler/BackupRunner/BackupJob）。Viewer 只保留一套 slideshow/MoreSheet。
- 已出 YiWei-0.2.0.apk（shiguang.jks 签名，versionCode 3）。未在真机自测。
- 签名信息移到 keystore.properties（已 gitignore）；本地 git 已初始化一次提交，未推送（GitHub 未授权、仓库名未定）。
- 地图暂无 SDK：按聚类点列表 + geo: 打开系统地图。

## 8. 1.0.0 进度（接手请先读 PLAN-0.3.0.md；版本 1.0.0 / versionCode 4）
### 已核实的 API 事实
- 沙箱与 Hark 浏览器都**连不上** oucwork.top（https 443/5667 隧道失败，http 被代理拦截返回 "not found"；浏览器 ERR_EMPTY_RESPONSE）。飞牛接口无法实测，沿用 0.2.0 代码（FnClient/NasX）。
- WebDAV 123pan（https://webdav.123pan.cn/webdav）：PROPFIND Depth:1 → 207，href 为绝对路径 `/webdav/...`（URL 编码），根目录自身 displayname 很怪（"备份照片"）要跳过；GET 文件 → 302 到 CDN 签名地址，支持 Range（206）；PUT 小 JSON → 201，再 PUT 覆盖 201，DELETE → 204（删除后短时间 GET 仍可能 200，最终 404）。JPEG 前 128KB 含 EXIF（DateTimeOriginal）+ 内嵌缩略图 → 用 Range 读头部做 GPS/时间/dHash。
- build.gradle.kts：`java.util.Properties` 在 android{} 内无法解析，已改为顶部 import。修后 0.2.0 代码基线构建通过（273s）。
### 状态（2026-10-09 02:30 北京时间）
- **构建通过**，`/workspace/work/out/YiWei-1.0.0.apk`（versionCode 4 / 1.0.0，shiguang.jks 签名，com.hark.shiguang；包含除 Icons.kt 的 Locale.US 修复外的全部代码）。未在真机测试。
- ⚠️ 环境变化：02:38 起沙箱变成 **x86_64** 且外网下载超时，`tools/` 里是 aarch64 的 JDK/aapt2，`build.sh` 报 `Exec format error`。x86 上重建：装 x86 JDK17，build-tools/34.0.0 里把 `*.x86` 备份改回原名，gradle.properties 的 aapt2FromMavenOverride 删掉或指向 x86 aapt2。最后一处源码改动（ui/Icons.kt 圆形路径 `format(Locale.US, …)`，防止逗号小数点语言下图标路径解析失败）**尚未进 APK**，中文系统不受影响。
- 已完成：
  - 细线图标 `ui/Icons.kt`（LI.*，1.6/选中 2.2 描边）；底栏 照片·相册·发现·设置（`ui/Home.kt`）。
  - 首页：顶部「飞牛 | WebDAV」分段 + 搜索；连接胶囊（账号名·内网/外网·延迟，60s 测一次）→ `ConnectionSheet`：头像一排切换账号/添加、自动/仅内网/仅外网、编辑内外网地址、测速、退出账号。WebDAV 模式胶囊 → `DavSheet` 切换 WebDAV 账户。大标题「10月」随滚动显示当前月份。
  - 多账号：`NasAccounts.kt`（每账号独立地址/登录/模式；ScrollMem/缓存按账号 key）。
  - 设置中心 `ui/Settings.kt`：账号卡片置顶（管理账号=连接面板）、外观（浅/深/跟随系统、5 强调色、网格列数）、功能（备份、WebDAV 来源、智能整理·AI、应用锁、存储与缓存、传输）、幻灯片、二维码同步、关于与诊断、退出。设置里不再有连接。
  - 只留 WebDAV：`ui/Pages.kt` DavAccountsScreen/DavDialog（多账户增删改）；`Dav.kt` DavLib：索引缓存、根目录文件夹（并入「相册」顶部）、虚拟相册（根目录 `/.yiwei-albums.json`，读-改-写）、本地分析（EXIF GPS/时间、md5 重复、dHash 相似）。
  - 相册整理：NAS（NasX 新建/改名/删除/设封面/加入/移出）+ WebDAV 虚拟相册同样操作。入口：相册页右上 ＋、长按相册（改名/删除）、相册内右上 ⋯、多选底栏「加入相册/设为封面/移出相册」（`ui/Ops.kt` AlbumRef/AlbumCtx/AlbumOps/DavAlbumPicker）。
  - 发现页：飞牛=人物/地点/智能分类/重复/地图/文件夹/回收站；WebDAV=地点/重复/相似/AI 分类 四卡 + 智能搜索 + 本地分析进度（立即分析）。
  - AI：`Ai.kt` 15 个预设（Meta Llama API 默认）+ `ui/AiScreen.kt` 设置页（测试连接/保存并开始、仅缩略图 512px、仅 Wi-Fi 充电、月预算、飞牛也用 AI）。搜索：WebDAV 用 AI 标签+关键词扩展；飞牛结果合并 AI 索引。
  - 登录：域名自动补 https/http + 443/5667/5666/80 探测，独立端口框（空=自动），二步验证码框（字段名 otp // UNVERIFIED），最近登录，局域网扫描 5666。
  - 滚动位置记忆（ScrollMem + rememberMemGrid/List；集合页按路由实例记忆）；看大图返回定位最后一张（ViewerReturn）。
  - 默认深色+蓝色，浅色版。应用锁（android.hardware.biometrics.BiometricPrompt，API28+，离开 30s 后重锁，无指纹时不锁死）。
- 已用 curl 验证 123pan：PUT/GET/DELETE `/.yiwei-albums-test.json` = 201/200/204（点开头文件可用），测试文件已删除。
- 未做/未验证：前台服务备份（仍是 JobScheduler）、首页备份状态行、时间线拖动跳月浮标（沿用旧快速滚动条）、飞牛接口全部无法实测（oucwork.top 不通）、二步验证字段名、设封面接口 body、真机 UI 走查。

## 2026-10-09 进度（1.0.2–1.0.5，versionName 仍 1.0.0 / versionCode 4）
- 1.0.2 WebDAV 视频：MediaMetadataRetriever 流式取帧缩略图（DavThumb）、播放前一次性解析 123 CDN 302、ExoPlayer 起播缓冲 800ms。
- 1.0.3 设置页按飞牛/WebDAV 隔离（账号卡、功能项）。
- 1.0.4 底栏「视频」tab（ui/Extra.kt）、顶部日历按年/月/日筛选、飞牛账号重命名、去掉幻灯片、上传签名（登录 secret 用登录 AES key/iv 解密，存 Store "sign.user@url"）、选图器上限 getPickImagesMaxLimit、飞牛人物照片多参数回退+search 回退、人物长按命名（接口为推测：/api/v1/ai-person/update 等）。
- 1.0.5 WebDAV 重复/相似分组页 ui/DavGroups.kt：每组推荐保留（分辨率>文件大小>非副本名），其余默认勾选，可单组/一键删除。
- 待做：WebDAV 人脸识别（参数照飞牛：置信度0.65/最少照片5/差异阈值0.36+预设；大模型检测人脸框+置信度，本地 mobilefacenet.tflite 192维向量聚类，模型已放 app/src/main/assets/mobilefacenet.tflite（未提交依赖） 并加 tensorflow-lite 依赖）、WebDAV Live（iPhone HEIC+MOV 同名配对；安卓 MotionPhoto XMP 偏移）、多飞牛账号合并显示（需按账号区分 FnClient/鉴权）。

## 待改需求（2026-10-09 收集中，用户说完再动手）
1. WebDAV 发现页布局不合理，需重新设计（截图：卡片高度不齐、重复照片 0 组卡片空、AI分类只有一张图、搜索框和"地点·青岛"区块堆在下面）。
2. 重复扫描、相似扫描加进度条；扫描完成后若无结果显示「扫描完成，未发现重复/相似照片」，有结果直接显示分组结果。
3. 「飞牛/WebDAV 隔离」按用户视频（音乐 App 示例）重做：
   - 去掉顶部「飞牛 | WebDAV」分段 + 下面的盘选择器两层结构，改为左上角单个「来源」胶囊（显示当前源名，如 123云盘）。
   - 点胶囊弹出下拉菜单，列出所有来源（每个飞牛账号、每个 WebDAV），当前项打勾。
   - 选中后 Toast「已切换到 XX 源」，整个 App（照片/视频/相册/发现/设置）整体换成该源内容，先显示骨架屏加载，再出内容；新源空则显示空态。
   - 每个源状态完全独立（扫描进度、缓存、发现结果各自保存），切回来直接恢复。
4. 底部导航「发现」Tab 改名为「AI」。
5. 「关于」页版本号同步：build.gradle versionName 改为与 APK 文件名一致（下一版 1.0.6），versionCode 递增，关于页读取 BuildConfig 显示。
6. 智能搜索「用一句话描述你要找的照片」结果不准确（截图：空输入仍显示找到 48 项，结果与描述不符）。需排查：AI 仅整理 6/3169 时搜索的回退逻辑；空输入不应出结果；改为 AI 描述/标签+地点+日期综合匹配并按相关度排序，未整理完时提示。
   - 原因：Dav.aiSearch 用 any() 只要命中任一扩展关键词就算（LLM 扩出的「天空/夜晚」等宽泛词命中一大片）；AI 只整理了 6/3169 张，其余仅靠城市/文件名；清空输入框（键盘删字）时 results 不清空，所以空输入仍显示「找到 48 项」。
   - 方案：按命中词数/原词权重打分排序，原句关键词必须命中；清空即清结果；未整理完提示「AI 已整理 x/y，结果可能不全」；可考虑对候选图用视觉大模型二次判断。
7. WebDAV AI 人脸识别（未实现，本轮一并做）：本地 MobileFaceNet 出 embedding 聚类，三个参数 置信度0.65/最少照片5/差异阈值0.36，大模型辅助人脸框/命名。


## 1.0.6 已交付 (2026-10-09)
- 来源胶囊（多飞牛/多WebDAV）、发现→AI、AI页横滑重设计、重复/相似扫描进度、智能搜索按概念AND+相关度排序、WebDAV 人脸识别（ML Kit + MobileFaceNet，0.65/5/0.36）、版本号走 BuildConfig。
- 未真机验证：人脸对齐、相似度阈值在 MobileFaceNet 上的实际效果。

## 1.0.7 已完成（人脸说明、照片去视频、相册并入照片、底部 照片/视频/AI/设置）；影视方案见 HANDOFF-影视.md
- 人物栏下加小字说明「人脸只在本机识别，不会上传」；进度卡不提下载。
- 照片页去掉视频（飞牛+WebDAV），标题只显示照片数。
- 底部导航：相册和视频互换位置 → 照片 / 相册 / 视频 / AI / 设置。
- 提议：「视频」改「影视」，海报墙 + 原视频列表；TMDB 刮削（用户自填免费 API Key），优先读本地 NFO/海报。等用户确认。
- 提议：相册并入照片页（标题旁「时间线 | 相册」切换），底部变 照片/影视/AI/设置。等用户确认。

## 1.0.8 已交付（2026-10-09，versionCode 8）
- 底部五个：照片 / 视频 / 影视 / AI / 设置（HomeState.tab：0 照片、1 视频、2 相册、3 AI、4 设置、5 影视）。双击当前底部按钮回到顶部（ScrollTop + rememberMemGrid/List 内置）。
- 影视：`Movies.kt`（MovieLib 按 sourceKey fn:/dav: 独立；config.json / library.json / progress.json / img/ 在 filesDir/{dav|fn}/<id>/movies/；NameParser 已在 JVM 上测过常见命名；NfoParser；Tmdb v3/v4、代理、匹配打分 0.62、并发 4、AI 兜底解析）。`ui/MoviesTab.kt` 海报墙（电影|剧集、排序、继续观看、骨架）、`ui/MovieDetail.kt`（详情、季/集、选版本、修正匹配、媒体库设置 + 目录选择器）、`ui/MoviePlayer.kt`（ExoPlayer + ResolvingDataSource：WebDAV 302 解析一次、同域带认证；外挂字幕先下载到 cacheDir/subs；倍速、音轨/字幕轨走 PlayerView 自带设置；横屏沉浸；剧集连播；5 s 存进度，≥90% 算看完）。
- 飞牛影视目录只能选「相册已添加的文件夹」（folder_view 接口），读不到 NFO/海报/字幕，靠 TMDB 补。// UNVERIFIED，飞牛未实测。rmvb/wmv/iso 只列不播。
- 扫描：DavLib 索引 6 小时内不重扫（手动重新扫描照旧）；Analyzer/Faces 只处理新照片；ScanPolicy（允许流量 / 不充电）；ScanService 前台服务（dataSync）让扫描在后台继续。
- 人脸：ArcFace 模板对齐（双眼 + 嘴中点 setPolyToPoly）+ 水平翻转平均；聚类加合并（阈值 +0.07）和重分配；默认差异 0.40（旧默认 0.36 自动迁移）；faces.json 加 v=2，旧向量作废需重扫（名字保留）。
- 多选 bug：长按松手时的点击会把刚选中的取消（只选一张时退出多选），已用 Selection.swallowTap 屏蔽。
- 大图底栏「加入相册」（飞牛相册 / WebDAV 虚拟相册），AI 页人物/地点长按「命名 / 创建相册」（飞牛分页取全部照片后建相册）。
- 删除：WebDAV 删除后同步清索引、meta、人脸、虚拟相册引用；飞牛进回收站，设置里可选「彻底删除」（recycle-bin/delete）。
- 缓存：Coil 磁盘 800→300 MB，内存 25%→20%；davthumb LRU 160 MB、缩略图最长边 480/质量 78；字幕缓存 30 天。
- 设置页重排：账号卡 / 外观 / 照片 / 影视 / 整理与 AI / 隐私与存储 / 关于；图标底改中性色。
- 新 logo：相框 + 琥珀色播放三角 + 角上的琥珀「瞬间点」（res/drawable/ic_fg.xml，通知图标 ic_notify.xml）；新增「琥珀」强调色；App 名统一为「一维相册」。

## 1.0.9 · wt-media 分支（影视/AI/设置/更新，另一位 AI 在 wt-photos 做照片功能，之后合并）
### 已完成（每项单独提交）
- **AI 分类卡住修复（TODO #3）**：`Ai.kt` AiRunner 重写为不退出的循环：跟随 `ScanPolicy`（删掉 `AiConfig.wifiCharging` / `ai.wc`）；条件不满足/预算到/断网时等待并自动继续；接口错误指数退避重试（15 s→10 min，400/413/415/422 针对单张图跳过）；`AiRunner.note` 是暂停原因，显示在 AI 页 ScanStatusCard 的说明行；`AiRunner.pause()` + `ScanPolicy.paused`（内存）让「暂停」按钮也停 AI；「立即继续」= manual 启动。AI 页（`ui/Home.kt` DavDiscoverTab LaunchedEffect）和照片页随 Analyzer/Faces 一起启动 AI；飞牛 AI 页启动 `startNas`（`AiConfig.nasToo` 时）。`ScanWatch.kt`：网络/充电变化时自动续跑 Analyzer/Faces/AI（App.onCreate 注册）。`ScanService` 通知显示 AI 进度。
- **改名「一维相册」（#1）**：res/values/strings.xml app_name，Manifest label，所有中文字符串、下载目录 Pictures/一维相册、网盘上传目录 /一维相册、HANDOFF 文档。包名不变。
- **设置去冗余（#4）**：照片组删「WebDAV 账户」（只留顶部卡「切换与管理」）；新组「网络与电量」两项（允许使用移动网络 / 不充电时也整理）统一管扫描+人脸+AI；手机备份只跟随「允许使用移动网络」（`Backup.kt`）；AI 页删「仅 Wi-Fi 且充电」、假开关改说明文字；备份页删「仅在 Wi-Fi 下备份」。迁移 `ScanPolicy.migrate()`（policy.v2）：只在用户从没动过扫描开关时，ai.wc=0 → 两项都允许；备份开着且 backupWifi=false → 允许移动网络。设置码导出 scanCell/scanNoCharge。
- **关于：反馈 / QQ 群（#5）**：`Support.kt` Feedback.email（mailto:1@yiwei.cc.cd，主题「一维相册反馈 <版本>」，正文带版本/Android/机型）、Feedback.joinQq（mqqapi 失败则复制群号 887416704）。
- **崩溃记录**：`CrashLog`（Support.kt）UncaughtExceptionHandler 写 filesDir/crash/last.txt（脱敏：URL/token/邮箱）；下次启动 `ui/SupportUi.kt` AppOverlays 弹窗「上次异常退出…」→ 邮件正文节选 + FileProvider 附件。
- **应用内更新（#10）**：`AppUpdate.kt`（对象名不能叫 Updater：和 androidx.compose.runtime.Updater 冲突）。列表 API 已用 curl 验证；`/b/api/share/download/info` 匿名调用对文件夹返回 code 5112「需要登录」，对文件是否可用 **未验证**（文件夹里还没有 apk）。失败 → 打开分享页。启动 12h 一次自动检查 + 关于里「检查更新」。REQUEST_INSTALL_PACKAGES + FileProvider 安装。
- **飞牛影视任意文件夹（#2）**：新 `data/FnFiles.kt`：fnOS 文件管理 WebSocket（`/websocket?type=main`，帧 = base64(HMAC-SHA256(登录 secret 解密值, json)) + json；`util.crypto.getRSAPub` → `user.authToken {main,token,si}` → `file.ls {path}`），协议来源 github.com/FNOSP/fnnas-api docs/protocol.md、docs/modules/file.md 和 github.com/Timandes/pyfnos。`Movies.kt` FnFs：根目录同时列出 NAS 文件夹（路径前缀 `fm:`，如 `fm:vol1/1000/影视`）和「相册 · xxx」文件夹（旧行为，作回退）；`fm:` 目录能列出 nfo/海报/字幕（canReadSidecars=true）。播放：同一目录也在相册里时用相册 stream id（已知可用），否则 `POST/GET /multiple-download`（cookie fnos-token）——**下载接口完全未验证**（来源 nobb.cc/archives/3776.html 的抓包描述，body 形状是猜的：先试 {files:[abs]}、{files:[rel]}、{path}）。**file.ls / authToken / 签名均未在真机验证**（沙箱连不上 NAS）；失败时文件夹选择器仍显示相册文件夹。需要 `NasX.signSecretB64`（登录时保存；老登录需退出重登一次）。
- **播放（#6 #7）**：`org.jellyfin.media3:media3-ffmpeg-decoder:1.3.1+2`（需 coreLibraryDesugaring，已开，desugar_jdk_libs 2.0.4）；`DefaultRenderersFactory` EXTENSION_RENDERER_MODE_PREFER + setEnableDecoderFallback(true)。PlayerView 默认就是 SurfaceView（HDR 输出 OK）。rmvb/wmv：**跳过**（media3 没有 RealMedia/ASF 解复用器，Jellyfin AAR 只含音频解码；要么 libVLC ~+25 MB，要么 NAS 转码），仍列为不可播。`Playback.kt`：MediaBadges（文件名解析 + 播放后按真实轨道写回 Store `badge.<hash>`）、DeviceCaps（MediaCodecList 查 video/dolby-vision、Display.getHdrCapabilities）、DV Profile 5 在非 DV 机型提示；徽标显示在详情页播放按钮下、文件列表和「选择版本」里（`ui/CastUi.kt` BadgeRow）。
- **播放细节（#9）**：`PlayPrefs`（Store `play.<itemId>` JSON）：按剧记住音轨/字幕语言（onTracksChanged 记录，字幕关闭记为 "off"）、片头/片尾秒数（播放器 ⋯ 菜单「设为当前位置」）、字幕延迟（只对外挂字幕：`SubShift` 改写时间轴后 replaceMediaItem）；全局字幕大小/位置（SubtitleView fractional size / bottom padding）。
- **DLNA 投屏（#10）**：`Dlna.kt`：SSDP M-SEARCH、设备描述解析、AVTransport SOAP（SetAVTransportURI/Play/Pause/Seek/Stop/GetPositionInfo）、`CastProxy`（手机局域网 IP 上的小 HTTP 服务，带认证转发 Range）。播放器右上角投屏按钮、详情页「投屏」按钮 → `CastDialog`（选电视 + 遥控）。**未在真实电视上测试**。权限 CHANGE_WIFI_MULTICAST_STATE / ACCESS_WIFI_STATE。
- **Baseline profile（#11）**：`androidx.profileinstaller:1.3.1` + 手写 `app/src/main/baseline-prof.txt`（已打进 assets/dexopt/baseline.prof）。
- APK：1.0.8 基线 46,264,818 B → 49,186,104 B（+2.9 MB，几乎全是 libffmpegJNI.so 两个 ABI）。

### NEXT-STEPS（如果被中断，从这里继续）
- 全部 11 项都已实现并编译通过。剩下的只有真机验证（见下）和合并：合并 wt-photos 时注意 `ui/Home.kt`（本分支只改了 DavPhotosTab 的 LaunchedEffect、DavDiscoverTab 的 LaunchedEffect + ScanStatusCard 调用、飞牛发现页加一行 startNas、锁屏文案改名）、`MainActivity.kt`（加了 AppOverlays() 一行 + 改名）、`ui/Settings.kt`、`App.kt`、`AndroidManifest.xml`、`build.gradle.kts`（ffmpeg/profileinstaller/desugaring）。
- 真机要验证：AI 自动续跑和暂停原因文案；飞牛 file.ls 和 /multiple-download（抓一次飞牛网页「文件管理」下载的请求，按实际 body 改 `FnFiles.downloadUrl`）；DTS/TrueHD 出声；DV P5 提示；DLNA 在几台电视上；123 云盘 download/info 放入 apk 后匿名是否可用（不行就只能打开分享页）；崩溃弹窗；QQ 跳转。
- 新 logo：相框 + 琥珀色播放三角 + 角上的琥珀「瞬间点」（res/drawable/ic_fg.xml，通知图标 ic_notify.xml）；新增「琥珀」强调色；App 名统一为「一维相册」。

## 1.0.9 照片线（wt-photos 分支）· NEXT-STEPS（每完成一项更新）
新代码尽量放新文件；MainActivity 只加了一个 `Route.X(ExtScreen)`，所有新页面在 `ui/Ext.kt` 的 `ExtScreen` 里分发。
- [x] 1 人物手动调整：`FaceEdits.kt`（faces/edits.json：face→group、excluded、hidden；cluster 后 `edits.build()` 应用，手动优先，合并后新脸按多数归组）。`ui/Ext.kt` PersonScreen（命名/合并到…/隐藏/多选「移出人物」）、HiddenPersons、飞牛本机隐藏 `FnPersonHide`（飞牛无合并接口，只做本机隐藏）。多选底栏扩展点 `SelExtra.actions`（Ops.kt SelectionBar）。
- [x] 2 那年今天：`ui/OnThisDay.kt`（WebDAV 用本地索引；飞牛用 TimelineSource.days 日索引 + 每个匹配日 1 次 getList）。时间线 header 里 `MemoriesRow`。
- [x] 3 高级搜索：`ui/AdvSearch.kt`（AdvFilter + FilterChipsRow + FilterSheet）接入 SearchScreen；空关键词也可只按筛选搜。Meta 新增 `cam`（EXIF Make/Model，仅新分析的照片有）。
- [x] 4 隐藏相册：`Hidden.kt` + `ui/HiddenAlbum.kt`（BiometricPrompt/锁屏密码）；时间线/相册/搜索/人物/地点/那年今天已排除；入口：相册页「隐藏相册」卡、大图⋯「隐藏/取消隐藏」、多选「隐藏」。已编译通过；未真机验证 BiometricPrompt。
- [x] 5 照片地图（已编译）： `ui/PhotoMap.kt`，osmdroid 6.1.18 + 高德瓦片（自定义 OnlineTileSourceBase），WGS84→GCJ02，按 zoom 网格聚类的自绘 Overlay；替换 ExtStubs.kt 的 PhotoMapScreen；入口 AI 页地点 header「地图」。
- [x] 6 简单编辑（已编译）：`ui/Editor.kt` 替换 ExtStubs.kt 的 EditScreen（大图⋯「编辑」已接入）。
- [x] 7 小组件：`MemoryWidget.kt`（RemoteViews，App 打开时间线时写 filesDir/widget/feed.json + ≤8 张 JPEG，updatePeriod 6h 轮换，不需联网/登录）+ res/layout/widget_memory.xml、res/xml/widget_memory.xml、Manifest receiver。
- [x] 8 平板：≥600dp 用 SideRail(NavigationRail)，网格 adaptiveCols(+2/+4)，相册 GridCells.Adaptive(150dp)。
- [x] 9 UI 美化（轻量一轮）：`ui/UiKit.kt` pressScale()（相册/地点/工具卡按压缩放）、空态图标改强调色圆底、日期头加粗+单行省略、大图页码胶囊、那年今天卡片。未改 Settings/影视。
- [x] 10 性能（便宜部分）：Hidden.visible 无隐藏项时直接返回原列表；网格已有稳定 key/contentType。未做 WebDAV 索引分段（LazyGrid 已按需组合，entries 构建是 O(n)，3 万张以内问题不大）。

### 1.0.9 照片线 · 合并注意 & 未验证
- 编译通过：`/workspace/work/out/wt-photos.apk`（46,864,874 B，比 1.0.8 的 46,264,818 B 多约 0.6 MB，主要是 osmdroid）。
- 新依赖：`org.osmdroid:osmdroid-android:6.1.18`（app/build.gradle.kts）。Manifest 加了 `.MemoryWidget` receiver；res 新增 layout/、values/widget_strings.xml、xml/widget_memory.xml、drawable/widget_*.xml。
- MainActivity 只加 `Route.X(ExtScreen)` 一行 + when 分支 + Surface 黑底判断。
- 合并时 Home.kt 冲突点：HomeScreen 外层改成 Row(SideRail + Box)、BottomBar 抽出 onTabTap、AI 页人物/地点 RowHeader（签名加了 extra 参数，onAction 移到最后）。ScanStatusCard 未动。
- 不需要 Settings 入口（隐藏相册入口在相册页；地图在 AI 页地点标题；小组件由系统添加）。可选：设置「隐私」里加「隐藏相册」NavRow → `Nav.push(Route.X(ExtScreen.HiddenAlbum))`。
- 真机待验：BiometricPrompt（API 29/30+）与 API 26–28 的锁屏确认；高德瓦片加载与 GCJ-02 偏移是否对齐；聚类点击；编辑后 WebDAV PUT（123pan）与飞牛 folder-view 上传（`p.path` 是否为真实路径、存储名带 `.前缀_taskId` // UNVERIFIED）；小组件在各桌面的显示与 6h 轮换；平板/折叠屏 NavigationRail；那年今天飞牛 getList 按天查询；人物合并后重新识别是否保持。
- 已知限制：飞牛人物只能本机隐藏（无合并/移出接口）；视频 tab 未过滤隐藏项；WebDAV 视频没有时长，时长筛选对其无效；相机筛选只对 1.0.9 起新分析的 WebDAV 照片生效（旧 meta 无 cam，清缓存重分析可补）。

## 1.0.9 交付状态（2026-10-09）
- wt-media + wt-photos 已合并到 main，versionCode=9 / 1.0.9，构建通过，签名同 1.0.8。
- 产物：/workspace/work/out/YiWei-1.0.9.apk、YiWei-1.0.9-src.zip
- 全部未真机验证。下一步优先级：
  1. 真机测 AI 自动续跑/重试/暂停原因；人脸合并在重扫后是否保留。
  2. 飞牛：普通目录 file.ls 与 /multiple-download 请求体（抓 fnOS 网页文件管理器一次下载对照修正）；飞牛编辑另存路径。
  3. DTS/TrueHD/AC3 出声；DV 回退与 Profile 5 提示；DLNA 多台电视。
  4. 123 云盘匿名下载（上传首个 APK 后验证，否则回落分享页）。
  5. 生物识别（Android 8–9/10/11+）、高德瓦片与 GCJ-02 对齐、小组件、平板 NavigationRail。
  6. 已知缺口：视频页未过滤隐藏项；WebDAV 视频无时长；相机筛选仅对新分析照片；rmvb/wmv 不支持（需 libVLC ~25MB）；设置页可选加「隐藏相册」入口；大 WebDAV 索引分段未做。

## 1.0.10 交付状态（2026-10-09）
- versionCode=10 / 1.0.10，签名同 1.0.8/1.0.9（SHA-256 ee4af42b…f342b），包名 com.hark.shiguang。
- 改动：
  1. AI 分类读 WebDAV 照片：优先读文件头 128KB 内嵌 EXIF 缩略图，失败再试原图、再试本地缩略图缓存；原因是 1.0.9 每张下载原图，123 云盘 WebDAV 下载流量受限，几十张后全失败→连续 8 张失败自动暂停。状态卡显示具体失败原因（如 HTTP 403）。
  2. 视频页（WebDAV + 飞牛）过滤隐藏项。
  3. 设置「隐私」加「隐藏相册」入口。
- 产物：/workspace/work/out/YiWei-1.0.10.apk、YiWei-1.0.10-src.zip
- 仍未真机验证；飞牛下载签名（/download?...t=&sign=）与 WebSocket file.ls 需真实抓包确认。

## 1.0.10 第二轮（2026-10-09 下午，versionCode 仍为 10 / 1.0.10）
全部改动 **未在真机验证**（沙箱连不上用户 NAS、没有安卓设备）。每项单独提交，提交号见 `git log`。

### ⚠️ 签名已更换（用户决定 A）
- 原 keystore（SHA-256 ee4af42b…f342cb）不在本机，用户决定改用**新 keystore**：项目根目录 `shiguang-new.jks`（JKS，RSA 2048，有效期 36500 天，alias `shiguang`，CN=YiWei），密码在 `keystore.properties`（均已 git-ignore，不进源码包）。备份：`/workspace/work/out/keystore-backup/`（jks + properties）。
- 新证书 SHA-256：`80f279a7ff4a8f4ac0cdd2927a36c23afca47b2fccb9699c9f2743df7a09e46f`。
- **后果：用新签名的 APK 不能覆盖安装旧签名（1.0.8/1.0.9/早先的 1.0.10）版本**，用户需先卸载旧版（本地数据会丢：WebDAV/飞牛账号、索引、人脸、AI 结果需重新登录/重扫）。以后所有版本必须一直用这把新 key，务必另存备份。

### 各项
- **#6 WebDAV 视频时长**：`Meta.dur`（秒，0=未知，-1=读失败不再重试，json 键 `du`）；`Analyzer` 第 3 阶段 `videoDurations`：`DavThumb.finalUrl` 先解 302，再 `MediaMetadataRetriever.setDataSource(url, WebDAV Basic 头)` 读 METADATA_KEY_DURATION；每次 2 个，单文件 25 s 超时（独立线程池，卡死的 retriever 不阻塞后续）；只在 ScanPolicy 允许（或手动「立即继续」）时跑。`DavLib.setPhotos` 把 `Meta.dur` 写进 `Photo.duration` → 高级搜索时长筛选 + 网格时长角标生效。
- **#7 相机型号补扫**：`Meta.camChecked`（json `ck`）。`Analyzer` 第 2 阶段 `camBackfill`：对已分析但没读过相机的照片 Range `bytes=0-131071` + ExifInterface 只取 Make/Model，不重做 AI/人脸/哈希；并发 3；全部完成后写 Store `dav.camfill.<accountId>=1`，以后不再跑。网络失败的下次再试。
- **#8 大图库**：`IndexStore`（Dav.kt）：索引按文件修改时间分月存 `filesDir/dav/<id>/index/<yyyy-MM>.json` + `months.json`（新→旧），只重写变化的分段；旧 `index.json` 首次读取时自动迁移并删除。冷启动 `ensure()` 先读最近 3 个月（`DavLib.RECENT_MONTHS`）上屏，再读其余月份合并；`fullyLoaded` 标志。`removeLocal` 改为从磁盘完整索引删除（防止冷启动半途时把未加载的月份写丢）。网格 key/contentType 未动（原本就稳定）。
  - 未做（留给下一步）：Photo 对象仍一次性全部构建在内存里（5 万张约几十 MB），`setPhotos` 每次全量排序；没有真正的按需分页/Paging；冷启动 <2 s 和 5 万张流畅度都未实测。
- **#9 字幕偏移**：media3 1.3.1 没有干净的内嵌字幕时间偏移入口（自定义 TextOutput 只能"延后"不能"提前"，且 seek/暂停会乱），所以 UI 标为「字幕延迟（仅外挂字幕）」，当前选中的是内嵌字幕或没有外挂字幕时按钮置灰并说明。外挂字幕仍走 SubShift 改时间轴。
- **#3 应用内更新**：只用 www.123684.com / www.123865.com（代码里没有 www.123pan.com）。download/info 返回 **5112（需要登录）** 或任何失败 → 浏览器打开分享页 https://1814107608.share.123pan.cn/123pan/Qpc7Vv-89Ywh；更新对话框里先说明这一点，并多了「打开分享页」按钮；「检查更新」失败也直接打开分享页。匿名 download/info 对文件是否可用仍 **UNVERIFIED**（需要用户先往分享文件夹传一个 apk）。
- **#1 飞牛普通目录（防御性）**：`FnFiles.missingSecret`：登录里没有签名 secret（1.0.9 之前的登录）时，file.ls / downloadUrl 不再发请求，提示「请退出飞牛账号并重新登录一次」（Toast 每次运行一次 + 播放器错误文案 + 文件夹选择器错误）。顺带修了一个 bug：影视播放器在主线程调用 `fs.playUrl`（飞牛 `/multiple-download` 要联网 → NetworkOnMainThread 被 runCatching 吞掉 → 空地址），现在在 IO 线程预先解析。`/multiple-download` 请求体、`file.ls`、`user.authToken` 仍全部 **UNVERIFIED**，需要抓包。
- **#2 飞牛编辑另存**：新文件名 `<原名>_edit_yyyyMMddHHmmss.jpg`，文件夹上传 `Trim-Overwrite=0`，并断言新名≠原名，原图不会被覆盖。上传后服务端若保留「.xxx_taskId」前缀：代码库里**没有已知的飞牛重命名接口**（NasX/FnFiles 都没有），所以没做重命名，**UNVERIFIED**；`Diag` 记录 stored 名称以便排查。
- **libVLC 兼容播放（用户决定 B）**：依赖 `org.videolan.android:libvlc-all:3.7.2`（Maven Central 可下载；3.x 最新是 3.7.7，但 3.7.3 起 POM 依赖 kotlin-stdlib 2.2.10，本项目 Kotlin 1.9.24 读不了 2.2 元数据，所以选 3.7.2）。`ui/VlcPlayer.kt`：仅作后备——.rmvb/.rm/.wmv/.asf 直接用 VLC；其他文件在 media3 报容器/解码不支持时，错误框出现「用兼容模式播放」从当前位置切到 VLC。media3 仍是主播放器。鉴权：VLC 不能带自定义头，走 `CastProxy.serveLocal`（127.0.0.1 本地代理转发 Range + 认证头）。进度照常保存，剧集结束自动下一集（下一集非 VLC 格式时切回 ExoPlayer）。VLC 模式下没有倍速/音轨/字幕选择/投屏菜单。`NameParser.UNPLAYABLE` 只剩 iso。`packaging.jniLibs.pickFirsts += **/libc++_shared.so`。APK 体积明显增加（见下）。
- 名称检查：源码与资源里已无「忆维」。

### 产物
- 见文末「1.0.10 第二轮产物」。

### NEXT-STEPS
1. 真机装新签名 APK（先卸载旧版）：过一遍 HANDOFF 之前的验证清单。
2. 需要抓包才能定的：飞牛 `/multiple-download`（或下载签名）与 websocket `file.ls`/`user.authToken`；飞牛编辑另存后的服务端文件名及重命名接口；123 云盘匿名 download/info（上传 apk 后测）；飞牛人物合并/移出接口。
3. 真机验证 libVLC：rmvb/wmv 在 WebDAV（123 302→CDN）与飞牛（本地代理带 accesstoken/authx）下能否播放、拖动、进度保存；体积是否可接受（可考虑按 ABI 拆分 APK）。
4. WebDAV 视频时长读取在 123 云盘 CDN 上的成功率/流量；相机补扫是否触发 123 的下载流量限制（每张 128 KB）。
5. 大图库：真正的分页/按需构建 Photo、冷启动计时（目标 <2 s / 5 万张）。

### 1.0.10 第二轮产物
- `/workspace/work/out/YiWei-1.0.10-newkey.apk`（= `app-release.apk`，81,532,788 B；aapt2：com.hark.shiguang / versionCode 10 / 1.0.10 / 标签「一维相册」/ arm64-v8a + armeabi-v7a；apksigner：CN=YiWei，SHA-256 80f279a7…09e46f）。
- 比上一版 49.8 MB 大约 31.7 MB，几乎全是 libvlc.so（两个 ABI）；已开 `jniLibs.useLegacyPackaging = true` 压缩 .so（不开时是 145 MB）。如嫌大：按 ABI 拆包（只发 arm64 约省一半 VLC 体积）或去掉 libVLC。
- `/workspace/work/out/YiWei-1.0.10-newkey-src.zip`（git archive，不含 jks / keystore.properties）。
- 注意：之前的 `/workspace/work/out/YiWei-1.0.10.apk`（04:27）经 apksigner 检查**没有签名**（当时 keystore 缺失），上文「签名同 1.0.8」不成立。
- 构建：本机内存紧，gradle daemon 两次被杀、一次 JuiceFS I/O 错误；稳定的命令是 `bash /workspace/tools/build.sh -x lintVitalAnalyzeRelease -x lintVitalReportRelease -x lintVitalRelease -x extractReleaseVersionControlInfo --no-parallel`（失败时先 `gradle --stop` 再试）。
- 第 10 项 rmvb/wmv：用户已决定用 libVLC（见上）。第 11 项飞牛人物合并：仍无接口，维持本机隐藏。

## 1.0.0（公测）· 2026-10-09 下午（versionName 1.0.0 / versionCode 11）
⚠️ **versionName 从 1.0.0 重新开始，但 versionCode 必须一直递增（现在 11，下一版 ≥12）**，否则无法覆盖安装。签名仍是 `shiguang-new.jks`（SHA-256 80f279a7…09e46f）。全部改动 **未真机验证**。
- **#4 结果显示在按钮上**：新组件 `ui/TestButton.kt`（TestButton/TestState）：点后「测试中…」+ 底部进度条（可报告 0–1 进度，否则不定进度）→「成功」（强调色）/「失败」（Danger 色），原因用小字显示在按钮下；`holdMs` 后按钮文字恢复，小字变灰保留，`resetKey`（输入）变化时清空。已用于：AI「测试连接」「测速」、WebDAV 添加/编辑对话框新增「测试连接」（`ui/Pages.kt` DavDialog，`CloudSources.create(draft()).connect()`）、影视 TMDB「保存并测试」、飞牛内网/外网「测速」。
- **#1 OpenRouter 免费模型**（`Ai.kt`、`ui/AiScreen.kt`）：
  - 报错原因（根据代码推断，没有用户原始报错文本）：旧预设的三个模型（meta-llama/llama-4-maverick、openai/gpt-4o-mini、google/gemini-2.5-flash）都是**付费**模型，没充值的账号返回 402；免费模型多为思考型，测试只给 `max_tokens=10`，被推理吃光后 content 为空/null（显示「（空回复）」或分类 JSON 解析失败）；OpenRouter 有时 HTTP 200 但 body 是 `{error:{…}}`，旧代码当成成功；错误解析不读 `error.metadata.raw`（上游真实原因）。
  - 改动：预设改为「OpenRouter（每日免费模型）」+ 3 个 `:free` 看图模型；`HTTP-Referer: https://yiwei.cc.cd` 与 `X-Title: 一维相册`（非 ASCII，`Headers.Builder.addUnsafeNonAscii`）；OpenRouter 请求 `max_tokens ≥1500` + `reasoning:{effort:low, exclude:true}`；`AiHttpException(code, msg, retryAfterMs)`；`errorText()` 解析 error.message / metadata.raw / provider_name，并给 401/402/404(data policy)/429 中文提示；200 带 error 也抛错；finish_reason=length 且无内容时提示换非思考模型。
  - 「获取免费模型」：GET `{base}/models`，保留 pricing.prompt 和 completion 都为 0、输出含 text、id 以 `:free` 结尾（或 `openrouter/free`）的模型，看图（architecture.input_modalities 含 image）排前并标「看图」；选纯文字模型时 Toast + 模型栏下方红字警告。`AiClient.visionOf` 缓存（内存）。
  - 测试连接成功后附「今日免费额度剩 x/y 次」（GET `{base}/key` 的 free_model_daily_requests）。
  - 429：AiRunner 优先用 `Retry-After` / `X-RateLimit-Reset`（毫秒时间戳）等待，最长 6 小时，说明行显示「被限流：… · HH:mm 自动继续」；无头时至少等 60 s 再走原指数退避。
  - 沙箱实测：/models 可匿名访问，当天 19 个价格为 0 的模型；无 Key 时 chat 返回 `{"error":{"message":"Missing Authentication header","code":401}}`。**没有真实 Key，未实测 chat。**
- **#2 LLM 测速**：`AiClient.speed()` 发一次约 30 字的请求，显示「延迟 N ms · X tokens/s」（completion_tokens / 总耗时，含网络和首字时间，所以偏保守）；按钮上显示 ms。
- **#3 飞牛内网/外网测速**（`data/Endpoint.kt` speedTest/rememberFaster、`ui/Home.kt` ConnectionSheet）：每个地址一行「测速」按钮；延迟 = 3 次 GET `$base/` 中位数；下载 = 约 3 秒循环下载（当前账号前 24 张照片缩略图，URL 前缀换成该地址，带 accesstoken/authx——签名只覆盖 path，两个地址都有效；外加 fnOS 网页 index.html 里的 js/css），显示 Mbps/秒数/MB。自动模式：两边都测过且外网明显更快（吞吐 >1.3 倍，或无吞吐时延迟 <一半）时，12 小时内两边都通也优先外网（Store `net.faster.<账号id>`）；否则仍是内网通就用内网。
- **#5 版本**：`app/build.gradle.kts` versionCode 11 / versionName 1.0.0；`BuildInfo.code`、`BuildInfo.label`（「1.0.0（11）」）；关于页「一维相册 1.0.0 公测版 · 版本号 11」；反馈/崩溃信息带 versionCode。
  - **应用内更新改为比较 versionCode**（`AppUpdate.kt`）：123 分享文件夹里的安装包必须命名为 `一维相册-1.0.0-11.apk`（也认 `一维相册-1.0.0(11).apk`、`一维相册_1.0.0_vc11.apk`），更新说明 `更新说明-1.0.0-11.txt` 或 `更新说明-1.0.0.txt`。不带 code 的文件名视为公测前旧包（≤1.0.10 → versionCode ≤10），永不提示；不带 code 且版本名 >1.0.10 时才退回按版本名比较。下载后 `getPackageArchiveInfo` 再查 versionCode，不比当前新就拒绝安装。「以后再说」按 `名称-code` 记。
  - App 名：strings/Manifest/所有中文资源均为「一维相册」（aapt2 label 已核对）。

### 1.0.0 产物
- `/workspace/work/out/YiWei-1.0.0.apk`（81,556,587 B；aapt2：com.hark.shiguang / versionCode 11 / 1.0.0 / 「一维相册」；apksigner：CN=YiWei，SHA-256 80f279a7ff4a8f4ac0cdd2927a36c23afca47b2fccb9699c9f2743df7a09e46f）。注意：此路径之前放的是早期 1.0.0（versionCode 4）旧包，已被覆盖。
- `/workspace/work/out/YiWei-1.0.0-src.zip`（git archive HEAD，不含 jks / keystore.properties）。
- 上传到 123 分享文件夹时请改名为 **`一维相册-1.0.0-11.apk`**（否则旧版 App 的更新检查认不出；旧版 1.0.10 App 仍按版本名比较，1.0.0 < 1.0.10，**旧版不会提示这次更新**，而且新签名本来也不能覆盖安装旧签名包，需卸载重装）。

### NEXT-STEPS
1. 真机：OpenRouter 免费模型（真实 Key）测试连接/测速/获取免费模型/分类；429 时说明行与自动继续时间；X-Title 中文头是否被 OpenRouter 接受（若报错改成 URL 编码或英文 "YiWei Album"）。
2. 真机：飞牛内网/外网测速数值是否合理（缩略图签名在外网地址上是否通过、FN Connect 中转地址）；自动模式偏好外网的逻辑是否会误选。
3. TestButton 在深浅色四套主题下的颜色；WebDAV 对话框里测试与「连接」按钮并存是否清楚。
4. 之后每次发版：versionCode +1，安装包命名 `一维相册-<versionName>-<versionCode>.apk`。
5. 仍待（沿用 1.0.10 清单）：飞牛 /multiple-download 与 file.ls 抓包、libVLC 真机、大图库分页、123 匿名下载。

## 1.0.1 · 影视线（wt-movies 分支 v101-movies，2026-10-09）
只改了影视相关文件 + 几处一行接入；**全部未真机验证**（沙箱在境外、无安卓设备、无 TMDB/弹弹play 密钥）。版本号未动（合并者统一改）。
- **#1 TMDB 自动网络** `TmdbNet.kt`：有密钥时（App 启动 `TmdbNet.start` 一行在 `App.onCreate`；首次刮削/「保存并测试」也会触发）3 s 探测 `api.themoviedb.org/3/configuration`（接受 TMDB JSON，含无密钥时的 401 status_code）与 `image.tmdb.org/t/p/w92/…png`。不通 → 用户填的接口/图片代理优先 → 内置候选并行探测、按顺序取第一个通的：API `https://api.tmdb.org`（TMDB 官方备用域名）、`https://tmdb.movie-pilot.org`（MoviePilot 公共中转；沙箱看到的是 Cloudflare Origin 证书，设备上 TLS 不过就自动跳过）；图片 `https://images.tmdb.org`、`https://wsrv.nl/?url=https://image.tmdb.org`（直接拼 `/t/p/<size>/<file>`）。境外直连通就保持原地址。结果存 Store `tmdb.net`，24 h、网络切换（NetworkCallback）、请求网络层失败时重测（`Tmdb.get` 失败 → `TmdbNet.onFailure()` 换了地址就重试一次）。设置页 TMDB 卡显示「直连 / 已自动使用代理 xxx」+「重新检测」。图片 URL 统一按 `https://image.tmdb.org/...` 存，显示/下载时 `TmdbNet.imgUrl()`（`posterModel`）换成当前图片地址。
  - 候选地址的「国内可达」来自公开资料（StrmAssistant wiki、MoviePilot 文档），**没有在大陆网络实测**；运行时探测兜底。
- **#6 刮削精准度**（`Movies.kt` NameParser / Tmdb.rank/decide / MovieLib，`ui/MovieDetail.kt`）：清洗英文标签（分辨率/编码/来源/音轨/平台/发布组）、中文标签（中字/国语/高清/双语/无删减…）、站点水印（www.xxx.com、电影天堂/阳光电影…）、方括号（动漫 `[组][标题][01]` 取标题）；年份取「最后一个前面有标题的合理年份」（Blade.Runner.2049.2017 → 2017）；中文名后的英文名作 altQuery；集数 SxxExx / SxxEPxx / 1x05 / 第x季第x集 / 第x集(话) / EPxx / ` - 01` / `[01]`；季号取 Season 文件夹或名字里的 S02/第二季；`{tmdb-123}`/`[tmdbid=123]` 直接用；movie.mkv/CD1 之类借文件夹名；根目录下的剧集按标题分开。匹配：带年份搜索（结果少再不带年份），对 标题/原名/（前 3 名相似度不足时）alternative_titles 做归一化相似度 + 年份（同年 +0.2、±1 +0.1、更远 −0.3）+ 人气（≤0.05）。只有总分 ≥0.80、相似度 ≥0.6 且明显领先（差 ≥0.05，或同名同年，或人气 5 倍以上）才自动采用；否则 `pending=true`（海报角标「待确认」，详情页「可能是 X（年）」→「是它 / 不是」）。优先级：手动修正 > `{tmdb-}` 标签/NFO `<tmdbid>`/`<uniqueid type=tmdb>` > 搜索打分 > 大模型解析名字后再打分。详情页按钮「重新识别」→ 对话框（电影/剧集、名称+年份搜索，候选带海报/年份/类型/简介，「自动重新识别」）。手动选择记在 `movies/fixes.json`（清空影视库、重扫后仍生效）。NameParser 用 javac + 编译后的 class 在 JVM 上跑过 30 个样例名。
- **#11 弹幕**（`Danmaku.kt`、`ui/DanmakuView.kt`、`ui/MoviePlayer.kt`）：弹弹play 开放 API，签名模式头 `X-AppId / X-Timestamp / X-Signature = base64(sha256(AppId+Timestamp+Path+AppSecret))`（doc.dandanplay.com/open）。设置 → 影视 → 「弹幕（弹弹play）」填 AppId/AppSecret（默认空，显示「需在弹弹play开放平台申请 AppId」）。匹配顺序：记住的结果 → `POST /api/v2/match`（文件名去扩展名、大小、时长；非计费网络或开了「移动网络也精确匹配」时 Range 读前 16 MiB 算 MD5，hash 缓存）→ isMatched 直接用 / 否则按作品名相似度+集号挑 → `/api/v2/search/episodes?tmdbId=…&episode=` → 按标题搜。弹幕 `/api/v2/comment/{id}?withRelated=true&chConvert=1`（缓存 6 h），应用 match 的 shift。`DanmakuView` 放在 PlayerView 的 overlayFrameLayout 里（视频+字幕之上、控制栏之下，点击穿透），按播放器时间逐帧绘制：滚动（同速不超车）/顶部/底部 4 s、彩色、描边；播放器顶栏「弹幕」菜单：开关、手动匹配（作品名+集数搜索，选中后记住）、弹幕设置（不透明度/字号/显示区域=密度/速度/三种模式/彩色）。VLC 兼容模式不显示。**没有 AppId 无法实测接口**；签名算法与文档示例一致。
- **#2 视频与影视隔离（影视侧）** `MediaIsolation.kt`：`isMediaPath(sourceKey, path)`、`roots(sourceKey)`、`exclude/davVisible/fnVisible`、`version`（Compose 重算用）；`MovieLib.saveDirs` 调 `invalidate`。已接入：`ui/Extra.kt` VideosTab（WebDAV、飞牛各一行）、`ui/Home.kt` 飞牛时间线与 DavPhotosTab 的 `stills` 各一行。飞牛 Photo.path 是 showFilePath 还是 /vol1/… 未确认，两边都去掉 `fm:`、`volN/uid/` 后按前缀比较。
- 合并注意：`App.kt` 一行、`ui/Home.kt` 两行、`ui/Extra.kt` 两行、`ui/MoviesTab.kt`（posterModel、待确认角标）；其余都在 Movies.kt / TmdbNet.kt / Danmaku.kt / MediaIsolation.kt / ui/MovieDetail.kt / ui/MoviePlayer.kt / ui/DanmakuView.kt。library.json 新键 pd/gs/mn/aq 向后兼容（旧版本忽略）。
- 待真机验证：大陆网络下 api.tmdb.org / 代理选择与设置页文案；海报经 wsrv.nl 加载；待确认阈值是否过严/过松；弹幕匹配率（非动漫的电视剧/电影在弹弹play 覆盖有限）、16 MiB 指纹在 123 云盘 302 CDN 上能否 Range、DanmakuView 在 SurfaceView 上的叠加与性能；飞牛路径隔离。

## 1.0.1 交付（2026-10-09 晚，versionName 1.0.1 / versionCode 12，master）
- 合并顺序 v101-movies（快进）→ v101-ai → v101-photos，唯一冲突 ui/Ext.kt（ExtScreen 并列保留 Onboarding / BgGuide / MemoryClip）。
- #13（NEXT-PLAN #9）首次使用引导 ui/Onboarding.kt：WIP 代码核对后完整，原样编译通过（commit 1150140）。
- #9（NEXT-PLAN #13）回忆短片：MemoryVideo.kt（MediaCodec H.264 720×1280 24fps 4Mbps，Canvas 绘制 → 编码器 flexible YUV Image，片头 2.5s + 每张 3s + 0.6s 交叉淡化 + Ken Burns，结尾 1s 淡出；res/raw/memory_ambient.m4a 原样拷贝 AAC 并按视频长度截断；最多 30 张按时间均匀抽样，只用照片不用视频）；ui/MemoryClip.kt（标题、进度/取消、分享到微信等=系统分享 video/mp4 走 FileProvider cache-path、系统播放器预览、保存到相册 Movies/一维相册）。入口：Collection 页（飞牛人物/地点/相册/收藏等）顶栏胶片图标、WebDAV 人物页顶栏、那年今天行右侧「生成短片」。
- **签名更换**：用户没有 1.0.0 的 shiguang-new.jks（80f279…e46f），1.0.1 起改用新密钥 yiwei-release.jks（alias yiwei，PKCS12，RSA 4096，有效期 100 年），证书 SHA-256 = 04812323e88a940c72008ab5c5ba171ab8c094376d366627501b55ef3f0bc4c3。1.0.0 及更早版本必须先卸载再装 1.0.1（应用内更新会安装失败）；1.0.1 之后用这把钥匙即可覆盖升级。jks 与 keystore.properties 只交给用户，不进源码包。
- 未真机验证（新增）：回忆短片各厂商编码器（YUV 平面布局/码率）、生成耗时（每帧 CPU 转 YUV，30 张约 1–2 分钟估计）、分享到微信的兼容性、Android 8–9 保存到相册（可能需存储权限，失败会提示「保存失败」）。

## 1.0.2（2026-10-09 傍晚，versionName 1.0.2 / versionCode 13，master）
用户需求原文见 NEXT-PLAN.md「1.0.2 需求」。签名仍用 yiwei-release.jks（SHA-256 04812323…3f0bc4c3），用户手里有 YiWei-signing-key.zip。
已完成并编译通过：
- #1 重复/相似并入 AI 整理：ui/DavGroups.kt 不再自己扫描（去掉「正在读取文件列表…」），只显示 Analyzer 已算出的 md5/dHash 结果，随 metaVersion 自动刷新；未分析完时顶部提示「已分析 x/y」+「去 AI 页」（HomeState.openAiTab()）。AI 页「清理空间」行显示组数和分析进度。飞牛：DuplicatesScreen 去掉扫描按钮，DiscoverTab 每 12 h 自动 NasX.repeatCheck(true)。
- #3 进度同步：新 ScanStatus.kt 是唯一进度源，ScanService 通知、照片页副标题（正在扫描 x/y 个文件夹）、AI 页整理卡（新增「读取文件」行）读同一数字；通知 1 s 刷新，空闲约 2 s 移除；Analyzer 启动时先写入真实已分析数，不再从 0 开始。
- #2 网络自适应：新 NetEnv.kt。检测 VPN/系统代理；对用到的主机（WebDAV 账户、飞牛地址）分别测「经 VPN」和「绑定底层 Wi-Fi/蜂窝」两条路由的 HEAD 延迟，直连明显更快就把该主机的 DNS 和 socket 绑到底层网络（NetEnv.dns + NetEnv.socketFactory，已接入 FnClient.http 及其派生的 CloudHttp/播放器/Viewer、Coil、DavThumb）。VPN 不允许绕过时（bindSocket EPERM）留在 VPN，并把扫描并发 6→12、分析 4→8，测试连接结果下方给出提示（把主机设为直连 / 分应用代理排除一维相册）。网络变化时重测，结果 30 min 过期。
- #4 仅完成改名：底部导航、影音页标题、设置分组「影视」→「影音」。
未真机验证：NetEnv 绕过在 Clash/v2rayNG/sing-box 等下是否生效、DNS 走底层后 123 云盘是否命中国内节点；飞牛 repeatCheck 自动触发是否会和 NAS 自身任务冲突。

## 1.0.2 · #4 影音 · 音乐播放器的原始建议（已在 1.0.3 实现，见下一节；与实际做法不同处以下一节为准）
目标：在「影音」页加「音乐」分段（与 电影 / 剧集 并列），播放飞牛和 WebDAV 网盘里的音乐，自动刮削，多格式。
建议实现（尽量复用现有代码）：
1. 数据：新建 Music.kt。曲库来源 = 用户在设置「影音」里勾选的音乐文件夹（照 Movies 的文件夹选择与 MediaIsolation 隔离做法，音乐不进照片/视频库）。WebDAV 用 cloud/DavScan 的 PROPFIND 列目录（按扩展名过滤），飞牛用 data/FnFiles.kt 的 file.ls。扩展名：mp3 flac ape wav m4a aac alac ogg opus wma dsf dff aiff wv。索引存 filesDir/music/<source>.json，增量（按 path+size+mtime）。
2. 刮削：先读内嵌标签——HTTP Range 读文件头 256 KB（ID3v2 在头部；FLAC/Vorbis comment、MP4 moov 多数在头部；APEv2/ID3v1 在尾部需再读尾 128 KB），用 MediaMetadataRetriever 对本地临时片段或直接对 URL（带鉴权头）取 title/artist/album/封面/时长；歌词优先同名 .lrc，其次内嵌 USLT。缺失项再联网补：国内可直连的源需真机核实（网易云/QQ 音乐非官方接口有风险，可做成可关闭选项）；无标签时按「歌手 - 歌名」文件名与文件夹名（专辑）推断。封面缓存到 cacheDir/music-art。
3. 播放：media3 MediaSessionService（后台播放、通知栏/锁屏/蓝牙控制），数据源复用 PlayHttp.client（OkHttpDataSource，带 WebDAV Basic / 飞牛 accesstoken 头）。已依赖 org.jellyfin.media3:media3-ffmpeg-decoder（含 ape/wma/alac/dsd 等音频解码，需核实 DSD 与 wma 在该构建里是否开启），渲染器用 EXTENSION_RENDERER_MODE_PREFER。Manifest 加 service（foregroundServiceType=mediaPlayback 及权限 FOREGROUND_SERVICE_MEDIA_PLAYBACK）。
4. 界面：MoviesTab 顶部分段加「音乐」；列表按 歌曲/专辑/歌手/文件夹；迷你播放条常驻底部导航上方；全屏播放页（封面、进度、上一首/下一首、随机/单曲/列表循环、滚动歌词）。ScanStatus.current() 里加音乐扫描进度。
5. 交付流程照旧：compileReleaseKotlin → build.sh → aapt2 badging 核对 1.0.2/13 → apksigner 核对证书 SHA-256 04812323e88a940c72008ab5c5ba171ab8c094376d366627501b55ef3f0bc4c3 → git archive 源码包（不含 jks/keystore.properties/local.properties）。
构建机注意：2 核 8 GB 无 swap，Kotlin 用 -Pkotlin.compiler.execution.strategy=in-process --max-workers=2，否则 Gradle daemon 会被 OOM 杀掉；不要 pkill（会把 shell 会话一起杀掉）。

## 1.0.3（2026-10-09 晚，versionName 1.0.3 / versionCode 14，master）· 影音 · 音乐播放器
签名仍是 yiwei-release.jks（SHA-256 04812323…3f0bc4c3）。git 历史：上一个源码包没有 .git，本版先 `git init` 把 1.0.2 源码作为基线提交（b69c852），之后每项一个 commit。**全部未真机验证**（沙箱在境外、无安卓设备、连不上用户的 NAS / 网盘）。

### 与 1.0.2 建议不同的决定
- 访问层用 `MovieFs`（DavFs / FnFs），不另写 PROPFIND / file.ls。库按 `Movies.keyOf()` 分：`filesDir/{dav|fn}/<id>/music/`（config.json 与 movies/config.json 同格式，MediaIsolation 两份都读）。
- 封面、歌词缓存放 `music/art/`、`music/lyrics/`（filesDir），没放 cacheDir：系统清缓存后封面不会全部变空白。
- 标签用自写的 Range 解析器（`MusicTags.kt`），没用 jaudiotagger（依赖 java.nio.file / ImageIO，Android 不全）。MediaMetadataRetriever 没用（网络 URL 上会长时间卡住）；标签里没有时长时，播放时由播放器回填（`MusicLib.setDuration`）。
- Jellyfin FFmpeg AAR 实际编译了 ape / wmav1 / wmav2 / wmapro / wmalossless / alac / dsd_* / wavpack 解码器，但 media3 1.3.1 没有 APE、ASF(WMA)、DSF/DFF、WavPack、AIFF 的解封装器，所以这些格式**交给 libVLC**（APK 里本来就有，零体积增加）。

### 文件
- `PlayHttp.kt`：原 MoviePlayer 里的 private object 抽出来共用；`resolve(url, headers, followRedirect)`（123 云盘 302 → CDN 签名地址后**不带** WebDAV 认证头，15 min 缓存，行为与 1.0.2 影视一致）、`range(url, headers, from, len)`。MoviePlayer 改为调用它（逻辑不变）+ 进入视频播放时 `MusicState.pause()`。
- `MusicTags.kt`：ID3v2.2/2.3/2.4（含 unsync、APIC/PIC、USLT、TXXX）、ID3v1、APEv2（含 Cover Art）、FLAC（STREAMINFO/Vorbis comment/PICTURE）、Ogg Vorbis/Opus（注释包、METADATA_BLOCK_PICTURE、尾部 granule 算时长）、MP4/M4A（ilst、mvhd 时长、stsd 判断 aac/alac，moov 在 mdat 之后也能跳过去读）、Monkey's Audio 头时长、WavPack 头时长、WAV（fmt/data/LIST INFO/id3 块）、AIFF（COMM 80 位采样率/ID3 块）、DSF（fmt + 元数据指针处 ID3）、DFF、ASF/WMA（Content Description / Extended / WM/Picture / File Properties 时长）。ID3「ISO-8859-1」字段里的 GBK 自动识别；.lrc 文本按 BOM → 严格 UTF-8 → GBK 解码。每个文件头部 256 KB 一次 Range，需要时再读尾部 160 KB / 大封面（上限 6 MB），每文件最多 8 次请求。**JVM 上用 ffmpeg 生成的 12 个样本跑过**（mp3/flac/m4a 两种/ogg/opus/wav/aiff/wma/wv、APEv2+GBK ID3v1 手工样本）：标题/歌手/专辑/年份/音轨/时长/封面/内嵌歌词全对。APE、DSF、DFF 没有编码器，未用真文件测。
- `Music.kt`：`Track`、`MusicLib`（扫描、增量：path+size+mtime，飞牛 mtime=0 时只比 size；「重新刮削」= 全部重读标签）、`MusicPrefs.online`、`Music`（扩展名、EXO/VLC 分流、歌手拆分 / 、 & feat. 等）、`Lrc`（多时间标签、[offset:]、同时间戳原文+译文合并）。扫描：列目录并发 `CloudMedia.SCAN_PARALLEL`，读标签并发 4（慢网 6）；封面优先内嵌，其次同目录 cover/folder/front/albumart/封面.jpg（或唯一一张图）；歌词顺序：缓存 → 同名 .lrc → 内嵌 → 联网（找不到记 .none，7 天后再试）。无标签时文件名推断「01. 歌名」「歌手 - 歌名」，文件夹名当专辑（CD1/Disc 2 取上一级并记碟号）。
- `MusicOnline.kt`：网易云 `GET https://music.163.com/api/cloudsearch/pc?s=…&type=1`（沙箱实测可用；`/api/search/get/web` 现在返回加密串、`/api/search/pc` 要求绑定手机，均不可用）+ `/api/song/lyric?id=…&lv=1&tv=-1`；LRCLIB `/api/get` 兜底歌词。只补缺失字段，打分（歌名归一化 + 歌手 + 时长±3 s + 专辑）达到阈值才采用；超时 6/8/12 s，失败静默，不影响扫描和播放。设置可关。**大陆网络下可用性未实测**（沙箱在美国）。
- `MusicPlayer.kt`：`SimpleBasePlayer`，自己管队列/循环/随机/位置，当前曲目交给 ExoPlayer（独立实例，FFmpeg 扩展优先，音频焦点、拔耳机暂停、WAKE_MODE_NETWORK）或 libVLC（`--no-video`，走 `CastProxy.serveLocal` 带鉴权，自己申请音频焦点 + 拔耳机广播 + PARTIAL_WAKE_LOCK/WifiLock）。随机 = 重排队列（当前曲目放第一），关闭随机还原原顺序，所以通知栏/蓝牙「下一首」与 App 显示一致。播放地址在开始播放时才解析（飞牛下载 token、123 CDN 签名会过期）；ExoPlayer 报错先清缓存重新解析地址重试一次，格式不支持则改用 VLC，再失败 Toast 提示并 1.5 s 后跳下一首（不整队停止）。
- `MusicService.kt`：`MusicService : MediaSessionService`（通知渠道「音乐播放」、小图标 ic_notify、点通知打开全屏播放页）；`onAddMediaItems` 原样放行（mediaId = "<sourceKey>|<track id>"）；划掉任务且未在播放时自停。`MusicState`：MediaController + Compose 状态（mediaId/isPlaying/队列/循环/随机/时长），所有 UI 命令都走它。循环/随机模式存 Store `music.repeat` / `music.shuffle`。
- `ui/MusicTab.kt`：影音页顶部「电影剧集 | 音乐」（`MoviesState.area`，存 `movies.area`）；音乐页：搜索、全部播放、随机播放、歌曲/专辑/歌手/文件夹，长按或 ⋯：下一首播放 / 加到播放列表 / 查看专辑 / 查看歌手 / 文件信息；格式角标（FLAC/APE/DSD/ALAC/WMA…）；`MusicListScreen`（专辑/歌手/文件夹详情）；`MiniPlayer`（Home 底部导航上方，宽屏在底部）；`MusicNowPlayingScreen`（封面/滚动歌词切换，点歌词跳转，进度拖动，列表循环→单曲循环→随机，播放列表底部弹窗可点播/删除/清空）；`MusicSettingsScreen`（音乐文件夹增删、立即扫描、重新刮削、联网补全开关、清空音乐库）。设置 → 影音 新增「音乐」入口。
- 接入：`MediaIsolation.roots()` = 影视 + 音乐目录（照片/视频页排除音乐文件夹，含其中的 cover.jpg）；新增 `musicRoots/isMusicPath`，`MovieLib.walk` 跳过音乐目录；`ScanStatus.current()` 加音乐扫描（通知与页面同一数字）；`FsEntry` 加 `mtime`（DavFs 填 getlastmodified）；`FolderPicker` 改为 internal、参数 (fs, kind, fmOnly)，音乐里飞牛根目录只列「文件管理」文件夹（相册文件夹只返回照片视频）。Manifest：MusicService（exported，intent-filter MediaSessionService，foregroundServiceType=mediaPlayback）、FOREGROUND_SERVICE_MEDIA_PLAYBACK、WAKE_LOCK。依赖：`androidx.media3:media3-session:1.3.1`。
- 1.0.2 的 Analyzer/AI 重复相似、NetEnv、ScanStatus 均未改动逻辑（只在 ScanStatus 末尾加一行音乐）。

### 待真机验证
1. 各格式实际能否播放：mp3/flac/m4a(AAC/ALAC)/ogg/opus/wav（ExoPlayer）；ape/wma/wv/aiff/dsf/dff（VLC 经本地代理）。DSD 在 VLC 3.x 下靠 avformat，**可能不出声或只能部分设备播放**；放不了会提示并跳过。
2. 后台 30 分钟以上、锁屏、通知栏、蓝牙耳机按键、拔耳机暂停、来电/导航抢焦点恢复；国产 ROM 后台限制（可能需要电池优化白名单，现有 BgGuide 的引导同样适用）。
3. 123 云盘：302 CDN 地址过期后续播（会自动重解析一次）；飞牛 `/multiple-download` 是否支持 Range（不支持则头部标签仍可读、尾部 APEv2/ID3v1 读不到；拖动进度可能失败）——仍 **UNVERIFIED**，与影视同。
4. 隔离：加音乐文件夹后照片/视频页不再出现其中文件；影视扫描不再进入音乐目录。
5. 网易云 cloudsearch 在大陆网络的速度与匹配准确率；LRCLIB 在大陆是否可达（不可达只是没歌词）。
6. 大曲库（上万首）首次扫描耗时与流量（每首约 256 KB + 部分 160 KB 尾部），列表滚动流畅度（目前一次性全部在内存，未分页）。
7. 已知小问题：投屏面板「停止投屏」会 `CastProxy.stop()`，正在用 VLC 播放的音乐（ape/wma 等）会断，需要重新点播；迷你播放条会盖住照片网格最后一行的一部分（照片页底部留白未加大）。

### 1.0.3 修复：首次扫描音乐 OOM 闪退（OPPO PHY110 / Android 16，2026-10-09 19:40）
- 崩溃：主线程 OutOfMemoryError（256 MB 堆满，栈只是压垮的位置）。原因推断（无内存快照）：读标签并发 4–6，每首可能同时持有 6 MB 级的大块（大封面 / moov）+ 块缓存副本 + 图片原始字节；每扫完一首就改一次 tracks 触发整页重组，并且每 20 首就把整个曲库 JSON 重写一次。
- 改动（Music.kt）：读标签并发 3；全局 `BIG_GATE`（同一时刻只有一个 >512 KB 的大块在内存里，含文件夹封面图）；块缓存总量上限、整块命中不再复制；封面统一缩到 600 px JPEG（inSampleSize 解码，不再生成 36 MB 位图）；超过 12 MB 的文件夹图片不当封面；扫描结果每秒批量写入列表一次（按 id 建索引，不再 O(n²)），曲库 20 秒最多保存一次。Manifest 加 `android:largeHeap="true"`（照片库本身也吃内存）。
- 版本号仍是 1.0.3 / 14（同 versionCode 可直接覆盖安装）。未真机复测。

### 1.0.3 修复：应用内更新找不到新版（2026-10-09 19:50）
- 原因 1（已实测）：123 分享文件夹「一维相册」里实际是 `YiWei-1.0.0.apk / YiWei-1.0.1.apk / YiWei-1.0.3.apk`，旧正则只认 `一维相册-<版本>[-code].apk`，没有 versionCode 的名字又被当成公测前旧包 → 永远「已是最新」。现在 `YiWei-` / `一维相册-` 前缀都认；没写 versionCode 的按 versionName 比较（公测起版本名只增不减）；下载后仍用 getPackageArchiveInfo 核对真实 versionCode，不比当前新就拒装。更新说明也认 `YiWei-更新说明-1.0.4.txt` / `更新说明-1.0.4.txt`。
- 原因 2（已实测）：匿名 `share/download/info` 对 apk 文件返回 **5112「您需要注册登录或付费后下载」**（两个域名都一样），App 不能免登录直接下。新增 `AppUpdate.viaWebDav`：本机若添加过 123 云盘 WebDAV 账户（分享者本人的盘），在根目录或下一级找「一维相册」文件夹里同名同大小的文件，用 WebDAV（302 → CDN，跨域自动去掉认证头）下载；都不行才打开浏览器分享页。WebDAV 里能否看到这个文件夹 **UNVERIFIED**（取决于分享文件夹在盘里的位置）。其他公测用户仍会走浏览器分享页（要登录 123）。若想所有人免登录直下：在 123 分享设置里开免登录下载（是否需要会员未核实），或换一个可直链的存储。
- 注意：已经装在手机上的旧 1.0.3 还是旧逻辑，必须手动装一次这个包；之后往分享文件夹传 `YiWei-1.0.4.apk`（versionCode ≥15）就能被检测到。同一版本号重新打的包不会被提示。
