package com.shiyin.music.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import android.util.Size
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shiyin.music.data.Track
import com.shiyin.music.data.formatDuration
import com.shiyin.music.ui.icons.Lucide
import com.shiyin.music.ui.theme.Caprasimo
import com.shiyin.music.ui.theme.Figtree
import com.shiyin.music.ui.theme.LocalOrganic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Generative-cover palette pairs [bg, fg], slot = trackId % 5, per handoff.
 *  Used as the fallback when a track has no real album art to extract from. */
@Composable
fun coverPalette(index: Int): Pair<Color, Color> {
    val c = LocalOrganic.current
    return when (index.mod(5)) {
        0 -> c.a300 to c.a900
        1 -> c.s300 to c.s900
        2 -> c.a200 to c.a800
        3 -> c.n300 to c.n800
        else -> c.s200 to c.s800
    }
}

// ── embedded album-art loading with an in-memory cache ─────────────────────
object ArtCache {
    // Byte-limited and keyed by (art identity, size bucket) so the
    // fullscreen player never reuses a small list thumbnail.
    // v1.2.0：24MB→64MB，大库封面多，小了滚动时反复淘汰→反复加载闪烁。容量优先。
    private val cache = object : LruCache<String, Bitmap>(64 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val misses = HashSet<String>()
    // v1.2.1: 重入保护——同 key(albumId@bucket)并发只跑一次 fetch,其余共享结果。
    // 旧实现无保护,专辑列表给同一专辑的多次重组/多 tile 各发一次 fetch → 15 线程同搜
    // 一张(日志实证)→ iTunes 限流 → 部分返回 0 → 封面瞬态"丢失" + 卡顿。
    private val pending = java.util.Collections.synchronizedMap(mutableMapOf<String, kotlinx.coroutines.CompletableDeferred<Bitmap?>>())

    // v3.0: in-memory albumId -> (bg, fg) color pair. Read on the main thread
    // (Compose) without touching disk; populated as covers load.
    // v4: PALETTE_VERSION — bump when PaletteExtractor algorithm changes.
    // primeColors only loads DB rows whose source ends with this version tag;
    // rows from older algorithms are skipped (not deleted), forcing ensureColors
    // to re-extract with the new algorithm and overwrite the DB row.
    internal const val PALETTE_VERSION = "v9"
    private val colorCache = HashMap<Long, Pair<Int, Int>>()
    private val colorPending = HashSet<Long>()

    private fun bucket(px: Int): Int = when {
        px <= 160 -> 160
        px <= 384 -> 384
        else -> 1024
    }

    /** v1.2.0：同步查内存 LruCache（不 load、不触磁盘/网络）。供 Composable 初始值
     *  用——列表滚动 item 回收重组时，produceState 初始值用它命中即显示，避免
     *  null→bitmap 一帧闪烁 + 反复加载。未命中返回 null，produceState 异步 load 补。 */
    fun getCached(track: Track, px: Int): Bitmap? {
        val artId = if (track.albumId > 0) track.albumId else -track.id
        val key = "$artId@${bucket(px)}"
        return synchronized(this) { cache.get(key) }
    }

    suspend fun load(context: Context, track: Track, px: Int, coverScope: CoverScope = CoverScope.AUTO): Bitmap? {
        val artId = if (track.albumId > 0) track.albumId else -track.id
        val b = bucket(px)
        val key = "$artId@$b"
        val missKey = "$artId" // a file with no art has none at any size
        synchronized(this) {
            cache.get(key)?.let { return it }
            if (missKey in misses) return null
        }
        // v1.2.1: 重入保护。同 key 并发:首个(owner)跑 fetchBmp 并 complete Deferred,
        // 其余直接 await 同一 Deferred → 只发一次网络请求(杀掉 N 线程同搜一张的 storm)。
        var owner = false
        val def = synchronized(pending) {
            pending[key]?.let { it } ?: run {
                owner = true
                kotlinx.coroutines.CompletableDeferred<Bitmap?>().also { pending[key] = it }
            }
        }
        if (owner) {
            try {
                val bmp = fetchBmp(context, track, key, missKey, b, coverScope)
                // v1.2.1: 先 complete(bmp) 再 ensureColors——若 ensureColors 抛异常(Cancellation/OOM),
                // 已完成的 Deferred 不会被 catch 的 complete(null) 覆盖(CompletableDeferred 不可重置),
                // awaiters 仍能拿到 bitmap,不因取色失败而丢封面。
                def.complete(bmp)
                if (bmp != null) ensureColors(context, track, bmp)
            } catch (e: Throwable) {
                // v1.2.1: 先 complete(null) 让 awaiters 不悬空;CancellationException 单独 rethrow,
                // 否则 catch(Throwable) 会吞掉取消→fetchBmp 不协作取消、结构化并发被破坏。
                def.complete(null)
                if (e is kotlinx.coroutines.CancellationException) throw e
            } finally {
                synchronized(pending) { if (pending[key] === def) pending.remove(key) }
            }
        }
        return def.await()
    }

    /**
     * v1.2.2: 给系统媒体会话(通知栏/锁屏/灵动岛)取封面。与 UI 同一条本地优先链,
     * 并优先复用 UI 正在显示的那张(内存 LruCache)。旧实现 fetchMediaSessionCover 手写
     * OkHttp 联网下载,不查缓存、大陆易失败→系统控制台/灵动岛经常无封面。本方法复用
     * [load](custom cover→内嵌→downloadBitmap 磁盘命中不联网),本地有图就不联网。
     */
    suspend fun loadForMediaSession(context: Context, track: Track, px: Int = 600): Bitmap? {
        // 优先 UI 已在内存的同一张(正在播放页/列表显示的那张),零成本且与用户所见一致
        getCached(track, px)?.let { return it }
        return load(context, track, px)
    }

    /** v1.2.2: 落盘 key 作用域。AUTO=按 albumId 判定(>0 专辑级,否则曲目级);TRACK=强制曲目级
     *  (孤立单曲/合集用,避免同 albumId 或 albumId=0 的封面互相覆盖)。 */
    enum class CoverScope { AUTO, TRACK }

    /** v1.2.1: load() 的 fetch 体(custom cover → 内嵌 → iTunes 兜底 + 缓存写入),抽出供重入保护 owner 调用。
     *  v1.2.2: 开头磁盘读优先(曾经获取过就永久本地可读,断网也能显示);任一路径成功拿图都统一落盘
     *  (writeAlbum 专辑级 / writeTrack 曲目级,视 coverScope),不再只落在 iTunes 下载末尾。 */
    private suspend fun fetchBmp(context: Context, track: Track, key: String, missKey: String, b: Int, coverScope: CoverScope): Bitmap? =
        withContext(Dispatchers.IO) {
            // v1.2.2: 磁盘读优先——先按 scope key 查本地持久化,命中直接解码(断网可用)。
            // 若这里命中,不会重复联网/重复落盘。
            val useTrackKey = coverScope == CoverScope.TRACK || track.albumId <= 0
            val diskBytes = if (useTrackKey) diskCache.readTrack(context, track.id, b)
                             else diskCache.readAlbum(context, track.albumId, b)
            if (diskBytes != null) {
                android.util.Log.d("ArtCache", "disk(scope) hit ${if (useTrackKey) "track" else "album"} id=${if (useTrackKey) track.id else track.albumId} bucket=$b")
                return@withContext android.graphics.BitmapFactory.decodeByteArray(diskBytes, 0, diskBytes.size)
            }
            // v1.2.2: 成功路径统一落盘(内存命中前/后都要写盘,确保"获取过一次就永久本地化")。
            fun persist(bmp: android.graphics.Bitmap) {
                val bytes = java.io.ByteArrayOutputStream().also {
                    bmp.compress(android.graphics.Bitmap.CompressFormat.WEBP_LOSSY, 90, it)
                }.toByteArray()
                if (useTrackKey) diskCache.writeTrack(context, track.id, b, bytes)
                else diskCache.writeAlbum(context, track.albumId, b, bytes)
            }
            try {
                // v4.3: custom cover override wins — it's the user's pinned "real"
                // album cover, so every track on the album shows the same art instead
                // of a standout single's embedded art. Loaded directly from the
                // stored content Uri, keyed by albumId so the whole album shares it.
                val customUri = albumCoverUri(context, track)
                if (customUri != null) {
                    val decoded = decodeUri(context, customUri, b) ?: run {
                        // custom cover missing on disk → fall through to embedded art
                        null
                    }
                    if (decoded != null) {
                        synchronized(this@ArtCache) {
                            cache.put(key, decoded)
                            misses.remove(missKey)
                        }
                        persist(decoded)
                        return@withContext decoded
                    }
                }
                val loaded = context.contentResolver.loadThumbnail(track.uri, Size(b, b), null)
                synchronized(this@ArtCache) { cache.put(key, loaded) }
                if (loaded != null) persist(loaded)   // v1.2.2: 内嵌封面也落盘
                loaded
            } catch (_: Exception) {
                // v2.0: iTunes fallback when embedded art is missing
                val networkResult = loadFromNetwork(context, track, b)
                val networkBmp = networkResult?.first
                if (networkBmp != null) {
                    synchronized(this@ArtCache) {
                        cache.put(key, networkBmp)
                        misses.remove(missKey)
                    }
                    persist(networkBmp)   // v1.2.2: 联网识别的也落盘(不再是仅 downloadBitmap 内部 artUrl 落盘)
                    // v4: store the iTunes releaseDate alongside the bitmap
                    val releaseDate = networkResult?.second ?: ""
                    if (releaseDate.isNotEmpty() && track.albumId > 0) {
                        withContext(Dispatchers.IO) {
                            try {
                                val db = com.shiyin.music.data.db.AppDatabase.get(context).dao()
                                db.updateAlbumReleaseDate(track.albumId, releaseDate)
                            } catch (_: Exception) { }
                        }
                    }
                    networkBmp
                } else {
                    synchronized(this@ArtCache) { misses.add(missKey) }
                    null
                }
            }
        }

    /** v9: load one iTunes candidate cover at ~[px] for the candidate picker.
     *  Public so the 更换专辑封面 dialog can render thumbnails async. v1.2.0 起
     *  走磁盘缓存——picker 翻过的候选下次打开秒出；用户选定的封面仍经
     *  saveAlbumCover(url) 持久化后走正常 load() 全缓存路径。 */
    suspend fun loadCandidateBitmap(context: Context, artUrl: String, px: Int): Bitmap? {
        // v1.2.0 #6: 本地写真(content:// 或 file://,用户选的本地文件)走 contentResolver,
        // 不走 OkHttp(OkHttp 不认 content://);同时绕过磁盘缓存避免本地文件被冗余缓存。
        if (artUrl.startsWith("content://") || artUrl.startsWith("file://")) {
            return withContext(Dispatchers.IO) {
                try {
                    val uri = android.net.Uri.parse(artUrl)
                    // v1.3.15: 先读文件头拿宽高、再按目标尺寸降采样解码。旧实现整张解码后
                    // createScaledBitmap —— 手机拍的 8000×6000 原图同样会撞 Canvas 上限闪退。
                    decodeScaledStream({ context.contentResolver.openInputStream(uri) }, px)
                } catch (_: Exception) { null }
            }
        }
        return downloadBitmap(context, artUrl, px)
    }

    /** v4.3: return the user's pinned custom cover Uri for [track]'s album, or
     *  null if none has been set. Read from album_info_override.coverUri. Runs
     *  on a background dispatcher; returns null on any error so callers fall
     *  through to embedded art. */
    private suspend fun albumCoverUri(context: Context, track: Track): android.net.Uri? {
        if (track.albumId <= 0) return null
        return withContext(Dispatchers.IO) {
            try {
                val ov = com.shiyin.music.data.db.AppDatabase.get(context).dao()
                    .albumInfoOverride(track.albumId)
                ov?.coverUri?.takeIf { it.isNotBlank() }?.let { android.net.Uri.parse(it) }
            } catch (_: Exception) { null }
        }
    }

    /** v4.3: decode a content/file Uri to a Bitmap of ~[px] size for custom
     *  album covers. Reuses ContentResolver.loadThumbnail for any content/file
     *  scheme, which is what the system photo picker returns.
     *  v9: also supports http(s):// Uris (remote iTunes cover URLs the user can
     *  now pin via the 更换专辑封面 candidate picker) — fetched via OkHttp and
     *  upscaled to [px], mirroring downloadBitmap's size-token replacement. */
    private suspend fun decodeUri(context: Context, uri: android.net.Uri, px: Int): Bitmap? {
        val scheme = uri.scheme?.lowercase()
        if (scheme == "http" || scheme == "https") {
            // v1.2.0：用户 pin 的 http 封面走磁盘缓存（复用 downloadBitmap 的盘查+落盘）
            return downloadBitmap(context, uri.toString(), px)
        }
        // v22: 用户手选的本地图片现在会先落盘再存 file://（见 MainViewModel.setAlbumCoverFromFile）。
        // 直接解码即可 —— loadThumbnail 是给 content:// 用的，对 file:// 会抛异常再回退，
        // 每次都白吃一次异常。
        if (scheme == "file") {
            // v1.3.15: 同样按目标尺寸降采样 —— 手机原图当封面会撞 Canvas 上限闪退。
            val path = uri.path ?: return null
            return decodeScaledFile(path, px)
        }
        return try {
            context.contentResolver.loadThumbnail(uri, Size(px, px), null)
        } catch (_: Exception) {
            // loadThumbnail unavailable for some schemes — fall back to decodeStream.
            decodeScaledStream({ context.contentResolver.openInputStream(uri) }, px)
        }
    }

    /** Extract and cache cover colors. Must be called **outside** any
     *  synchronized(this@ArtCache) { … } block because it is a suspend function. */
    private suspend fun ensureColors(context: Context, track: Track, bmp: Bitmap) {
        val albumId = track.albumId
        if (albumId <= 0) return // no stable key for cover-free tracks
        synchronized(this@ArtCache) {
            if (albumId in colorCache || albumId in colorPending) {
                android.util.Log.d("PaletteTrace", "ensureColors albumId=$albumId SKIP inCache=${albumId in colorCache} inPending=${albumId in colorPending}")
                return
            }
            colorPending.add(albumId)
        }
        val pair = withContext(extractDispatcher) {
            com.shiyin.music.data.colors.PaletteExtractor.extract(bmp)
        }
        android.util.Log.d("PaletteTrace", "ensureColors albumId=$albumId extracted bg=${pair?.let { Integer.toHexString(it.bgArgb) } ?: "null"} fg=${pair?.let { Integer.toHexString(it.fgArgb) } ?: "null"}")
        synchronized(this@ArtCache) {
            colorPending.remove(albumId)
            if (pair != null) colorCache[albumId] = pair.bgArgb to pair.fgArgb
        }
        // Persist asynchronously — never block the cover path on a DB write.
        // tag source with PALETTE_VERSION so primeColors can distinguish
        // new-algorithm colors from old ones without a schema migration.
        // v5: upsertColorsPreservingMeta 只更 bg/fg/source，绝不抹掉
        // url/fetchedAt/releaseDate（旧 upsertAlbumArtCache(REPLACE) 每次取色都
        // 把 iTunes 封面 URL 与发行日期一并清空）。source 必须升到 v5，否则
        // primeColors 不装载该行、每次冷启都重算。
        if (pair != null) {
            kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
                try {
                    val db = com.shiyin.music.data.db.AppDatabase.get(context).dao()
                    db.upsertColorsPreservingMeta(
                        albumId, pair.bgArgb, pair.fgArgb, "local:$PALETTE_VERSION",
                    )
                    android.util.Log.d("PaletteTrace", "ensureColors albumId=$albumId WROTE source=local:$PALETTE_VERSION bg=${Integer.toHexString(pair.bgArgb)} fg=${Integer.toHexString(pair.fgArgb)}")
                } catch (e: Exception) {
                    android.util.Log.e("PaletteTrace", "ensureColors albumId=$albumId DB WRITE FAILED", e)
                }
            }
        }
    }

