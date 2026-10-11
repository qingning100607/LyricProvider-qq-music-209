# QQ 音乐歌词提供器

词幕（Lyricon）的歌词提供插件：把 QQ 音乐正在播放的歌词（原文 + 翻译）送到状态栏渲染。

基于上游 [tomakino/LyricProvider](https://github.com/tomakino/LyricProvider) 的 `qq-music` 模块，针对 QQ 音乐新版歌词引擎做了适配。

- 包名：`io.github.proify.lyricon.qmprovider`
- 当前版本：2.0.4（versionCode 20204）
- 本仓库只含 `qq-music` 模块，编译需要上游项目的根构建配置，用法见「构建」

## 测试环境

- QQ 音乐 20.9.0.8（versionCode 7458）
- 词幕 1.0.40-rc3（Xposed）
- Android 13+，LSPosed / KernelSU

## 新版引擎结构（适配依据）

- 歌词容器 `com.lyricengine.base.k`：`e` = `CopyOnWriteArrayList<t>`；前三个 String 字段是歌名 / 歌手 / 专辑
- 歌词行 `com.lyricengine.base.t`：`a` = 文本、`b` = 起始(ms)、`c` = 时长(ms)、`d` = 行类型、`g` = 逐字时间段
- 注意：`c` 是"到下一句开始的时间"（含句间停顿），不是这句唱了多久；`g` 才给出每个字的起止

## 改动

1. 容器类名改为 `com.lyricengine.base.k`。旧代码在 `com.lyricengine.base.a`~`z` 里扫"名字带 lyric 且有 List 字段"的类，会 hook 错类。
2. 只接收当前歌的容器：入口按容器自带的歌名 / 歌手核对，对不上就丢。
3. 翻译位判据：只有"歌名空**且**歌手也对不上"才算别的歌；歌名空但歌手相符仍认（本歌缺歌名的翻译轨）。
4. 重复轨（与原文重合 ≥60% 行）不占翻译位。
5. 版权 / 来源说明行不参与配对。
6. 原文与翻译的配对：按两条轨的顺序一对一，并先把表头和词曲 / 制作人员行（`词：`、`曲：`、`Lyrics by：`、`Composed by：` …）剔出配对；只接受时间相近的一对（窗口 3 秒），窗口内最多看两行、取时间更近的那一行。
   修掉的问题：a) 第一句歌词的翻译被 0ms 的表头抢走，只剩原文；b) 翻译轨整体晚一秒多时，翻译被吸到后一句上（《Alone》第 2、4 句只剩原文）；c) 同一句时间差正好 2 秒时被旧窗口（不含 2 秒）卡掉；d) 翻译轨少一条时，缺的那句没译文、后一句的译文被吸到它上一句（《That Girl》的 "Oh no"）。
   「改选下一行」只在三个条件同时成立时才做：当前这行差 1 秒以上、下一行近得多（≥300ms）、且这一段轨本身对得很紧（最近 3 对差 <600ms）——否则退回「顺序取第一条」，免得整条轨错位一格。
7. 纯音乐占位不覆盖正文。
8. 合并推送去重：300ms 内只推最后一次（词幕每次 `setSong` 都会 `reset()` 视图，重复推送会打断入场动画）。
9. 冷启动 60 秒轮询补 hook（`hookClassLoader()` 在 Android 13+ 上无效）。
10. 行尾钳制 `clampOverlaps()`：引擎给的 `duration` 会在行尾多留一截（表头 +600ms、歌词行 +200~1700ms），行尾越界会让词幕认为"同时有多行活跃"，双行时的切行动画就不播。发送前把每行 `end` 钳到下一行 `begin - 1`。
11. 翻译候选的首行若是别的歌的表头（形如「歌名 - 歌手」且歌名对不上）则跳过。
12. 原文核对增加"歌名主体"比对（去掉括号补充说明、再去标点空格），避免标题标点差异导致整份歌词被丢。
13. 非原文轨若带本歌元数据、翻译位空着，改挂翻译位（两条轨到达顺序不固定）。
14. 高亮区间对齐 `alignHighlightSpan()`：词幕在没有逐词数据时会把整行当成一个词、按 `duration` 匀速扫，于是高亮越到句尾越落后于人声。这里用引擎逐字段的真实首尾时间把扫描区间收到这句真正唱的那一段：`duration` = 从显示起点扫到末字结束，`begin` 最多比首字提前 300ms 显示；行 `end` 不变，所以行的显示时长和间奏判定不受影响。
15. 逐字下发：把引擎的逐字时间段（首字起点 + 每字时长，绝对 ms、字与字首尾相接）装进 `RichLyricLine.words` 交给词幕，高亮就按每个字的时间走，长音会停、快句会连着扫。逐字文本必须能原样拼回整行才会下发，拼不回就退回第 14 条的整行匀速扫。
16. 逐字裁剪 `fitWordsToWindow()`：行尾被第 10 条钳短之后，最后几个字的时间可能落到窗口外，而词幕是按字推进高亮的 —— 越界的字会让这一行的高亮动到窗口之外、切行时和下一行的高亮同时走。发送前把逐字时间裁进本行窗口，窗口本身不动。
17. 并发加固：跨线程共享的状态改成并发容器 / 加锁（`sungRanges` = `ConcurrentHashMap`，`QQLyricsCache` = 加锁 + 最近 50 首 LRU），几个跨线程读写的字段加了 `@Volatile`。
18. 换歌 6 秒后仍然没拿到本曲歌词，推一次空歌词清屏。
只下发文本和逐字时间，不下发任何动画数据。
日志：本仓库的代码里没有任何日志 / 诊断代码。排查问题用的诊断版本只在本地使用，不进仓库。

## 构建

```bash
# 1. 克隆上游项目
git clone https://github.com/tomakino/LyricProvider.git
cd LyricProvider

# 2. 用本仓库的 qq-music 覆盖上游模块
rm -rf qq-music && cp -r /path/to/this-repo/qq-music ./

# 3. debug
./gradlew :qq-music:assembleDebug

# 4. release
./gradlew :qq-music:assembleRelease
```

release 有两个点（`proguard-rules.pro` 里已经包含第一条）：

1. `-keep class io.github.proify.lyricon.qmprovider.** { *; }`：R8 混淆会让 `assets/xposed_init` 里的入口类名失配，模块直接失效。
2. 某些 proot / 容器环境需要 `android.enableResourceOptimizations=false` 和 `android.enableResourceShrinking=false`，否则产出的 APK 会缺 `AndroidManifest.xml` / `resources.arsc`。

## 安装

编译好的 APK 在 [Releases](https://github.com/qingning100607/LyricProvider-qq-music-209/releases)。

1. 安装 APK，在 LSPosed 里启用模块，作用域勾选 QQ 音乐（含 `:QQPlayerService`）。
2. 强制停止 QQ 音乐后重新打开。
3. 词幕 → 歌词提供服务，应能看到"QQ 音乐"。

## 已知限制

- QQ 把原文和译文放在同一条轨里交替的歌（英文行 / 中文行轮流），目前显示为单行交替而不是双行，例如 *Love Story*。
- 真翻译容器既没有歌名、歌手也和当前歌对不上时，退化成单行。
- 切歌瞬间可能短暂残留上一首的歌词（上游行为）。
- 本模块依赖引擎实现细节，QQ 音乐升级后可能失效，届时需要重新核对类名与字段。

## 许可与致谢

遵循上游 Apache-2.0（见 `LICENSE`），保留原作者版权声明。

感谢 [Proify](https://github.com/proify)、[Tomakino](https://github.com/tomakino) 的 LyricProvider 与词幕。
