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

    /** 诊断用：本曲推送次数（词幕每次 setSong 都会 reset() 视图） */

    
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
    
    /**
     * 翻译候选的首行是不是「别的歌」的表头。
     *
     * QQ 会把「下一首」的歌词文档预载进内存，而下一首常常是**同一位歌手的另一首**，
     * 元数据里的歌手能对上 —— 光靠 isCurrentSong 拦不住（歌手旁路不能删，否则
     * 「爱情魔法！」vs「爱的魔法」这类歌名有出入的本歌容器会被误杀）。
     *
     * 这里只用一个特征：首行形如「歌名 - 歌手」，且连字符前面那截歌名跟当前歌名对不上 → 别的歌。
     * 本歌的翻译容器首行一般是「QQ音乐享有本翻译作品的著作权」之类，或本歌自己的表头（歌名能对上），都不会命中。
     */
    private fun looksLikeOtherSongHeader(lyrics: List<RichLyricLine>): Boolean {
        val first = lyrics.firstOrNull()?.text?.trim() ?: return false
        // 「 - 」这种带空格的才是表头样式，歌名里带连字符的不会被误判
        val dash = Regex("\\s[-–—]").find(first) ?: return false
        val titlePart = normalizeForCompare(first.substring(0, dash.range.first))
        val current = normalizeForCompare(currentSongTitle)
        if (titlePart.isEmpty() || current.isEmpty()) return false

        // 精确比对（旧写法拿整行去 contains 歌名里的词，会被"爱情"/"爱情转移"这种共享词漏过去）
        return !(current.contains(titlePart) || titlePart.contains(current))
    }

    /** 只留字母/数字的归一化（等价于 isCurrentSong 里那套去空白/标点），用于歌名比对 */
    private fun normalizeForCompare(x: String?): String =
        x?.trim()?.lowercase()?.filter { it.isLetterOrDigit() } ?: ""

    /** 容器元数据的歌手是不是当前歌的歌手（归一化后互相包含） */
    private fun sameArtist(metaArtist: String?): Boolean {
        val a = normalizeForCompare(currentSongArtist)
        val b = normalizeForCompare(metaArtist)
        return a.isNotEmpty() && b.isNotEmpty() && (a.contains(b) || b.contains(a))
    }

    /**
     * 翻译候选的元数据能不能信：
     * - 有歌名 → 信（入口 isCurrentSong 已经核过一遍）
     * - 没歌名但有歌手且歌手就是当前歌 → 信（本歌自己缺歌名的翻译轨，如 Sacred Play Secret Place）
     * - 歌名歌手都空 / 歌手对不上 → 基本是 QQ 预载的别的歌，不信
     */
    private fun translationMetaOk(meta: Array<String?>): Boolean =
        !meta.getOrNull(0).isNullOrBlank() || sameArtist(meta.getOrNull(1))

    /**
     * 整轨几乎只有拉丁字母 → 罗马音轨（QQ 的日文歌常同时给"中文翻译轨 + 罗马音轨"）。
     * 用途：翻译位先被罗马音占了也不怕，后面来了中文轨就替换它。
     */
    private fun isRomaLike(lyrics: List<RichLyricLine>): Boolean {
        val text = lyrics.joinToString("") { it.text ?: "" }
        var letters = 0
        var cjk = 0
        for (ch in text) {
            if (ch.isLetter()) letters++
            val c = ch.code
            if (c in 0x3040..0x30FF || c in 0x4E00..0x9FFF || c in 0xAC00..0xD7AF) cjk++
        }
        return letters >= 10 && cjk * 5 < letters
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
            
            // 附加判据：QQ 的文档标题常和播放器标题只差标点（"ありがとう・・・" vs "ありがとう…"），
            // 按"歌名主体（去掉括号补充说明）+ 去标点/空格"再比一次
            val titleKey = normalizeForCompare(currentSongTitle.substringBefore(" (").substringBefore("（"))
            val matchesCurrentSong = titleWords.any { word ->
                firstLine.contains(word, ignoreCase = true) || 
                lyrics.any { it.text?.contains(word, ignoreCase = true) == true }
            }
            
            dlog("Matches current song: $matchesCurrentSong")
            
            // 歌名主体命中（去标点后包含）也算本歌
            val matchesByTitleKey = titleKey.isNotEmpty() &&
                    (normalizeForCompare(firstLine).contains(titleKey) ||
                            lyrics.any { normalizeForCompare(it.text).contains(titleKey) })
            if (titleKey.isNotEmpty()) dlog("歌名主体比对: key=$titleKey 命中=$matchesByTitleKey")

            if (!matchesCurrentSong && !matchesByTitleKey && lyrics.size > 5) {
                // 不是本歌的原文 —— 但可能是"先到的翻译轨"（QQ 的轨顺序不固定，实测
                // 《ありがとう…》的中文轨比日文原文先到）。带本歌元数据的就挂到翻译位，别浪费。
                if (translationLyrics == null && translationMetaOk(meta) &&
                    !isDuplicateOf(null, lyrics) && !looksLikeOtherSongHeader(lyrics)
                ) {
                    translationLyrics = lyrics
                    dlog("非原文轨（疑似先到的翻译），改挂翻译位")
                    scheduleMerge()
                } else {
                    dlog("Lyrics don't match current song, skipping")
                }
                return
            }
            
            originalLyrics = lyrics
            dlog("Stored as original")
        } else if (translationLyrics == null || isRomaLike(translationLyrics!!)) {
            if (isDuplicateOf(originalLyrics, lyrics)) {
                dlog("翻译候选与原文内容重合（重复轨），跳过")
                return
            }
            // 歌名为空、歌手也对不上 → 基本都是 QQ 预载的「别的歌」；
            // 但歌名为空、歌手就是当前歌 → 是本歌自己缺了歌名的翻译轨（如 Sacred Play Secret Place）
            if (!translationMetaOk(meta)) {
                dlog("翻译候选无歌名且歌手对不上（疑似别的歌的预载），跳过")
                return
            }
            // 歌手对得上也可能放进同一位歌手的另一首（QQ 会预载下一首），再看一眼首行
            if (looksLikeOtherSongHeader(lyrics)) {
                dlog("翻译候选首行像别的歌的表头: $firstLine，跳过")
                return
            }
            // 已经是罗马音轨时，别让另一条罗马音轨顶替；只有中文轨才值得替换
            if (translationLyrics != null && isRomaLike(lyrics)) {
                dlog("已有罗马音轨，罗马音不顶替")
                return
            }
            if (translationLyrics != null) dlog("中文轨替换掉已有的罗马音轨")
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

        // 迟到清屏：换歌后 6 秒还没拿到本曲任何歌词（有些歌 QQ 就是没词），
        // 就推一首空歌词，免得词幕一直挂着上一首的歌词在走。
        // 只在本曲一次都没推过时才推空；之后真歌词到了照样能推上去（currentLyricsHash 还是 0）。
        Thread {
            try {
                Thread.sleep(6000)
            } catch (e: InterruptedException) {
                return@Thread
            }
            if (songId == currentSongId && originalLyrics == null && translationLyrics == null &&
                currentLyricsHash == 0
            ) {
                dlog("本曲 6 秒内没有歌词，推空歌词清屏")
                sendEmptyLyrics()
            }
        }.start()
    }

    private fun sendLyrics(lyrics: List<RichLyricLine>) {
        // 新版 QQ 引擎的行尾会越界到下一行，这里钳一下（见 clampOverlaps 注释）
        val fixed = clampOverlaps(lyrics)
        val hash = fixed.hashCode()
        if (hash == currentLyricsHash) {
            return
        }

        if (currentSongId.isNullOrEmpty()) {
            return
        }

        currentLyricsHash = hash
        QQLyricsCache.put(currentSongId!!, fixed)
        sendSong(fixed)

    }

    /**
     * 把每行的 [RichLyricLine.end] 钳到下一行 begin 之前（最后一行不动）。
     *
     * 新版 QQ 引擎给的 duration 会在行尾多留一截（实测：表头行固定 +600ms，歌词行 +200~1700ms），
     * 原样透传会让词幕 `TimingNavigator.hasOverlap` 为真 → `forEachAt` 同时返回多行 →
     * `activeLines.size > 1` → `LyricPlayerView` 的 YoYo 切行动画那一支永远进不去（退化成布局过渡）。
     *
     * 钳制不会改变"当前显示哪一行"（词幕 `forEachAtOrPrevious` 在空隙里仍保留上一行），
     * 也不会影响间奏判定（`InterludeTracker` 只在 genuinely 有大间隙时才触发）。
     */
    private fun clampOverlaps(lines: List<RichLyricLine>): List<RichLyricLine> {
        if (lines.size < 2) return lines

        // 引擎偶尔会乱序（翻译/原文轨交错），不能想当然认为 lines 已按 begin 升序：
        // 乱序时 nextBegin 可能小于 cur.begin，会把一条正常行钳成 1ms。先按时间排。
        val sorted = lines.sortedBy { it.begin }

        var clamped = 0
        val out = ArrayList<RichLyricLine>(sorted.size)
        for (i in sorted.indices) {
            val cur = sorted[i]
            val nextBegin = sorted.getOrNull(i + 1)?.begin
            // nextBegin > cur.begin：begin 相同的重复行没法钳（钳了就是负长度），交给词幕原逻辑
            if (nextBegin != null && nextBegin > cur.begin && cur.end > nextBegin) {
                val newEnd = (nextBegin - 1).coerceAtLeast(cur.begin + 1)
                if (newEnd < cur.end) {
                    out.add(cur.copy(end = newEnd, duration = (newEnd - cur.begin).coerceAtLeast(1)))
                    clamped++
                    continue
                }
            }
            out.add(cur)
        }

        if (clamped > 0) dlog("诊断[钳制]: ${lines.size} 行里钳了 $clamped 行的 end")
        return out
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

    /**
     * 日志开关：用户担心磁盘写入影响性能，已改成空实现（零 I/O）。
     * 调用点全部保留，要排查问题时把实现换回下面两行即可：
     *   Log.i(TAG, msg)
     *   File("/data/data/com.tencent.qqmusic/files/lyricon_debug.log").appendText(...)
     * （带日志的完整版本另存了一份：/sdcard/Download/qq-music-src/QQMusic-withlogs.kt）
     */
    @Suppress("UNUSED_PARAMETER")
    private fun dlog(msg: String) {
    }
}