    /** Returns cached real-cover colors for an album, or null if not yet
     *  extracted. Safe to call on the main thread. */
    fun colorFor(albumId: Long): Pair<Int, Int>? =
        if (albumId > 0) synchronized(colorCache) { colorCache[albumId] } else null

    /** v4.3: drop every cached bitmap + color for [albumId] so the next load
     *  picks up a freshly edited cover (or freshly edited album name → new
     *  iTunes match). Call after a cover change or album-info edit + rematch. */
    fun invalidateAlbum(albumId: Long) {
        if (albumId <= 0) return
        synchronized(this) {
            val artId = albumId.toString()
            val keys = cache.snapshot().keys.filter { it.startsWith("$artId@") }
            for (k in keys) cache.remove(k)
            misses.remove(artId)
            synchronized(colorCache) { colorCache.remove(albumId) }
        }
    }

    /** v1.2.2: 同 invalidateAlbum 并一并删除该专辑的本地持久化封面文件(album key),
     *  使封面变更/重新匹配后磁盘不再命中旧图。 */
    fun invalidateAlbum(context: Context, albumId: Long) {
        invalidateAlbum(albumId)
        if (albumId > 0) diskCache.deleteAlbum(context, albumId)
    }

    /** Warm the in-memory color cache from disk (called once at startup).
     *  v4: only loads rows whose source matches PALETTE_VERSION; older rows are
     *  skipped (not deleted) so ensureColors re-extracts with the new algorithm. */
    suspend fun primeColors(context: Context, rows: List<com.shiyin.music.data.db.AlbumArtCacheEntity>) {
        var loaded = 0
        val skippedSources = mutableMapOf<String, Int>()
        synchronized(colorCache) {
            for (r in rows) {
                // Only accept colors from the current palette algorithm version.
                // Old-version rows have source like "local" (no :vN suffix) → skip.
                if (r.bgArgb != 0 && r.fgArgb != 0 && r.source.endsWith(":$PALETTE_VERSION")) {
                    colorCache[r.albumId] = r.bgArgb to r.fgArgb
                    loaded++
                } else {
                    skippedSources.merge(r.source, 1) { a, b -> a + b }
                }
            }
        }
        android.util.Log.d("PaletteTrace", "primeColors rows=${rows.size} loaded=$loaded (source=:$PALETTE_VERSION) skipped=${rows.size - loaded} bySource=$skippedSources")
    }

