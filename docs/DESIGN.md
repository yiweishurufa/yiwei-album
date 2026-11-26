# 一维相册 设计语言 1.6.0「暗房与相纸」

> 2026-11-25 起草，第 1 阶段。用户要求：「装上这些技能，然后根据技能重新设计一维相册的 UI 交互 模块，动画 图标 字体 排版 颜色 元素等等」。
> 本文件是所有界面代码的依据；token 的唯一出处是 `app/src/main/java/com/hark/shiguang/ui/Theme.kt`（颜色 / 字号 / 间距 / 圆角 / 动效）、
> `Skeuo.kt`（层级与阴影）、`KubaiIcons.kt`（图标）、`UiKit.kt`（按压、触感）。页面里不写裸数值。
> 每条规则后面的【】注明借鉴自哪个设计技能。分模块的落地进度见 `docs/iteration/redesign-ROADMAP.md`（与工作区 ROADMAP 同步）。

## 0. 不变的边界

- 不改信息架构、不删功能、不改存储格式：`ui.style`（`skeuo` / `flat`）、`themeMode`、`accent`（0–5 索引）等键原样兼容。
- 保留深色 / 浅色 / 跟随系统，保留「界面风格」两档开关（重新定义见 §4）、6 个强调色。
- 照片网格、大图、播放器的性能优先于装饰：网格不加阴影、不做逐格动画。

## 1. 产品气质

- **一句话**：把 NAS 和网盘里的照片、影视、音乐安静地摆出来，让内容本身是屏幕上唯一有颜色的东西。
- **性格**：安静、精确、带一点暖。【unslop · Direction：用具体的词，不用 clean/modern/minimal】
- **世界**：暗房与相纸——中性的灰，像相纸边缘和灯箱；颜色只来自照片、海报、专辑封面。【unslop §2；impeccable Modes：相册是 Experience 模式，界面要退后，作品先行】
- **签名细节**：数字用 Allo 等宽数字（月份大标题、年份、时长、计数），滚动时宽度不跳。【unslop · Signature；typography-system】
- **去 AI 味清单**（新代码禁止）：彩色圆底 + 图标的空状态模板、渐变文字、彩色发光阴影、纸面噪点 / 大渐变底、`!` 感叹号、只为好看的循环动画、层层嵌套的卡片、每个标题前的小 emoji 或 eyebrow 小字。【impeccable craft-floor · Refuse；unslop §4 §8 §9；taste-skill】
- 少说明：一行副标题只放状态值；解释放 ⓘ。（用户 2026-10-23 反馈「小字说明太多」）【product-design-review】

## 2. 颜色

一条中性灰阶（只带极少暖意）+ 一个强调色 + 少量功能色。强调色只用于：主按钮、选中态、关键数字。功能色只表达含义。【unslop §2；color-system；impeccable android.md · Material color roles】

### 2.1 中性色

| token | 简洁·深色 | 简洁·浅色 | 质感·深色 | 质感·浅色 | 用途 |
|---|---|---|---|---|---|
| `C.Bg` | #0E0E10 | #F5F4F1 | #121214 | #EFECE6 | 页面底 |
| `C.Surface` | #18181B | #FFFFFF | #1D1D20 | #FCFBF8 | 卡片、面板 |
| `C.Surface2` | #222226 | #ECEBE7 | #28282C | #E6E3DC | 凹槽、输入框、次级面 |
| `C.Line` | 白 8% | 黑 8% | 白 9% | 黑 9% | 发丝线 |
| `C.Text` | #F2F1EE | #1B1B1D | #F2F1EE | #1E1D1B | 正文 |
| `C.Sub` | #A6A49F | #5C5B57 | #A9A7A2 | #5A5853 | 次要文字、默认图标 |
| `C.Faint` | #7C7A76 | #85847F | #7E7C78 | #83817B | 只给装饰图标、箭头、禁用；**不用于必须读的文字** |

三个文字角色（正文 / 次要 / 弱），不再增加。【unslop §2「三种文字角色」】

### 2.2 对比度（WCAG 2.x，脚本 `contrast.py` 计算）

