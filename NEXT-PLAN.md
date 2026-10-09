你接手一个 Android 项目「一维相册」（包名 com.hark.shiguang），当前版本 1.0.9（versionCode=9），已构建通过但全部新功能未真机验证。请先读源码根目录的 HANDOFF.md、HANDOFF-影视.md、TODO-1.0.9.md，再按下面方案做 1.0.10。

【项目基本信息】
- Kotlin + Jetpack Compose（BOM 2024.06.00，Kotlin compiler ext 1.5.14），minSdk 26，targetSdk 34。
- 依赖：media3 + org.jellyfin.media3:media3-ffmpeg-decoder:1.3.1+2（只解音频，已开启 core library desugaring）、osmdroid 6.1.18、profileinstaller。
- 照片源两种：WebDAV（如 123 云盘 WebDAV）和飞牛 fnOS 相册；影视走飞牛。
- 底部导航必须保留：照片 / 视频 / 影视 / AI / 设置（≥600dp 时为 NavigationRail）。
- 新页面统一在 ui/Ext.kt 的 ExtScreen 里分发（MainActivity 用 Route.X(ExtScreen)）。
- 签名证书 SHA-256（1.0.1 起）必须保持：04812323e88a940c72008ab5c5ba171ab8c094376d366627501b55ef3f0bc4c3（yiwei-release.jks，alias yiwei）。1.0.0 的旧证书已不可用，1.0.0 及更早用户需卸载重装一次；以后不能再换 keystore。keystore 和 keystore.properties 不在源码包里（已 git-ignore），由用户另外提供，放到项目根目录。
- 应用显示名一律「一维相册」，不要再出现「忆维」。
- 用户偏好：先听完全部需求，再统一修改，一次性交付完整 APK + 源码包；每完成一项就 git 提交，并更新 HANDOFF.md 的 NEXT-STEPS。

【一、必须修正（依赖未确认接口，最可能出问题）】
1. 飞牛普通目录播放：data/FnFiles.kt 的 downloadUrl() 用的是 /multiple-download，请求体是推测的。做法：在 fnOS 网页「文件管理」里下载一个文件，用浏览器开发者工具抓请求（URL、方法、headers、body、签名方式），按实际情况修正；若还是拿不到直链，就改为通过 websocket 申请下载 token，或在 Dlna.kt 已有的本地代理上加鉴权转发。file.ls（websocket，用相册登录的 secret 签名）同样需要按实际抓包核对。旧登录可能缺签名 key，要提示“退出重新登录一次”。
2. 飞牛照片编辑另存：ui/Editor.kt 假设照片显示路径就是真实目录，且飞牛 folder-view 上传可能把文件存成「.前缀_taskId」。需要实测并修正上传路径和文件名，确保原图不被覆盖。
3. 123 云盘应用内更新（AppUpdate.kt）：文件夹列表接口可用（www.123684.com / www.123865.com，不要用 www.123pan.com，那个返回 HTML）。匿名请求 download/info 返回过 code 5112「需要登录」。等用户往分享里的「一维相册」文件夹（FileId=129194314）上传「一维相册-1.0.9.apk」后实测；如果匿名下载不可用，就保留“打开分享页 https://1814107608.share.123pan.cn/123pan/Qpc7Vv-89Ywh”的回退，并在对话框里说明。

【二、已知缺口（补完）】
4. 视频页（底部「视频」tab）未过滤隐藏相册的项目：接入 Hidden.visible()。
5. 设置「隐私」里加「隐藏相册」入口：Nav.push(Route.X(ExtScreen.HiddenAlbum))。
6. WebDAV 视频没有时长，导致高级搜索的时长筛选无效：在分析阶段用 MediaMetadataRetriever 对远端视频的 HTTP 流（带 WebDAV 鉴权 header）读取时长并写入 Meta；注意只在 ScanPolicy 允许的网络/充电条件下做，并限并发。
7. 相机型号筛选只对 1.0.9 起新分析的照片生效：加一次后台增量补扫，只读 EXIF 头（Range 请求前 128KB），补写 Meta.cam，不重做 AI/人脸。
8. 大图库性能：WebDAV 索引分段/分页加载（按月分段持久化，首屏只读最近几个月），保持网格稳定 key/contentType；目标 5 万张流畅滚动、冷启动 <2s。
9. 字幕时间偏移目前只对外挂字幕生效（通过改写时间戳）；内嵌字幕可研究 media3 的 TextRenderer 延迟/偏移实现，做不到就在 UI 上标明“仅外挂字幕”。

【三、可选/需用户决定】
10. rmvb/wmv 播放：media3 不支持这两种封装，FFmpeg AAR 只解音频。可选方案是 libVLC（约 +25MB），或按 ABI 拆分 APK 来控制体积。先问用户是否接受体积增加，再做。
11. 飞牛人物：飞牛没有已知的合并/移出接口，目前只能本机隐藏。如果抓包能找到 fnOS 相册的人物合并接口，就接入；找不到就维持现状。