    /** v1.1+: 清空全部内存封面/颜色缓存（清理识别缓存后调用，强制下次重新取色/加载）。
     *  v1.2.0：一并清磁盘封面缓存。 */
    fun clearAll(context: Context) {
        synchronized(this) {
            cache.evictAll()
            misses.clear()
            colorCache.clear()
            colorPending.clear()
        }
        diskCache.clear(context)
    }

    /**
     * Load album art from iTunes Search API. Returns (bitmap, releaseDate) where
     * releaseDate is the ISO-8601 string from the API response ("1987-07-21T07:00:00Z")
     * — note this is the iTunes Store availability date, NOT guaranteed to be the
     * original release date. Used for sort-by-release-order on the artist album list;
     * UI must not label it as the official release date.
     *
     * v9: among name-matching candidates we now prefer albums/compilations over
     * singles so a same-named lead single's cover never overrides the album's.
     * Single is only chosen when it's the sole name match (true fallback).
     */
    private suspend fun loadFromNetwork(context: Context, track: Track, px: Int): Pair<Bitmap?, String>? {
        // v9 需求B: when the user has manually marked this album as "单曲"
        // (override.type == "Single"), skip the album-level iTunes lookup
        // (entity=album keyed on album+artist — which for a single-named album
        // matches the same-named **album** cover and stamps it onto every
        // track) and instead do a per-track lookup keyed on title+artist with
        // entity=song. That's the cover the single actually had on release —
        // the whole reason the user marked it 单曲 in the first place.
        val overrideType = albumTypeOverride(context, track.albumId)
        if (overrideType.equals("Single", ignoreCase = true)) {
            return loadPerTrackCover(context, track, px)
        }
        val candidates = fetchITunesCandidates(track, multiRegion = false)
        if (candidates.isEmpty()) return null
        val best = pickBestCoverCandidate(track.album, candidates)
        val bmp = downloadBitmap(context, best.artUrl, px) ?: return null
        return bmp to best.releaseDate
    }

    /** v9 需求B: read the user's manual album type override ("Album"/"EP"/
     *  "Single" / ""). Returns "" when there's no override or on any IO
     *  failure — callers fall through to the default album-level lookup. */
    private suspend fun albumTypeOverride(context: Context, albumId: Long): String {
        if (albumId <= 0) return ""
        return withContext(Dispatchers.IO) {
            try {
                com.shiyin.music.data.db.AppDatabase.get(context).dao()
                    .albumInfoOverride(albumId)?.type ?: ""
            } catch (_: Exception) { "" }
        }
    }

    /** v9 需求B: per-track cover lookup — iTunes `entity=song` keyed on
     *  track **title + artist**. Used for albums the user marked 单曲, where
     *  the album-level `entity=album` query returns the same-named **album**'s
     *  cover (wrong — the user wanted the single's own cover). Returns the
     *  first result that carries artwork; null if iTunes has nothing or the
     *  request fails. Falls back to album-level lookup if no song result. */
    private suspend fun loadPerTrackCover(context: Context, track: Track, px: Int): Pair<Bitmap?, String>? {
        return withContext(Dispatchers.IO) {
            try {
                val term = java.net.URLEncoder.encode("${track.title} ${track.artist} music", "UTF-8")
                val url = "https://itunes.apple.com/search?term=$term&media=music&limit=5&entity=song"
                val client = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                val body = client.newCall(okhttp3.Request.Builder().url(url).build()).execute().body?.string()
                    ?: return@withContext null
                val results = com.google.gson.JsonParser.parseString(body).asJsonObject
                    .getAsJsonArray("results") ?: return@withContext null
                // Pick the first result that has artwork and matches the track
                // title (loose contains check — iTunes sometimes appends
                // "(Remaster)" or a feature tag). This avoids grabbing a
                // random cover-credit artist's track that merely shares the
                // search term.
                val pick = results.mapNotNull { elem ->
                    val o = elem.asJsonObject
                    val art = o.get("artworkUrl100")?.asString ?: return@mapNotNull null
                    val name = o.get("trackName")?.asString ?: ""
                    CoverSongMatch(art, name, o.get("releaseDate")?.asString ?: "")
                }.firstOrNull { it.trackName.equals(track.title, ignoreCase = true) || it.trackName.contains(track.title, ignoreCase = true) }
                    ?: results.mapNotNull { elem ->
                        val o = elem.asJsonObject
                        o.get("artworkUrl100")?.asString?.let { CoverSongMatch(it, "", "") }
                    }.firstOrNull()
                pick?.let {
                    val bmp = downloadBitmap(context, it.artUrl, px) ?: return@withContext null
                    bmp to it.releaseDate
                }
            } catch (_: Exception) { null }
        }
    }

    /** Inline holder for a parsed song-entity iTunes result. */
    private data class CoverSongMatch(
        val artUrl: String,
        val trackName: String,
        val releaseDate: String,
    )


    /** A single iTunes Search album result exposed to the cover-picker UI. */
    data class Candidate(
        val artUrl: String,
        val albumName: String,
        val collectionType: String,  // "Album" / "Single" / "EP" / "Compilation" / ""
        val releaseDate: String,
    )

    /** v9: fetch up to 12 iTunes album candidates for [track]. Deduplicated by
     *  artUrl so the picker doesn't show the same cover twice. Public so the
     *  更换专辑封面 dialog can render a candidate grid + let the user pick one.
     *  Bug3 fix: also pulls song-entity results so same-name Single covers
     *  appear alongside albums in the picker (iTunes' `entity=album` filter
     *  otherwise drops them). */
    suspend fun loadCandidates(track: Track, offset: Int = 0, limit: Int = 8): List<Candidate> {
        return fetchITunesCandidates(track, limit = limit, includeSongs = true, offset = offset, multiRegion = true)
            .distinctBy { it.artUrl }
    }

