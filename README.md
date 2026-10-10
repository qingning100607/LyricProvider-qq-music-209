# QQ 音乐歌词提供器 · qq-music 模块（适配 QQ 音乐 20.9.0.8）

词幕（Lyricon）的 **歌词提供服务** 插件，把 QQ 音乐正在播放的歌词（原文 + 翻译）送到状态栏渲染。

- 上游项目：**[tomakino/LyricProvider](https://github.com/tomakino/LyricProvider)**（Apache-2.0）
- 本仓库内容：上游 `qq-music` 模块 + 针对 **新版 QQ 音乐歌词引擎** 的适配修复（源码基线来自本模块的反射实现版本）
- 包名：`io.github.proify.lyricon.qmprovider`

> 说明：本仓库只包含 **qq-music 模块**。它能编译是因为依赖上游 LyricProvider 项目的根构建配置，所以用法是"把 `qq-music/` 放进上游项目里"（见下面「构建」）。

---

## 一、适配环境

| 项 | 值 |
|---|---|
| QQ 音乐 | **20.9.0.8**（versionCode 7458） |
| 词幕 | 1.0.40-rc3 及以上（Xposed） |
| Android | 13+，需 Root / LSPosed |
| 模块版本 | versionCode 20202 / versionName **2.0.2** |

## 二、新版歌词引擎结构（适配依据）

| 对象 | 类 | 字段 |
|---|---|---|
| 歌词容器 | `com.lyricengine.base.k` | `e` = `CopyOnWriteArrayList<com.lyricengine.base.t>`；前三个 String 字段 = 歌名 / 歌手 / 专辑 |
| 歌词行 | `com.lyricengine.base.t` | `a`=文本、`b`=起始(ms)、`c`=时长(ms)、`d`=行类型、`g`=逐字时间段 |

## 三、改了哪些地方

1. **新版引擎类名适配**（核心）
   旧代码 `for (i in 'a'..'z') { knownClassPatterns.add("com.lyricengine.base.$i") }` 会把包下多个含 `List` 字段的类都 hook 上（hook 到错的类 → 取不到歌词）。改为确定性类名 `com.lyricengine.base.k`。
2. **只接收本歌的容器**：`processLyrics(lyrics, meta)` 入口按容器自带的歌名 / 歌手核对，不是当前歌的容器直接丢弃。
3. **翻译位加严**：QQ 会预载**下一首**的歌词文档，这些容器 `歌名/歌手` 为空，会顶掉翻译位；现在"没有歌名元数据的候选"不许占翻译位。
4. **重复轨过滤**：与原文内容重合 ≥60% 的候选（同一首歌的另一条轨）不占翻译位。
5. **版权声明行**（`享有本翻译作品的著作权` 等）不参与配对。
6. **翻译一对一配对**：±2s 内取"最近且未被占用"的一条（原实现取第一条，会重复配到前几行）。
7. **纯音乐占位不覆盖正文**。
8. **合并推送去重**：300ms 窗口内只推最后一次（词幕每次 `setSong` 都会 `reset()` 视图，重复推送会打断入场动画）。
9. **冷启动兜底**：60 秒轮询补 hook（`hookClassLoader()` 在 Android 13+ 上无效）。
10. 日志：`Log.i` → `dlog()`（**当前版本为空实现，零磁盘 I/O**；调用点保留，排查问题时把实现换回 `Log.i` 即可）。
11. **行尾钳制 `clampOverlaps()`**（修"双行时切行动画不播"）：新版引擎的 `duration` 会在行尾多留一截（表头行 +600ms、歌词行 +200~1700ms），`end` 越界 → 词幕 `TimingNavigator.hasOverlap=true` → `forEachAt()` 同时返回多行 → `activeLines.size > 1` → 切行动画分支进不去。发送前把每行 `end` 钳到下一行 `begin - 1`（先按 `begin` 排序 + `nextBegin > cur.begin` 守卫，最后一行不动）。实测 *My Jealousy* 48 行里 46 行越界（最大 1721ms）→ 钳后 `hasOverlap=false`。
12. **翻译候选"首行表头"判据**：候选首行若是「歌名 - 歌手」样式且不含当前歌名的任何词 → 判为别的歌的文档，跳过（挡住"同一位歌手的下一首"被预载进翻译位，例如放《最伟大的作品》时预载的《不能说的秘密》）。
13. **原文核对"歌名主体"附加判据**：QQ 的文档标题常与播放器标题只差标点（`ありがとう・・・` 三个 U+30FB vs `ありがとう…` U+2026）→ 源码的 `contains` 全 false → 三份数据全被丢。现在把歌名去括号补充说明、再去标点/空格后比对（`normalizeForCompare`），命中即算本歌。
14. **非原文轨"改挂翻译位"**：轨的到达顺序不固定（实测《ありがとう…》中文轨比日文原文先到）。核对失败时不再直接丢，若它带本歌元数据、翻译位空着、首行也不是别的歌的表头 → 改挂翻译位。
15. **翻译位判据放宽 + 中文优先于罗马音**：只有"歌名空**且**歌手也对不上"才算别的歌（歌名空但歌手=当前歌 → 认，例如 *Sacred Play Secret Place*）；新增 `isRomaLike()`，先到的罗马音轨会被后到的中文轨替换。
16. **迟到清屏**：换歌 6 秒后本曲仍一次都没推过歌词 → 推一首空歌词（免得词幕一直挂着上一首歌的歌词在走）。注意词幕 `SongPreprocessor.fillGap()` 会给空歌词补一行"歌名 - 歌手"。

**明确没有的东西**：不下发任何逐字时间轴（代码里没有 `LyricWord` / `translationWords`），不下发任何动画数据；动画、进度、行切换全部由词幕控制。

## 四、构建

```bash
# 1. 克隆上游项目
git clone https://github.com/tomakino/LyricProvider.git
cd LyricProvider

# 2. 用本仓库的 qq-music 覆盖上游模块
rm -rf qq-music && cp -r /path/to/this-repo/qq-music ./

# 3. debug
./gradlew :qq-music:assembleDebug

# 4. release（需要自己的签名）
export RELEASE_STORE_FILE=/path/to/your.jks
export RELEASE_STORE_PASSWORD=xxx
export RELEASE_KEY_ALIAS=xxx
export RELEASE_KEY_PASSWORD=xxx
./gradlew :qq-music:assembleRelease
```

release 有两个坑（`proguard-rules.pro` 里已包含第一条）：

1. `-keep class io.github.proify.lyricon.qmprovider.** { *; }` —— R8 混淆会让 `assets/xposed_init` 里的入口类名失配，模块直接失效。
2. 某些 proot / 容器环境里需要 `android.enableResourceOptimizations=false` 和 `android.enableResourceShrinking=false`，否则产出的 APK 缺 `AndroidManifest.xml` / `resources.arsc`。

## 五、安装
预编译 APK 见本仓库 [Releases](../../releases)（最新 `v2.0.2`）。
1. 安装 APK，在 LSPosed 里启用本模块，**作用域勾选 QQ 音乐**（含 `:QQPlayerService` 进程）。
2. 强制停止 QQ 音乐后重新打开。
3. 词幕 → 歌词提供服务，应能看到"QQ 音乐"。

## 六、已知限制

- QQ 把**原文和译文塞在同一条轨里**的歌（英文行 / 中文行交替），目前会显示成单行交替而不是双行（例如 *Love Story*）。
- 若某首歌的**真翻译**容器既没有歌名、歌手也和当前歌对不上，会退化成单行（判据见 §三-15）。
- 词幕侧"双行时切行动画不播"：**已修**（根因在提供器侧）—— 行间 `end` 越界 → 词幕 `TimingNavigator.hasOverlap=true` → `forEachAt()` 同时返回多行 → `activeLines.size > 1` → `LyricPlayerView` 那支"恰好 1 行被替换 → YoYo 切行动画"就进不去。`clampOverlaps()` 钳制后实测 `hasOverlap=false`。
  （旧版本这里写过"双行占 2 个视图所以不播动画"，核对 `tomakino/lyricon@c93da0e` 后确认是错的：双行是同一个 `RichLyricLineView` 内的 `main`+`secondary`。）
- 切歌瞬间可能短暂残留上一首的歌词（跟随上游行为）。

## 七、许可与致谢

- 遵循上游 **Apache-2.0**（见 `LICENSE`），保留原作者版权声明。
- 感谢 **[Proify](https://github.com/proify)** / **[Tomakino](https://github.com/tomakino)** 的 LyricProvider 与词幕。
- 本仓库的修改部分由本仓库作者完成；如上游愿意接收，欢迎按 `patch/qq-music-qq209-fix.patch` 的形状提交 PR（`PR-description.md` 里有现成的 PR 文案）。