| 主题 | 正文/底 | 正文/卡片 | 次要/底 | 次要/卡片 | 弱/底（只给图标、禁用） | 错误色/卡片 |
|---|---|---|---|---|---|---|
| 简洁·深色 | 17.1 | 15.7 | 7.7 | 7.1 | 4.5 | 6.7 |
| 简洁·浅色 | 15.6 | 17.2 | 6.2 | 6.8 | 3.4 | 5.3 |
| 质感·深色 | 16.6 | 14.9 | 7.8 | 7.0 | 4.5 | 6.4 |
| 质感·浅色 | 14.3 | 16.3 | 6.0 | 6.9 | 3.3 | 5.2 |

- 正文、次要文字、占位文字 ≥ 4.5:1；弱色 ≥ 3:1（非文字图形的下限）。输入框占位提示改用 `C.Sub`。【accessibility-excellence；impeccable craft-floor · Contrast】

### 2.3 强调色（每个有深色 / 浅色两档）

| 强调色 | 深色值 | 作文字/深色卡片 | 按钮字（近黑）/深色值 | 浅色值 | 作文字/浅色卡片 | 按钮字（白）/浅色值 | 选中胶囊：强调字/14% 浅底（质感浅色） |
|---|---|---|---|---|---|---|---|
| 蓝（默认） | #7AA7FF | 7.4 | 8.1 | #2F62D6 | 5.3 | 5.5 | 4.5 |
| 绿 | #4FCB8A | 8.6 | 9.4 | #17744A | 5.6 | 5.8 | 4.7 |
| 橙 | #FFA45C | 9.0 | 9.8 | #A64E0F | 5.5 | 5.6 | 4.6 |
| 粉 | #FF8AA0 | 7.9 | 8.6 | #B22D4D | 6.0 | 6.2 | 5.0 |
| 紫 | #B89CFF | 7.8 | 8.5 | #6F4BD8 | 5.5 | 5.7 | 4.7 |
| 石墨 | #D9D7D2 | 12.3 | 13.4 | #3A3936 | 11.2 | 11.5 | 9.0 |

- 深色主题的强调色是浅色，按钮上的字用近黑 #0E0E10（白字只有约 2:1）。【unslop §2「accent 上的字要 4.5:1，不够就换深色字」】
- 原「琥珀」与「橙」几乎同色，索引 5 改为无色相的「石墨」，给想让照片成为唯一颜色的人。
- `C.AccentSoft` = 强调色 16%（深色）/ 12%（浅色），用于选中胶囊、底栏选中块。
- 功能色 `Green / Gold / Danger …` 降一档饱和度，深浅各一档；只表达成功 / 警告 / 错误，不做装饰。【unslop §2「语义色调低」】

## 3. 字体与字号

- 中文与正文：系统字体（各厂商的思源黑体 / HarmonyOS Sans / MiSans 等），跟随系统字号（全部用 sp）。【impeccable android.md · sp units】
- 数字与拉丁字母展示位：**Allo**（Josh Puckett，SIL OFL 1.1，Inter 衍生）。只取 ASCII + `·–—…×°` 子集，opsz 24、ROND 50，Medium / SemiBold 两个静态字重，各约 28 KB（`res/font/allo_num_*.ttf`，许可 `assets/licenses/allo-OFL.txt`）。中文由系统字体按字回退。【typography-system；unslop §5「一套界面字体 + 至多一个搭档」】
- 所有会变化的数字开 `tnum`（`Type.num` / `Type.TNUM`）：计数、时长、文件大小、进度。【unslop §5「数字 tabular-nums」】
- 字重只用 Normal 400 / Medium 500 / SemiBold 600；不再用 Bold 700。层级靠字号和颜色。【unslop §5】
- 小字不收字距；只有 ≥ 26sp 的展示字收紧到约 −0.02em。【unslop §5；impeccable craft-floor · tracking floor】