    /** Shared iTunes query — returns parsed candidates. name-matching + type
     *  filtering happen in the callers (pickBestCoverCandidate / UI). [limit]
     *  defaults to 5 for the auto-match path (fast) but is raised for the
     *  candidate picker so the user has more than one or two to choose from.
     *
     *  Bug3 fix: iTunes Search's `entity=album` filter SILENTLY DROPS
     *  collectionType=Single results — but a same-named lead single's artwork
     *  is exactly what the user might want to pick from when an album cover
     *  can't be found (or when the artist really did reissue the album under a
     *  different ID). We query `entity=album` for the auto-match path (fast,
     *  correct), and the candidate picker additionally fetches `entity=song`
     *  results so the picker grid surfaces same-name single covers too. The
     *  auto path still uses pickBestCoverCandidate's Album/Compilation first
     *  rule. */
    private suspend fun fetchITunesCandidates(
        track: Track,
        limit: Int = 5,
        includeSongs: Boolean = false,
        offset: Int = 0,
        multiRegion: Boolean = false,
    ): List<Candidate> {
        if (track.album == com.shiyin.music.data.NO_ALBUM || track.albumId <= 0) return emptyList()
        return withContext(Dispatchers.IO) {
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            // v1.2.1: 多区域搜索。iTunes 默认 US 区,日/中文资源要用英语译名才搜不到;
            // 带 country 参数搜原产地区域商店(日语歌搜 JP、中文搜 CN/TW),用原始名即可命中。
            // multiRegion=false(auto 匹配,要快):只搜最佳猜测单区域;true(picker,要多候选):循环所有区域。
            // v1.3.3b review#B8: auto 的 CJK 分支原先固定 JP——纯汉字(无假名)的中文歌手
            // 也被路由 JP 区,中文专辑在 JP 商店常搜不到(自动封面命中率下降)。改按语言
            // 分派:含假名=日语→JP;仅汉字=中文→CN(候选次区),首命中即停的机制不变。
            val hasCjk = { s: String -> com.shiyin.music.data.normalize.CharUtil.isCjk(s) }
            val hasKana = { s: String -> s.any { it.code in 0x3040..0x30FF } }
            val cjkText = hasCjk(track.album) || hasCjk(track.artist) || hasCjk(track.title)
            val countries = when {
                !multiRegion && cjkText && (hasKana(track.album) || hasKana(track.artist) || hasKana(track.title)) -> listOf("JP")
                !multiRegion && cjkText -> listOf("CN", "JP")
                !multiRegion -> listOf("US")
                cjkText -> listOf("JP", "CN", "TW", "HK", "US")
                else -> listOf("US", "GB")
            }
            val out = ArrayList<Candidate>()
            val seenArt = HashSet<String>()
            val queries = mutableListOf("album").apply { if (includeSongs) add("song") }
            for (entity in queries) {
                // v1.2.1: 主 term 用"专辑名+歌手";若搜不到,回退"歌名+歌手"(专辑名错误/泛化时救回)。
                val terms = ArrayList<String>(2).apply {
                    add("${track.album} ${track.artist}")
                    add("${track.title} ${track.artist}")
                }
                val foundAny = intArrayOf(0)
                for (term0 in terms) {
                    if (foundAny[0] > 0) break  // 主 term 命中就不再回退
                    for (country in countries) {
                        // v1.2.1: auto(multiRegion=false)首个命中区域即停(快);picker(multiRegion=true)
                        // 不 break——查所有区域商店,聚合各国封面变体给用户挑(原 break 让 picker 只显一区)。
                        if (!multiRegion && foundAny[0] > 0) break
                        try {
                            val term = java.net.URLEncoder.encode(term0, "UTF-8")
                            val url = "https://itunes.apple.com/search?term=$term&media=music&limit=$limit&entity=$entity&offset=$offset&country=$country"
                            val req = okhttp3.Request.Builder().url(url).build()
                            val body = client.newCall(req).execute().body?.string() ?: continue
                            val json = com.google.gson.JsonParser.parseString(body).asJsonObject
                            val results = json.getAsJsonArray("results") ?: continue
                            var added = 0
                            for (elem in results) {
                                val o = elem.asJsonObject
                                val artUrl = o.get("artworkUrl100")?.asString ?: continue
                                if (!seenArt.add(artUrl)) continue  // 跨区域去重
                                val collectionType = if (entity == "song") "Single" else (o.get("collectionType")?.asString ?: "")
                                val name = o.get("collectionName")?.asString ?: o.get("trackName")?.asString ?: ""
                                out += Candidate(
                                    artUrl = artUrl.replace("100x100bb", "600x600bb").replace("100x100", "600x600"),
                                    albumName = name,
                                    collectionType = collectionType,
                                    releaseDate = o.get("releaseDate")?.asString ?: "",
                                )
                                added++
                            }
                            foundAny[0] += added
                            android.util.Log.d("AIM", "coverCand entity=$entity country=$country term=${term0.take(30)} → +$added")
                        } catch (_: Exception) { /* skip this country */ }
                    }
                }
            }
            out
        }
    }

    /** Pick the best cover candidate among those whose name matches [album]:
     *  prefer Album/Compilation ("real albums"), fall back to Single only when
     *  no album/compilation matches by name. If nothing matches by name, fall
     *  back to the first candidate rather than nothing (preserves old behavior
     *  for obscure releases). */
    private fun pickBestCoverCandidate(album: String, candidates: List<Candidate>): Candidate {
        val nameMatches = candidates.filter {
            it.albumName.equals(album, ignoreCase = true) || it.albumName.contains(album, ignoreCase = true)
        }
        val pool = nameMatches.ifEmpty { candidates }
        // Real albums first, then compilations, then anything else (singles last).
        val realAlbum = pool.firstOrNull { it.collectionType == "Album" || it.collectionType == "Compilation" }
        return realAlbum ?: pool.firstOrNull() ?: candidates.first()
    }

    /** v1.2.0 阶段二：iTunes 下载封面字节的磁盘缓存。冷启动不再每次重下网络。
     *  键 = artUrl 的稳定 hash（不受 px 变化影响，存的是高清 hq 字节）；
     *  无 albumId↔artUrl 映射——invalidateAlbum 后旧 artUrl 文件成孤儿，
     *  由大小上限（~50MB）+ LRU 淘汰，不阻塞取色/内存/颜色缓存失效逻辑。 */
    private val diskCache by lazy { CoverDiskCache() }

    /** v1.2.0 阶段二修正：取色 extract 限 4 并发，避免冷启动大量封面同时 decode+取色
     *  占满 64 个 IO 线程导致某首歌取色排队数十秒（歌词本背景灰色迟迟不变色）。 */
    private val extractDispatcher = Dispatchers.IO.limitedParallelism(4)

    /** Download [artUrl] at ~[px] (iTunes' 100x100 is upscaled by string-replacing
     *  the size token). Returns null on any network/decode failure.
     *  v1.2.0：先查磁盘缓存命中→直接解码；未命中联网下→落盘→解码。 */
    private suspend fun downloadBitmap(context: Context, artUrl: String, px: Int): Bitmap? {
        return withContext(Dispatchers.IO) {
            try {
                val hqUrl = artUrl.replace("100x100bb", "${px}x${px}bb")
                    .replace("100x100", "${px}x${px}")
                // 1) 磁盘命中：读字节解码。key 含 bucket(px)，不同分辨率分开存——
                //    大图(1024)永远读 1024 高清，小图(160)单独存，不互相污染分辨率。
                diskCache.read(context, artUrl, bucket(px))?.let { bytes ->
                    decodeScaledBytes(bytes, px)?.also {
                        android.util.Log.d("ArtCache", "disk hit artUrl=${artUrl.takeLast(40)} px=$px bucket=${bucket(px)}")
                    } ?: run {
                        // v1.3.10: 盘上是解不开的字节(旧版本把 403/404 错误页也落了盘)→
                        // 删掉并当作未命中继续重新下载,坏缓存不再永久占住这一格。
                        android.util.Log.d("AIM", "disk bytes decode FAIL → drop ${artUrl.take(50)}")
                        diskCache.delete(context, artUrl, bucket(px))
                        null
                    }
                } ?: run {
                    // 2) 未命中：联网下载，落盘，解码
                    val req = okhttp3.Request.Builder().url(hqUrl)
                        .header("User-Agent", "ShiyinMusic/2.0 (music-player; android)")
                        // Discogs CDN 可能检查 Referer，加匹配 discogs.com 的 Referer 绕过
                        .also { if (artUrl.contains("discogs.com")) it.header("Referer", "https://www.discogs.com/") }
                        .build()
                    val client = okhttp3.OkHttpClient.Builder()
                        .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                        .build()
                    val resp = client.newCall(req).execute()
                    val bytes = resp.body?.bytes()
                    // v1.3.10: 非 2xx 不落盘也不解码。Discogs 的图片 URL 带签名会过期,
                    // 过期后返回 403 错误页;旧实现不校验状态码,把错误页当图片字节写进
                    // 磁盘缓存 → 这一格此后永远解不开(用户反馈"部分歌手头像显示不出来"
                    // 且"改几次都没修好"的直接原因:坏字节被永久缓存)。
                    if (bytes == null || !resp.isSuccessful) {
                        android.util.Log.d("AIM", "img download fail code=${resp.code} ${artUrl.take(50)}")
                        return@run null
                    }
                    val bmp = decodeScaledBytes(bytes, px)
                    if (bmp == null) {
                        android.util.Log.d("AIM", "img decode FAIL bytes=${bytes.size} code=${resp.code} ${artUrl.take(50)}")
                        return@run null
                    }
                    // 只对歌手写真(discogs)记成功日志，专辑封面不打扰
                    if (artUrl.contains("discogs")) android.util.Log.d("AIM", "img ok bytes=${bytes.size} ${artUrl.take(50)}")
                    diskCache.write(context, artUrl, bucket(px), bytes) // 落盘供下次冷启动命中
                    bmp
                }
            } catch (e: Exception) {
                android.util.Log.d("AIM", "img download FAIL ${artUrl.take(50)}: ${e.javaClass.simpleName} ${e.message?.take(80)}")
                null
            }
        }
    }

