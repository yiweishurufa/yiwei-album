<div align="center">

# 一维相册

Android 原生飞牛 fnOS 相册客户端 —— 家里的 NAS、手头的云盘，一个 App 全收纳。

![Kotlin](https://img.shields.io/badge/Kotlin-1.9.24-7F52FF?logo=kotlin&logoColor=white)
![Compose](https://img.shields.io/badge/Jetpack%20Compose-4285F4?logo=jetpackcompose&logoColor=white)
![minSdk](https://img.shields.io/badge/minSdk-26-3DDC84?logo=android&logoColor=white)
![version](https://img.shields.io/badge/version-1.0.3-orange)

</div>

## 功能

**时间线**
- 日 / 月 / 年三档视图，双指捏合直接切换
- 右侧时间快速拖动条，长按滑动多选，批量收藏 / 分享 / 删除
- 回忆、去年今日，人物与地点自动归集

**多源聚合**
- 飞牛 fnOS 相册（登录、时间线、相册、人物、AI 搜索）
- 第三方云盘：OpenList、WebDAV、123 云盘、百度网盘，统一浏览
- 内外网自动探测切换，在家走局域网、出门走外网

**影视**
- 电影 / 剧集目录刮削：海报、年份、简介自动匹配
- 缩略图到大图一镜到底的查看器

**体验**
- 浅色 / 深色 / 跟随系统，克制配色，Android 原生感
- 离线可用，照片只走你自己的服务器

## 截图

（截图待补充，放在 `docs/images/` 后会在这里展示）

## 构建

环境：JDK 17、Android SDK 34、Gradle 8.7

```bash
# 一键准备环境
bash tools/setup.sh
source tools/env.sh

# 构建
bash tools/build.sh
```

产物在 `app/build/outputs/apk/`。`tools/` 下还有 `gen_memory_music.py` 等辅助脚本。

## 签名

正式包签名信息放在 `keystore.properties`（已被 `.gitignore` 排除，不会提交）：

```properties
storeFile=yiwei-release.jks
storePassword=***
keyAlias=***
keyPassword=***
```

> 请勿把 `.jks` 签名文件提交到公开仓库。

## 项目结构

```
app/src/main/java/com/hark/shiguang/
├── App.kt            应用入口与全局状态
├── MainActivity.kt   路由与导航
├── ui/               Compose 界面（时间线、首页、主题、影视…）
├── data/             飞牛 API 客户端与数据层
├── cloud/            第三方云盘接入层
└── Ai.kt / Movies.kt AI 搜索与影视刮削
```

## 许可

待定。
