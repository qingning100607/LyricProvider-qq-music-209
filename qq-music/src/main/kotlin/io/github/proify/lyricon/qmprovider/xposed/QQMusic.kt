/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.qmprovider.xposed

import android.app.Application
import android.media.MediaMetadata
import android.media.session.PlaybackState
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import io.github.proify.lyricon.lyric.model.LyricWord
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.ProviderLogo
import io.github.proify.lyricon.qmprovider.BuildConfig
import io.github.proify.qrckit.QrcDownloader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * QQ音乐（官方版 / com.tencent.qqmusic）歌词提供器 —— 下载路（QRC）。4.3.0
 *
 * 触发：`android.media.session.MediaSession.setMetadata`（系统 API，不随 App 版本失效）
 *
 * 换歌流程（关键，别改坏）：
 *   1. 元数据 key 变化 → 记下 currentKey
 *   2. **clearScreen()**：立刻推一首空歌词占位，把上一首从状态栏清掉
 *      —— 注意：清屏**不动 pushedId**（去重标记），否则后面"管理器返回上一首"会被误推出去
 *   3. resolveAndPush()：取 songId（① 管理器 SongInfo → C3()  ② 兜底：歌名+歌手搜索）
 *      —— 若解析结果 == pushedId（管理器还没更新、返回的是上一首），
 *         判为**过期**，退避重试，而不是推出去
 *   4. push()：真正推送（内部按 pushedId 去重），下载完成后补上歌词
 */
object QQMusic : YukiBaseHooker() {

    private const val TAG = "QQMusicProvider"

    private const val MEDIA_SESSION_CLASS = "android.media.session.MediaSession"

    /** 歌词加载管理器（混淆名，随版本可能变化；取不到会自动走兜底） */
    private const val LOAD_MANAGER_CLASS = "com.tencent.qqmusic.business.lyricnew.load.manager.h"

    /** 歌曲类 */
    private const val SONG_INFO_TYPE = "com.tencent.qqmusicplayerprocess.songinfo.SongInfo"

    private const val SEARCH_URL = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp"

    /** 解析结果判为"过期"时的重试间隔与次数 */
    private const val STALE_RETRY_MAX = 6
    private const val STALE_RETRY_STEP_MS = 400L

    private var provider: LyriconProvider? = null

    @Volatile private var hostApplication: Application? = null

    /** 最近一次**真正**推给词幕的 songId（去重用；清屏占位不动它） */
    @Volatile private var pushedId: String? = null

    /** pushedId 那次推送对应的元数据 key；歌词下载回来时用它校验"还是当前这首" */
    @Volatile private var pushedKey: String? = null

    /** 当前这一首的元数据 key（换歌判定 + 解析过期判定） */
    @Volatile private var currentKey: String? = null

    /** 已经为哪个 key 清过屏 */
    @Volatile private var clearedKey: String? = null

    @Volatile private var songTitle: String = ""
    @Volatile private var songArtist: String = ""
    @Volatile private var songDuration: Long = 0L

    private var lastSong: Song? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val downloading = ConcurrentHashMap.newKeySet<String>()

    /** 歌词加载管理器单例（结构查找） */
    @Volatile private var managerInstance: Any? = null
    @Volatile private var managerLookupDone = false

    /** 管理器的 SongInfo 字段缓存 */
    @Volatile private var songInfoField: Field? = null

    override fun onHook() {

        onAppLifecycle {
            onCreate {
                hostApplication = this
                DiskSongCache.initialize(this)
            }
            onTerminate { scope.cancel() }
        }

        runCatching { hookMediaSession() }
        runCatching { managerInstance() }
    }

    /* ------------------------------------------------------------------ */
    /* 触发：系统 MediaSession（稳定）                                      */
    /* ------------------------------------------------------------------ */

    private fun hookMediaSession() {
        val cls = MEDIA_SESSION_CLASS.toClass(appClassLoader) ?: run {
            return
        }
        var n = 0
        cls.declaredMethods.forEach { method ->
            when {
                method.name == "setMetadata" && method.parameterCount == 1 -> {
                    n++
                    method.hook {
                        after {
                            val md = args[0] as? MediaMetadata ?: return@after
                            onMetadata(md)
                        }
                    }
                }

                method.name == "setPlaybackState" && method.parameterCount == 1 -> {
                    n++
                    method.hook {
                        after {
                            provider?.player?.setPlaybackState(args[0] as? PlaybackState)
                        }
                    }
                }
            }
        }
    }

