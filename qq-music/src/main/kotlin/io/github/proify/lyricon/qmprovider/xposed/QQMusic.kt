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
    @Volatile
    private var isInitialized = false

    // 下面这些会被多个线程碰：MediaSession 回调线程（换歌/播放状态）、
    // QQ 的构造器线程（抽歌词）、以及本模块自己起的合并线程与 watchdog。
    // 加 @Volatile 只是保证"改完能被别的线程看见"，逻辑没动。
    @Volatile
    private var currentSongId: String? = null
    @Volatile
    private var currentSongTitle: String = ""
    @Volatile
    private var currentSongArtist: String = ""
    @Volatile
    private var currentSongDuration: Long = 0

    @Volatile
    private var lastLyricDataHash: Int = 0
    @Volatile
    private var currentLyricsHash: Int = 0
    @Volatile
    private var originalLyrics: List<RichLyricLine>? = null
    @Volatile
    private var translationLyrics: List<RichLyricLine>? = null

    /** 引擎自带的"真实唱段"区间：key = 起始ms|文本，值是 [第一个字的开始, 最后一个字的结束] */
    // 抽歌词在 QQ 的构造器线程，读它在合并线程，clear() 又在换歌回调里 —— 用并发容器免得撞车
    private val sungRanges = java.util.concurrent.ConcurrentHashMap<String, LongArray>()

    /** 高亮区间对齐后，行最多比"第一个字"提前显示的毫秒数（QQ 原生最多提前 2 秒） */
    private val HEAD_LEAD_MS = 300L
    /** 原文轨 / 翻译轨配对的时间窗：两条轨同一句的时间差要落在这个范围内（常见 0~2 秒） */
    private val TRANS_MATCH_MS = 3000L
    /** 向后多看一行时，必须比当前这一行"近"这么多毫秒才改选（避免翻译轨整体延迟时把整条轨错位一格） */
    private val TRANS_PREFER_MS = 300L
    /** 判定"这一段翻译轨对得很紧"的阈值：最近几对的时间差都小于它就认为没有整体偏移 */
    private val TRANS_TIGHT_MS = 600L


    @Volatile
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

    /** 冷启动保险：容器类可能比 hook 时机更晚加载，轮询补 hook */
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
                        // 引擎自带的逐字时间段：既下发给词幕（高亮按每个字的时间走），
                        // 也记下"这句真正从哪一刻唱到哪一刻"，供后面的窗口对齐用（见 alignHighlightSpan）
                        val words = engineWords(line, text)
                        if (words != null) {
                            sungRanges[sungKey(startTime, text)] = longArrayOf(words.first().begin, words.last().end)
                        }
                        lyricLines.add(RichLyricLine(
                            begin = startTime,
                            end = startTime + duration,
                            duration = duration,
                            text = text,
                            words = words,
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
     * QQ 会把「下一首」的歌词文档预载进内存，而下一首常常是同一位歌手的另一首，
     * 元数据里的歌手能对上 —— 光靠 isCurrentSong 拦不住（歌手旁路不能删，否则
     * 「爱情魔法！」vs「爱的魔法」这类歌名有出入的本歌容器会被误杀）。
     *
     * 这里只用一个特征：首行形如「歌名 - 歌手」，且连字符前面那截歌名跟当前歌名对不上，就当成别的歌。
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
     * - 有歌名就信（入口 isCurrentSong 已经核过一遍）
     * - 没歌名但有歌手、而且歌手就是当前歌，也信（本歌自己缺歌名的翻译轨，如 Sacred Play Secret Place）
     * - 歌名歌手都空、或者歌手对不上，基本是 QQ 预载的别的歌，不信
     */
    private fun translationMetaOk(meta: Array<String?>): Boolean =
        !meta.getOrNull(0).isNullOrBlank() || sameArtist(meta.getOrNull(1))

    /**
     * 整轨几乎只有拉丁字母，就是罗马音轨（QQ 的日文歌常同时给"中文翻译轨 + 罗马音轨"）。
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

    /**
     * 制作人员/版权信息行的标签（QQ 文档开头那一串「词：/曲：/Lyrics by：/Composed by：…」）。
     * 这些行照常显示，只是不该拿别人的翻译。
     */
    private val CREDIT_LABELS = listOf(
        "lyrics by", "composed by", "produced by", "published by", "vocals by",
        "background vocals", "co-producer", "mixing engineer", "mastering engineer",
        "recorded at", "repertoire owner", "written by", "arranged by", "music by",
        // 中文标签一律连冒号一起匹配：不然"你为我作曲"这类歌词正文会被误杀
        "词：", "曲：", "编曲：", "作词：", "作曲：", "制作人：", "混音：", "母带：", "录音：", "出品：", "监制："
    )

    /**
     * 是不是「不参与翻译配对」的非歌词行：文档表头（歌名 - 歌手）或制作人员/版权行。
     * 依据：新版引擎里这些行都挤在开头 0~2.4 秒，和第一句歌词只差一两百毫秒，
     * 一旦参与配对就会把第一句的中文抢走。
     */
    private fun isCreditOrHeaderLine(line: RichLyricLine, docFirstLine: String?): Boolean {
        val t = line.text?.trim() ?: return true
        if (t.isEmpty()) return true
        // 半角冒号归一化，免得"作词:李荣浩"漏掉
        val lower = t.lowercase().replace(':', '：')
        // 制作人员行/词曲行一律写在行首（"Lyrics by：…"、"词：李荣浩"、"编曲：…"），
        // 所以只认行首：用 contains 会被"你为我作曲"这类歌词正文骗到
        if (CREDIT_LABELS.any { lower.startsWith(it) }) return true
        // 只有文档第一行才可能是表头，且必须形如「歌名 - 歌手」（带空格的连字符）
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
                // 不是本歌的原文，但可能是"先到的翻译轨"（QQ 的轨顺序不固定，
                // 中文轨可能比日文原文先到）。带本歌元数据的就挂到翻译位。
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
            // 歌名为空、歌手也对不上，基本都是 QQ 预载的「别的歌」；
            // 但歌名为空、歌手就是当前歌，是本歌自己缺了歌名的翻译轨（如 Sacred Play Secret Place）
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
            lyrics.sortedBy { it.begin }.take(6).forEach { t ->
            }
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

        // 配对：QQ 的原文轨和翻译轨是同两条时间轴，正常情况下逐行一一对应
        //（两条轨正常情况下逐行一一对应），按顺序对齐即可。
        //   ·源码写法「每条原文行取 ±2 秒内第一条翻译」会被表头（0ms）和「词：/曲：/Lyrics by：」
        //    这类制作人员行抢走第一句的翻译（它们跟第一句只差一两百毫秒）；
        //   ·反过来「每条翻译取全局最近的原文行」又会在翻译轨整体延迟约 1.4 秒时
        //    把翻译吸到后一句上（《Alone》第 2、4 句只剩原文）。
        // 所以这里：先剔掉不参与配对的表头/制作人员行，再按顺序对齐，只接受时间相近的一对。
        // 另外翻译轨偶尔会缺一两条（有的歌某一句就是没有译文），
        // 此时"顺序上第一条"会把下一句的翻译吸过来、缺的那句反而空白，所以窗口内多看一行、取时间更近的那行。
        val transList = translation!!
        val transDocFirst = transList.minByOrNull { it.begin }?.text
        // 翻译轨也可能带表头/制作人员行（双语文档里"作词：…"这类会被翻译成中文），
        // 它们同样不参与配对，否则会顶掉第一句歌词的翻译、把整条轨错位一格
        val transLyrics = transList.filterNot { isCreditOrHeaderLine(it, transDocFirst) }
        val assignedTrans = HashMap<Int, String>()   // 原文行下标 -> 翻译文本
        val transToOrig = HashMap<Int, Int>()        // 翻译下标 -> 原文下标（-1=没配上）

        val origSortedIdx = original!!.indices.sortedBy { original!![it].begin }
        val docFirstLine = original!!.getOrNull(origSortedIdx.firstOrNull() ?: 0)?.text
        val lyricIdx = origSortedIdx.filterNot { isCreditOrHeaderLine(original!![it], docFirstLine) }

        val transSorted = transLyrics.sortedBy { it.begin }
        // 最近几对的时间差：用来判断"这一段轨对不对得紧"（紧 = 翻译轨没有整体偏移）
        val recentDelta = ArrayDeque<Long>()
        var li = 0
        var switched = 0
        for ((ti, trans) in transSorted.withIndex()) {
            // 越过时间上明显更早、又没等到翻译的歌词行
            while (li < lyricIdx.size && original!![lyricIdx[li]].begin < trans.begin - TRANS_MATCH_MS) li++
            val d0 = if (li < lyricIdx.size) {
                kotlin.math.abs(trans.begin - original!![lyricIdx[li]].begin)
            } else Long.MAX_VALUE
            val d1 = if (li + 1 < lyricIdx.size) {
                kotlin.math.abs(trans.begin - original!![lyricIdx[li + 1]].begin)
            } else Long.MAX_VALUE
            // 默认：顺序取第一条落在窗内的歌词行。
            // 这个默认对"整条翻译轨统一晚一截"的歌最稳（错开一格就全错了）。
            var pick = -1
            if (d0 < TRANS_MATCH_MS) pick = li
            if (pick == -1 && d1 < TRANS_MATCH_MS) pick = li + 1
            // 改选下一行的情形：QQ 的翻译轨少了一条（有的歌某一句就是没有译文），
            // 这时"顺序第一条"会把下一句的译文吸到上一句、缺的那句反而空白。
            // 但必须同时满足三个条件才敢改，免得把"整条轨有偏移"的歌弄错位：
            //   ① 当前这行确实对得远（>= 1 秒）；② 下一行比它近得多；③ 这一段轨本身对得很紧。
            val tight = (recentDelta.minOrNull() ?: Long.MAX_VALUE) <= TRANS_TIGHT_MS
            if (pick == li && d1 < TRANS_MATCH_MS && d0 >= 1000 &&
                d1 + TRANS_PREFER_MS <= d0 && tight
            ) {
                pick = li + 1
                switched++
            }
            if (pick >= 0) {
                val oIdx = lyricIdx[pick]
                val origLine = original!![oIdx]
                val text = trans.text
                if (text != null && text != origLine.text && text != "//" && text.isNotBlank()) {
                    assignedTrans[oIdx] = text
                    transToOrig[ti] = oIdx
                }
                li = pick + 1
                recentDelta.addLast(kotlin.math.abs(trans.begin - origLine.begin))
                while (recentDelta.size > 3) recentDelta.removeFirst()
            }
        }
        original!!.forEachIndexed { i, origLine ->
            merged.add(RichLyricLine(
                begin = origLine.begin,
                end = origLine.end,
                duration = origLine.duration,
                text = origLine.text,
                words = origLine.words,
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
                sendEmptyLyrics()
            }
        }.start()
    }

    private fun sendLyrics(lyrics: List<RichLyricLine>) {
        // 新版 QQ 引擎的行尾会越界到下一行，这里钳一下（见 clampOverlaps 注释）
        val aligned = fitWordsToWindow(alignHighlightSpan(clampOverlaps(lyrics)))
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

    /**
     * 按引擎自带的逐字时间段，把"高亮扫描区间"收窄到这句真正唱的那一段。
     *
     * 背景：词幕的「相对进度高亮」在没有逐词数据时，会把整行当成一个词、以 [RichLyricLine.duration]
     * 为时长匀速扫完，而 QQ 的行 duration 是"到下一句开始的时间"（含句间停顿，
     * 有一行窗口 27 秒、实际只唱 0.8 秒的情况），于是高亮越到句尾越落后于人声。
     *
     * 改动两处（这是有意为之的取舍，不是"什么都不变"）：
     * - `duration`：改成"从显示起点扫到最后一个字结束"，高亮扫完的时刻 = 人声唱完的时刻；
     * - `begin`：最多比"第一个字"提前 [HEAD_LEAD_MS] 显示（QQ 原生最多提前 2 秒）。
     *
     * 副作用：
     * - `begin` 变晚，词幕的活跃判定 `begin <= pos <= end` 随之改变，这一行出现得比原来晚
     *   （最多约 1.7 秒），而在它出现之前 `forEachAtOrPrevious` 会继续显示上一行，
     *   也就是"上一行唱完还挂着一会儿"；
     * - `end` 不动，所以行显示到什么时候、以及 `InterludeTracker` 的间奏判定都不受影响。
     *
     * 逐字时间段缺失、时间不合法、或算出来反而更长时，一律原样返回（退回源码行为）。
     */
    private fun alignHighlightSpan(lines: List<RichLyricLine>): List<RichLyricLine> {
        if (sungRanges.isEmpty()) return lines
        var n = 0
        val out = lines.map { line ->
            val range = sungRanges[sungKey(line.begin, line.text)] ?: return@map line
            val sungBegin = range[0]
            val sungEnd = range[1]
            // 行首最多提前 HEAD_LEAD_MS 显示；并且必须严格小于 end
            //（begin == end 会被词幕当成非法行整行丢掉）
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

    /**
     * 把每行的逐字时间段裁进本行自己的窗口 [begin, end]。
     *
     * 引擎给的逐字时间是绝对毫秒。行尾被 [clampOverlaps] 钳过之后，一行最后几个字可能落到窗口外面；
     * 词幕拿到逐字数据后是按"字"推进高亮的，越界的字会让这一行的高亮一直动到窗口之外，
     * 切行时就会和下一行的高亮同时走（看起来像两行的动画叠在一起）。
     *
     * 只裁逐字时间，窗口本身不动：这一行什么时候出现、什么时候切走都和原来一样。
     */
    private fun fitWordsToWindow(lines: List<RichLyricLine>): List<RichLyricLine> {
        var n = 0
        var worst = 0L
        val out = lines.map { line ->
            val words = line.words
            if (words.isNullOrEmpty()) return@map line
            if (words.last().end <= line.end && words.first().begin >= line.begin) return@map line
            val over = words.last().end - line.end
            if (over > 0) {
                n++
                if (over > worst) worst = over
            }
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

    /**
     * 把每行的 [RichLyricLine.end] 钳到下一行 begin 之前（最后一行不动）。
     *
     * 新版 QQ 引擎给的 duration 会在行尾多留一截（表头行固定多 600ms，歌词行多 200~1700ms），
     * 原样透传会让词幕 `TimingNavigator.hasOverlap` 为真，`forEachAt` 就会同时返回多行，
     * `activeLines.size > 1` 之后 `LyricPlayerView` 的 YoYo 切行动画那一支永远进不去（退化成布局过渡）。
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

    /**
     * 读取引擎自带的逐字时间段，转成词幕的 [LyricWord]。
     *
     * 引擎行对象里有一串「逐字时间段」（字段结构：起始ms / 时长ms / 文字），
     * 字符与字符首尾相接、绝对时间轴，末字的结束正好是下一句的开头，
     * 也就是说它能准确回答"这句到底从哪一刻唱到哪一刻"，而行对象上的 duration 只是
     * "到下一句开始的时间"（含停顿）。
     *
     * 返回值有两个用途：写进 [RichLyricLine.words] 交给词幕按字推进高亮，
     * 同时给 [sungRanges] 记一份首字开始 / 末字结束。
     *
     * 结构不对、时间不合法、或拼不回整行文本时一律返回 null（宁可退回源码行为，也不改坏歌词）。
     */
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
        // 逐字文本必须能原样拼回整行（词幕会用逐字重建文本，拼不回就不敢下发）
        if (out.joinToString("") { it.text.orEmpty() } != lineText) return null
        return out
    }

}
