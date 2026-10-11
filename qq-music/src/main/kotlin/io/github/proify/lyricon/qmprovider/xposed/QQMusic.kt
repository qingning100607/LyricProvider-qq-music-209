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
import io.github.proify.lyricon.lyric.model.LyricWord
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

    // 引擎逐字时间里的真实唱段：key = 起始ms|文本 -> [首字起点, 末字终点]
    private val sungRanges = HashMap<String, LongArray>()

    // 高亮区间对齐后，行最多比第一个字提前显示的毫秒数
    private val HEAD_LEAD_MS = 300L

    
    private var isLyricClassHooked = false

    // 合并推送：短时间内多个文档各推一次会让词幕反复 reset 视图，只推最后一次
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
        "享有本翻译作品的著作权", "本翻译作品", "歌词贡献者", "翻译贡献者"
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

    // 冷启动保险：容器类可能比 hook 时机更晚加载，轮询补 hook
    private fun startHookWatchdog() {
        Thread {
            val deadline = System.currentTimeMillis() + 60_000L
            while (System.currentTimeMillis() < deadline) {
                if (isLyricClassHooked) return@Thread
                try {
                    val clazz = "com.lyricengine.base.k".toClassOrNull(appClassLoader)
                    if (clazz != null) {
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
        if (processName != PLAYER_SERVICE_PROCESS) {
            return
        }

        hookMediaSession()
        hookClassLoader()
        startHookWatchdog()

        onAppLifecycle {
            onCreate {
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
        } catch (e: Exception) {
            Log.e(TAG, "initProvider failed: ${e.message}", e)
        }
    }

    private fun hookClassLoader() {
        try {
            val pathClassLoader = appClassLoader ?: return
            val classLoaderClass = pathClassLoader.javaClass
            
            val loadClassMethod = classLoaderClass.getDeclaredMethod("loadClass", String::class.java, Boolean::class.java)
            
            loadClassMethod.hook {
                after {
                    val className = args[0] as? String ?: return@after
                    val clazz = this.result as? Class<*> ?: return@after
                    
                    if (!isLyricClassHooked && isPotentialLyricDataClass(className, clazz)) {
                        tryHookLyricClass(clazz)
                    }
                }
            }
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
            val linesField = findLinesField(clazz)
            if (linesField == null) {
                Log.d(TAG, "No suitable lines field found in ${clazz.name}")
                return
            }
            
            detectedLinesFieldName = linesField.name
            val constructors = clazz.declaredConstructors
            constructors.forEach { constructor ->
                constructor.hook {
                    after {
                        val instance = this.instance ?: return@after
                        extractLyricsDynamic(instance)
                    }
                }
            }
            
            hookLyricMethods(clazz)
            
            isLyricClassHooked = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to hook lyric class ${clazz.name}: ${e.message}")
        }
    }
    
    private fun hookLyricMethods(clazz: Class<*>) {
        val methods = clazz.declaredMethods
        methods.take(20).forEach { method ->
            val methodName = method.name
            val paramCount = method.parameterCount
            
            try {
                method.hook {
                    after {
                        val instance = this.instance ?: return@after
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
        val hash = System.identityHashCode(instance)
        if (hash == lastLyricDataHash) {
            return
        }
        
        if (currentSongId == null) {
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
                        // 记下引擎逐字段的首尾，供高亮区间对齐用（逐字数据不下发）
                        engineWords(line, text)?.let { words ->
                            sungRanges[sungKey(startTime, text)] = longArrayOf(words.first().begin, words.last().end)
                        }
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
        
        detectedLineClassFields = fields
        return fields
    }

    private fun fallbackHookKnownClasses() {
        val knownClassPatterns = mutableListOf<String>()
        
        // ===== 新版引擎（QQ 音乐 20.9.0.8）的歌词容器类 =====
        // 旧版按 a~z 扫"名字带 lyric 且有 List 字段"的类，会 hook 错类；
        // 新版类名确定：com.lyricengine.base.k（字段 e = CopyOnWriteArrayList，行对象 t）
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
                    val hasListField = clazz.declaredFields.any { field ->
                        java.util.List::class.java.isAssignableFrom(field.type)
                    }
                    
                    if (hasListField) {
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
            clazz.declaredConstructors.forEach { constructor ->
                constructor.hook {
                    after {
                        val instance = this.instance ?: return@after
                        
                        Thread {
                            Thread.sleep(100)
                            extractLyricsDynamic(instance)
                        }.start()
                    }
                }
            }
            
            isLyricClassHooked = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to hook ${clazz.name}: ${e.message}")
        }
    }
    
    // 引擎内存里同时存在多首歌的歌词容器（上一首/下一首的残留），而源码只按"第一个到的算
    // 原文、第二个到的算翻译"来区分，分不出容器属于谁，别人的歌词就会进翻译位。
    // 容器自带歌名/歌手字段，这里只用它做一件事：不是当前播放的歌就丢弃。
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
    
    // 翻译候选的首行是不是别的歌的表头。
    // QQ 会预载下一首的歌词文档，下一首常常是同一位歌手的另一首，歌手元数据对得上，
    // 所以还要看首行：形如「歌名 - 歌手」且歌名跟当前歌对不上的，当成别的歌丢掉。
    // 本歌的翻译容器首行是版权声明或对得上的表头，不会命中。
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

    // 翻译候选的元数据能不能信：有歌名就信（入口已经核过一遍）；
    // 没歌名但歌手是当前歌也信（本歌缺歌名的翻译轨）；
    // 歌名歌手都空、或歌手对不上，基本都是预载的别的歌，不信。
    private fun translationMetaOk(meta: Array<String?>): Boolean =
        !meta.getOrNull(0).isNullOrBlank() || sameArtist(meta.getOrNull(1))

    // 整轨几乎只有拉丁字母就是罗马音轨（日文歌常同时给中文轨和罗马音轨）。
    // 翻译位先被罗马音占了也没关系，后面来了中文轨可以替换它。
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

    // 文档开头的词曲/制作人员行标签（词：、曲：、Lyrics by：、Composed by： …）。
    // 这些行照常显示，只是不参与翻译配对。
    private val CREDIT_LABELS = listOf(
        "lyrics by", "composed by", "produced by", "published by", "vocals by",
        "background vocals", "co-producer", "mixing engineer", "mastering engineer",
        "recorded at", "repertoire owner", "written by", "arranged by", "music by",
        // 中文标签一律连冒号一起匹配：不然"你为我作曲"这类歌词正文会被误杀
        "词：", "曲：", "编曲：", "作词：", "作曲：", "制作人：", "混音：", "母带：", "录音：", "出品：", "监制："
    )

    // 不参与翻译配对的行：文档表头（歌名 - 歌手），以及词曲/制作人员行。
    // 它们都挤在文档开头，跟第一句歌词只差一两百毫秒，参与配对会抢走第一句的翻译。
    private fun isCreditOrHeaderLine(line: RichLyricLine, docFirstLine: String?): Boolean {
        val t = line.text?.trim() ?: return true
        if (t.isEmpty()) return true
        // 半角冒号归一化，免得"作词:李荣浩"漏掉
        val lower = t.lowercase().replace(':', '：')
        // 这些行都写在行首，所以只认行首，免得歌词里出现"作曲"之类的词被误判
        if (CREDIT_LABELS.any { lower.startsWith(it) }) return true
        // 表头只可能是文档第一行，且形如「歌名 - 歌手」（连字符前后带空格）
        return t == docFirstLine?.trim() && Regex("\\s[-–—]\\s?").containsMatchIn(t)
    }

    private fun processLyrics(lyrics: List<RichLyricLine>, meta: Array<String?>) {
        // 不是本歌的容器（别的歌的残留）直接丢弃
        if (!isCurrentSong(meta)) {
            return
        }

        val lastLineEnd = lyrics.lastOrNull()?.end ?: 0
        val firstLine = lyrics.firstOrNull()?.text ?: ""
        
        if (isInstrumentalPlaceholder(lyrics)) {
            if (originalLyrics != null || currentLyricsHash != 0) {
                return
            }
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
            
            // 歌名主体命中（去标点后包含）也算本歌
            val matchesByTitleKey = titleKey.isNotEmpty() &&
                    (normalizeForCompare(firstLine).contains(titleKey) ||
                            lyrics.any { normalizeForCompare(it.text).contains(titleKey) })
            if (!matchesCurrentSong && !matchesByTitleKey && lyrics.size > 5) {
                // 不是本歌的原文：可能是先到的翻译轨（两条轨的先后顺序不固定）。
                // 带本歌元数据的就挂到翻译位，别浪费。
                if (translationLyrics == null && translationMetaOk(meta) &&
                    !looksLikeOtherSongHeader(lyrics)
                ) {
                    translationLyrics = lyrics
                    scheduleMerge()
                } else {
                }
                return
            }
            
            originalLyrics = lyrics
        } else if (translationLyrics == null || isRomaLike(translationLyrics!!)) {
            if (isDuplicateOf(originalLyrics, lyrics)) {
                return
            }
            // 歌名为空、歌手也对不上 → 基本都是 QQ 预载的「别的歌」；
            // 但歌名为空、歌手就是当前歌 → 是本歌自己缺了歌名的翻译轨（如 Sacred Play Secret Place）
            if (!translationMetaOk(meta)) {
                return
            }
            // 歌手对得上也可能放进同一位歌手的另一首（QQ 会预载下一首），再看一眼首行
            if (looksLikeOtherSongHeader(lyrics)) {
                return
            }
            // 已经是罗马音轨时，别让另一条罗马音轨顶替；只有中文轨才值得替换
            if (translationLyrics != null && isRomaLike(lyrics)) {
                return
            }
            translationLyrics = lyrics
        } else {
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
    }
    
    private fun mergeAndSendLyrics() {
        val original = originalLyrics
        // 去掉“著作权声明/来源说明”行，避免被配到开头几行
        val translation = translationLyrics?.filterNot { line ->
            val t = line.text
            t != null && NOTICE_KEYWORDS.any { t.contains(it) }
        }
        
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

        // 原文轨和翻译轨是逐行对应的两条时间轴，按顺序一对一配。
        // 源码那种"每条原文取 ±2 秒内第一条翻译"的写法，会被表头和词曲/制作人员行抢走
        // 第一句的翻译；反过来"每条翻译取最近原文行"又会在翻译轨整体晚一秒多时把翻译
        // 吸到后一句上。所以先剔掉不参与配对的行，再按顺序对齐，只接受时间相近（<2 秒）的一对。
        val transList = translation!!
        val transDocFirst = transList.minByOrNull { it.begin }?.text
        // 翻译轨同样可能带表头/制作人员行，它们也不参与配对，否则会把整条轨顶得错位一格
        val transLyrics = transList.filterNot { isCreditOrHeaderLine(it, transDocFirst) }
        val assignedTrans = HashMap<Int, String>()   // 原文行下标 -> 翻译文本
        val origSortedIdx = original!!.indices.sortedBy { original!![it].begin }
        val docFirstLine = original!!.getOrNull(origSortedIdx.firstOrNull() ?: 0)?.text
        val lyricIdx = origSortedIdx.filterNot { isCreditOrHeaderLine(original!![it], docFirstLine) }
        val transSorted = transLyrics.sortedBy { it.begin }
        var li = 0
        for (trans in transSorted) {
            // 越过时间上明显更早、又没等到翻译的歌词行
            while (li < lyricIdx.size && original!![lyricIdx[li]].begin < trans.begin - 2000) li++
            // 按顺序取第一条时间相近的歌词行；当前这条超出窗口时，允许往后跳 1 行
            var pick = -1
            var probe = li
            while (probe < lyricIdx.size && probe <= li + 1) {
                if (kotlin.math.abs(trans.begin - original!![lyricIdx[probe]].begin) < 2000) {
                    pick = probe
                    break
                }
                probe++
            }
            if (pick >= 0) {
                val oIdx = lyricIdx[pick]
                val origLine = original!![oIdx]
                val text = trans.text
                if (text != null && text != origLine.text && text != "//" && text.isNotBlank()) {
                    assignedTrans[oIdx] = text
                }
                li = pick + 1
            }
        }
        original!!.forEachIndexed { i, origLine ->
            merged.add(RichLyricLine(
                begin = origLine.begin,
                end = origLine.end,
                duration = origLine.duration,
                text = origLine.text,
                translation = assignedTrans[i]
            ))
        }

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
                }

                if (method.name == "setPlaybackState" && method.parameterCount == 1) {
                    method.hook {
                        after {
                            val state = args[0] as? PlaybackState
                            provider?.player?.setPlaybackState(state)
                        }
                    }
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

        lastLyricDataHash = 0
        currentLyricsHash = 0
        originalLyrics = null
        translationLyrics = null
        sungRanges.clear()
        val cached = QQLyricsCache.get(songId)
        if (cached != null) {
            currentLyricsHash = cached.hashCode()
            sendSong(cached)
        }

        // 换歌后 6 秒还没拿到本曲任何歌词（有些歌 QQ 就是没词），推一次空歌词清屏，
        // 免得词幕一直挂着上一首在走。只在本曲一次都没推过时执行，之后真歌词到了照常推送。
        Thread {
            try {
                Thread.sleep(6000)
            } catch (e: InterruptedException) {
                return@Thread
            }
            if (songId == currentSongId && originalLyrics == null && translationLyrics == null &&
                currentLyricsHash == 0
            ) {
                sendEmptyLyrics()
            }
        }.start()
    }

    private fun sendLyrics(lyrics: List<RichLyricLine>) {
        // 引擎行尾会越界到下一行，先钳一下（见 clampOverlaps）
        val aligned = alignHighlightSpan(clampOverlaps(lyrics))
        val hash = aligned.hashCode()
        if (hash == currentLyricsHash) {
            return
        }

        if (currentSongId.isNullOrEmpty()) {
            return
        }

        currentLyricsHash = hash
        QQLyricsCache.put(currentSongId!!, aligned)
        sendSong(aligned)

    }

    private fun sungKey(begin: Long, text: String?): String = "$begin|${text.orEmpty()}"

    // 把高亮扫描区间收窄到这句真正唱的那一段。
    //
    // 没有逐词数据时，词幕会把整行当成一个词、按 duration 匀速扫完；而 QQ 的行 duration
    // 是"到下一句开始的时间"，含句间停顿（有的行窗口二十多秒，实际只唱一秒），
    // 于是高亮越到句尾越落后于人声。这里按引擎逐字段把区间收到真实唱段上：
    // duration = 从显示起点扫到末字结束；begin 最多比首字提前 HEAD_LEAD_MS 显示。
    // end 不动，所以行的显示时长和间奏判定不受影响；begin 变晚会让这一行比原来晚一点出现，
    // 在它出现之前词幕仍然显示上一行。
    // 逐字数据缺失或算出来不合理时原样返回。
    private fun alignHighlightSpan(lines: List<RichLyricLine>): List<RichLyricLine> {
        if (sungRanges.isEmpty()) return lines
        var n = 0
        val out = lines.map { line ->
            val range = sungRanges[sungKey(line.begin, line.text)] ?: return@map line
            val sungBegin = range[0]
            val sungEnd = range[1]
            // 行首最多提前 HEAD_LEAD_MS 显示，且必须严格小于 end（相等会被词幕丢掉）
            var newBegin = maxOf(line.begin, sungBegin - HEAD_LEAD_MS)
            if (newBegin >= line.end) newBegin = line.end - 1
            val newDuration = sungEnd - newBegin
            // 只在"确实更短、且落在一行之内"时才改
            if (newDuration <= 0 || newDuration >= line.duration || sungEnd > line.end + 1) return@map line
            if (newBegin == line.begin && newDuration == line.duration) return@map line
            n++
            line.copy(begin = newBegin, duration = newDuration)
        }
        val noWord = lines.count { sungRanges[sungKey(it.begin, it.text)] == null }
        return out
    }

    // 把每行的 end 钳到下一行 begin 之前（最后一行不动）。
    // 引擎给的 duration 会在行尾多留一截（表头 +600ms，歌词行 +200~1700ms），原样透传
    // 会让相邻行时间轴重叠，词幕据此认为"同时有多行活跃"，切行动画就走不到。
    // 钳制不改变当前显示哪一行（空隙里词幕仍保留上一行），也不影响间奏判定。
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

    /** 引擎「行对象」里逐字时间段字段的反射句柄（begin/duration/text），首次用到时探测 */
    private var wordTimeFields: Triple<Field, Field, Field>? = null

    /** 找出这一行的逐字列表（行对象里第一个非空的 List/Collection 字段） */
    private fun findWordList(line: Any): List<*>? {
        for (f in line.javaClass.declaredFields) {
            if (!Collection::class.java.isAssignableFrom(f.type)) continue
            val v = runCatching {
                f.isAccessible = true
                f.get(line) as? Collection<*>
            }.getOrNull() ?: continue
            if (v.isEmpty()) continue
            return v.toList()
        }
        return null
    }

    // 读取引擎行对象里的逐字时间段，只取首尾时间，不下发逐字数据。
    // 结构：起始ms / 时长ms / 文字，字与字首尾相接、绝对时间轴，末字结束正好是下一句开头，
    // 所以它能给出这句真实的起唱和收尾时刻，而行对象上的 duration 只是"到下一句开始的时间"。
    // 结构不符、时间不合法、或拼不回整行文本时返回 null，退回源码行为。
    private fun engineWords(line: Any, lineText: String): List<LyricWord>? {
        val list = findWordList(line) ?: return null
        val t = wordTimeFields ?: run {
            val cls = list.firstOrNull()?.javaClass ?: return null
            val textField = cls.declaredFields.firstOrNull { it.type == String::class.java } ?: return null
            val numberFields = cls.declaredFields.filter {
                it.type == java.lang.Long::class.java || it.type == java.lang.Long.TYPE ||
                    it.type == java.lang.Integer::class.java || it.type == java.lang.Integer.TYPE
            }
            if (numberFields.size < 2) return null
            Triple(numberFields[0], numberFields[1], textField).also {
                wordTimeFields = it
            }
        }
        val out = ArrayList<LyricWord>(list.size)
        for (el in list) {
            if (el == null) return null
            val begin = runCatching { t.first.isAccessible = true; (t.first.get(el) as? Number)?.toLong() }.getOrNull() ?: return null
            val dur = runCatching { t.second.isAccessible = true; (t.second.get(el) as? Number)?.toLong() }.getOrNull() ?: return null
            val text = runCatching { t.third.isAccessible = true; t.third.get(el) as? String }.getOrNull() ?: return null
            if (begin < 0 || dur <= 0) return null
            out.add(LyricWord(text = text, begin = begin, end = begin + dur, duration = dur))
        }
        // 逐字文本必须能原样拼回整行，拼不回就当作没有逐字数据
        if (out.joinToString("") { it.text.orEmpty() } != lineText) return null
        return out
    }

}