    private fun onMetadata(md: MediaMetadata) {
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
        val album = md.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        val mediaId = md.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
        if (title.isBlank()) return

        // 换歌判定：优先 MEDIA_ID，没有就用 歌名|歌手|专辑
        val key = mediaId?.takeIf { it.isNotBlank() } ?: "$title|$artist|$album"
        if (key == currentKey) return
        currentKey = key

        songTitle = title
        songArtist = artist
        md.getLong(MediaMetadata.METADATA_KEY_DURATION).takeIf { it > 0 }?.let { songDuration = it }

        clearScreen(key)
        scope.launch { resolveAndPush(key, 0) }
    }

    /**
     * 换歌瞬间立刻清屏：推一首空歌词占位。
     * **不动 pushedId** —— pushedId 既是"同一首歌不重复推"的去重依据，
     * 也是"解析结果是否过期"的判据；一旦被改成临时 id，
     * 后面管理器返回上一首时就会被误推出去（于是显示上一首的歌词）。
     */
    private fun clearScreen(key: String) {
        if (key == clearedKey) return
        // provider 未就绪时**不标记 clearedKey** —— 否则这次清屏永远补不上
        if (!ensureProvider()) return
        clearedKey = key
        setSong(
            Song(
                id = "meta:$key",
                name = songTitle,
                artist = songArtist,
                duration = songDuration,
                lyrics = emptyList()
            )
        )
    }

    /* ------------------------------------------------------------------ */
    /* 取 songId：先管理器，后搜索                                           */
    /* ------------------------------------------------------------------ */

    private suspend fun resolveAndPush(key: String, attempt: Int) {
        if (key != currentKey) return   // 已经切到别的歌了，本次解析作废

        // ① 首选：歌词加载管理器持有的当前歌曲
        managerSongId()?.let { id ->
            if (id == pushedId) {
                // 管理器还返回上一首 → 过期，稍后重试（不要推！）
                retryStale(key, attempt) { resolveAndPush(key, it) }
                return
            }
            push(id)
            return
        }

        // ② 兜底：按「歌名 + 歌手」搜索
        val found = resolveBySearch(songTitle, songArtist)
        if (found == null) {
            return
        }
        if (found == pushedId) {
            retryStale(key, attempt) { resolveAndPush(key, it) }
            return
        }
        push(found)
    }

    /** 解析结果仍是上一首：判过期，退避重试；超过上限就放弃（保持清屏） */
    private suspend fun retryStale(key: String, attempt: Int, again: suspend (Int) -> Unit) {
        if (attempt >= STALE_RETRY_MAX) {
            return
        }
        delay(STALE_RETRY_STEP_MS * (attempt + 1))
        again(attempt + 1)
    }

    /** 取管理器里当前歌曲的 id（单例用结构查找，不写死混淆方法名） */
    private fun managerSongId(): String? {
        val inst = managerInstance() ?: return null
        val field = songInfoField ?: runCatching {
            inst.javaClass.declaredFields.firstOrNull { it.type.name == SONG_INFO_TYPE }
                ?.also { it.isAccessible = true }
        }.getOrNull()?.also { songInfoField = it } ?: return null

        val songInfo = runCatching { field.get(inst) }.getOrNull() ?: return null
        val id = runCatching {
            songInfo.javaClass.getMethod("C3").invoke(songInfo) as? Long
        }.getOrNull() ?: return null
        return if (id > 0) id.toString() else null
    }

    /** 结构查找管理器单例：静态字段(自类型) → 静态无参方法(返回自类型) */
    private fun managerInstance(): Any? {
        managerInstance?.let { return it }
        val cls = LOAD_MANAGER_CLASS.toClassOrNull(appClassLoader)
        if (cls == null) {
            if (!managerLookupDone) {
                managerLookupDone = true
            }
            return null
        }
        // 静态字段，类型为该类本身
        runCatching {
            cls.declaredFields.firstOrNull {
                Modifier.isStatic(it.modifiers) && it.type == cls
            }?.also { it.isAccessible = true }?.get(null)
        }.getOrNull()?.let {
            managerInstance = it
            return it
        }
        // 静态无参方法，返回类型为该类本身（如 U()）
        runCatching {
            cls.declaredMethods.firstOrNull {
                Modifier.isStatic(it.modifiers) && it.parameterTypes.isEmpty() && it.returnType == cls
            }?.also { it.isAccessible = true }?.invoke(null)
        }.getOrNull()?.let {
            managerInstance = it
            return it
        }
        if (!managerLookupDone) {
            managerLookupDone = true
        }
        return null
    }

