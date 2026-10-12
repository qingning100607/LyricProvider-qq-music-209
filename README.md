# QQ 音乐歌词提供器

词幕（Lyricon）的歌词提供插件：把 QQ 音乐正在播放的歌的歌词（原文 / 翻译 / 罗马音 + 逐字）送到状态栏渲染。

2.0.5 起改成下载路：hook 歌词加载管理器拿到当前歌曲的数字 songId，用上游的 `:share:qrckit` 从 QQ 音乐服务器下载已经分好轨的 QRC 歌词，直接交给词幕。分轨和逐字都是服务器给的，本地不再判断"哪条是原文、哪条是翻译"。

- 包名：`io.github.proify.lyricon.qmprovider`
- 当前版本：2.0.5（versionCode 20205）
- 本仓库只含 `qq-music` 模块，编译需要上游项目的根构建配置，用法见「构建」

## 测试环境

- QQ 音乐 20.9.0.8（versionCode 7458）
- 词幕 1.0.40-rc3（Xposed）
- Android 13+，LSPosed / KernelSU

## 工作原理

1. hook `com.tencent.qqmusic.business.lyricnew.load.manager.h` 的 `handlePlaySongChanged`（换歌时触发，形参 `W(boolean)`）。
2. 从该实例里取出当前 `SongInfo`（`com.tencent.qqmusicplayerprocess.songinfo.SongInfo`），反射 `C3()` 拿到 QQ 数字 songId。
3. `QrcDownloader.downloadLyrics(songId)` 下载 QRC 歌词，解析出来的 `richLyricLines` 自带分轨（原文 / 翻译 / 罗马音）和逐字时间段，直接 `setSong` 交给词幕。
4. 翻译占位符 `"//"` 置空，词幕按单行显示。
5. 纯音乐那类占位文档（"纯音乐，请欣赏"、"没有填词"）不当歌词：换歌时先推一首空歌词把上一首清掉，下载到占位文档后直接结束，不往词幕推。
6. 磁盘缓存：`files/lyricon/lyrics/<语言>/<songId>.json.gz`（JSON + deflate）。换歌先读缓存，没有才去下载。

## 和 2.0.4 及以前的区别

2.0.4 及以前走的是另一条路：直接读 QQ 引擎内存里的歌词容器，再在本地判断哪条轨是原文、哪条是翻译，还要用逐字时间做高亮对齐。2.0.5 换成官方下载接口，分轨由服务器给，本地没有配对判断，也没有逐字对齐这类启发式。

旧代码见 tag `v2.0.4`（对应的 Release 也还在）。

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
2. 如果装过同功能的其它包名版本（例如早前的 `com.qingning100607.lyricon.qmprovider`），卸掉或关掉它的模块开关 —— 两个都开会让两边都往词幕推歌词。
3. 词幕 → 歌词提供服务，选中本模块。

## 已知限制

- 需要联网下载歌词。服务器上没有该曲目的 QRC 时拿不到歌词；首次播放要等下载完成，之后走磁盘缓存。
- 依赖 QQ 的内部类名和 `SongInfo.C3()`，QQ 音乐升级后可能要重新核对。
- 只针对 20.9.0.8 做过适配。

## 许可与致谢

遵循上游 Apache-2.0（见 `LICENSE`），保留原作者版权声明。

感谢 [Proify](https://github.com/proify)、[Tomakino](https://github.com/tomakino) 的 LyricProvider 与词幕。