    /**
     * v1.3.15: 按目标显示尺寸**降采样**解码字节,永不交出原尺寸位图。
     *
     * 真机闪退(2026-10-01):某歌手的候选写真是一张 8192×6144 的扫描件,
     * 原尺寸解码 = 192MB,Compose 一画就抛
     * `Canvas: trying to draw too large(201326592bytes) bitmap` —— 搜写真必崩。
     * 网图与磁盘缓存回读都走这里;采样系数由 [bitmapSampleSize] 定(长边封顶 + 目标 2 倍)。
     * 读不出宽高(非图片字节/坏缓存)返回 null,交由调用方按"解码失败"处理。
     */
    private fun decodeScaledBytes(bytes: ByteArray, targetPx: Int): Bitmap? {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = android.graphics.BitmapFactory.Options().apply {
            inSampleSize = bitmapSampleSize(bounds.outWidth, bounds.outHeight, targetPx)
        }
        return runCatching { android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) }.getOrNull()
    }

    /** [decodeScaledBytes] 的流版本:本地文件/相册图片用。[open] 会被调用两次(先量尺寸再解)。 */
    private fun decodeScaledStream(open: () -> java.io.InputStream?, targetPx: Int): Bitmap? = runCatching {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open()?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) } ?: return@runCatching null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        val opts = android.graphics.BitmapFactory.Options().apply {
            inSampleSize = bitmapSampleSize(bounds.outWidth, bounds.outHeight, targetPx)
        }
        open()?.use { android.graphics.BitmapFactory.decodeStream(it, null, opts) }
    }.getOrNull()

    /** [decodeScaledStream] 的本地文件版本(用户手选的本地图片)。 */
    private fun decodeScaledFile(path: String, targetPx: Int): Bitmap? =
        decodeScaledStream({ java.io.FileInputStream(path) }, targetPx)
}

@Composable
fun rememberAlbumArt(track: Track?, sizeDp: Dp, coverScope: ArtCache.CoverScope = ArtCache.CoverScope.AUTO): ImageBitmap? =
    rememberAlbumArtState(track, sizeDp, coverScope).art

/** 封面 + 「是否已解析完成」。null 且 resolved=true 才代表"这首确实没有封面"。 */
internal data class AlbumArtState(val art: ImageBitmap?, val resolved: Boolean)

/**
 * v23: [rememberAlbumArt] 的带状态版本。
 *
 * 为什么要多一个 resolved:封面为 null 有两种截然不同的含义——「还在异步加载」
 * 与「这首根本没有封面」。旧 API 二者不可分,于是 CoverArt 的"加载间隙沿用上一张"
 * 兜底会在**无封面曲目**上永久显示别的歌的封面(列表滚动/兜底源全失败时尤其明显)。
 */
@Composable
internal fun rememberAlbumArtState(
    track: Track?,
    sizeDp: Dp,
    coverScope: ArtCache.CoverScope = ArtCache.CoverScope.AUTO,
): AlbumArtState {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { sizeDp.roundToPx() }.coerceAtLeast(32)
    // v1.2.0：初始值同步查内存缓存命中即显示，避免列表滚动 item 回收重组时
    // null→bitmap 一帧闪烁 + 反复加载。未命中 produceState 异步 load 补。
    val initial = track?.let { ArtCache.getCached(it, px)?.asImageBitmap() }
    // v1.3.x 第10项:produceState 的键用**专辑维度**的 artId，不是 track.id。
    // 封面缓存本来就按 albumId 归档(getCached/load 都是 artId)，用 track.id 做键会
    // 让"同一张专辑内切歌"也重启 producer → value 回到初值那一瞬就是用户看到的
    // "同一专辑也闪一下"。改成 artId 后，同专辑切歌完全不重启、位图原样复用。
    // (albumId<=0 的孤立单曲仍按 track.id 区分，行为不变。)
    val artId = track?.let { if (it.albumId > 0) it.albumId else -it.id }
    val state = produceState<AlbumArtState>(
        AlbumArtState(initial, initial != null),
        artId,
        coverScope,
    ) {
        value = AlbumArtState(
            track?.let { ArtCache.load(context, it, px, coverScope)?.asImageBitmap() },
            resolved = true,
        )
    }
    return state.value
}

/** v9: load an iTunes candidate thumbnail by its remote artUrl for the cover
 *  picker grid. v1.2.0 起磁盘缓存，翻过的候选下次秒出；throws nothing —
 *  null until the fetch resolves.
 *
 *  v1.3.10: 加 [onRemoteLoadFailed]——远端 URL 加载失败(典型:Discogs 签名过期 403、
 *  源站删图)时通知调用方清缓存重新解析。此前失败后缓存行仍在、URL 仍指向死链,
 *  头像永久空白(用户反馈"部分歌手头像显示不出来,改几次都没修好")。
 *  同一 URL 只上报一次,重组/重试不会打成风暴;本地 file://、content:// 不上报
 *  (多为文件缺失,重解析无意义)。 */
@Composable
fun rememberCandidateArt(artUrl: String?, sizeDp: Dp, onRemoteLoadFailed: (() -> Unit)? = null): ImageBitmap? {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { sizeDp.roundToPx() }.coerceAtLeast(32)
    val reported = remember(artUrl) { java.util.concurrent.atomic.AtomicBoolean(false) }
    val state = produceState<ImageBitmap?>(null, artUrl) {
        value = artUrl?.let {
            val bmp = ArtCache.loadCandidateBitmap(context, it, px)
            android.util.Log.d("AIM", "rememberCandidateArt url=${it.take(55)} px=$px bmp=${bmp != null}")
            if (bmp == null && it.startsWith("http") && reported.compareAndSet(false, true)) {
                onRemoteLoadFailed?.invoke()
            }
            bmp?.asImageBitmap()
        }
    }
    return state.value
}

/**
 * v3.0: Real cover-derived tint for the player/lyrics pages.
 *
 * Returns the dominant (bg, fg) pair extracted from the track's album art:
 *  - When [track] has a real cover and the colors have been extracted, returns
 *    the album's actual mood (Spotify-style dynamic tint).
 *  - Until then (first-load), returns the fixed [coverPalette] fallback so the
 *    page renders instantly with a sensible color and cross-fades to the real
 *    tint once the bitmap is ready.
 *  - When [track] has no cover at all, stays on the fallback so blank-cover
 *    tracks keep their generative block look.
 *
 * The caller observes [state] (a StateFlow-like snapshot) so a late extraction
 * re-arms this composable and repaints.
 */