| 角色 | 字号 / 行高 (sp) | 字重 | 字体 | 用在 |
|---|---|---|---|---|
| displayLarge | 40 / 46 | 600 | Allo + 系统 | 年份封面 |
| displayMedium | 34 / 40 | 600 | Allo + 系统 | 年份卡 |
| displaySmall | 30 / 36 | 600 | Allo + 系统 | 照片页月份大标题 |
| headlineLarge | 26 / 32 | 600 | 系统 | 详情页标题 |
| headlineMedium | 22 / 28 | 600 | 系统 | 面板标题 |
| headlineSmall | 19 / 26 | 600 | 系统 | 二级页顶栏标题 |
| titleLarge | 18 / 24 | 500 | 系统 | 吸顶栏、空状态标题、对话框标题 |
| titleMedium | 16 / 22 | 500 | 系统 | 分区标题、来源胶囊 |
| titleSmall | 14 / 20 | 500 | 系统 | 卡片小标题 |
| bodyLarge | 16 / 24 | 400 | 系统 | 设置行标题、输入框 |
| bodyMedium | 14 / 21 | 400 | 系统 | 正文、说明 |
| bodySmall | 12.5 / 18 | 400 | 系统（次要色） | 一行副标题 |
| labelLarge | 14 / 20 | 500 | 系统 | 按钮、胶囊、分段 |
| labelMedium | 12 / 16 | 500 | 系统 | 底栏标签 |
| labelSmall | 11 / 14 | 500 | 系统 | 角标 |
| `Type.badge` | 11 / 14 | 600 | Allo，tnum | 缩略图上的视频时长 |

比例约 1.2；中文行高 1.4–1.5 倍。【typography-system；layout-system】

## 4. 两种界面风格（存储键不变）

| | 质感（`skeuo`，默认，原「拟物」） | 简洁（`flat`） |
|---|---|---|
| 页面底 | 中性纸色纯色（去掉了颗粒和渐变） | 中性纯色 |
| 卡片 | 2dp 中性柔影 + 上沿高光线 + 同色内极小明度变化 | 纯色面 |
| 凹槽（开关轨道 / 输入框 / 分段） | 上沿略暗的内凹 | 纯色 `Surface2` / `Chip` |
| 按钮 / 圆钮 | 3dp 浮起，按下 1dp + 压暗 + 缩放 | 纯色，按下压暗 + 缩放 |
| 底栏 | 不透明 + 发丝线 + 极淡上投影 | 不透明 + 发丝线 |

保留锤子拟物「东西浮在纸上、按下会陷下去」的骨架，去掉米黄底、颗粒和大渐变（用户当年要的是「拟物阴影」，不是做旧）。【unslop §2「渐变只允许同一中性色内的小幅明度变化」；impeccable craft-floor · Depth「阴影要有偏移和柔和模糊」；apple-design · 材质与层级】

## 5. 间距、栅格、圆角、层级

- **间距**：4 的倍数（2 只用于小控件内部微调）。`Space.xxs 2 / xs 4 / s 8 / m 12 / l 16 / xl 24 / xxl 32 / xxxl 48`；页面左右 16；卡片内边距 16；列表 / 设置行最小高 56；触摸目标 ≥ 48。同组小、组间大：分组标题上 28 下 8。【unslop §1；layout-system；impeccable android.md · 48dp】
- **对齐**：设置行图标占固定 32dp 一列，标题起点 = 16 + 32 + 12 = 60，分隔线从 60 开始；分组标题与卡片内文字左对齐（32）。【unslop §1「东西要对齐」】
- **照片网格**：间隙 2dp（≥ 6 列 1dp），缩略图圆角 3dp（≥ 6 列 0）；手机默认 4 列，平板 +2、≥ 1000dp +4。【apple-design · 内容优先；layout-system】
- **圆角**：`Radius.photo 3 / xs 6 / s 10 / m 14 / l 20 / sheet 28`。按钮、输入框 14；卡片 20；面板上沿、对话框 28；嵌套圆角 = 外圆角 − 内边距（最小 4）。Material `Shapes` 同步：extraSmall 6、small 10、medium 14、large 20、extraLarge 28。【unslop §7 · Nested radius】
- **层级**：`Elevation.card 2 / raised 3 / pressed 1 / photo 2`（dp）。深色主题层级主要靠面的明度台阶（Bg → Surface → Surface2 每级 +约 4% 明度）+ 发丝线；阴影只在质感风格里辅助。【unslop §2「深色主题靠面的台阶和发丝线」；impeccable android.md · tonal elevation】

