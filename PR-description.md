# 给上游的说明（qq-music 模块）

这个仓库是 `qq-music` 模块的一份适配版本，针对 QQ 音乐新版歌词引擎（20.9.0.8，versionCode 7458）。

## 新版引擎的类名

- 歌词容器：`com.lyricengine.base.k`，字段 `e` = `CopyOnWriteArrayList<com.lyricengine.base.t>`
- 歌词行：`com.lyricengine.base.t`，字段 `a`=文本、`b`=起始(ms)、`c`=时长(ms)、`d`=行类型、`g`=逐字时间段

原实现在 `com.lyricengine.base.a`~`z` 里找"名字带 lyric 且有 List 字段"的类，会 hook 到同包里其它类；新版可以直接用确定类名。

## 除了类名还动了什么

改动都在 `QQMusic.kt`，完整 diff 见 `patch/qq-music-qq209-fix.patch`，主要几处：

- 入口按容器自带的歌名 / 歌手核对，丢弃不是当前歌的容器（引擎内存里同时存在上一首 / 下一首的残留）
- 原文与翻译的配对改成"按两条轨顺序一对一"，配对前先剔掉表头和词曲 / 制作人员行
- 发送前把每行 `end` 钳到下一行 `begin - 1`（引擎的 `duration` 会在行尾多留一截）
- 用引擎的逐字时间段把高亮扫描区间对齐到这句真实的唱段（逐字本身不下发）
- 换歌 6 秒后仍没有歌词时推一次空歌词

是否合并、怎么改，按上游的取舍来即可。
