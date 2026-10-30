# 飞牛官方「飞牛相册」独立 App 调研（附：飞牛影视搜索页 / 播放器）

调研时间：2026-10-30 22:20–23:00 CST。官方 App 10-29 刚上线，第三方深度评测还很少（上线不到 48 小时），下文凡是来自"推断"或"只有单一来源"的都已标注。
图标：✅ 有官方出处 · 🟡 第三方/媒体出处 · ❓ 推断或未核实

---

## 0. 时间线与版本（先把事实钉死）

| 日期 | 事件 | 来源 |
|---|---|---|
| 2026-09-30 | 博主发现"独立相册 App 即将上架" 🟡 | https://www.foxnan.com/2026/09/5605.html （2026-09-30） |
| 2026-10-15 | 官方开启独立相册 App 内测招募（10-18 截止报名，10-20 公布名单）✅ | https://club.fnnas.com/forum.php?mod=viewthread&tid=74879 （2026-10-15）；IT之家 https://www.ithome.com/1/013/586.htm （2026-10-15） |
| 2026-10-20 | 首批 150 名体验官名单；提醒"相册 App 与飞牛 App 备份只开一个" ✅ | https://club.fnnas.com/forum.php?mod=viewthread&tid=75195 （2026-10-20） |
| 2026-10-22 | 服务端相册应用 v0.9.14：手机备份可"关联已有同型号设备的备份记录并沿用原目录"、修 Google Photos 导入、回收站同名冲突、LIVP 识别、SMB 挂载目录权限校验 ✅ | https://club.fnnas.com/forum.php?mod=viewthread&tid=75293 （2026-10-22） |
| **2026-10-29** | **飞牛相册 App v1.0.2 正式上线**：安卓官网可下（各商店审核中），iOS、鸿蒙已上架；同日飞牛主 App v1.37.3 新增"与相册 App 备份互斥" ✅ | https://club.fnnas.com/forum.php?mod=viewthread&tid=75758 （2026-10-29） |
| 2026-10-30 | 博主：内测仅一周就公测，"最大变化是 UI"，未来"有望加桌面小组件" 🟡（小组件是博主推测，非官方承诺） | https://www.foxnan.com/2026/10/6839.html （2026-10-30） |

官方 10-29 更新日志里关于独立 App 的**全部**文字只有三条：①全新 UI"更简洁、更通透、更自然"；②新增「沉浸视图」"无缝流畅的照片浏览体验"；③新增「合集」页"聚合相册、人物、地点等照片合集，支持调整排序"。✅（tid=75758）
→ 也就是说：**独立 App 的服务端仍是同一个 trim.photos（相册应用 v0.9.x），功能基本是主 App 相册的子集+新 UI**，新东西主要是"沉浸视图""合集页"两个交互层。

---

## 1. 官方相册功能全清单（按模块）

说明：独立 App 复用服务端相册应用的能力，以下大多数功能在主 App/网页已存在；"独立 App 是否全部搬过来"官方没有逐项列表，凡标 ❓ 表示未能核实独立 App 内是否已有入口。

### 1.1 全局导航（来自用户提供的 10-30 截图 + 官方日志）
- **底部三件套**：左「筛选」圆按钮、中间胶囊 Tab「图库 / 合集」、右「搜索」圆按钮。（用户截图，2026-10-30）
- 两 Tab 结构：图库 = 时间线；合集 = 所有聚合入口。✅（tid=75758 + 截图）

### 1.2 图库（时间线）
- 按拍摄时间流展示全部照片/视频，"当前相册以时间流展示为主"。✅ 官方回复 https://club.fnnas.com/forum.php?mod=viewthread&tid=60630 （2026-04-08）
- 时间轴 / 文件夹两种视图可切换。✅ 官网 https://fnnas.com/
- 「查看当天照片」：在预览里一键定位回时间轴当天。✅ 相册 v0.8.36 https://club.fnnas.com/forum.php?mod=viewthread&tid=21754 （2025-04-10）
- 筛选「未整理的照片」（未进相册）。✅ App v1.25.0 https://tech.ifeng.com/c/8oT6x9tLtRf （2025-11-21）
- 异步生成缩略图、可手动刷新异常缩略图。✅ 相册 v0.9.9 https://club.fnnas.com/forum.php?mod=viewthread&tid=68402 （2026-07-23）
- 显示拍摄时区，可编辑时区。✅ App v1.28.1 https://club.fnnas.com/forum.php?mod=viewthread&tid=59707 （2026-03-27）