## 6. 图标

- 来源：**Kubai Icons「icons-line」**（MIT，© 2026 Kubai087 and contributors）。由 `tools/gen_kubai_icons.py` 从 JSON 生成 `ui/KubaiIcons.kt` 的 `KI.*`（Compose `ImageVector`，不引入运行时依赖）；许可原文 `assets/licenses/kubai-icons-MIT.txt`，设置 → 通用 → 开源许可 里有声明。【kubai087_kubai-icons；line】
- 规格：24 网格、线宽 1.5、圆头圆角；显示尺寸 24dp（底栏、顶栏）、22dp（设置行）、18dp（行尾箭头）、16dp（胶囊内）。一处一种尺寸。【unslop §3「一种尺寸一种线宽」；line · GUIDE】
- 颜色：跟随所在文字角色（默认 `C.Sub`，顶栏操作 `C.Text`）；图标不自带颜色。【unslop §3】
- **选中态 = 线性双色**：外形内先铺一层 22% 同色，再描线（`KI.x(duo = true)`），用 `Icon(tint = 强调色)` 着色。比换成实心更安静，也保留了线条的统一。【duotone；line · 四种变体中的 line-duo】
- 已替换：全部 `LI.*`（36 个，`Icons.kt` 现在只是转到 `KI` 的别名）；底栏、顶栏、吸顶栏、二级页返回、设置行尾箭头、网格 / 大图流切换直接用 `KI`。Kubai 的前后遮挡（`occ` / `gap`）由生成脚本算成裁剪路径，前面的部件会在后面的部件上留出空隙。Material Rounded 图标（播放器控件、错误图标、收藏 / 实况角标等）按 ROADMAP 分批替换，替换完成前允许并存，但同一行 / 同一栏内必须是同一套。
- App 图标：保留「蓝色简约 + 飞牛元素」方向，按 design-playful-app-icons 的规则重画在 ROADMAP 的最后阶段做。

## 7. 动效（`Theme.kt · object Motion`）

原则：动效只用来解释「发生了什么」；按下立刻有反馈；进入稍慢、退出更快；弹簧可被手势打断并保留速度；不做循环装饰。【emil-design-eng；interaction-physics；animate；choreograph；unslop §9】

