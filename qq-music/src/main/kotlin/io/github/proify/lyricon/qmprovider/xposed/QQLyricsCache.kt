/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.qmprovider.xposed

import android.util.Log
import io.github.proify.lyricon.lyric.model.RichLyricLine

/**
 * 最近播过的歌的歌词缓存：切歌时不用等引擎重新推一遍。
 *
 * 只保留最近 [MAX_SONGS] 首（LRU）。数量不封顶的话，一次听几百首会一直堆在内存里，
 * 而现在每条歌词还带着逐字时间段，比只存整行要大不少。
 * 写入来自合并线程、读取来自换歌回调，所以统一加锁。
 */
object QQLyricsCache {

    private const val TAG = "QQLyricsCache"
    private const val MAX_SONGS = 50

    private val cache = object : LinkedHashMap<String, CachedLyrics>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedLyrics>?): Boolean =
            size > MAX_SONGS
    }

    private data class CachedLyrics(
        val lyrics: List<RichLyricLine>,
        val songId: String
    )

    fun put(songId: String, lyrics: List<RichLyricLine>) {
        synchronized(cache) { cache[songId] = CachedLyrics(lyrics, songId) }
        Log.d(TAG, "Cached lyrics for: $songId (${lyrics.size} lines)")
    }

    fun get(songId: String): List<RichLyricLine>? {
        val cached = synchronized(cache) { cache[songId] }
        if (cached != null) {
            Log.d(TAG, "Cache hit for: $songId")
            return cached.lyrics
        }
        Log.d(TAG, "Cache miss for: $songId")
        return null
    }

    fun clear() {
        synchronized(cache) { cache.clear() }
        Log.d(TAG, "Cache cleared")
    }
}
