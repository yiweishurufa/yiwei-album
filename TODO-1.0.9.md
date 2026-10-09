# 1.0.9 待改（用户逐条口述，说完再统一改）
1. 所有地方改名为「一维相册」（已确认，替换「忆维」；含 app_name、关于、通知、HANDOFF、APK 文件名 YiWei 可保留）
2. 飞牛影视：选择影视文件夹只能选相册文件夹，选不了 NAS 上别的文件夹 → 需找飞牛文件管理（非相册）目录浏览接口
3. AI 分类卡在 48/3211：AI 页 LaunchedEffect 不启动 AiRunner；暂停/继续按钮不含 AiRunner；AiRunner 仍用自己的「仅 Wi-Fi+充电」(ai.wc 默认1)不跟 ScanPolicy；遇失败/预算/条件不满足即 break 不再恢复；状态文案(预算/报错)不显示在卡片。→ 并入 ScanService + ScanPolicy，自动续跑，失败重试，卡片显示 AI 暂停原因。
4. 设置去冗余：
   a. 账号入口三处（设置顶部卡「切换与管理」、照片组「WebDAV 账户」、顶部来源切换「管理 WebDAV」）→ 设置里只留顶部卡
   b. 网络/充电三套（整理组 移动网络也扫描/不充电也扫描；AI 页 仅 Wi-Fi 且充电；备份 仅 Wi-Fi）→ 扫描+人脸+AI 统一用整理组两项，删 AI 页那项；备份「仅 Wi-Fi」也并入（用户确认合并）
   c. AI 页「只发送缩略图（固定开启）」假开关 → 改成说明文字
   d. 人脸、大模型、影视库设置在设置页和各 tab 各有入口（保留 tab 内入口，设置页作总入口，可接受）
5. 关于里加「反馈」(mailto:1@yiwei.cc.cd，附版本号/机型) 和「加入群聊」(QQ群 887416704：点击尝试 mqqapi 跳转 QQ，失败则复制群号)
6. 影视软解：加 FFmpeg 音频软解（DTS/DTS-HD/TrueHD/AC3/EAC3 等，Jellyfin media3-ffmpeg-decoder 或自编），尽量扩充可播格式；评估 rmvb/wmv 能否软解（libVLC 体积大，需权衡）
7. 杜比视界/HDR：播放用 SurfaceView HDR 输出、enableDecoderFallback；检测机型能力；DV P8→HDR10 回退，P5 非 DV 机型提示，P7 只播基础层；详情页/版本列表加「杜比视界 / HDR10 / 4K / 音轨格式」标识
8. 用户要求加入全部建议功能（第一批 1-6 + 第二批 7-13）：
   - 应用内检查更新（更新文件放用户网盘，地址/格式待定）
   - 崩溃自动记录 + 一键发反馈邮箱
   - 人物手动调整：合并、移出照片、隐藏路人
   - 影视播放细节：按剧记住音轨/字幕语言、跳片头片尾、字幕大小/位置/偏移
   - 那年今天
   - 大图库提速（baseline profile、索引分段）
   - DLNA 投屏到电视
   - 照片地图
   - 隐藏相册（指纹）
   - 简单编辑：裁剪/旋转/亮度，另存传回
   - 桌面小组件（那年今天/随机回忆）
   - 平板/折叠屏布局
   - 高级搜索：日期范围/相机/时长/类型
9. UI 再整体美化一轮
10. 自动更新源：123云盘分享 https://1814107608.share.123pan.cn/123pan/Qpc7Vv-89Ywh （shareKey=Qpc7Vv-89Ywh，无提取码）
    - 列表 API：GET https://www.123684.com/b/api/share/get?limit=100&next=1&orderBy=file_name&orderDirection=asc&shareKey=..&SharePwd=&ParentFileId=<id>&Page=1&event=homeListFile&operateType=1 （头 platform: web, App-Version: 3；www.123pan.com 返回 HTML，需用 123684/123865 域名）
    - 根目录有文件夹「一维相册」FileId=129194314（目前为空）
    - 约定：文件夹里放 一维相册-<版本>.apk，可选 更新说明-<版本>.txt；app 取最大版本号比较 BuildInfo
    - 下载：POST /b/api/share/download/info {ShareKey,FileID,S3keyFlag,Size,Etag} 拿 DownloadURL（待有文件时实测）；失败则打开分享页