### 1.3 沉浸视图（新）
- 官方原话："新增「沉浸视图」，带来无缝流畅的照片浏览体验"。✅（tid=75758）
- ❓ 具体形态官方没写。从措辞"无缝"和"沉浸预览时切换视频"（这个词早在 2025-08 的 iOS 修复里出现过，https://h5.ifeng.com/c/vivoArticle/v0024AqjopEEwcYoFlf--CdI7kmynDSSS2isMSAArQZ6rqZY__ ，2025-08-21）推断：是**去掉网格边距/日期头的大图流（类似 iOS 照片的「全部照片」紧凑大图或 Google Photos 的沉浸滑动），左右/上下连续翻阅、隐藏 UI**。建议让用户补一张沉浸视图截图再定稿。

### 1.4 合集页（新）——分区确认情况
| 分区 | 确认程度 | 怎么用 | 对应接口（见第 4 节） |
|---|---|---|---|
| 相册（用户自建/共享相册） | ✅ 官方明说"聚合相册" | 点卡片进相册 | `/api/v1/album/list` |
| 人物 | ✅ 官方明说 | 人脸聚类头像墙，点进人物照片 | `/api/v1/ai-person/list` |
| 地点 | ✅ 官方明说 | 按城市/地点卡片，点进该地照片 | `/api/v1/explore/geos`（+ 地图 `/api/v1/map/*`） |
| 资料记录 | ✅ 用户截图 | 横滑卡片：证件、身份证、证书、转账记录…（AI"智能识别"的文档类分类） | `/api/v1/ai-smart-rec/categories?mainCategory=information` |
| 标签 | ✅ 用户截图（空状态"为照片添加标签后，相关内容会按标签整理并显示在这里"） | 手动标签聚合 | 标签列表 ❓（推测走 `search/filterlist` 的 `photo_tag` 维度），照片用搜索 filter `photo_tag` |
| 媒体类型 | ✅ 用户截图 | 两列按钮：视频、实况/动态照片、动图、RAW、全景、360°照片视频、屏幕截图 | `/api/v1/media_category/list` + 搜索 filter `file_type` |
| 事物/场景（智能分类 scene） | ❓ 推断有：主 App 一直有"事物场景、资料记录两个分类"（https://club.fnnas.com/forum.php?mod=viewthread&tid=21550 ，2025-04-08），截图里出现了资料记录，场景类大概率在截图之外 | 猫狗/海边/美食等 | `/api/v1/ai-smart-rec/categories?mainCategory=scene` |
| 宝宝相册 / 共享 / 收藏 / 回收站 | ❓ 未核实是否放在合集页（官方小红书带"宝宝相册"标签 🟡 https://post.smzdm.com/p/apqmw7pw/ ，2026-10-20） | — | 收藏用 filter `is_collect`；回收站 `/api/v1/recycle-bin/*` |
- **分区可折叠**（截图）、**"支持调整排序"**（官方）——即用户可拖动调整各分区顺序 ✅。

### 1.5 人物（AI 人脸）
- NAS 本地人脸识别、自动聚合人物相册；可命名、合并、隐藏/显示、从人物移除照片、在预览里手动加面孔创建人物、把相似面孔批量并入。✅ https://help.fnnas.com/zh-CN/articles/v1/photo/photo-ai
- 三个高级参数：置信度阈值、生成人物最少照片数、相似度差异阈值（"宽松聚合"、相似多孩场景）。✅ 同上
- 人物"创建相册并共享"，照片随人物自动同步。✅ 2025-08-21 IT之家（上文 ifeng 链接）
- 相册可"设置人物为条件"自动收录。✅ photo-ai 帮助页

