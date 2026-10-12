/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.qmprovider.xposed

import android.content.Context
import com.highcapable.yukihookapi.hook.log.YLog
import io.github.proify.extensions.deflate
import io.github.proify.extensions.inflate
import io.github.proify.extensions.json
import io.github.proify.lyricon.lyric.model.Song
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.decodeFromStream
import java.io.File
import java.util.Locale

/**
 * 歌曲歌词磁盘缓存管理器
 * 采用 JSON + Gzip (Deflate) 压缩存储，按语言区域分目录存放
 */
@OptIn(ExperimentalSerializationApi::class)
object DiskSongCache {

    private const val TAG = "DiskSongCache"

    /** 缓存格式版本：改动缓存写入逻辑时升这个号，老缓存会在初始化时被整目录清空一次 */
    private const val CACHE_VERSION = 2
    private const val VERSION_FILE = "cache_version"

    private lateinit var cacheRootDir: File
    private lateinit var localizedLyricDir: File

    fun initialize(context: Context) {
        val currentLocale = Locale.getDefault().toLanguageTag()
        cacheRootDir = File(context.filesDir, "lyricon")
        localizedLyricDir = File(cacheRootDir, "lyrics/$currentLocale")

        migrateIfNeeded()
        if (!localizedLyricDir.exists()) {
            localizedLyricDir.mkdirs()
        }
    }

    /**
     * 缓存版本迁移：版本号不符时把整个 lyrics 目录清空一次。
     *
     * 旧版本曾把纯音乐替换成固定文案写进缓存，而那句文案恰好与 QQ 服务器给纯音乐的
     * 原文**完全相同**，按内容无法区分新旧 —— 所以用版本号一刀切，最可靠。
     */
    private fun migrateIfNeeded() {
        runCatching {
            val versionFile = File(cacheRootDir, VERSION_FILE)
            val current = if (versionFile.exists()) versionFile.readText().trim() else ""
            if (current != CACHE_VERSION.toString()) {
                val lyricsDir = File(cacheRootDir, "lyrics")
                if (lyricsDir.exists()) lyricsDir.deleteRecursively()
                lyricsDir.mkdirs()
                versionFile.writeText(CACHE_VERSION.toString())
            }
        }
    }

    /**
     * 从磁盘获取缓存的歌曲信息
     */
    fun get(songId: String): Song? {
        val cacheFile = resolveCacheFile(songId)
        if (!cacheFile.exists()) return null

        return runCatching {
            // 解压并流式解码
            val decompressedBytes = cacheFile.readBytes().inflate()
            json.decodeFromStream<Song>(decompressedBytes.inputStream())
        }.onFailure { e ->
            YLog.error("$TAG: Failed to load cache for $songId", e)
        }.getOrNull()
    }

    /**
     * 将歌曲信息持久化到磁盘
     */
    fun put(song: Song) {
        val songId = song.id
        if (songId.isNullOrBlank()) return

        runCatching {
            val cacheFile = resolveCacheFile(songId)
            // 序列化 -> 压缩
            val compressedData = json.encodeToString(song).toByteArray().deflate()

            if (cacheFile.parentFile?.exists() == false) {
                cacheFile.parentFile?.mkdirs()
            }
            cacheFile.writeBytes(compressedData)
        }.onFailure { e ->
            YLog.error("$TAG: Failed to save cache for ${song.id}", e)
        }
    }

    /**
     * 检查是否存在指定歌曲的缓存
     */
    fun isCached(songId: String): Boolean = resolveCacheFile(songId).exists()

    /**
     * 获取缓存文件句柄
     * 使用 .json.gz 作为后缀名（虽然底层是 Deflate，但 .gz 语义更通俗）
     */
    private fun resolveCacheFile(songId: String): File {
        return File(localizedLyricDir, "$songId.json.gz")
    }

//    /**
//     * 清理当前语言环境下的所有缓存
//     */
//    fun clearCurrentLocaleCache() {
//        localizedLyricDir.deleteRecursively()
//        localizedLyricDir.mkdirs()
//    }
}