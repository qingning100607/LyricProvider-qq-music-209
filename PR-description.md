# PR: fix(qq-music): 适配 QQ 音乐 20.9.0.8 新版歌词引擎（com.lyricengine.base.k）

## 环境

| 项 | 值 |
|---|---|
| QQ 音乐 | 20.9.0.8（versionCode 7458） |
| 词幕渲染端 | 1.0.40-rc3（Xposed） |
| Android | 13+ / Root（KernelSU） |
| 本 patch 基线 | 上游 qq-music 模块（`src/main/kotlin/.../xposed/QQMusic.kt`，768 行那版） |

## 问题

1. **新版 QQ 音乐完全取不到歌词 / hook 错类**
   旧代码用 `for (i in 'a'..'z') { knownClassPatterns.add("com.lyricengine.base.$i") }` 暴力扫描 `com.lyricengine.base` 包下的单字母类名。20.9.0.8 里这些类里**多个都有 `List` 字段**，会 hook 到错误的类上。
   新版确定性的容器类只有一个：`com.lyricengine.base.k`，字段 `e` = `CopyOnWriteArrayList<com.lyricengine.base.t>`；行对象 `t`：`a`=文本、`b`=起始(ms)、`c`=时长(ms)。

2. **翻译位显示"别的歌"的歌词（时对时不对）**
   QQ 会预载**下一首**的歌词文档，这些预载容器的 `歌名/歌手` 是空的；而源码是"先到=原文、后到=翻译"，谁的容器先到谁占翻译位：
   ```
   17:10:26.196  容器元数据: 歌名=null, 歌手=null
   17:10:26.196  First line: 室内系的TrackMaker -hanser   ← 别的歌，顶了翻译位
   17:10:26.197  Stored as translation
   ```

## 改动（patch 内容）

1. `fallbackHookKnownClasses()`：`a..z` 扫描 → `com.lyricengine.base.k`
2. `processLyrics(lyrics, meta)` 入口核对**容器自带的歌名/歌手**，不是本歌的容器直接丢弃
3. 翻译位额外加严：候选容器**没有歌名元数据**时跳过（QQ 预载的下一首歌词就走这条被挡住）
4. 重复轨过滤（与原文内容重合 ≥60% 的候选不占翻译位）
5. 版权声明行（`享有本翻译作品的著作权` 等）不参与配对
6. 翻译一对一配对：`±2s` 内取**最近且未被占用**的一条（原实现取第一条，会重复配到前几行）
7. 纯音乐占位不再覆盖已有正文
8. 合并推送去重：300ms 窗口内只推最后一次（词幕每次 `setSong` 都会 `reset()` 视图，重复推送会打断入场动画）
9. 冷启动兜底：60s 轮询补 hook（`hookClassLoader()` 在 Android 13+ 上无效）
10. 日志：`Log.i` → `dlog()`（写入 `files/lyricon_debug.log`，本机 logcat 不可用时的唯一验证手段；**不想要可以整段删掉**）
11. **行尾钳制**：`clampOverlaps()` 把每行 `end` 钳到下一行 `begin - 1`（最后一行不动）。新版引擎的 `duration` 会在行尾多留一截（表头行 +600ms、歌词行 +200~1700ms），`end` 越界会让词幕 `TimingNavigator.hasOverlap=true` → `forEachAt()` 返回多行 → `activeLines.size > 1` → `LyricPlayerView` 的 YoYo 切行动画分支进不去（实测 48 行里有 46 行越界，最大 1721ms；钳制后 `hasOverlap=false`）
12. **翻译候选"首行表头"判据**：候选首行若是「歌名 - 歌手」样式且不含当前歌名的任何词 → 判为别的歌的文档，跳过（挡住"同一位歌手的下一首"被预载进翻译位，例如放《最伟大的作品》时预载的《不能说的秘密》）

**不下发任何逐字/动画数据**：不写 `RichLyricLine.words` / `translationWords`，代码里没有 `LyricWord`；动画、进度、行切换全部由词幕控制。

## 验证日志（同机实测）

```
丢弃非本歌容器: 歌名=虚拟, 歌手=陈粒

翻译候选无歌名元数据（疑似别的歌的预载），跳过
翻译候选首行像别的歌的表头: 不能说的秘密 - 周杰伦 (Jay Chou)，跳过

诊断[钳制]: 81 行里钳了 51 行的 end
诊断[重叠]: 行数=81 重叠条数=0 最大越界=0ms hasOverlap=false

[0-4102]    好きだから。 - 『ユイカ』 [并不是因为你的帅气才喜欢上你]
[3717-6652] 好きだからかっこいいんだよ [是因为喜欢上你后才发觉你的帅气]

[7878-10783] 'Cos of my jealousy [皆因我的嫉妒心作祟]
[10784-14391] That's what I can see [这一点我心知肚明]
```

## 副作用 / 已知限制

- 若某首歌的**真翻译**容器也没有歌名元数据，会退化成单行（日志会打 `翻译候选无歌名元数据…跳过`）
- 词幕侧"双行时切行动画不播"：**根因在提供器侧**，本次由 `clampOverlaps()` 修复 —— 行间 `end` 越界会让词幕 `TimingNavigator.hasOverlap=true` → `forEachAt()` 同时返回多行 → `activeLines.size > 1`，`LyricPlayerView` 那支"恰好 1 行被替换 → YoYo 切行动画"的分支就进不去。钳制后实测 `hasOverlap=false`。
  （早期版本这里写过"双行占 2 个视图所以不播动画"，经核对 `tomakino/lyricon@c93da0e` 后确认是错的：双行是同一个 `RichLyricLineView` 内的 `main`+`secondary`，`activeLines` 数的是歌词行。）

## 建议的 commit 拆分

1. `fix(qq-music): 适配新版歌词引擎类名（com.lyricengine.base.k）` ← 核心，必须
2. `fix(qq-music): 丢弃非本歌容器 / 翻译位不再被别的歌占用`
3. `fix(qq-music): 翻译配对、重复轨、版权行、纯音乐占位、推送去重` ← 可选