### 1.6 地点 / 地图
- 地图相册（按 GPS 打点、按地点浏览）。🟡 B站演示"备份、去重、文字搜图、宝宝相册、地图相册" https://www.bilibili.com/video/BV1DvTKzMEw3 ；官网"地图"相册 ✅ https://tech.ifeng.com/c/8oPqm0T08W6 （2025-11-19，1.0 内测公告"人物、宝宝、地图、场景等多种智能相册"）

### 1.7 搜索
- 普通条件搜索：关键词 + 多维筛选（文件类型、收藏、人物、标签、目录、地点、日期、相机型号、曝光/ISO/焦距/光圈、未入相册/无标签/无定位…），返回总数和按年月日聚合的时间线。✅ 官方 CLI 文档 https://github.com/fnnas-labs/fnos-cli-skill/blob/main/skills/fnos-cli/reference/photos.md （v0.4.0，2026-10-16）
- **AI 以文搜图**（CLIP 类语义搜索，例："抱着孩子""海边散步"）；开"视频增强识别"可搜视频内容（仅 mp4/mov）。✅ photo-ai 帮助页
- 以图搜相似照片。✅ App v1.25.0（2025-11-21）
- 底部「搜索」独立入口、「筛选」独立入口。✅ 截图
- ❌ 没有 OCR 文字搜图（用户仍在许愿）。🟡 https://post.smzdm.com/p/apqmw7pw/ （2026-10-20）

### 1.8 预览 / 查看器
- 原图、RAW、实况（iPhone Live、安卓各品牌动态照片、vivo 动图）、GIF、360° 照片/视频（缩放拖拽）、全景。✅ 相册 v0.8.36、v0.9.0（2026-03-27）、App v1.25.0
- 视频转码播放（省流/兼容）。✅ App v1.25.0
- 幻灯片：顺序/随机、可调速度。✅ App v1.25.0
- EXIF 信息、描述编辑、查看照片所在相册并跳转。✅ v0.8.28 / v1.25.0
- 安卓动态照片可存成 iOS 实况到本地（iOS）。✅ App v1.28.1

### 1.9 备份上传
- 手机自动备份，原图/RAW/实况/动图无损。✅ 1.0 内测公告 https://tech.ifeng.com/c/8oPqm0T08W6
- 备份完成后"释放手机空间"。🟡 2026-08-20 官方视频，转述 https://post.smzdm.com/p/apqm6nx7/ （2026-10-14）
- 指定相册备份、排除不需要的相册（iOS）。🟡 https://post.smzdm.com/p/apqmw7pw/ （2026-10-20，转述 9 月更新）
- 换手机时"关联已有同型号设备的备份记录、沿用原目录"。✅ v0.9.14（2026-10-22）
- 与飞牛主 App 备份互斥，防止同机双份上传。✅ 主 App v1.37.3（2026-10-29）
- 网页端拖拽上传整个文件夹；Google Photos Takeout 导入（还原相册、收藏、标签、描述）。✅ 2026-03-27 更新
- OPPO/一加/真我（ColorOS 17）系统相册原生直连备份与浏览。✅ IT之家 https://www.ithome.com/0/960/718.htm （2026-06-05）

### 1.10 整理
- 相册：创建、改名、封面、条件相册（人物/标签等条件自动收录）。✅ photo-ai 帮助页；条件相册按标签不自动收新图的 bug 🟡 https://club.fnnas.com/forum.php?mod=viewthread&tid=36197 （2025-09-02）
- 收藏、标签（只存数据库，不写回文件 EXIF/XMP）。✅ 官方回复"有规划" https://club.fnnas.com/forum.php?mod=viewthread&tid=14346 （2025-02-08）
- 相似/重复检查：合并、保留、预览。✅ v0.8.36
- 回收站：可还原收藏、标签、人物和所属相册。✅ v0.8.36
- 文件夹管理（扫描目录、SMB 挂载目录）。✅ v0.9.14

### 1.11 分享
- 分享给设备内其他用户（共享相册）。✅ https://help.fnnas.com/zh-CN/articles/v1/photo/photo-share （标题可见）
- 外链分享：免登录、可设密码和到期，相册外链随照片更新同步；集中管理所有链接。✅ 2025-08-21（ifeng 链接）

