/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.qmprovider.xposed

import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.util.Log
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.ProviderLogo
import java.lang.reflect.Field
import java.lang.reflect.Modifier

object QQMusic : YukiBaseHooker() {

    private const val TAG = "QQMusicProvider"
    private const val PLAYER_SERVICE_PROCESS = "com.tencent.qqmusic:QQPlayerService"

    private var provider: LyriconProvider? = null
    private var isInitialized = false
    
    private var currentSongId: String? = null
    private var currentSongTitle: String = ""
    private var currentSongArtist: String = ""
    private var currentSongDuration: Long = 0
    
    private var lastLyricDataHash: Int = 0
    private var currentLyricsHash: Int = 0
    
    private var originalLyrics: List<RichLyricLine>? = null
    private var translationLyrics: List<RichLyricLine>? = null
    
    private var isLyricClassHooked = false

    /** 合并推送：短时间内多个文档各推一次会让词幕反复 reset() 视图，这里只推最后一次 */
    private val mergeLock = Any()
    @Volatile
    private var mergeVersion: Long = 0L
    private val MERGE_DELAY_MS = 300L

    private fun scheduleMerge() {
        val version = synchronized(mergeLock) { ++mergeVersion }
        Thread {
            try {
                Thread.sleep(MERGE_DELAY_MS)
            } catch (_: InterruptedException) {
                return@Thread
            }
            if (synchronized(mergeLock) { mergeVersion } != version) return@Thread
            mergeAndSendLyrics()
        }.start()
    }

    /** 版权声明/来源说明行：不该出现在翻译位 */
    private val NOTICE_KEYWORDS = listOf(
        "享有本翻译作品的著作权", "本翻译作品", "歌词贡献者", "翻译贡献者",
        "由AI生成", "由文曲", "由AI工具", "以下为AI"
    )

    /** 与原文内容高度重合（≥60% 行相同）= 同一首歌的重复轨，不是翻译 */
    private fun isDuplicateOf(original: List<RichLyricLine>?, candidate: List<RichLyricLine>): Boolean {
        if (original.isNullOrEmpty() || candidate.isEmpty()) return false
        val n = minOf(original.size, candidate.size, 40)
        if (n < 3) return false
        var same = 0
        for (i in 0 until n) {
            val a = original[i].text?.trim()
            val b = candidate[i].text?.trim()
            if (!a.isNullOrEmpty() && a == b) same++
        }
        return same.toDouble() / n >= 0.6
    }

