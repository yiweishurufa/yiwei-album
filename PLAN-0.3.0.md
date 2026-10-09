# 一维相册 0.3.0 需求（用户 2026-10-09 确认）
（用户原文方案，全部要做）
一、
1 首页精致度/图标：统一细线条图标（1.6px 描边，选中加粗+底色块），大标题「10月」，日期分组带地点，留白圆角重调。
2 设置中心：账号卡片置顶；「外观」=主题（浅/深/跟随系统）、强调色、网格；「功能」=备份、WebDAV、AI、应用锁、缓存、关于。
3 连接挪到首页：顶栏胶囊「家里的飞牛 · 内网 · 12ms」，点开=内外网地址、延迟、自动/仅内网/仅外网。设置里不再有连接。
4 返回不回顶部：每页记住滚动位置；大图滑到别的照片，返回定位到最后看的那张。
5 多账号：胶囊面板顶部一排头像一点即切，每账号独立地址/登录/缓存。
6 只留 WebDAV：删 123/百度/OpenList 来源；支持多个 WebDAV。
7 飞牛/WebDAV 隔离：首页最上方分段开关，缓存分开；WebDAV 模式的「人物」位置换「文件夹」(并进相册顶部)。
8 飞牛相册整理：新建、改名、设封面、删除、批量加减，写入飞牛。
9 WebDAV 自动整理：地点(EXIF GPS)、重复(hash)、相似(dHash) 手机本地算；AI 分类和智能搜索用用户自填大模型接口。
10 WebDAV 相册：新建/删除，采用「虚拟相册」：清单存 WebDAV 根目录一个小 JSON 文件，照片不移动。
二、
- 登录：地址栏支持 IP、域名、FN ID；输入域名自动补 http/https 和默认端口（先试 https:443 / 5667 等飞牛默认端口，再 http:5666，探测可用者）；端口必须允许用户手动修改（地址栏旁单独端口输入，留空=自动）；局域网自动发现 NAS；二步验证；记住历史登录。
- 备份：首页一行显示备份状态；前台服务备份。
- 时间线：右侧日期浮标拖动跳月；双指缩放日/月/年。
- 应用锁：指纹（BiometricPrompt，无新依赖可用 android.hardware.biometrics.BiometricPrompt, API28+；26-27 跳过）。
- AI：只传 512px 缩略图、不传位置；仅 Wi-Fi 充电时跑；月预算到自动暂停；可选飞牛照片也用 AI 搜索。
- AI 提供商预设：主流全加，尤其 Meta Llama（Meta Llama API api.llama.com OpenAI 兼容，模型如 Llama-4-Maverick-17B-128E-Instruct-FP8），另 OpenAI、Anthropic Claude、Google Gemini、DeepSeek、通义千问、智谱 GLM、Kimi、豆包、硅基流动、OpenRouter、Groq、Together、Ollama/自定义 OpenAI 兼容。用户自填 key/baseUrl/model。
三、
- 默认主题：深色 + 蓝色强调色；浅色版也做。
- 包名 com.hark.shiguang、签名 shiguang.jks 不变；versionCode 4 / versionName 1.0.0；APK 命名 YiWei-1.0.0.apk（用户定版本 1.0.0）。
- 底栏统一「照片 · 相册 · 发现 · 设置」，右上角只留搜索；人物并进发现。