### 1.12 编辑
- ❓ 未找到官方相册"裁剪/调色"编辑功能的更新日志（只有元数据编辑：描述、时区）。判断：**官方几乎没有图片编辑**。

### 1.13 回忆
- ❌ 没有"那年今日""节假日相册""回忆视频"，用户在许愿。🟡 https://post.smzdm.com/p/apqmw7pw/ （2026-10-20）

### 1.14 隐私 / 加密
- 全部 AI 在 NAS 本地跑。✅ photo-ai 帮助页
- 系统级访问码（Web/客户端前置校验）。✅ https://www.mydata-api.com/info/444.html （2026-08-03 转述 07-23 官方更新）
- ❓ 未找到"隐藏相册/加密保险箱"类功能的官方记录。

### 1.15 多用户 / 家庭
- 按 NAS 账号隔离个人图库；共享相册给设备内用户。✅
- ❌ "家人空间 / 公共家庭相册"未上线，用户从 2026-05 催到现在。🟡 smzdm 10-14、10-16、10-20 三篇
- ❌ 相册不能直接选"团队文件夹"作为来源（影视可以），社区用 `ln -s` 绕过。🟡 https://www.cuixinjiang.cn/archives/fnnas-73981 （2026-10-12）
- 问"A 账户识别后 B 账户是否需重新 AI 识别"——无官方答复。🟡 tid=75195 34 楼（2026-10-24）

### 1.16 设置 / AI
- AI 模型下载/离线导入、GPU/NPU 加速（Intel/NVIDIA/AMD ROCm、9 月起更多 NPU 与老 J 系列核显）。✅ photo-ai 帮助页；系统 v1.2.0800 https://club.fnnas.com/forum.php?mod=viewthread&tid=73572 （2026-09-24）
- 维护工具：重新识别 EXIF、重置识别、执行未识别。✅

---

## 2. 用户评价

### 好评
1. **手机端体验在 NAS 相册里排第一**：搜索分类方便、比群晖丝滑；Live Photo 原格式无损、网页端可播放动态效果。🟡 https://post.smzdm.com/p/a03xglpr/ （2026-10-22）；https://post.smzdm.com/p/apqmw7pw/ （2026-10-20）
2. **独立 App 降低家人门槛**："打开就是相册""给家人装更方便"，社区里"帮我妈备份太麻烦""从群晖转来一直的痛"类回复很多。✅ tid=74879 回帖（2026-10-15/16）；🟡 foxnan 09-30
3. **免费、AI 全本地**（人脸 + 以文搜图 + 智能分类），低端机也能跑。🟡 smzdm https://post.smzdm.com/p/anvq0l6v/ （2026-08-19）
4. **迭代快**：内测一周即上线。🟡 foxnan 10-30

### 主要吐槽 / 缺失（按出现频率）
1. **无双向同步删除**：手机删了 NAS 还在。🟡 https://post.smzdm.com/p/a03xdgq8/ （2026-10-16，B站+小红书两端被反复顶起）
2. **没有电视端 / Apple TV 看相册**（"挂了两年"）。🟡 同上；✅ tid=74879 多条回帖；第三方已做了 "NAS Photo TV" 填坑 https://club.fnnas.com/forum.php?fid=12&filter=typeid&typeid=3
3. **iOS 后台无感备份**不行。🟡 同上；✅ tid=75195 5 楼
4. **家庭空间/公共家庭相册缺失**。🟡 三篇 smzdm
5. **重复备份**：同一张图反复备份，有人核哈希攒出 8 个副本。🟡 https://post.smzdm.com/p/apqm6nx7/ （2026-10-14）——官方 10-29 的"备份互斥"正是为此打补丁
6. **ProRAW 发黑、Live Photo 还原失效**（知乎实测）。🟡 smzdm 10-14 / 10-16
7. **团队文件夹 / 远程挂载（SMB/WebDAV）不能当相册源**；官方答"远程挂载体验会比较差，待评估"。✅ https://club.fnnas.com/forum.php?mod=viewthread&tid=40670 （2025-10-24 官方回复）
8. **AI 以文搜图在 J/N 系列上一次 30–55 秒**（SQL 里 `WHERE vector <-> $2 < $3` 让 HNSW 索引失效，变全表扫描）。🟡 https://www.cuixinjiang.cn/archives/fnnas-74627 （2026-10-12）
9. **AI 识别时 CPU 打满整机卡**（老平台），1.2.0800 起 J5005 才有硬件加速。🟡 https://www.cuixinjiang.cn/archives/fnnas-75712 （2026-10-30）
10. **多来源混在一起无法分开浏览、缩略图垃圾文件（_thumbnail）不能过滤、不读文件 keywords/Eagle 标签**。✅ 官方回复"已记录" tid=60630（2026-04-08）、tid=14508（2025-02-10）
11. **标签/评级不写回 EXIF/XMP**。✅ tid=14346
12. **离线/请求失败就退出登录**（希望像群晖一样保持）。✅ tid=75195 26 楼（2026-10-22）
13. 时区 bug：小米截图同步后差 8 小时（0.9.0，已知问题）。✅ https://club.fnnas.com/forum.php?mod=viewthread&tid=59801 （2026-03-28）
14. 许愿：OCR 搜字、那年今日、节假日相册、桌面小组件。🟡 smzdm 10-20、foxnan 10-30