@Composable
fun rememberCoverPalette(track: Track?): Pair<Color, Color> {
    val c = LocalOrganic.current
    val fallback = coverPalette(track?.paletteIndex ?: 0)
    if (track == null) return fallback
    // v1.3.x 闪白修复(歌词本打开时黑字歌词先白后黑):原 initial=fallback——
    // rememberCoverPalette 的同步路径不查内存色缓存,首帧总是 generative 兜底,
    // 取色异步落定后才翻转真实色。浅色封面(黑字)的歌:首帧白字→翻转黑字=
    // "变白闪一下再变回黑色";深色封面(白字)兜底同色无感。歌词本内容层
    // 淡入窗 [0.40,0.65] 正好罩住翻转时刻,闪到可见。
    // 修:initial 同步读 ArtCache.colorFor(播放页显示封面时即写入的内存 Map,
    // 主线程同步读零成本)——首帧即真实色,未解析窗口直接消失。缓存空
    // (冷启动极端时序)才由 produceState 轮询兜底,行为不变。
    val cachedPair = ArtCache.colorFor(track.albumId)
    val initial = if (cachedPair != null) {
        Color(cachedPair.first) to Color(cachedPair.second)
    } else fallback
    // Snapshot the in-memory color cache. ArtCache.colorFor updates when a
    // cover finishes loading, but reading here only re-composes when `track`
    // changes — so we also subscribe to the art load via an effect that polls.
    val context = LocalContext.current
    val state = produceState<Pair<Color, Color>>(initial, track.id) {
        // Prime: load art at a small size to trigger extraction if needed.
        ArtCache.load(context, track, 384)
        // Poll the color cache briefly, since extraction lands asynchronously
        // after the bitmap decode. Settle within ~1.5s.
        var tries = 0
        while (tries < 30) {
            val pair = ArtCache.colorFor(track.albumId)
            if (pair != null) {
                value = Color(pair.first) to Color(pair.second)
                android.util.Log.d("PaletteTrace", "rememberCoverPalette track=${track.id} albumId=${track.albumId} RESOLVED bg=${Integer.toHexString(pair.first)} fg=${Integer.toHexString(pair.second)}")
                return@produceState
            }
            kotlinx.coroutines.delay(50)
            tries++
        }
        android.util.Log.d("PaletteTrace", "rememberCoverPalette track=${track.id} albumId=${track.albumId} UNRESOLVED after 30 tries → fallback")
    }
    return state.value
}

/**
 * Album cover: embedded art when present, otherwise the handoff's generative
 * block — palette color, big initial, decorative circle.
 *
 * v3.0: when real art is present the cover fills the box and we do NOT paint
 * a colored background behind it (the previous fixed palette tint bled through
 * the Fit margins as the "green/orange default background"). The background
 * only shows for cover-free tracks, using the generative palette.
 */
/**
 * v1.3.10 需求 A17:封面"换了一张"时的过渡方式。
 *
 * - [CROSS_FADE] 旧封面垫底淡出、新封面淡入(默认;播放页/列表等处)。
 * - [FLIP] **翻面**:沿 Y 轴 0°→180°,过 90°(侧面不可见)时换画,后半程补 `scaleX=-1`
 *   抵消镜像。用于**封面位置固定**的场景——迷你条的设计是"封面不跟拖动走,只有歌名/歌手滑",
 *   所以换封面必须靠它自己的动效交代,否则就是硬切/闪一下。
 * - [NONE] 直接换,不做过渡。
 */
enum class CoverTransition { CROSS_FADE, FLIP, NONE }

@Composable
fun CoverArt(
    track: Track?,
    size: Dp,
    shape: Shape = RoundedCornerShape(8.dp),
    fontSize: Int = (size.value * 0.36f).toInt(),
    showDeco: Boolean = false,
    fillToParent: Boolean = false,
    modifier: Modifier = Modifier,
    coverScope: ArtCache.CoverScope = ArtCache.CoverScope.AUTO,
    /** 专辑切换时的过渡方式,见 [CoverTransition]。 */
    transition: CoverTransition = CoverTransition.CROSS_FADE,
) {
    val artState = rememberAlbumArtState(track, size, coverScope)
    val art = artState.art
    val (bg, fg) = coverPalette(track?.paletteIndex ?: 0)
    val sizeMod = if (fillToParent) Modifier.fillMaxSize() else Modifier.size(size)

    // v1.3.x 修复(用户反馈⑮:切歌时封面"正确→首字→正确"闪一下):
    // rememberAlbumArt 按 track.id 重启 produceState,新曲封面未就绪期间返回 null
    // → 旧实现立刻落到"首字占位",封面到达再切回,闪一帧。
    // 修复:①加载间隙保持上一张已就绪封面(不闪首字);
    //      ②封面**真的换了**(跨专辑;同专辑复用同一张不做)才做 240ms 交叉淡化
    //        ——旧封面垫底淡出、新封面淡入,两层同时在场,无中间空帧。
    var lastArt by remember { mutableStateOf<ImageBitmap?>(null) }
    var prevArt by remember { mutableStateOf<ImageBitmap?>(null) }
    /** 翻面的"背面"内容(换掉的那张旧封面);非空 = 正在翻。 */
    var flipFrom by remember { mutableStateOf<ImageBitmap?>(null) }
    var lastAlbumId by remember { mutableLongStateOf(Long.MIN_VALUE) }
    val fadeAlpha = remember { androidx.compose.animation.core.Animatable(1f) }
    val flipRot = remember { androidx.compose.animation.core.Animatable(0f) }
    // 只在跨过 90°(换画的那一瞬)通知重组;旋转角本身走 graphicsLayer 延迟读,逐帧不重组
    val flipSecondHalf by remember { derivedStateOf { flipRot.value > 90f } }
    LaunchedEffect(art) {
        val target = art ?: run {
            // art 变 null(加载中 / track 置空):必须清掉过渡中间态。
            // 否则在上一轮过渡未跑完时再切歌,残留的 prevArt/翻面态会让下一次绘制变成
            // "半透明封面压在上一张上"或"翻到一半停住"的重影。
            prevArt = null
            flipFrom = null
            flipRot.snapTo(0f)
            fadeAlpha.snapTo(1f)
            return@LaunchedEffect
        }
        val albumChanged = track?.albumId != lastAlbumId
        val swapped = lastArt != null && albumChanged && target !== lastArt
        when {
            // v1.3.10 需求 A17:翻面——迷你条用。封面位置固定时,靠它交代"换了一张"。
            swapped && transition == CoverTransition.FLIP -> {
                flipFrom = lastArt
                lastArt = target
                lastAlbumId = track?.albumId ?: Long.MIN_VALUE
                flipRot.snapTo(0f)
                flipRot.animateTo(180f, tween(360, easing = androidx.compose.animation.core.FastOutSlowInEasing))
                // 收尾:此时朝观众的是 lastArt(新封面),180° 与 0° 视觉等价 → 归零不跳变
                flipFrom = null
                flipRot.snapTo(0f)
            }
            swapped && transition == CoverTransition.CROSS_FADE -> {
                prevArt = lastArt
                lastArt = target
                lastAlbumId = track?.albumId ?: Long.MIN_VALUE
                fadeAlpha.snapTo(0f)
                fadeAlpha.animateTo(1f, tween(240, easing = androidx.compose.animation.core.FastOutSlowInEasing))
                prevArt = null
            }
            else -> {
                lastArt = target
                lastAlbumId = track?.albumId ?: Long.MIN_VALUE
                flipRot.snapTo(0f)
                fadeAlpha.snapTo(1f)
            }
        }
    }
    // 显示目标:同一首**还在加载** → 沿用上一张(消掉 null→首字的闪烁);
    // 已解析完成仍为 null(这首确实没有封面)→ 落到首字占位。
    // 旧实现是 `art ?: lastArt`,把"没有封面"也当成"加载中",于是无封面的曲目会
    // 永久显示上一首的封面(列表滚动复用 slot 时更明显)。
    val display = art ?: lastArt?.takeIf { !artState.resolved }
    // v1.3.10 需求 A17 细节修正(用户:"A → B 时先闪 0.1s 的 B 封面,然后才翻面"):
    // 换歌那一帧 art 往往**已经就绪**(封面早在拖动时就缓存好了),于是会先画出 B,
    // 而翻面状态是在 LaunchedEffect 里下一帧才建立的 → 中间露一帧 B。
    // 修法:翻面模式下,只要"专辑已变但翻面尚未开始",这一帧继续画**旧封面**,
    // 等翻面状态到位再翻过去 → 视觉上从 A 直接翻到 B,没有中间帧。
    val pendingFlip = transition == CoverTransition.FLIP && flipFrom == null &&
        lastArt != null && lastAlbumId != (track?.albumId ?: Long.MIN_VALUE)
    val shown = if (pendingFlip) lastArt else display
    Box(
        modifier = modifier
            .then(sizeMod)
            .clip(shape)
            .background(if (shown != null) Color.Transparent else bg),
        contentAlignment = Alignment.Center,
    ) {
        if (shown != null) {
            if (flipFrom != null) {
                // 翻面中:单层旋转。过 90° 换成新封面并补 scaleX=-1 抵消镜像。
                // 后半程取新封面;若此刻新封面还没就绪(极冷场景)就继续用旧的那张,
                // 不要留空帧。
                val img = if (flipSecondHalf) (display ?: flipFrom) else flipFrom
                img?.let {
                    Image(
                        it, contentDescription = null,
                        modifier = Modifier.fillMaxSize().graphicsLayer {
                            rotationY = flipRot.value
                            cameraDistance = 12f * density
                            scaleX = if (flipRot.value > 90f) -1f else 1f
                        },
                        contentScale = ContentScale.Crop,
                    )
                }
            } else {
                // 旧封面垫底(淡出层):交叉淡化期间两层同时在场。
                prevArt?.let { p ->
                    Image(p, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
                Image(
                    shown, contentDescription = null,
                    modifier = Modifier.fillMaxSize().graphicsLayer { alpha = fadeAlpha.value },
                    contentScale = ContentScale.Crop,
                )
            }
        } else {
            if (showDeco) {
                Box(
                    Modifier
                        .size(size * 1.22f)
                        .offset(x = size * 0.34f, y = -size * 0.42f)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.18f))
                )
            }
            Text(
                track?.initial ?: "♪",
                fontFamily = Caprasimo,
                fontSize = fontSize.sp,
                color = fg,
            )
        }
    }
}