| 场景 | 规格（Compose） | 说明 |
|---|---|---|
| 按压（按钮、圆钮、胶囊、底栏项、来源胶囊） | 按下 `spring(dampingRatio = 1, stiffness = 1600)` 缩到 0.97（圆钮 0.92、底栏项 0.94、大卡 0.985）；松手 `spring(0.75, 900)` | `Motion.pressIn/pressOut`，`Modifier.pressScale` / `skeuoPressable`。不从 0 放大。【emil-design-eng · scale(0.97)；mobile-native §5 按下即反馈】 |
| 状态（颜色、选中、文字色） | `tween(180, CubicBezier(0.23, 1, 0.32, 1))` | `Motion.state()`；只动颜色 / 透明度。【emil-design-eng · 强 ease-out】 |
| 位移（开关钮、底栏选中胶囊展开） | `spring(1, 700)`（临界阻尼，约 250ms） | `Motion.move()`；底栏胶囊 scaleX 0.5→1 + 淡入。 |
| 进入 / 退出 | 进入 `tween(240, EaseOut)`；退出 `tween(160)` | 退出比进入快。【emil-design-eng · 退出更快】 |
| 页面转场 | 共享 X 轴：进栈新页从右 28dp 滑入 + 淡入，旧页左移 14dp + 淡出；出栈反向；`spring(1, 420)` | `MainActivity`。只走一小段，不整屏推。【impeccable android.md · shared-axis】 |
| 大图 / 播放器打开 | 放大淡入 0.94→1，`spring(0.86, 320)`；关闭淡出 + 缩到 0.96 | 1.6.3 起是共享元素式：从缩略图位置展开；下拉时照片跟手缩小、背景变透明，松手按距离 96dp 或向下速度关闭，缩回网格里那一格（`ViewerScreen` + `Hero`），找不到格子时退回整页淡出。【impeccable android.md · container transform；gestures】 |
| 底部面板 / 对话框 | `spring(0.9, StiffnessMediumLow)`（bounce ≈ 0.1） | `Motion.sheet()`；拖动关闭要带速度判定。页面里只用 `ui/Dialogs.kt` 的 `AppAlertDialog`（0.94→1 弹出、标题 titleLarge、圆角 28）、`AppBottomSheet`（统一拖动条、上沿 28；弹簧与速度判定用 Material 自带）、`AppDropdownMenu`（圆角 14）。【emil-design-eng · drawer；gestures】 |
| 列表进入 | 只给首屏前 6 项：上浮 8dp + 淡入，间隔 32ms | `Modifier.enterRise(index)`（UiKit，只播一次）；`Motion.STAGGER / STAGGER_MAX / RISE`；不阻塞交互，滚动加载的项不做。【choreograph；emil-design-eng · stagger 30–80ms】 |
| 迷你播放条 / 正在播放页 | 播放条从底栏上方滑入（`Motion.sheet`）；正在播放页从下往上滑入半屏距离 + 淡入，收起时向下滑出并压在上层 | 1.6.6 起；`Home` 的 AnimatedVisibility + `MainActivity` 转场。像从播放条展开的面板。 |
| 播放 ↔ 暂停 / 进度条 | 换图标：0.6→1 缩放（`Motion.move`）+ 淡入；进度条按住 4→8dp 并出现 14dp 圆点 | `ui/PlayerKit.kt`：`PlayPauseIcon`、`ScrubBar`（点哪跳哪、读屏 setProgress）。音乐与视频播放器共用。 |
| 视频播放器控制层 | 整体淡入淡出；顶栏从上、底栏从下各滑 1/3 自身高度；手势气泡 0.92→1 放大淡入 | 布局保持飞牛影视（用户决定），只换视觉与动效。 |
| 提示条（代替 Toast） | 从下方滑入（`Motion.sheet`）、淡出；普通 2.5 s，带「撤销」4 s | `ui/Snack.kt`：反色胶囊、圆角 14、liveRegion；App 在后台时仍用系统 Toast。 |
| 骨架屏扫光 | 1600ms 线性循环，只在首屏 | 循环只给「正在加载」这一真实状态。【loading-states；unslop §9】 |

- **遵守系统「移除动画」**：`ShiGuangTheme` 读 `Settings.Global.ANIMATOR_DURATION_SCALE`，为 0 时 `Motion.reduced = true`：缩放、位移、弹簧改成直接切换，转场只剩 90ms 淡入淡出，骨架不扫光；颜色变化保留（不会引起晕动），状态变化同时有颜色 / 文字变化，不只靠动画表达。【emil-design-eng · reduced motion；interaction-physics「反馈不只靠动作」；impeccable android.md】
- 只动 `graphicsLayer`（scale / alpha / translation）和颜色；不动宽高和阴影半径。【animation-performance；interaction-physics「只动 transform 和 opacity」】
- 高频操作（双击回顶、连续翻页）不加额外动画。【emil-design-eng · 频率表】

## 8. 触感反馈（`UiKit.kt · object Haptics`）

只在「状态真的变了」的时刻轻轻一下，跟随系统触感开关。【gestures；interaction-physics；apple-design · Haptics】

| 时刻 | 反馈 |
|---|---|
| 切换底栏 tab、分段、筛选胶囊（只在切到新项时） | `CLOCK_TICK` |
| 开关打开 / 关闭（设置行整行可点） | API 34+ `TOGGLE_ON/OFF`，以下 `CLOCK_TICK` |
| 长按进入多选、拖动多选 | `LongPress`（已有） |
| 操作完成 / 被拒 | `CONFIRM` / `REJECT`（API 30+），以下 `VIRTUAL_KEY` / `LONG_PRESS` |