    /** 冷启动保险：容器类可能比 hook 时机更晚加载，轮询补 hook */
    private fun startHookWatchdog() {
        Thread {
            val deadline = System.currentTimeMillis() + 60_000L
            while (System.currentTimeMillis() < deadline) {
                if (isLyricClassHooked) return@Thread
                try {
                    val clazz = "com.lyricengine.base.k".toClassOrNull(appClassLoader)
                    if (clazz != null) {
                        dlog("watchdog: 容器类已加载，补 hook")
                        hookAllConstructors(clazz)
                        if (isLyricClassHooked) return@Thread
                    }
                } catch (_: Throwable) {
                }
                try {
                    Thread.sleep(1000)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
        }.start()
    }
    private var detectedLinesFieldName: String? = null
    private var detectedLineClassFields: Map<String, Field>? = null

    override fun onHook() {
        dlog("=== onHook called ===")
        dlog("processName: $processName")

        if (processName != PLAYER_SERVICE_PROCESS) {
            dlog("Skipping non-player process: $processName")
            return
        }

        dlog("Hooking in QQPlayerService process")

        hookMediaSession()
        hookClassLoader()
        startHookWatchdog()

        onAppLifecycle {
            onCreate {
                dlog("App onCreate in QQPlayerService")
                initProvider()
            }
        }
    }

    private fun initProvider() {
        if (isInitialized) return
        val ctx = appContext ?: return
        isInitialized = true

        try {
            provider = LyriconFactory.createProvider(
                context = ctx,
                providerPackageName = Constants.PROVIDER_PACKAGE_NAME,
                playerPackageName = ctx.packageName,
                logo = ProviderLogo.fromSvg(Constants.ICON)
            ).apply {
                register()
                player.setDisplayTranslation(true)
            }
            dlog("Provider initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "initProvider failed: ${e.message}", e)
        }
    }

    private fun hookClassLoader() {
        dlog("Hooking ClassLoader for dynamic class detection...")
        
        try {
            val pathClassLoader = appClassLoader ?: return
            val classLoaderClass = pathClassLoader.javaClass
            
            val loadClassMethod = classLoaderClass.getDeclaredMethod("loadClass", String::class.java, Boolean::class.java)
            
            loadClassMethod.hook {
                after {
                    val className = args[0] as? String ?: return@after
                    val clazz = this.result as? Class<*> ?: return@after
                    
                    if (!isLyricClassHooked && isPotentialLyricDataClass(className, clazz)) {
                        dlog("Potential lyric data class detected: $className")
                        tryHookLyricClass(clazz)
                    }
                }
            }
            dlog("ClassLoader hooked successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to hook ClassLoader: ${e.message}")
            fallbackHookKnownClasses()
        }
    }

    private fun isPotentialLyricDataClass(className: String, clazz: Class<*>): Boolean {
        if (isLyricClassHooked) return false
        if (clazz.isInterface || Modifier.isAbstract(clazz.modifiers)) return false
        if (className.startsWith("java.") || className.startsWith("android.")) return false
        if (className.startsWith("kotlin.") || className.startsWith("kotlinx.")) return false
        if (className.startsWith("com.google.") || className.startsWith("com.squareup.")) return false
        if (className.contains("View") || className.contains("Activity") || className.contains("Fragment")) return false
        if (className.contains("Adapter") || className.contains("Holder") || className.contains("Layout")) return false
        
        if (!className.contains("lyric", ignoreCase = true) && 
            !className.contains("LyricEngine", ignoreCase = true)) {
            return false
        }
        
        val fields = clazz.declaredFields
        if (fields.isEmpty()) return false
        
        for (field in fields) {
            val fieldType = field.type.name
            if (fieldType.contains("CopyOnWriteArrayList") || 
                fieldType.contains("List") ||
                fieldType.contains("ArrayList")) {
                Log.d(TAG, "Class $className has list field: ${field.name} of type $fieldType")
                return true
            }
        }
        
        return false
    }

    private fun tryHookLyricClass(clazz: Class<*>) {
        if (isLyricClassHooked) return
        
        try {
            dlog("Attempting to hook class: ${clazz.name}")
            
            val linesField = findLinesField(clazz)
            if (linesField == null) {
                Log.d(TAG, "No suitable lines field found in ${clazz.name}")
                return
            }
            
            detectedLinesFieldName = linesField.name
            dlog("Found lines field: ${linesField.name}")
            
            val constructors = clazz.declaredConstructors
            dlog("Found ${constructors.size} constructors")
            
            constructors.forEach { constructor ->
                dlog("Hooking constructor: ${constructor.parameterTypes.map { it.name }}")
                constructor.hook {
                    after {
                        dlog("Constructor called for ${clazz.name}")
                        val instance = this.instance ?: return@after
                        extractLyricsDynamic(instance)
                    }
                }
            }
            
            hookLyricMethods(clazz)
            
            isLyricClassHooked = true
            dlog("Successfully hooked lyric class: ${clazz.name}")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to hook lyric class ${clazz.name}: ${e.message}")
        }
    }
    
    private fun hookLyricMethods(clazz: Class<*>) {
        dlog("Looking for methods in ${clazz.name}")
        
        val methods = clazz.declaredMethods
        dlog("Found ${methods.size} methods")
        
        methods.take(20).forEach { method ->
            val methodName = method.name
            val paramCount = method.parameterCount
            
            dlog("Hooking method: $methodName with ${paramCount} params")
            
            try {
                method.hook {
                    after {
                        val instance = this.instance ?: return@after
                        dlog("Method $methodName called on ${clazz.name}")
                        extractLyricsDynamic(instance)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to hook method $methodName: ${e.message}")
            }
        }
    }

    private fun findLinesField(clazz: Class<*>): Field? {
        for (field in clazz.declaredFields) {
            val fieldType = field.type
            if (java.util.List::class.java.isAssignableFrom(fieldType) ||
                java.util.concurrent.CopyOnWriteArrayList::class.java.isAssignableFrom(fieldType)) {
                return field
            }
        }
        return null
    }

    private fun extractLyricsDynamic(instance: Any) {
        dlog("extractLyricsDynamic called")
        
        val hash = System.identityHashCode(instance)
        if (hash == lastLyricDataHash) {
            dlog("Same hash, skipping")
            return
        }
        
        if (currentSongId == null) {
            dlog("No current song, skipping")
            return
        }
        
        try {
            val clazz = instance.javaClass
            
            val linesFieldName = detectedLinesFieldName ?: findLinesField(clazz)?.name ?: return
            val linesField = clazz.getDeclaredField(linesFieldName)
            linesField.isAccessible = true
            val lines = linesField.get(instance) as? java.util.List<*>
            
            if (lines.isNullOrEmpty()) {
                return
            }
            
            dlog("Found ${lines.size} lines for song: $currentSongTitle")
            
            val firstLine = lines.firstOrNull()
            if (firstLine == null) return
            
            val lineClass = firstLine.javaClass
            val lineFields = detectLineFields(lineClass)
            
            val lyricLines = mutableListOf<RichLyricLine>()
            
            for ((index, line) in lines.withIndex()) {
                if (line == null) continue
                
                try {
                    val text = lineFields["text"]?.let { field ->
                        field.isAccessible = true
                        field.get(line) as? String ?: ""
                    } ?: ""
                    
                    val startTime = lineFields["startTime"]?.let { field ->
                        field.isAccessible = true
                        (field.get(line) as? Number)?.toLong() ?: 0L
                    } ?: 0L
                    
                    val duration = lineFields["duration"]?.let { field ->
                        field.isAccessible = true
                        (field.get(line) as? Number)?.toLong() ?: 0L
                    } ?: 0L
                    
                    if (text.isNotBlank() && text != "//") {
                        lyricLines.add(RichLyricLine(
                            begin = startTime,
                            end = startTime + duration,
                            duration = duration,
                            text = text,
                            translation = null
                        ))
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Failed to parse line $index: ${e.message}")
                }
            }
            
            if (lyricLines.isNotEmpty()) {
                lastLyricDataHash = hash
                val meta = containerMeta(instance)
                dlog("容器元数据: 歌名=${meta[0]}, 歌手=${meta[1]}")
                processLyrics(lyricLines, meta)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract lyrics: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun detectLineFields(lineClass: Class<*>): Map<String, Field> {
        detectedLineClassFields?.let { return it }
        
        val fields = mutableMapOf<String, Field>()
        val declaredFields = lineClass.declaredFields
        
        val stringFields = mutableListOf<Field>()
        val numberFields = mutableListOf<Field>()
        
        for (field in declaredFields) {
            when {
                field.type == String::class.java -> stringFields.add(field)
                field.type == Long::class.java || field.type == java.lang.Long::class.java ||
                field.type == Int::class.java || field.type == java.lang.Integer::class.java -> {
                    numberFields.add(field)
                }
            }
        }
        
        if (stringFields.isNotEmpty()) {
            fields["text"] = stringFields.first()
        }
        
        if (numberFields.size >= 1) {
            fields["startTime"] = numberFields[0]
        }
        if (numberFields.size >= 2) {
            fields["duration"] = numberFields[1]
        }
        
        dlog("Detected line fields: text=${fields["text"]?.name}, startTime=${fields["startTime"]?.name}, duration=${fields["duration"]?.name}")
        
        detectedLineClassFields = fields
        return fields
    }

    private fun fallbackHookKnownClasses() {
        dlog("Falling back to known class names...")
        
        val knownClassPatterns = mutableListOf<String>()
        
        // ===== 唯一的适配改动：新引擎（QQ音乐 20.9.0.8）的歌词容器类 =====
        // 旧版是按 a~z 暴力扫"名字里带 lyric 且有 List 字段"的类，会 hook 错类；
        // 新版确定性类名是 com.lyricengine.base.k（字段 e = CopyOnWriteArrayList，行对象 t）
        knownClassPatterns.add("com.lyricengine.base.k")
        knownClassPatterns.addAll(listOf(
            "com.tencent.qqmusic.common.utils.lyric.f",
            "com.tencent.qqmusic.common.utils.lyric.e",
            "com.tencent.qqmusic.common.utils.lyric.d",
            "com.tencent.qqmusic.common.utils.lyric.c"
        ))
        
        for (className in knownClassPatterns) {
            try {
                val clazz = className.toClassOrNull(appClassLoader)
                if (clazz != null) {
                    dlog("Found class: $className")
                    dlog("Class fields: ${clazz.declaredFields.map { "${it.name}:${it.type.simpleName}" }}")
                    
                    val hasListField = clazz.declaredFields.any { field ->
                        java.util.List::class.java.isAssignableFrom(field.type)
                    }
                    
                    if (hasListField) {
                        dlog("Class has List field, hooking all constructors")
                        hookAllConstructors(clazz)
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Class $className error: ${e.message}")
            }
        }
    }
    
    private fun hookAllConstructors(clazz: Class<*>) {
        if (isLyricClassHooked) return
        
        try {
            val linesField = findLinesField(clazz)
            if (linesField == null) return
            
            detectedLinesFieldName = linesField.name
            dlog("Hooking ${clazz.name}, lines field: ${linesField.name}")
            
            clazz.declaredConstructors.forEach { constructor ->
                dlog("Hooking constructor: ${constructor.parameterTypes.map { it.name }}")
                constructor.hook {
                    after {
                        dlog("Constructor called for ${clazz.name}")
                        val instance = this.instance ?: return@after
                        
                        Thread {
                            Thread.sleep(100)
                            extractLyricsDynamic(instance)
                        }.start()
                    }
                }
            }
            
            isLyricClassHooked = true
            dlog("Successfully hooked lyric class: ${clazz.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to hook ${clazz.name}: ${e.message}")
        }
    }
    
    // ===== 唯一新增的判定 =====
    // 引擎内存里同时存在多首歌的歌词容器（上一首/下一首的残留），而源码里
    // “第一个到的算原文、第二个到的算翻译”分不出容器属于谁 —— 别人的歌词就会进翻译位。
    // 容器本身自带歌名/歌手字段（引擎自己判“首行是不是头部行”用的就是它），
    // 这里只用它做一件事：不是当前播放的歌 -> 丢弃。
    private var containerStringFields: List<java.lang.reflect.Field>? = null

    private fun containerMeta(instance: Any): Array<String?> {
        return try {
            val fields = containerStringFields ?: instance.javaClass.declaredFields
                .filter { it.type == String::class.java }
                .onEach { it.isAccessible = true }
                .also { containerStringFields = it }

            Array(3) { i -> runCatching { fields.getOrNull(i)?.get(instance) as? String }.getOrNull() }
        } catch (e: Exception) {
            arrayOf(null, null, null)
        }
    }

    /** 容器自带的歌名/歌手是否指向当前播放的歌（信息缺失时不拦截，走源码原逻辑） */
    private fun isCurrentSong(meta: Array<String?>): Boolean {
        fun norm(x: String?): String =
            x?.trim()?.lowercase()?.replace(Regex("[\\s\\p{Punct}·・（）()【】～~]"), "") ?: ""

        val title = norm(currentSongTitle)
        val artist = norm(currentSongArtist)
        val metaTitle = norm(meta.getOrNull(0))
        val metaArtist = norm(meta.getOrNull(1))

        if (title.isEmpty() || metaTitle.isEmpty()) return true
        if (metaTitle.contains(title) || title.contains(metaTitle)) return true
        if (artist.isNotEmpty() && metaArtist.isNotEmpty() &&
            (metaArtist.contains(artist) || artist.contains(metaArtist))
        ) return true

        return false
    }

    private fun isInstrumentalPlaceholder(lyrics: List<RichLyricLine>): Boolean {
        if (lyrics.size > 3) return false
        val text = lyrics.joinToString("") { it.text ?: "" }
        return text.contains("纯音乐") || 
               text.contains("没有填词") ||
               text.contains("instrumental") ||
               text.contains("Instrumental")
    }
    
    private fun processLyrics(lyrics: List<RichLyricLine>, meta: Array<String?>) {
        // 不是本歌的容器（别的歌的残留）直接丢弃
        if (!isCurrentSong(meta)) {
            dlog("丢弃非本歌容器: 歌名=${meta[0]}, 歌手=${meta[1]}")
            return
        }

        val lastLineEnd = lyrics.lastOrNull()?.end ?: 0
        val firstLine = lyrics.firstOrNull()?.text ?: ""
        
        dlog("Processing ${lyrics.size} lines, lastLineEnd=$lastLineEnd")
        dlog("First line: $firstLine")
        dlog("Current song title: $currentSongTitle")
        
        if (isInstrumentalPlaceholder(lyrics)) {
            if (originalLyrics != null || currentLyricsHash != 0) {
                dlog("已有正文歌词，忽略纯音乐占位")
                return
            }
            dlog("Instrumental placeholder detected, clearing all lyrics")
            originalLyrics = null
            translationLyrics = null
            lastLyricDataHash = 0
            sendEmptyLyrics()
            return
        }
        
        if (originalLyrics == null) {
            val titleWords = currentSongTitle.split(" ", "(", "（", "-", "～", "~", "[", "【")
                .filter { it.isNotBlank() && it.length >= 2 }
            
            val matchesCurrentSong = titleWords.any { word ->
                firstLine.contains(word, ignoreCase = true) || 
                lyrics.any { it.text?.contains(word, ignoreCase = true) == true }
            }
            
            dlog("Matches current song: $matchesCurrentSong")
            
            if (!matchesCurrentSong && lyrics.size > 5) {
                dlog("Lyrics don't match current song, skipping")
                return
            }
            
            originalLyrics = lyrics
            dlog("Stored as original")
        } else if (translationLyrics == null) {
            if (isDuplicateOf(originalLyrics, lyrics)) {
                dlog("翻译候选与原文内容重合（重复轨），跳过")
                return
            }
            // 元数据缺失的容器大多是 QQ 预载的「别的歌」，不能占翻译位
            if (meta.getOrNull(0).isNullOrBlank()) {
                dlog("翻译候选无歌名元数据（疑似别的歌的预载），跳过")
                return
            }
            translationLyrics = lyrics
            dlog("Stored as translation")
        } else {
            dlog("Already have both, skipping")
        }
        
        scheduleMerge()
    }
    
    private fun sendEmptyLyrics() {
        if (currentSongId.isNullOrEmpty()) return
        
        val song = Song(
            id = currentSongId!!,
            name = currentSongTitle,
            artist = currentSongArtist,
            duration = 0,
            lyrics = emptyList()
        )
        
        provider?.player?.setSong(song)
        dlog("Sent empty lyrics for instrumental song")
    }
    
    private fun mergeAndSendLyrics() {
        val original = originalLyrics
        // 去掉“著作权声明/来源说明”行，避免被配到开头几行
        val translation = translationLyrics?.filterNot { line ->
            val t = line.text
            t != null && NOTICE_KEYWORDS.any { t.contains(it) }
        }
        
        dlog("mergeAndSendLyrics: original=${original?.size}, translation=${translation?.size}")
        
        if (original == null && translation == null) {
            return
        }
        
        if (original == null && translation != null) {
            sendLyrics(translation)
            return
        }
        
        if (original != null && translation == null) {
            sendLyrics(original)
            return
        }
        
        val merged = mutableListOf<RichLyricLine>()

        // 每一条翻译只配一次：取 ±2 秒内“最近”的那条原文行
        // （源码原本是 find 取第一条，同一条翻译会被重复配到前面好几行，连歌名/词曲行也会带上）
        val usedTrans = HashSet<Int>()
        val transList = translation!!

        for (origLine in original!!) {
            var bestIndex = -1
            var bestDelta = Long.MAX_VALUE
            for ((i, trans) in transList.withIndex()) {
                if (i in usedTrans) continue
                val delta = kotlin.math.abs(trans.begin - origLine.begin)
                if (delta < 2000L && delta < bestDelta) {
                    bestDelta = delta
                    bestIndex = i
                }
            }

            val transText = if (bestIndex >= 0) {
                usedTrans.add(bestIndex)
                transList[bestIndex].text
            } else null

            merged.add(RichLyricLine(
                begin = origLine.begin,
                end = origLine.end,
                duration = origLine.duration,
                text = origLine.text,
                translation = transText?.takeIf {
                    it != origLine.text && it != "//" && it.isNotBlank()
                }
            ))
        }
        
        dlog("Merged ${merged.size} lines")
        sendLyrics(merged)
    }

    private fun hookMediaSession() {
        runCatching {
            val mediaSessionClass = "android.media.session.MediaSession".toClass(appClassLoader) 
                ?: return@runCatching

            mediaSessionClass.declaredMethods.forEach { method ->
                if (method.name == "setMetadata" && method.parameterCount == 1) {
                    method.hook {
                        after {
                            val metadata = args[0] as? MediaMetadata ?: return@after
                            handleMetadataChange(metadata)
                        }
                    }
                    dlog("Hooked setMetadata")
                }

                if (method.name == "setPlaybackState" && method.parameterCount == 1) {
                    method.hook {
                        after {
                            val state = args[0] as? PlaybackState
                            provider?.player?.setPlaybackState(state)
                        }
                    }
                    dlog("Hooked setPlaybackState")
                }
            }
        }.onFailure { e ->
            Log.e(TAG, "hookMediaSession failed: ${e.message}")
        }
    }

    private fun handleMetadataChange(metadata: MediaMetadata) {
        val rawTitle = metadata.getString(MediaMetadata.METADATA_KEY_TITLE) ?: return
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
        val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)

        val songId = "$rawTitle|$artist|$album"

        if (songId == currentSongId) {
            if (duration > currentSongDuration) {
                currentSongDuration = duration
            }
            return
        }

        currentSongId = songId
        currentSongTitle = rawTitle
        currentSongArtist = artist
        currentSongDuration = duration

        dlog("=== New song ===")
        dlog("Title: $rawTitle")
        dlog("Artist: $artist")
        dlog("Duration: $duration")

        lastLyricDataHash = 0
        currentLyricsHash = 0
        originalLyrics = null
        translationLyrics = null
        
        val cached = QQLyricsCache.get(songId)
        if (cached != null) {
            dlog("Using cached lyrics")
            currentLyricsHash = cached.hashCode()
            sendSong(cached)
        }
    }

    private fun sendLyrics(lyrics: List<RichLyricLine>) {
        val hash = lyrics.hashCode()
        if (hash == currentLyricsHash) {
            return
        }
        
        if (currentSongId.isNullOrEmpty()) {
            return
        }

        currentLyricsHash = hash
        QQLyricsCache.put(currentSongId!!, lyrics)
        sendSong(lyrics)

        dlog("=== Lyrics (${lyrics.size} lines) ===")
        lyrics.take(5).forEach { line ->
            val trans = line.translation?.let { " [$it]" } ?: ""
            dlog("[${line.begin}] ${line.text}$trans")
        }
    }
    
    private fun sendSong(lyrics: List<RichLyricLine>) {
        if (currentSongId.isNullOrEmpty()) return

        val song = Song(
            id = currentSongId!!,
            name = currentSongTitle,
            artist = currentSongArtist,
            duration = 0,
            lyrics = lyrics
        )

        provider?.player?.setSong(song)
    }

    /** logcat 在这台设备上读不到，所以同时落盘（不影响任何原有逻辑） */
    private fun dlog(msg: String) {
        Log.i(TAG, msg)
        runCatching {
            val f = java.io.File("/data/data/com.tencent.qqmusic/files/lyricon_debug.log")
            f.appendText(java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date()) + "  " + msg + "\n")
        }
    }
}