> ⚠️ 独立 App v1.0.2 本身上线才一天多，**还没找到针对它的系统性吐槽帖**；以上大多是对服务端相册能力的长期反馈，独立 App 只换了壳，问题基本继承。

---

## 3. 对一维相册的启示

一维相册现状（读 /workspace/work/repos/yiwei-album，HEAD 2166454 / 1.2.9）：已经接了 `ai-smart-rec/categories`（scene + information）、`media_category/list`、`explore/geos`、`map/*`、`ai-person/*`、`album/*`、`magic-search/do|similar`、`repeat-photo/*`、`recycle-bin/*`、`search/filterlist` 等；`ui/Pages.kt` 已有「智能分类」页（场景与事物 / 文档与信息 / 媒体类型三个 Pill），还有 OnThisDay（那年今日）、TravelAlbums、PhotoMap、HiddenAlbum、Editor。**数据层几乎齐了，差的是"合集式"的组织与沉浸浏览的交互。**

### 3.1 值得借鉴（按价值排序）
| # | 借鉴点 | 价值 | 难度 | 说明 |
|---|---|---|---|---|
| 1 | **「合集」页：一页纵向分区，聚合 相册 / 人物 / 地点 / 事物场景 / 资料记录 / 标签 / 媒体类型**，每区横滑卡片 + 标题"›"进全量 + 可折叠 + 可拖动调整分区顺序（记住在本地） | 极高：把现有散落的入口（智能分类页、人物、地图、相册）收拢成一个家人看得懂的页面 | 中（数据接口全都有，主要是 Compose 布局 + 排序持久化，约 1–2 次维护运行） | 媒体类型区直接用两列圆角按钮；标签区要做空状态文案；资料记录 = `mainCategory=information` |
| 2 | **底部"图库 / 合集"胶囊双 Tab + 左筛选 + 右搜索** 的极简首页导航 | 高：给长辈的心智负担最低 | 中（与现有 4 底部 tab 路线图冲突，需决定：作为照片 tab 内的顶部/底部切换，而不是替换全局导航） | 建议放在"照片"主 tab 内部，不动全局 4 tab |
| 3 | **沉浸视图**：时间线一键切到无边距大图流，连续滑动、隐藏状态栏和日期头，视频/实况自动播 | 高：官方主打卖点 | 中高（需 HorizontalPager/大图预加载、内存控制，配合已有 Viewer） | 先做"网格 ↔ 单列大图流"切换的第一阶段 |
| 4 | 标签作为一等公民（合集里一个区、照片详情可加标签） | 中 | 低–中（`photo_tag` 筛选已在 FnModels；需确认写标签接口 ❓） | |
| 5 | 备份互斥提示：检测到飞牛 App/飞牛相册 App 也开着备份时提示二选一 | 中（避免用户被官方那套"8 个副本"坑） | 低 | 仅做提示即可，无法读取官方 App 状态时，提供设置里的说明 |
| 6 | "查看当天照片"：大图里一键回到时间线当天位置 | 中 | 低 | |
| 7 | 筛选"未整理的照片"（`not_album`） | 中 | 低（filter 已定义，值 `1` 未核实） | |

