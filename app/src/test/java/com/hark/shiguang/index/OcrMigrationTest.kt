package com.hark.shiguang.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 1.1.0 数据库 1 → 2 迁移：迁移里执行的建表 SQL 必须和 Room 导出的 v2 表结构一字不差，
 * 否则老用户升级时 Room 校验失败 → fallbackToDestructiveMigration 清空照片索引。
 * v1 的表在 v2 里也必须原样保留（迁移只加表，不动旧数据）。
 */
class OcrMigrationTest {
    // JVM 单元测试里 org.json 只是 android.jar 的桩，这里用正则读 Room 导出的 json
    private val entity = Regex("\"tableName\":\\s*\"([^\"]+)\",\\s*\"createSql\":\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")

    private fun tables(v: Int): Map<String, String> {
        val f = listOf("schemas", "app/schemas").map { File(it, "com.hark.shiguang.index.PhotoIndexDb/$v.json") }.first { it.exists() }
        return entity.findAll(f.readText()).associate { m ->
            val name = m.groupValues[1]
            name to m.groupValues[2].replace("\\\"", "\"").replace("\${TABLE_NAME}", name)
        }
    }

    @Test fun migrationSqlMatchesExportedSchema() {
        val v2 = tables(2)
        assertEquals(v2["dav_ocr"], IndexSchema.CREATE_DAV_OCR)
    }

    @Test fun v1TablesUnchangedInV2() {
        val v1 = tables(1); val v2 = tables(2)
        assertTrue(v2.keys.containsAll(v1.keys))
        for ((name, sql) in v1) assertEquals("table $name changed", sql, v2[name])
        assertEquals(v1.keys + "dav_ocr", v2.keys)
    }
}