    private fun resolveBySearch(title: String, artist: String): String? = runCatching {
        if (title.isBlank()) return@runCatching null
        val keyword = if (artist.isBlank()) title else "$title $artist"
        val url = "$SEARCH_URL?w=${URLEncoder.encode(keyword, "UTF-8")}" +
                "&format=json&p=1&n=5&cr=1&catZhida=1"
        val body = httpGet(url) ?: return@runCatching null
        val list = JSONObject(body).optJSONObject("data")
            ?.optJSONObject("song")
            ?.optJSONArray("list") ?: return@runCatching null
        if (list.length() == 0) return@runCatching null

        pickBest(title, artist, list)?.let { best ->
            val id = best.optLong("songid", 0L)
            if (id > 0) return@runCatching id.toString()
        }
        null
    }.getOrNull()

    private fun pickBest(title: String, artist: String, list: JSONArray): JSONObject? {
        val nt = norm(title)
        val na = norm(artist)
        var best: JSONObject? = null
        var bestScore = 0
        for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: continue
            val name = norm(o.optString("songname"))
            val singers = norm(singersOf(o))
            var score = 0
            when {
                name == nt -> score += 4
                nt.isNotEmpty() && (name.contains(nt) || nt.contains(name)) -> score += 3
            }
            if (na.isNotEmpty() && singers.isNotEmpty() &&
                (singers.contains(na) || na.contains(singers))
            ) score += 2
            if (score > bestScore) {
                bestScore = score
                best = o
            }
        }
        return if (bestScore >= 3) best else null
    }

    private fun singersOf(o: JSONObject): String {
        val arr = o.optJSONArray("singer") ?: return ""
        val names = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() }?.let { names.add(it) }
        }
        return names.joinToString("/")
    }

    private fun norm(s: String): String = s.lowercase().replace(NORM_REGEX, "")

    private fun httpGet(urlStr: String): String? = runCatching {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("User-Agent", "Mozilla/5.0")
            setRequestProperty("Referer", "https://y.qq.com/")
        }
        try {
            val code = conn.responseCode
            val body = if (code in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            }
            body
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    /* ------------------------------------------------------------------ */
    /* 推送                                                                */
    /* ------------------------------------------------------------------ */

    private fun push(songId: String) {
        // 去重：同一首歌不重复推（清屏占位不影响这里，因为 pushedId 只在真正推送时更新）
        if (songId == pushedId) return
        // provider 未就绪时**不占用 pushedId** —— 否则这首歌会被去重挡住，一直不出词
        if (!ensureProvider()) {
            return
        }
        pushedId = songId
        pushedKey = currentKey

        DiskSongCache.get(songId)?.let { cached ->
            setSong(cached)
            return
        }

        // 显式空歌词占位
        setSong(
            Song(
                id = songId,
                name = songTitle,
                artist = songArtist,
                duration = songDuration,
                lyrics = emptyList()
            )
        )

        if (!downloading.add(songId)) return
        scope.launch {
            try {
                val lines = QrcDownloader.downloadLyrics(songId).parsedLyric.richLyricLines
                if (lines.isNotEmpty()) {
                    // 行尾钳制 + 逐字裁剪：消除行间重叠，恢复词幕的 YoYo 切行动画
                    val fixed = clampOverlaps(lines)
                    val song = Song(
                        id = songId,
                        name = songTitle,
                        artist = songArtist,
                        duration = songDuration,
                        lyrics = fitWordsToWindow(fixed).filterInvalidTranslation()
                    )
                    DiskSongCache.put(song)
                    // 双校验：songId 仍是最近推送的那首，**且**那次推送对应的还是当前这首歌。
                    // 只查 songId 不够：切到新歌但解析失败时 pushedId 不会变，
                    // 旧歌的迟到下载会把刚清掉的屏幕又变回旧歌的歌词。
                    if (songId == pushedId && pushedKey == currentKey) setSong(song)
                }
            } catch (e: Exception) {
            } finally {
                downloading.remove(songId)
            }
        }
    }

    private fun ensureProvider(): Boolean {
        provider?.let { return true }
        val ctx = hostApplication ?: return false
        synchronized(this) {
            provider?.let { return true }
            return runCatching {
                provider = LyriconFactory.createProvider(
                    context = ctx,
                    providerPackageName = Constants.PROVIDER_PACKAGE_NAME,
                    playerPackageName = Constants.MUSIC_PACKAGE_NAME,
                    logo = ProviderLogo.fromSvg(Constants.ICON)
                ).apply {
                    register()
                    player.setDisplayTranslation(true)
                }
                true
            }.getOrDefault(false)
        }
    }

    private fun setSong(song: Song) {
        if (lastSong == song) return
        lastSong = song
        provider?.player?.setSong(song)
    }

    private fun List<RichLyricLine>.filterInvalidTranslation(): List<RichLyricLine> =
        onEach { line -> if (line.translation?.trim() == "//") line.translation = null }

    /**
     * 行尾钳制：把每行的 end 钳到「下一行 begin 之前」，签名与实现对齐 v2.0.4。
     *
     * QRC 的行尾会在下一行起点之后多留一截（歌词行 200~1700ms）。原样透传会让
     * 词幕 `TimingNavigator.hasOverlap` 为真 → `forEachAt` 同时返回多行 →
     * `activeLines.size > 1`，`LyricPlayerView` 的 YoYo 切行动画那一支永远进不去
     * （退化成布局过渡，观感就是切行时横向一顿）。
     *
     * 钳制不改变"当前显示哪一行"（词幕 `forEachAtOrPrevious` 在空隙里仍保留上一行）。
     */
    private fun clampOverlaps(lines: List<RichLyricLine>): List<RichLyricLine> {
        if (lines.size < 2) return lines

        // 不假设 lines 已按 begin 升序（原文/翻译轨交错时会乱序）：
        // 乱序时 nextBegin 可能小于 cur.begin，会把一条正常行钳成 1ms。先按时间排。
        val sorted = lines.sortedBy { it.begin }

        val out = ArrayList<RichLyricLine>(sorted.size)
        for (i in sorted.indices) {
            val cur = sorted[i]
            val nextBegin = sorted.getOrNull(i + 1)?.begin
            // nextBegin > cur.begin：起点相同的行没法钳（钳了就是负长度），交给词幕原逻辑
            if (nextBegin != null && nextBegin > cur.begin && cur.end > nextBegin) {
                val newEnd = (nextBegin - 1).coerceAtLeast(cur.begin + 1)
                if (newEnd < cur.end) {
                    out.add(cur.copy(end = newEnd, duration = (newEnd - cur.begin).coerceAtLeast(1)))
                    continue
                }
            }
            out.add(cur)
        }
        return out
    }

    /**
     * 逐字裁剪：把每个字的区间裁进本行窗口 [begin, end]，实现对齐 v2.0.4。
     *
     * 行尾被 [clampOverlaps] 钳过之后，一行最后几个字可能落到窗口外；词幕是按"字"
     * 推进高亮的，越界的字会让这一行的高亮一直动到窗口之外，切行时和下一行的高亮
     * 一起走（看起来两行动画叠在一起）。
     *
     * 只裁逐字时间，窗口本身不动：这一行什么时候出现、什么时候切走都不变。
     */
    private fun fitWordsToWindow(lines: List<RichLyricLine>): List<RichLyricLine> {
        val out = lines.map { line ->
            val words = line.words
            if (words.isNullOrEmpty()) return@map line
            if (words.last().end <= line.end && words.first().begin >= line.begin) return@map line
            val kept = words.filter { it.end > line.begin && it.begin < line.end }.map { w ->
                val b = maxOf(w.begin, line.begin)
                val e = minOf(w.end, line.end)
                if (b == w.begin && e == w.end) w
                else LyricWord(text = w.text, begin = b, end = e, duration = e - b)
            }
            line.copy(words = kept.ifEmpty { null })
        }
        return out
    }

    // 纯音乐一律**透传服务器返回的内容**（与上游一致），不做关键词过滤、不替换文案。
    // 旧版本写入的脏缓存不按内容区分（那句文案与 QQ 服务器的原文相同，分不开），
    // 统一交给 DiskSongCache 的版本迁移一次性清空。

    /* ------------------------------------------------------------------ */
    /* 日志 / 工具                                                          */
    /* ------------------------------------------------------------------ */

    private val NORM_REGEX = Regex("[\\s\\p{Punct}·・（）()【】\\[\\]《》～~、，。！？：；“”‘’—–]")

}