### 3.2 官方做得差、一维可以做得更好
| 官方痛点 | 一维可做的 | 难度 |
|---|---|---|
| AI 以文搜图在低端 NAS 上 30s+ | 一维已有本地 OCR / 端侧 AI（Ai.kt、OcrRunner）：**先返回本地索引结果、后台再并入 magic-search 结果**，加 loading 骨架和超时 | 中 |
| 没有 OCR 文字搜 | 已有 OcrRunner → 在搜索页明确给出"图片里的文字"结果分组，把它作为宣传点 | 低（已有） |
| 没有那年今日/回忆 | 已有 OnThisDay、TravelAlbums → 放进合集页顶部"回忆"区，官方没有 | 低 |
| 没有 TV 端 | 一维有投屏（CastUi）→ 合集/沉浸视图加"投到电视放幻灯片" | 中 |
| 团队文件夹/WebDAV/网盘不能当相册源 | 一维已支持 WebDAV/网盘（cloud/Dav*）→ 合集页加"其他来源"区，多来源可分开浏览（正是官方 tid=60630 用户要的） | 低–中 |
| 离线即掉登录 | 保持会话 + 离线显示缓存缩略图 | 中 |
| 标签不写回 EXIF/XMP | 可选"导出时写入 XMP keywords"（只对下载/导出文件，不碰 NAS 原图） | 中 |
| 双向删除缺失 | 手机端"释放空间/同步删除"需要备份记录比对，风险大，**不建议现在做** | 高 |

---

## 4. 官方是否公开了新 API？

**结论：没有为"合集 / 沉浸视图"公开任何新接口；但飞牛在 2026-09-24 起有了一份"官方出品、公开在 GitHub 上"的相册接口说明，可当半官方依据。**

1. **飞牛应用开放平台（developer.fnnas.com）开放 API 第一期**只覆盖：文件授权、页面路由、界面语言/主题、平台配置、错误码——**不含相册**。✅ https://developer.fnnas.com/api/overview/ （抓取于 2026-10-30）
2. **官方 fnOS CLI + Skill**（fnnas-labs，MIT，版权方 广州铁刃智造技术有限公司；系统 v1.2.0800 起支持 OAuth 授权，2026-09-24 发布，Skill v0.4.0 提交于 2026-10-16）公开了相册接口说明：✅ https://github.com/fnnas-labs/fnos-cli-skill/blob/main/skills/fnos-cli/reference/photos.md
   - 鉴权：系统 OAuth access token，经代理 `/ogh/ac/h/*uri` 调原生 HTTP API，**不再需要相册私有签名**（这对一维相册是重要信号：将来可以走 OAuth 正门）
   - `GET /p/api/v1/photo/folder/list` 扫描目录（含照片/视频数、扫描状态、写权限、配额）
   - `POST /p/api/v2/search/results` 条件搜索：`keyword`、`limit(1..200)`、`filters[]`、`antiFilters[]`；常见 filterName：`file_type`（photo/video/live_photo/gif/raw/360/panorama/screenshot）、`is_collect`、`person`、`photo_tag`、`file_dir`、`not_geo`；返回 `list`、`timeline[{year,month,day,itemCount}]`、`total`
   - `GET /p/api/v1/gallery/getOne?id=` 详情（EXIF、经纬度、ownerId/ownerName、isCollect、isLive、标签、各尺寸缩略图 URL）
   - 预览尺寸 `xxs/xs/s/m/o`（o=原图）
   - `POST /p/api/v1/magic-search/ready` / `magic-search/do {keyword, antiFilters}` AI 语义搜索；无稳定 total；业务码 4044 = 模型不支持当前语言
   - `photos request` 可调任意 `/api/...` 或 `/p/api/...` 未封装端点