【四、真机验证清单（交付前逐项过，修掉问题）】
- AI 分类：随扫描自动启动；断开 Wi-Fi 或停止充电后暂停，条件恢复后续跑；API 失败重试（15s 到 10min 退避）；卡片显示暂停原因；暂停键也能控制 AI。
- 人脸：手动合并在重新扫描后仍然保留；移出照片、隐藏路人生效。
- 播放：DTS/TrueHD/AC3/EAC3 有声音；HDR10/HLG 正常输出；DV Profile 8 回退 HDR10；Profile 5/7 在不支持的设备上有提示；每部剧记住音轨和字幕；跳过片头片尾；DLNA 在 2–3 个品牌的电视上能播放、暂停、拖动。
- 隐藏相册：Android 8–9（锁屏确认）、Android 10、Android 11+（BiometricPrompt）都能验证。
- 照片地图：高德瓦片能加载，WGS-84→GCJ-02 后照片位置和地图对得上，点击聚合点能打开照片。
- 小组件在 MIUI/HyperOS、ColorOS、鸿蒙等桌面上能显示，6 小时轮换一次。
- 平板/折叠屏：NavigationRail、网格列数正常。
- 崩溃弹窗、反馈邮件（1@yiwei.cc.cd，附版本/Android 版本/机型）、QQ 群 887416704 的 mqqapi 跳转（失败时复制群号）。

【构建与交付】
- 构建：bash /workspace/tools/build.sh -x extractReleaseVersionControlInfo（构建机 2 核约 7GB 内存，构建要串行；在其他环境请用 ./gradlew assembleRelease，并配置原签名 keystore）。
- 版本号改为 1.0.10 / versionCode=10。
- 用 aapt2 dump badging 确认应用名和版本，用 apksigner verify --print-certs 确认签名 SHA-256 不变。
- 交付：YiWei-1.0.10.apk + 源码包，并写明哪些功能已真机验证、哪些还没有。

## 待做需求（2026-10-09 用户口述，等说完统一做）
1. 影视：用户填入刮削源（TMDB）后自动识别网络环境——境内自动走反代，境外保持直连原地址。
2. 视频与影视隔离：WebDAV 和飞牛的「照片/视频」tab 不显示影视库里的视频（影视媒体库目录下的文件从视频 tab 排除），影视只在影视板块出现。
3. 加快扫描速度（WebDAV/飞牛照片视频扫描、索引建立；并发 PROPFIND、增量扫描、缩略图/分析与列目录解耦等）。
4. OpenRouter 免费模型自动切换：当前模型额度用完（429/402）或不通（超时/5xx/404）时，自动换到下一个可用的免费视觉模型继续，按列表轮换并记住冷却时间。
5. 实况照片补全：飞牛识别全部 isLive 类型（含安卓 Motion Photo 2/3）并显示角标、长按播放；WebDAV 支持 iPhone HEIC/JPG+MOV 同名配对、安卓 Motion Photo（XMP MicroVideoOffset / ftyp 标记）识别与播放。
6. 影视刮削精准度：文件名清洗（去分辨率/编码/字幕组/网站标签）、提取年份与 SxxExx、用父目录名辅助、TMDB 搜索按年份过滤 + 标题相似度打分，低置信度不自动匹配；优先 NFO/tmdbid；支持手动「重新识别/选择正确条目」。
7. 总原则：功能默认面向国内用户（不翻墙也能用），翻墙只给进阶用户。影响：TMDB 默认走国内可达的反代/镜像并自动检测；AI 默认推荐国内可直连的模型服务（如通义/智谱/DeepSeek/硅基流动等，其中有免费额度的优先），OpenRouter 归为进阶选项；地图已用高德；更新走 123 云盘；所有外网依赖都要有国内可达的默认值与失败提示。
8. 本地 AI 兜底：ML Kit 端侧图像标签离线分类，不耗额度；大模型做细分类和搜索，额度用完不停工。
9. 首次使用引导：照片源 → AI（推荐国内免费模型、一键测试）→ 影视刮削（自动检测反代）。
10. 网盘预设：Alist/OpenList 一键添加（阿里云盘/夸克/百度网盘），坚果云、123 云盘 WebDAV 填写提示。
11. 弹幕：影视播放接弹弹play 弹幕库，按剧集自动匹配。
12. 后台扫描通知：扫描与 AI 整理通知栏进度（前台服务），引导关闭 MIUI/ColorOS/鸿蒙省电限制。
13. 回忆短片：按人物/地点/那年今天生成带音乐的幻灯片视频，可分享到微信。


## 1.0.1 状态（2026-10-09 晚）
- 已完成并合入 master：影视线（TMDB 直连/代理、刮削增强、弹弹play、影视隔离）、AI 线（国内免费模型、自动切换、ML Kit 本地标签、首次使用引导）、照片线（扫描提速/增量、Live Photo、网盘预设、后台扫描通知/自启动引导、回忆短片）。
- 下一步（1.0.2 候选）：
  1. 真机/NAS 过一遍 HANDOFF.md「未真机验证」清单，优先：回忆短片编码与微信分享、飞牛 Photo.path 与影视隔离、增量扫描正确性、国内模型额度。
  2. 回忆短片可选增强：背景音乐多首可选、每张时长可调、YUV 转换改 GL/RenderScript 替代以提速、生成放到前台服务避免切后台被杀。
  3. AppUpdate 安装前比对 APK 签名，签名不同时提示「需先卸载旧版」，避免以后再换钥匙时静默失败。


## 1.0.2 需求（2026-10-09 用户口述）与状态
1. 重复照片不要单独扫描，并入 AI 分析；重复/相似页只显示扫完的结果 —— 已完成
2. 开着翻墙时 123 云盘延迟很高，App 自动检测网络环境并调整 —— 已完成（NetEnv，待真机验证）
3. 后台扫描通知进度与 App 内不同步 —— 已完成（ScanStatus 统一进度源；用户尚未说明具体哪处不一致，两种情况都已处理）
4. 影视改名影音，加入音乐播放器（飞牛/网盘音乐、自动刮削、多格式）—— 仅完成改名，播放器方案见 HANDOFF.md「1.0.2 · 下一个 AI 要做」
