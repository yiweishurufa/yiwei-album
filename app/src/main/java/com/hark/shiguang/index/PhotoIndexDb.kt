package com.hark.shiguang.index

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import com.hark.shiguang.App
import com.hark.shiguang.cloud.CloudEntry

/**
 * 1.1.0 照片索引数据库（Room）。一张表存所有 WebDAV 账户的文件索引（account = 账户 id），
 * 另外两张表存「扫描到一半」的进度，被打断后下次从断点继续（H7）。
 *
 * 旧版的 `dav/<id>/index/<yyyy-MM>.json` 第一次启动时自动导入（见 [IndexStore]），导入失败或数据库打不开
 * 就继续用 JSON，旧文件不删。
 */
@Entity(
    tableName = "dav_entry",
    primaryKeys = ["account", "path"],
    indices = [Index(value = ["account", "month"]), Index(value = ["account", "modified"]), Index(value = ["account", "size"])],
)
data class DavEntryRow(
    val account: String,
    val path: String,
    val name: String,
    val size: Long,
    val modified: Long,
    val isVideo: Boolean,
    val isDir: Boolean,
    val month: String,
) {
    fun toEntry() = CloudEntry(name, path, isDir, size, modified, null, !isDir && !isVideo, isVideo && !isDir)

    companion object {
        fun of(account: String, e: CloudEntry) =
            DavEntryRow(account, e.path, e.name, e.size, e.modified, e.isVideo && !e.isDir, e.isDir, IndexDiff.monthOf(e.modified))
    }
}

/** H7: one folder already listed in the current (unfinished) scan pass. [kids] = JSON [[path, tag|null], …]. */
@Entity(tableName = "dav_scan_dir", primaryKeys = ["account", "dir"])
data class ScanDirRow(val account: String, val dir: String, val tag: String?, val kids: String)

/** H7: media found in a folder of the current (unfinished) scan pass. */
@Entity(tableName = "dav_scan_media", primaryKeys = ["account", "path"], indices = [Index(value = ["account", "dir"])])
data class ScanMediaRow(val account: String, val dir: String, val path: String, val name: String, val size: Long, val modified: Long, val isVideo: Boolean) {
    fun toEntry() = CloudEntry(name, path, false, size, modified, null, !isVideo, isVideo)
}

/** H7: which pass the rows above belong to (roots + start time); a pass with other roots or too old is discarded. */
@Entity(tableName = "dav_scan_state", primaryKeys = ["account"])
data class ScanStateRow(val account: String, val roots: String, val startedAt: Long, val full: Boolean)

@Dao
abstract class PhotoIndexDao {
    @Query("SELECT DISTINCT month FROM dav_entry WHERE account = :a ORDER BY month DESC")
    abstract fun months(a: String): List<String>

    @Query("SELECT * FROM dav_entry WHERE account = :a AND month IN (:months)")
    abstract fun byMonths(a: String, months: List<String>): List<DavEntryRow>

    @Query("SELECT * FROM dav_entry WHERE account = :a")
    abstract fun all(a: String): List<DavEntryRow>

    @Query("SELECT COUNT(*) FROM dav_entry WHERE account = :a")
    abstract fun count(a: String): Int

    @Upsert
    abstract fun upsert(rows: List<DavEntryRow>)

    @Query("DELETE FROM dav_entry WHERE account = :a AND path IN (:paths)")
    abstract fun deletePaths(a: String, paths: List<String>)

    @Query("DELETE FROM dav_entry WHERE account = :a")
    abstract fun clear(a: String)

    /** Paging 3: files (no folders), largest first — 「大文件」清理页。 */
    @Query("SELECT * FROM dav_entry WHERE account = :a AND isDir = 0 ORDER BY size DESC, path")
    abstract fun pageBySize(a: String): PagingSource<Int, DavEntryRow>

    /** Paging 3: files, newest modified first. */
    @Query("SELECT * FROM dav_entry WHERE account = :a AND isDir = 0 ORDER BY modified DESC, path")
    abstract fun pageByTime(a: String): PagingSource<Int, DavEntryRow>

    /** Applies an [IndexDiff.Result] atomically (a crash leaves the old or the new index, never a mix). */
    @Transaction
    open fun apply(a: String, ups: List<DavEntryRow>, dels: List<String>) {
        dels.chunked(500).forEach { deletePaths(a, it) }
        ups.chunked(500).forEach { upsert(it) }
    }

    /** Full import (migration from JSON): replaces everything of [a]. */
    @Transaction
    open fun replaceAll(a: String, rows: List<DavEntryRow>) {
        clear(a)
        rows.chunked(500).forEach { upsert(it) }
    }

    // ------------------------------------------------------------------ H7 scan progress
    @Query("SELECT * FROM dav_scan_state WHERE account = :a")
    abstract fun scanState(a: String): ScanStateRow?

    @Upsert
    abstract fun putScanState(s: ScanStateRow)

    @Query("SELECT * FROM dav_scan_dir WHERE account = :a")
    abstract fun scanDirs(a: String): List<ScanDirRow>

    @Query("SELECT * FROM dav_scan_media WHERE account = :a")
    abstract fun scanMedia(a: String): List<ScanMediaRow>

    @Upsert
    abstract fun putScanDir(d: ScanDirRow)

    @Upsert
    abstract fun putScanMedia(m: List<ScanMediaRow>)

    @Query("DELETE FROM dav_scan_media WHERE account = :a AND dir = :dir")
    abstract fun clearScanMediaOf(a: String, dir: String)

    @Transaction
    open fun saveListed(a: String, dir: ScanDirRow, media: List<ScanMediaRow>) {
        clearScanMediaOf(a, dir.dir)
        media.chunked(500).forEach { putScanMedia(it) }
        putScanDir(dir)
    }

    @Query("DELETE FROM dav_scan_dir WHERE account = :a")
    abstract fun clearScanDirs(a: String)

    @Query("DELETE FROM dav_scan_media WHERE account = :a")
    abstract fun clearScanMediaAll(a: String)

    @Query("DELETE FROM dav_scan_state WHERE account = :a")
    abstract fun clearScanStateRow(a: String)

    @Transaction
    open fun clearScan(a: String) { clearScanDirs(a); clearScanMediaAll(a); clearScanStateRow(a) }
}

@Database(entities = [DavEntryRow::class, ScanDirRow::class, ScanMediaRow::class, ScanStateRow::class], version = 1, exportSchema = true)
abstract class PhotoIndexDb : RoomDatabase() {
    abstract fun dao(): PhotoIndexDao

    companion object {
        @Volatile private var inst: PhotoIndexDb? = null

        fun get(): PhotoIndexDb = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(App.ctx, PhotoIndexDb::class.java, "photo_index.db")
                // 以后改表结构要写 Migration；没写时宁可清空重扫（JSON 旧索引仍在，见 IndexStore），也不闪退
                .fallbackToDestructiveMigration()
                .build().also { inst = it }
        }
    }
}