3. **合集页各分区的接口（官方未单独公开，均为一维相册已在用的私有接口，社区/抓包得来）**：
   - 相册 `GET /api/v1/album/list`；人物 `GET /api/v1/ai-person/list`；地点 `GET /api/v1/explore/geos {offset:0,limit:-1}`、地图 `/api/v1/map/previewAllPoint|photoList|timeLine`；资料记录 / 事物场景 `GET /api/v1/ai-smart-rec/categories {limit:-1, mainCategory:"information"|"scene"}`，分类照片走 `magic-search/do {keyword:分类名}`；媒体类型 `GET /api/v1/media_category/list`（category 1..8 = photo/video/live_photo/gif/raw/360/panorama/screenshot），照片走 `search/results` + `file_type`；标签：`photo_tag` 筛选（标签列表来源 ❓，推测 `GET /api/v1/search/filterlist` 中的 photo_tag 项）。来源：yiwei-album 代码 `app/src/main/java/com/hark/shiguang/data/FnExtra.kt`、`FnClient.kt`、`FnModels.kt`。
   - ❓ "合集分区排序"是否存服务端（有无 `collection/sort` 之类接口）未知，独立 App 未开源、无抓包，**建议一维直接本地存排序**。
4. 其他参考：第三方 trim-cli 封装（techysy/fnos-trim-cli-skill）也有同样的 photos 说明，另有影视接口 `/v/api/v1/search/list?q=`、`/api/v1/play/info`，影视需 Media 独立 OAuth token。🟡 https://github.com/techysy/fnos-trim-cli-skill/blob/main/reference/media.md
5. 缩略图流地址形态 `/p/api/v1/stream/p/t/{photoId}/s/{uuid}`。🟡 https://club.fnnas.com/forum.php?mod=viewthread&tid=66745 （2026-07-02）

---

## 5. 顺带：飞牛影视的搜索页与播放器（供用户口述第 1、2 条参照）

### 搜索 / 筛选
- 全局搜索片名、演员；基于影片信息多条件筛选。✅ App Store 官方描述 https://apps.apple.com/cn/app/%E9%A3%9E%E7%89%9B%E6%92%AD%E6%94%BE%E5%99%A8/id6738575136
- 媒体库筛选维度：影视类型、类型、分辨率、动态范围、音频规格、国家地区、发行年代、是否已观看；排序：标题、评分、发行年代、添加日期（升/降）；海报带豆瓣评分、分辨率、年份角标。🟡 横评 https://www.163.com/dy/article/JIL2BTBU05562UIT.html （2024-12-05，早期版本）
- 可多选条件组合。🟡 https://www.mfunz.com/soft/23871.html
- 搜索分词优化（影视 v0.8.57）。✅ https://tech.ifeng.com/c/8oT6x9tLtRf （2025-11-21）
- 用户截图（refs/movie-search-ref.webp）显示的行式筛选条（分类/排序/类型/地区/年份）与此一致。

### 播放器
- 倍速、码率/清晰度（含"原画"）、音轨、画面比例、TV 投屏、字幕（自动匹配下载、多语言切换、外挂、字号/位置/时间偏移）。✅ App Store 官方描述
- 跳过片头片尾（可自定义时长）。✅ 播放器 v1.4.1 https://view.inews.qq.com/a/20250710A08QQG00 （2025-07-10）
- 画中画、离线下载、横屏、ASS 特效字幕、杜比视界。✅ 1.0 公告、v1.25.0、10-29 日志
- 用户许愿：长按倍速（类似 B 站）。✅ https://club.fnnas.com/forum.php?mod=viewthread&tid=19495 （2025-03-22，官方"已在需求池"）
- 用户截图（refs/player-ref.webp）的布局：顶左返回+标题、顶右齿轮；左侧旋转/比例；右侧锁屏；进度条两端时间；底栏 倍速/原画 | 上一集/-10s/播放/+10s/下一集 | 音轨/字幕/选集。

---

## 6. 未能确认 / 建议后续补
1. 沉浸视图的具体交互（需用户补截图或录屏）。
2. 合集页截图之外的分区（人物/地点/相册的具体位置与卡片形态、是否有事物场景/宝宝相册/共享区）。
3. 标签列表的确切接口、写标签接口。
4. 独立 App 是否已包含主 App 相册的全部功能（例如外链分享、幻灯片、回收站入口）。
5. 合集排序是否云端同步。
