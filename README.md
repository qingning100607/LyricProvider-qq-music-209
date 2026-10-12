# QQ 音乐歌词提供器

词幕（Lyricon）的歌词提供插件：把 QQ 音乐正在播放的歌的歌词（原文 / 翻译 / 罗马音 + 逐字）送到状态栏渲染。

走下载路：hook 系统 MediaSession 判定换歌，拿到当前歌曲的数字 songId，用上游的 `:share:qrckit` 从 QQ 音乐服务器下载已经分好轨的 QRC 歌词，直接交给词幕。分轨和逐字都是服务器给的，本地不再判断"哪条是原文、哪条是翻译"。

- 包名：`com.qingning100607.lyricon.qmprovider`（4.3.x 起换到这个包名，和上游模块包名区分开）
- 当前版本：2.0.10（versionCode 20210）
- 目标 App：`com.tencent.qqmusic`
- 本仓库只含 `qq-music` 模块，编译需要上游项目的根构建配置，用法见「构建」

## 安装

编译好的 APK 在 [Releases](https://github.com/qingning100607/LyricProvider-qq-music-209/releases)。里面有两个：

| APK | 说明 |
|---|---|
| `QQMusicProvider-2.0.10-release.apk` | 正式版，**不含日志**（本仓库的源码就是它），日常用这个 |
| `QQMusicProvider-2.0.10-debug.apk` | 带日志，出问题时用（日志路径见下） |

1. 安装 APK，在 LSPosed 里启用模块，作用域勾选 QQ 音乐（含 `:QQPlayerService`）；
2. **如果之前装过 `io.github.proify.lyricon.qmprovider`（2.x 那版），先把它卸掉或关掉模块开关** —— 两个都开会让两边都往词幕推歌词；
3. 强制停止 QQ 音乐再打开。

## 工作

### 触发：系统 MediaSession（不随 App 版本失效）

```
hook android.media.session.MediaSession.setMetadata / setPlaybackState
  MEDIA_ID（没有就用 歌名|歌手|专辑）→ 换歌判定
  TITLE / ARTIST / DURATION → 元数据
```

### 换歌流程

1. 元数据 key 变化 → 记下 `currentKey`；
2. **clearScreen()**：立刻推一首空歌词占位，把上一首从状态栏清掉 —— 清屏**不动 `pushedId`**（去重标记），否则后面"管理器还返回上一首"会被误推出去；
3. `resolveAndPush()`：取 songId（见下）——若解析结果 == `pushedId`（管理器还没更新、返回的是上一首），判为**过期**，退避重试（最多 6 次，间隔 400ms×n），期间保持清屏；
4. `push()`：真正推送（按 `pushedId` 去重），下载完成后补上歌词。

### 取 songId

```
① 管理器（不联网）
   com.tencent.qqmusic.business.lyricnew.load.manager.h
   结构查找单例（静态字段 / 静态无参方法，返回类型是该类本身）
   → 取其中类型为 com.tencent.qqmusicplayerprocess.songinfo.SongInfo 的字段
   → SongInfo.C3() 得到数字 songId
② 联网
   拿 TITLE + ARTIST 去 c.y.qq.com 搜索；歌名必须匹配（分数门槛），否则放弃
```

### 下载与推送

```
QrcDownloader.downloadLyrics(songId)
  → parsedLyric.richLyricLines（已分好轨：原文/翻译/罗马音 + 逐字）
  → clampOverlaps() 行尾钳制，消掉行间重叠（恢复词幕的 YoYo 切行动画）
  → fitWordsToWindow() 把逐字裁进本行窗口（避免两行高亮动画叠着走）
  → 翻译占位符 "//" 置空
  → 磁盘缓存后 setSong
```

- **纯音乐**：一律透传服务器返回的内容，不做关键词过滤、不改文案。
- 磁盘缓存：`files/lyricon/lyrics/<语言>/<songId>.json.gz`（JSON + deflate）。缓存带 `CACHE_VERSION`，改动写入逻辑时升号即可让老缓存整目录清一次。
- 迟到的下载会**双校验**（`songId == pushedId && pushedKey == currentKey`）后推，避免切歌后旧歌的下载把刚清掉的屏幕又刷回旧歌词。

## 日志

**正式版不含日志**：本仓库源码里没有 flog，编译出来的 APK 里连日志字符串都搜不到，不会创建任何日志文件。

排查问题时换 **debug APK**，它写这两个地方：

```
/data/data/com.tencent.qqmusic/files/lyricon_debug.log
/sdcard/Android/data/com.tencent.qqmusic/files/lyricon_debug.log
```

关键行：

| 日志 | 含义 |
|---|---|
| `✓ 管理器单例（静态字段/静态方法）` | 权威路线就绪 |
| `管理器: songId=xxx` | 拿到当前歌曲 |
| `→ 推送 songId=xxx` | 已推给词幕 |
| `downloaded N lines` | 歌词下载成功 |
| `解析结果仍是上一首，判为过期，重试 n/6` | 管理器还没更新，正在重试 |
| `✗ 找不到这首歌` | 两条路都失败 |

QQ 音乐升级后如果不出词，按这个顺序查（装 debug 版看日志）：

1. `✗ 管理器类不存在（已改名？）` → 改名了，把新类名填到 `LOAD_MANAGER_CLASS`（或直接走搜索兜底）；
2. `管理器类在，但取不到单例` → 单例的取法变了，看 `managerInstance()`；
3. `✗ 找不到这首歌` → 搜索兜底也没命中，看歌名/歌手元数据是否正常。

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

## 已知限制

- 需要联网下载歌词。服务器上没有该曲目的 QRC 时拿不到歌词；首次播放要等下载完成，之后走磁盘缓存。
- 管理器单例与 `SongInfo.C3()` 都是 QQ 内部实现，升级后可能要重新核对；核对不上时会自动退回「歌名 + 歌手」搜索。
- 此版本在QQ音乐 20.9.0.8（versionCode 7458）测试通过。

## 许可与致谢

遵循上游 Apache-2.0（见 `LICENSE`），保留原作者版权声明。

感谢 [Proify](https://github.com/proify)、[Tomakino](https://github.com/tomakino) 的 LyricProvider 与词幕。