/**
 * v3.0: A 2x2 mosaic assembled from up to four distinct album covers, used as
 * the default playlist cover when the user hasn't picked one. Employs linear
 * stacks of [CoverArt] thumbnails so it reuses the embedded-art cache (no
 * extra downloads). Fewer than 4 distinct covers center a smaller tile.
 */
@Composable
fun CoverArtMosaic(
    tracks: List<Track>,
    size: Dp,
    shape: Shape = RoundedCornerShape(10.dp),
    fillToParent: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val c = LocalOrganic.current
    // Pick up to 4 distinct covers by albumId.
    val distinct = remember(tracks) {
        val seen = HashSet<Long>()
        val out = ArrayList<Track>()
        for (t in tracks) {
            val key = if (t.albumId > 0) t.albumId else -t.id
            if (seen.add(key)) out.add(t)
            if (out.size >= 4) break
        }
        out
    }
    val szMod = if (fillToParent) Modifier.fillMaxSize() else Modifier.size(size)
    Box(
        modifier = modifier.then(szMod).clip(shape).background(c.n200),
    ) {
        when (distinct.size) {
            0 -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    OIcon(Lucide.ListMusic, (if (fillToParent) 42.dp else size * 0.42f), c.n500)
                }
            }
            1, 2 -> {
                Column(Modifier.fillMaxSize()) {
                    distinct.forEach { t ->
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            CoverArt(t, if (fillToParent) 1.dp else size, fillToParent = fillToParent, shape = RoundedCornerShape(0.dp))
                        }
                    }
                }
            }
            else -> {
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        Box(Modifier.weight(1f).fillMaxHeight()) { CoverArt(distinct[0], 1.dp, fillToParent = true, shape = RoundedCornerShape(0.dp)) }
                        Box(Modifier.weight(1f).fillMaxHeight()) { CoverArt(distinct[1], 1.dp, fillToParent = true, shape = RoundedCornerShape(0.dp)) }
                    }
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        Box(Modifier.weight(1f).fillMaxHeight()) { CoverArt(distinct[2], 1.dp, fillToParent = true, shape = RoundedCornerShape(0.dp)) }
                        if (distinct.size >= 4) {
                            Box(Modifier.weight(1f).fillMaxHeight()) { CoverArt(distinct[3], 1.dp, fillToParent = true, shape = RoundedCornerShape(0.dp)) }
                        }
                    }
                }
            }
        }
    }
}

// ── shadows (organic sm/md/lg approximation) ───────────────────────────────
fun Modifier.shadowSm(shape: Shape) = this.shadow(2.dp, shape, clip = false)
fun Modifier.shadowMd(shape: Shape) = this.shadow(6.dp, shape, clip = false)

/**
 * v1.3.10 需求 A20:点击**无涟漪**。
 *
 * 用户明确不喜欢默认的灰色涟漪——"点一下灰块占满整个按钮的范围,真的好丑"。
 * 这里只去掉**视觉反馈**:命中范围、enabled、onClick 时机与 [clickable] 完全一致。
 *
 * 需要"看得见的按压反馈"时别用它,而是像设置页三段控件 / clarify 选项行那样,
 * 自己拿 interactionSource + `indication = null` 自绘按下态(颜色/描边)。
 */
@Composable
fun Modifier.clickableNoRipple(
    enabled: Boolean = true,
    onClickLabel: String? = null,
    onClick: () -> Unit,
): Modifier = clickable(
    enabled = enabled,
    onClickLabel = onClickLabel,
    interactionSource = remember { MutableInteractionSource() },
    indication = null,
    onClick = onClick,
)
fun Modifier.shadowLg(shape: Shape) = this.shadow(14.dp, shape, clip = false)

// ── text helpers ───────────────────────────────────────────────────────────
@Composable
fun heading(size: Int, color: Color = LocalOrganic.current.text) = TextStyle(
    fontFamily = Caprasimo,
    fontSize = size.sp,
    color = color,
    lineHeight = (size * 1.12f).sp,
)

/**
 * v1.3.x 第1项：主页面顶栏与侧边栏品牌区**共用**的顶部基准。
 *
 * 此前两边各写各的（侧边栏 `padding(20.dp)` 且行高由文字撑；主页面 `padding(top=22.dp)`
 * 且行高由 26sp 问候语撑），字号又不同，中心线自然对不上。改成同一偏移 + 同一行高 +
 * 同一居中方式后，两侧的垂直中心线在任何屏宽与字号下都一致——不是靠负 margin 硬凑。
 */
val TopBarTop = 22.dp
val TopBarHeight = 40.dp

fun body(size: Float, weight: FontWeight = FontWeight.Normal, color: Color) = TextStyle(
    fontFamily = Figtree,
    fontSize = size.sp,
    fontWeight = weight,
    color = color,
)

/** Icon with explicit size + tint (Lucide vectors are built white, tinted here). */
@Composable
fun OIcon(icon: ImageVector, size: Dp, tint: Color, modifier: Modifier = Modifier) {
    Icon(icon, contentDescription = null, modifier = modifier.size(size), tint = tint)
}

/**
 * 同上，但接 [Painter] —— 供**矢量 drawable**（`painterResource(R.drawable.xxx)`）使用。
 *
 * 为什么不用 `vectorResource` 直接变成 ImageVector：这个 Compose 版本上
 * `androidx.compose.ui.res.vectorResource` 解析不到（编译期 Unresolved reference），
 * 而 `painterResource` 可用。图标仍由 [Icon] 按 tint 上色，行为与 ImageVector 版一致。
 */
@Composable
fun OIcon(painter: Painter, size: Dp, tint: Color, modifier: Modifier = Modifier) {
    Icon(painter, contentDescription = null, modifier = modifier.size(size), tint = tint)
}

