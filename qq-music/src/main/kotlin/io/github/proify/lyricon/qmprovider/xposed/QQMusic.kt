/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.qmprovider.xposed

import android.app.Application
import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.util.Log
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.ProviderLogo
import io.github.proify.qrckit.QrcDownloader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * QQ 音乐歌词提供器（下载路 / QRC）。
 *
 * 与上游 qq-music-hd 同架构：不反射歌词容器，而是 hook 播放器的「当前歌曲」
 * 拿到 songId（QQ 数字 musicId），用 [QrcDownloader] 从 QQ 音乐服务器下载
 * 已分好轨的歌词（原文 / 翻译 / 罗马音 + 逐字），直接交给词幕。
 *
 * songId 来源：com.tencent.qqmusic.business.lyricnew.load.manager.h 的
 * handlePlaySongChanged（W(boolean)，换歌时触发）。该类持有当前 SongInfo，
 * 反射 SongInfo.C3() 即 songId。
 *
 * 目标环境：QQ 音乐 20.9.0.8（versionCode 7458）。
 */
object QQMusic : YukiBaseHooker() {

    private const val TAG = "QQMusicProvider"
    private const val PLAYER_SERVICE_PROCESS = "com.tencent.qqmusic:QQPlayerService"
    private const val SONG_INFO_TYPE = "com.tencent.qqmusicplayerprocess.songinfo.SongInfo"
    private const val LOAD_MANAGER_CLASS = "com.tencent.qqmusic.business.lyricnew.load.manager.h"

    private var provider: LyriconProvider? = null
    private var isInitialized = false

    @Volatile private var currentSongId: String? = null
    @Volatile private var pendingSongId: String? = null
    @Volatile private var songTitle: String = ""
    @Volatile private var songArtist: String = ""
    @Volatile private var songDuration: Long = 0L

    private var lastSong: Song? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val downloading = ConcurrentHashMap.newKeySet<String>()

    override fun onHook() {
        if (processName != PLAYER_SERVICE_PROCESS) return

        hookMediaSession()
        hookSongChanged()

        onAppLifecycle {
            onCreate {
                DiskSongCache.initialize(this)
                initProvider(this)
            }
            onTerminate { scope.cancel() }
        }
    }

    private fun initProvider(ctx: Application) {
        if (isInitialized) return
        isInitialized = true
        runCatching {
            provider = LyriconFactory.createProvider(
                context = ctx,
                providerPackageName = Constants.PROVIDER_PACKAGE_NAME,
                playerPackageName = ctx.packageName,
                logo = ProviderLogo.fromSvg(Constants.ICON)
            ).apply {
                register()
                player.setDisplayTranslation(true)
            }
        }.onFailure { Log.e(TAG, "initProvider failed", it) }
    }

    private fun hookMediaSession() {
        runCatching {
            val cls = "android.media.session.MediaSession".toClass(appClassLoader) ?: return@runCatching
            cls.declaredMethods.forEach { method ->
                when {
                    method.name == "setMetadata" && method.parameterCount == 1 ->
                        method.hook {
                            after {
                                val md = args[0] as? MediaMetadata ?: return@after
                                songTitle = md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
                                songArtist = md.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
                                songDuration = md.getLong(MediaMetadata.METADATA_KEY_DURATION)
                                trySetSong()
                            }
                        }

                    method.name == "setPlaybackState" && method.parameterCount == 1 ->
                        method.hook {
                            after {
                                provider?.player?.setPlaybackState(args[0] as? PlaybackState)
                            }
                        }
                }
            }
        }.onFailure { Log.e(TAG, "hookMediaSession failed", it) }
    }

    /** hook 歌词加载管理器的 handlePlaySongChanged，拿当前歌曲 songId */
    private fun hookSongChanged() {
        val cls = LOAD_MANAGER_CLASS.toClassOrNull(appClassLoader)
        if (cls == null) {
            return
        }
        val targets = cls.declaredMethods.filter {
            it.name == "W" && it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == java.lang.Boolean.TYPE
        }
        targets.forEach { m ->
            m.hook {
                after {
                    val inst = this.instance ?: return@after
                    val id = readSongId(inst)
                    if (id != null) {
                        pendingSongId = id
                        trySetSong()
                    }
                }
            }
        }
    }

    /** 从播放管理器实例里读当前 SongInfo，再取 songId（SongInfo.C3()） */
    private fun readSongId(manager: Any): String? = runCatching {
        val songInfo = manager.javaClass.declaredFields
            .firstOrNull { it.type.name == SONG_INFO_TYPE }
            ?.also { it.isAccessible = true }
            ?.get(manager) ?: return@runCatching null
        val id = songInfo.javaClass.getMethod("C3").invoke(songInfo) as? Long ?: return@runCatching null
        if (id > 0) id.toString() else null
    }.getOrNull()

    private fun trySetSong() {
        val songId = pendingSongId ?: return
        if (songId == currentSongId) return
        currentSongId = songId

        DiskSongCache.get(songId)?.let {
            setSong(it)
            return
        }
        // 先占位（歌词随后补）
        setSong(Song(id = songId, name = songTitle, artist = songArtist, duration = songDuration))

        if (!downloading.add(songId)) return
        scope.launch {
            try {
                val lines = QrcDownloader.downloadLyrics(songId).parsedLyric.richLyricLines
                // 纯音乐：服务器也会给一份占位文档（"纯音乐，请欣赏"这类），别当歌词推给词幕。
                // 上面已经推过一首空歌词把上一首清了，这里直接结束。
                if (isInstrumentalPlaceholder(lines)) return@launch
                if (lines.isNotEmpty()) {
                    val song = Song(
                        id = songId,
                        name = songTitle,
                        artist = songArtist,
                        duration = songDuration,
                        lyrics = lines.filterInvalidTranslation()
                    )
                    DiskSongCache.put(song)
                    if (songId == currentSongId) setSong(song)
                }
            } catch (e: Exception) {
            } finally {
                downloading.remove(songId)
            }
        }
    }

    private fun setSong(song: Song) {
        if (lastSong == song) return
        lastSong = song
        provider?.player?.setSong(song)
    }

    /** QQ 的翻译占位符 "//" 视为「无翻译」，置空后词幕按单行显示（对齐上游） */
    private fun List<RichLyricLine>.filterInvalidTranslation(): List<RichLyricLine> =
        onEach { line -> if (line.translation?.trim() == "//") line.translation = null }

    /** 纯音乐占位文档（"纯音乐，请欣赏"这类），不算歌词（同最早那版源码的判据） */
    private fun isInstrumentalPlaceholder(lyrics: List<RichLyricLine>): Boolean {
        if (lyrics.size > 3) return false
        val text = lyrics.joinToString("") { it.text ?: "" }
        return text.contains("纯音乐") ||
                text.contains("没有填词") ||
                text.contains("instrumental") ||
                text.contains("Instrumental")
    }
}