## 9. 通用组件（第 1 阶段已换新）

- **主按钮 `GradientButton`**（名字保留以免改 40 多处）：实心强调色、高 50、圆角 14、文字 16sp 600、左右 24；按压缩放；一屏一个。**次要按钮 `SecondaryButton`**：中性底、正文色。【impeccable android.md · 一个主操作】
- **选择胶囊 `ChoiceChip`**：高 32（触摸区 48），圆角 16；选中 = 14% 强调浅底 + 强调字，不用实心色块；颜色渐变 180ms；读屏「标签页 / 按钮，已选中」。
- **列表行 `NavRow` / 开关行 `ToggleRow`**：最小高 56；图标 22dp 次要色、无底块；标题 bodyLarge；右侧状态值次要色等宽数字；开关行整行 `toggleable(Role.Switch)`，读屏只读一次。
- **卡片 `Card`**：圆角 20，内边距由行决定（16）；不嵌套卡片。【impeccable craft-floor「嵌套卡片永远是错的」】
- **分段 `Segmented` / `FillSegmented`**：凹槽 + 选中块，文字颜色渐变，切换轻震。
- **圆形按钮 `RoundIcon`**：40–44dp，按下缩到 0.92；照片上的半透明底保持扁平。
- **底栏**：不透明底 + 顶部发丝线；整格可点；选中 = 双色图标 + 浅色胶囊横向展开 + 文字正文色 500。宽屏（≥ 600dp）换 NavigationRail，同一套图标与颜色。【impeccable android.md · 导航随尺寸变化】
- **顶栏**：来源胶囊（状态点 + 名称 + 下拉）+ 日历 / 搜索 24dp 图标按钮；吸顶栏 = 98% 页面底 + 底部发丝线，标题 titleLarge（Allo 数字）。二级页顶栏：44dp 返回圆钮 + headlineSmall 标题。
- **海报 `PosterCard`**（1.6.6）：2:3、圆角 10、质感轻影、按压 0.985、角标深色 60% 底 + 白字（不用强调色块）、观看进度 3dp 线压在白 24% 底轨上。
- **播放器小部件 `PlayerKit`**（1.6.7）：`PlayPauseIcon`、`ScrubBar`、`popOnChange`（收藏心形回弹）、`NowPlayingBars`（正在播放三根小柱，只在真在播时动）。
- **提示条 `Snack`**（1.6.9）：`toast()` 在前台时自动走它；可逆操作用 `toastUndo()` 带「撤销」。
- **空状态 / 错误**：一个 44dp 弱色线性图标 + titleLarge 标题 + 一句 bodyMedium + 一个主按钮；去掉彩色圆底。需要用户处理的错误（密码、证书、空间）图标用错误色。【unslop · 空状态；loading-states；impeccable onboard】

## 10. 读屏与无障碍

- 每个可点元素有角色：按钮 `Role.Button`、底栏 / 分段 / 筛选 `Role.Tab` + `selected`、开关行 `Role.Switch`（整行）、强调色 `Role.RadioButton` + 「蓝色」等描述。【accessibility-excellence】
- 标题有 `heading()`：照片页月份大标题、吸顶栏标题、二级页标题、设置分组标题，读屏可按标题跳转。
- 纯装饰图标 `contentDescription = null`；只有图标的按钮必须有描述（「搜索照片」「按日期查看照片」「返回」）。
- 触摸目标 ≥ 48dp；文字对比度见 §2.2；全部字号用 sp，系统字体放大到 130% 时单行标题省略而不是裁切。【impeccable android.md · Verifying：深色 + 字体缩放要进验收】
- 状态不只靠颜色：选中同时改字重 / 图标变体，错误同时有图标和文字。

## 11. 验收

- 每批改动：本地 `assembleDebug + testDebugUnitTest` 通过才推送；推送后看 GitHub Actions。
- 真机：深色 / 浅色 × 质感 / 简洁 × 字体 100% / 130% × 「移除动画」开 / 关，截图对比（impeccable android.md · Verifying the build）。**第 1 阶段未在真机上验证**。
