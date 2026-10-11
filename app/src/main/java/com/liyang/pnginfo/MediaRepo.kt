package com.liyang.pnginfo

import android.app.PendingIntent
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.MediaStore.MediaColumns

data class MediaItem(
    val id: Long,            // MediaStore id；外部分享进来的单张图为 -1
    val uri: Uri,
    val name: String,
    val time: Long,          // 毫秒：拍摄时间，没有则用修改时间
    val size: Long,
    val bucketId: Long,
    val bucket: String,
    val relPath: String,     // 例：Pictures/Telegram/
    val mime: String,
)

data class Album(
    val bucketId: Long,
    val name: String,
    val relPath: String,
    val count: Int,
    val cover: Uri,
    val latest: Long,
)

enum class SortKey(val label: String) { DATE("时间"), NAME("文件名"), SIZE("大小") }

fun List<MediaItem>.sortedBy(key: SortKey, asc: Boolean): List<MediaItem> {
    val c: Comparator<MediaItem> = when (key) {
        SortKey.DATE -> compareBy<MediaItem> { it.time }.thenBy { it.name.lowercase() }
        SortKey.NAME -> compareBy<MediaItem> { it.name.lowercase() }.thenBy { it.time }
        SortKey.SIZE -> compareBy<MediaItem> { it.size }.thenBy { it.name.lowercase() }
    }
    return sortedWith(if (asc) c else c.reversed())
}

/** 读写手机图库（MediaStore）。复制不需要额外授权；移动、删除到回收站会弹系统确认框。 */
object MediaRepo {
    private val IMAGES: Uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

    private val PROJECTION = arrayOf(
        MediaColumns._ID,
        MediaColumns.DISPLAY_NAME,
        MediaStore.Images.Media.DATE_TAKEN,
        MediaColumns.DATE_MODIFIED,
        MediaColumns.SIZE,
        MediaStore.Images.Media.BUCKET_ID,
        MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
        MediaColumns.RELATIVE_PATH,
        MediaColumns.MIME_TYPE,
    )

    /** 全部图片（不含回收站里的）；trashed=true 时只查回收站。 */
    fun queryAll(ctx: Context, trashed: Boolean = false): List<MediaItem> {
        val args = Bundle().apply {
            if (trashed) putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY)
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_EXCLUDE)
        }
        val out = ArrayList<MediaItem>()
        ctx.contentResolver.query(IMAGES, PROJECTION, args, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val taken = c.getLong(2)
                out += MediaItem(
                    id = id,
                    uri = ContentUris.withAppendedId(IMAGES, id),
                    name = c.getString(1) ?: "image",
                    time = if (taken > 0) taken else c.getLong(3) * 1000,
                    size = c.getLong(4),
                    bucketId = c.getLong(5),
                    bucket = c.getString(6) ?: "",
                    relPath = c.getString(7) ?: "",
                    mime = c.getString(8) ?: "image/png",
                )
            }
        }
        return out
    }

    fun albums(all: List<MediaItem>): List<Album> =
        all.groupBy { it.bucketId }.map { (id, items) ->
            val newest = items.maxBy { it.time }
            Album(id, newest.bucket.ifBlank { newest.relPath.trimEnd('/') }, newest.relPath, items.size, newest.uri, newest.time)
        }.sortedByDescending { it.latest }

    /** 新文件写入哪个集合：Download 目录只能写进 Downloads 集合，其他（DCIM / Pictures）写进 Images。 */
    private fun collectionFor(relPath: String): Uri =
        if (relPath.startsWith(Environment.DIRECTORY_DOWNLOADS)) MediaStore.Downloads.EXTERNAL_CONTENT_URI else IMAGES

    /** 按字节复制（PNG 里的生成信息原样保留）。同名文件系统会自动改名为「名字 (1)」。 */
    fun copyInto(ctx: Context, item: MediaItem, relPath: String) {
        val cr = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaColumns.DISPLAY_NAME, item.name)
            put(MediaColumns.MIME_TYPE, item.mime)
            put(MediaColumns.RELATIVE_PATH, relPath)
            put(MediaColumns.IS_PENDING, 1)
        }
        val dest = cr.insert(collectionFor(relPath), values) ?: error("无法在 $relPath 新建文件")
        try {
            cr.openInputStream(item.uri).use { input ->
                cr.openOutputStream(dest).use { output ->
                    requireNotNull(input) { "读不到 ${item.name}" }.copyTo(requireNotNull(output))
                }
            }
            cr.update(dest, ContentValues().apply { put(MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            cr.delete(dest, null, null)
            throw e
        }
    }

    /** 移动：改 RELATIVE_PATH（需要先通过 writeRequest 拿到授权）。文件 id 不变，收藏保留。 */
    fun moveInto(ctx: Context, item: MediaItem, relPath: String) {
        val n = ctx.contentResolver.update(item.uri, ContentValues().apply { put(MediaColumns.RELATIVE_PATH, relPath) }, null, null)
        check(n > 0) { "${item.name} 没有移动成功" }
    }

    fun writeRequest(ctx: Context, items: List<MediaItem>): PendingIntent =
        MediaStore.createWriteRequest(ctx.contentResolver, items.map { it.uri })

    /** 永久删除（回收站里的「永久删除 / 清空回收站」），无法恢复。 */
    fun deleteRequest(ctx: Context, items: List<MediaItem>): PendingIntent =
        MediaStore.createDeleteRequest(ctx.contentResolver, items.map { it.uri })

    /** 放进 / 移出系统回收站（30 天后系统自动清除）。 */
    fun trashRequest(ctx: Context, items: List<MediaItem>, trash: Boolean): PendingIntent =
        MediaStore.createTrashRequest(ctx.contentResolver, items.map { it.uri }, trash)
}