// ── buttons ────────────────────────────────────────────────────────────────
@Composable
fun CircleButton(
    size: Dp,
    bg: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(bg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** Pill button. bg=null + border!=null gives the "btn-secondary" outline look. */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    bg: Color? = LocalOrganic.current.accent,
    textColor: Color = LocalOrganic.current.bg,
    fontSize: Float = 14f,
    padH: Dp = 18.dp,
    padV: Dp = 10.dp,
    borderColor: Color? = null,
    icon: ImageVector? = null,
    iconSize: Dp = 14.dp,
    contentArrangement: Arrangement.Horizontal = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
) {
    val pill = RoundedCornerShape(999.dp)
    var m = modifier.clip(pill)
    if (bg != null) m = m.background(bg)
    if (borderColor != null) m = m.border(1.dp, borderColor, pill)
    Row(
        modifier = m
            .clickable(onClick = onClick)
            .padding(horizontal = padH, vertical = padV),
        horizontalArrangement = contentArrangement,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) OIcon(icon, iconSize, textColor)
        Text(
            text,
            style = body(fontSize, FontWeight.Bold, textColor),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ── the design's 52x30 pill switch ─────────────────────────────────────────
@Composable
fun OrganicSwitch(checked: Boolean, onToggle: () -> Unit) {
    val c = LocalOrganic.current
    val knobX by animateDpAsState(if (checked) 22.dp else 0.dp, tween(200), label = "knob")
    Box(
        Modifier
            .width(52.dp)
            .height(30.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(if (checked) c.accent else c.n400)
            .clickable(onClick = onToggle)
            .padding(3.dp)
    ) {
        Box(
            Modifier
                .offset(x = knobX)
                .size(24.dp)
                .shadowSm(CircleShape)
                .clip(CircleShape)
                .background(Color.White)
        )
    }
}

// ── the 3-bar playing EQ indicator ─────────────────────────────────────────
@Composable
fun EqBars(playing: Boolean, modifier: Modifier = Modifier, barMaxHeight: Dp = 16.dp) {
    val c = LocalOrganic.current
    // v1.3.2: 可缩放(barMaxHeight),Agent 步骤面板复用作"进行中"音浪指示
    val s = barMaxHeight.value / 16f
    val t = rememberInfiniteTransition(label = "eq")
    val h1 by t.animateFloat(5f, 14f, infiniteRepeatable(tween(450, easing = LinearEasing), RepeatMode.Reverse), label = "b1")
    val h2 by t.animateFloat(13f, 4f, infiniteRepeatable(tween(400, easing = LinearEasing), RepeatMode.Reverse), label = "b2")
    val h3 by t.animateFloat(8f, 15f, infiniteRepeatable(tween(500, easing = LinearEasing), RepeatMode.Reverse), label = "b3")
    Row(
        modifier = modifier.height(barMaxHeight),
        horizontalArrangement = Arrangement.spacedBy(2.5.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        for (h in listOf(if (playing) h1 else 8f, if (playing) h2 else 12f, if (playing) h3 else 6f)) {
            Box(
                Modifier
                    .width(3.5.dp)
                    .height((h * s).dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(c.accent)
            )
        }
    }
}

// ── shared track row ───────────────────────────────────────────────────────
@Composable
fun TrackRow(
    track: Track,
    isCurrent: Boolean,
    isPlaying: Boolean,
    subtitle: String,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    coverSize: Dp = 46.dp,
    coverRadius: Dp = 14.dp,
    showDivider: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
    titleHighlights: List<IntRange> = emptyList(),
    subtitleHighlights: List<IntRange> = emptyList(),
    // v5.2 隐藏曲:整库 songs 列表+歌单里也要让人看出这是被跳过的曲。
    // 调用方传 isHiddenTrack=true 时行变灰,标题压暗。
    isHiddenTrack: Boolean = false,
) {
    val c = LocalOrganic.current
    // v4.3: search-result highlighting — matched spans tinted with the theme
    // accent so highlights never clash with the organic palette.
    val titleAnnotated = remember(track.title, titleHighlights, c) {
        buildAnnotatedString {
            append(track.title)
            for (r in titleHighlights) {
                if (r.first >= 0 && r.last < track.title.length) {
                    addStyle(SpanStyle(color = c.a600, fontWeight = FontWeight.ExtraBold), r.first, r.last + 1)
                }
            }
        }
    }
    val subtitleAnnotated = remember(subtitle, subtitleHighlights, c) {
        buildAnnotatedString {
            append(subtitle)
            for (r in subtitleHighlights) {
                if (r.first >= 0 && r.last < subtitle.length) {
                    addStyle(SpanStyle(color = c.a600, fontWeight = FontWeight.Bold), r.first, r.last + 1)
                }
            }
        }
    }
    val rowAlpha = if (isHiddenTrack && !isCurrent) 0.45f else 1f
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { alpha = rowAlpha }
                .clip(RoundedCornerShape(12.dp))
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(horizontal = 4.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CoverArt(track, coverSize, RoundedCornerShape(coverRadius), fontSize = (coverSize.value * 0.41f).toInt())
            Column(Modifier.weight(1f)) {
                val titleColor = when {
                    isCurrent -> c.a700
                    isHiddenTrack -> c.n500
                    else -> c.text
                }
                Text(
                    titleAnnotated,
                    style = body(15f, FontWeight.SemiBold, titleColor),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    subtitleAnnotated,
                    style = body(12.5f, FontWeight.Normal, c.n600),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (isCurrent) EqBars(isPlaying)
            trailing?.invoke()
        }
        if (showDivider) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(c.divider)
            )
        }
    }
}

fun trackSubtitle(track: Track): String = track.artist

/**
 * v1.3.2: 密文输入框(API Key 等敏感值)——默认 •••• 遮蔽,尾部眼睛按钮切换明/密文。
 * Agent 设置与写真源设置共用。
 */
@Composable
fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String = "",
    modifier: Modifier = Modifier,
) {
    val c = LocalOrganic.current
    var visible by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.material3.OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, style = body(12f, FontWeight.Normal, c.n600)) },
        placeholder = if (placeholder.isBlank()) null else ({ Text(placeholder, style = body(13f, FontWeight.Normal, c.n500)) }),
        singleLine = true,
        modifier = modifier.fillMaxWidth(),
        visualTransformation = if (visible) androidx.compose.ui.text.input.VisualTransformation.None
        else androidx.compose.ui.text.input.PasswordVisualTransformation(),
        trailingIcon = {
            Box(
                Modifier.size(32.dp).clip(CircleShape).clickable { visible = !visible },
                contentAlignment = Alignment.Center,
            ) { OIcon(if (visible) Lucide.EyeOff else Lucide.Eye, 16.dp, c.n500) }
        },
        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
            focusedTextColor = c.text, unfocusedTextColor = c.text, cursorColor = c.accent,
            focusedBorderColor = c.accent, unfocusedBorderColor = c.divider,
        ),
    )
}

// ── v1.3.x: 弹窗底部按钮行 ─────────────────────────────────────────────────

/** 弹窗底部动作项。[emphasized]=主操作(确认/保存,强调色加粗);默认次操作(取消类,中性色)。 */
class DialogAction(
    val label: String,
    val emphasized: Boolean = false,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/**
 * v1.3.x: 所有 AlertDialog 底部按钮的统一排布——单槽位右对齐横排,结构上保证永不竖排。
 * 取代 M3 的 confirmButton/dismissButton 双槽位:其内部 FlowRow 在长文案(如
 * "应用已识别部分")/窄空间/大字号下会把两键折成上下两行("取消/保存一上一下"的根源),
 * 高内容弹窗还会把按钮挤出可视区。用法:confirmButton = { DialogActionsRow(listOf(...)) },
 * dismissButton 不再传。两个颜色参数仅供深色弹窗覆盖(歌词页的暗色对话框)。
 */
@Composable
fun DialogActionsRow(
    actions: List<DialogAction>,
    emphasizedColor: Color? = null,
    secondaryColor: Color? = null,
) {
    val c = LocalOrganic.current
    val emph = emphasizedColor ?: c.accent
    val sec = secondaryColor ?: c.n600
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (a in actions) {
            androidx.compose.material3.TextButton(onClick = a.onClick, enabled = a.enabled) {
                // 禁用态统一半透明(取代各弹窗手调 n400/n500 灰)——TextButton 只挡点击,
                // 自定色文本不会自动变灰,须显式降透明度。
                val color = (if (a.emphasized) emph else sec).copy(alpha = if (a.enabled) 1f else 0.45f)
                Text(
                    a.label,
                    style = if (a.emphasized) body(14f, FontWeight.Bold, color)
                            else body(14f, FontWeight.Normal, color),
                )
            }
        }
    }
}
