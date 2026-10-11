package com.liyang.pnginfo

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** App 自己保存的状态：收藏、钉选的文件夹、排序方式。收藏按 MediaStore id 记，移动文件不会丢。 */
class Store(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("gallery", Context.MODE_PRIVATE)

    var favorites by mutableStateOf(prefs.getStringSet("favorites", emptySet())!!.mapNotNull { it.toLongOrNull() }.toSet())
        private set
    var pinned by mutableStateOf(prefs.getStringSet("pinned", emptySet())!!.mapNotNull { it.toLongOrNull() }.toSet())
        private set
    var sortKey by mutableStateOf(runCatching { SortKey.valueOf(prefs.getString("sort", "DATE")!!) }.getOrDefault(SortKey.DATE))
        private set
    var sortAsc by mutableStateOf(prefs.getBoolean("asc", false))
        private set

    fun isFavorite(id: Long) = id in favorites

    /** 选中的全部已收藏则取消收藏，否则全部加入收藏。返回操作后是否为收藏。 */
    fun toggleFavorites(ids: Collection<Long>): Boolean {
        val add = !ids.all { it in favorites }
        favorites = if (add) favorites + ids else favorites - ids.toSet()
        prefs.edit().putStringSet("favorites", favorites.map { it.toString() }.toSet()).apply()
        return add
    }

    /** 删掉已经不存在的图片 id（被删除或移到回收站的）。 */
    fun pruneFavorites(existing: Set<Long>) {
        val kept = favorites.filter { it in existing }.toSet()
        if (kept.size != favorites.size) {
            favorites = kept
            prefs.edit().putStringSet("favorites", kept.map { it.toString() }.toSet()).apply()
        }
    }

    fun togglePin(bucketId: Long) {
        pinned = if (bucketId in pinned) pinned - bucketId else pinned + bucketId
        prefs.edit().putStringSet("pinned", pinned.map { it.toString() }.toSet()).apply()
    }

    /** 点当前排序项 = 切换正反序；点别的项 = 换排序方式（时间默认新的在前，名称默认 A→Z，大小默认大的在前）。 */
    fun chooseSort(key: SortKey) {
        if (key == sortKey) sortAsc = !sortAsc
        else { sortKey = key; sortAsc = key == SortKey.NAME }
        prefs.edit().putString("sort", sortKey.name).putBoolean("asc", sortAsc).apply()
    }
}

enum class ClipMode { COPY, CUT }

/** App 内的剪贴板：复制 / 剪切的图片，到目标文件夹里粘贴。 */
class Clip {
    var items by mutableStateOf<List<MediaItem>>(emptyList())
        private set
    var mode by mutableStateOf(ClipMode.COPY)
        private set

    fun set(list: List<MediaItem>, m: ClipMode) { items = list; mode = m }
    fun clear() { items = emptyList() }
}
