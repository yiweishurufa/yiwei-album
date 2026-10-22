package com.hark.shiguang

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.*

/**
 * 1.1.0 自动旅行相册：纯函数聚类，不碰 Android / 网络，单元测试见 TripClusterTest。
 *
 * 规则（用户 2026-10-22 路线图）：
 * - 常住地 = GPS 点最多的格子（[Rules.homeCellDeg] 度见方），取格内点的平均位置；
 * - 离常住地超过 [Rules.awayKm] 的照片算「在外」；
 * - 在外的日子按日期连成一段，相邻两个在外日相差超过 [Rules.maxGapDays] 天就拆开；
 *   中间某天只有常住地附近的照片（已经回家了），也拆开；
 * - 一段跨 1～[Rules.maxDays] 天、在外照片 ≥ [Rules.minPhotos] 张才算一次旅行；
 * - 没有 GPS（NaN、0,0、越界）或没有拍摄日期的照片不参与。
 */
object TripCluster {

    data class Rules(
        val awayKm: Double = 50.0,
        val minPhotos: Int = 15,
        val maxDays: Int = 30,
        val maxGapDays: Int = 2,
        val homeCellDeg: Double = 0.1,
    )

    /** One photo: [key] identifies it (cloud path), [time] is "YYYY:MM:DD HH:MM:SS", [place] the offline city name or "". */
    data class Point(val key: String, val time: String, val lat: Double, val lng: Double, val place: String = "")

    data class Home(val lat: Double, val lng: Double, val count: Int)

    data class Trip(
        val start: LocalDate, val end: LocalDate,
        /** Keys of the trip's photos, oldest first. */
        val keys: List<String>,
        val coverKey: String,
        /** Up to two place names ("杭州、绍兴"), or a rough coordinate area when no name is known. */
        val place: String,
        val lat: Double, val lng: Double,
    ) {
        val days: Int get() = ChronoUnit.DAYS.between(start, end).toInt() + 1
        /** Stable id: same dates + same first photo = same trip across recomputes. */
        val id: String get() = "$start~$end~${keys.firstOrNull().orEmpty()}"
    }

    data class Result(val home: Home?, val trips: List<Trip>)

    fun hasGps(lat: Double, lng: Double): Boolean =
        !lat.isNaN() && !lng.isNaN() && lat in -90.0..90.0 && lng in -180.0..180.0 && !(lat == 0.0 && lng == 0.0)

    /** "YYYY:MM:DD…" -> date, null when missing / unparsable / before 1971 (EXIF placeholder dates). */
    fun dayOf(time: String): LocalDate? {
        if (time.length < 10) return null
        val y = time.substring(0, 4).toIntOrNull() ?: return null
        val m = time.substring(5, 7).toIntOrNull() ?: return null
        val d = time.substring(8, 10).toIntOrNull() ?: return null
        if (y < 1971) return null
        return runCatching { LocalDate.of(y, m, d) }.getOrNull()
    }

    /** Great-circle distance in km. */
    fun km(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1); val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
        return 2 * r * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    fun home(points: List<Point>, rules: Rules = Rules()): Home? {
        val cells = HashMap<Long, MutableList<Point>>()
        for (p in points) {
            if (!hasGps(p.lat, p.lng)) continue
            val cx = floor(p.lng / rules.homeCellDeg).toLong(); val cy = floor(p.lat / rules.homeCellDeg).toLong()
            cells.getOrPut((cx shl 32) xor (cy and 0xffffffffL)) { ArrayList() }.add(p)
        }
        // ties: the cell whose points come first in time order wins, so the answer doesn't depend on HashMap order
        val best = cells.values.maxWithOrNull(compareBy<List<Point>> { it.size }.thenByDescending { l -> l.minOf { it.time } }) ?: return null
        return Home(best.sumOf { it.lat } / best.size, best.sumOf { it.lng } / best.size, best.size)
    }

    fun cluster(all: List<Point>, rules: Rules = Rules()): Result {
        val pts = all.filter { hasGps(it.lat, it.lng) && dayOf(it.time) != null }
        val home = home(pts, rules) ?: return Result(null, emptyList())
        val away = ArrayList<Pair<Point, LocalDate>>()
        val homeDays = HashSet<LocalDate>()
        for (p in pts) {
            val d = dayOf(p.time)!!
            if (km(home.lat, home.lng, p.lat, p.lng) > rules.awayKm) away += p to d else homeDays += d
        }
        if (away.isEmpty()) return Result(home, emptyList())
        away.sortWith(compareBy({ it.second }, { it.first.time }, { it.first.key }))
        val awayDays = away.mapTo(HashSet()) { it.second }

        val trips = ArrayList<Trip>()
        var run = ArrayList<Pair<Point, LocalDate>>()
        fun flush() {
            if (run.isEmpty()) return
            val span = ChronoUnit.DAYS.between(run.first().second, run.last().second) + 1
            if (span in 1..rules.maxDays && run.size >= rules.minPhotos) trips += build(run)
            run = ArrayList()
        }
        for (e in away) {
            val prev = run.lastOrNull()?.second
            if (prev != null && e.second != prev) {
                val gap = ChronoUnit.DAYS.between(prev, e.second)
                // a day in between with photos only near home = the trip ended and a new one began
                val wentHome = (1 until gap).any { prev.plusDays(it) in homeDays && prev.plusDays(it) !in awayDays }
                if (gap > rules.maxGapDays || wentHome) flush()
            }
            run += e
        }
        flush()
        return Result(home, trips.sortedByDescending { it.start })
    }

    private fun build(run: List<Pair<Point, LocalDate>>): Trip {
        val ps = run.map { it.first }
        val lat = ps.sumOf { it.lat } / ps.size; val lng = ps.sumOf { it.lng } / ps.size
        val names = ps.map { it.place.trim() }.filter { isName(it) }.groupingBy { it }.eachCount()
            .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).take(2).map { it.key }
        val place = if (names.isNotEmpty()) names.joinToString("、") else area(lat, lng)
        return Trip(run.first().second, run.last().second, ps.map { it.key }, ps[ps.size / 2].key, place, lat, lng)
    }

    /** A real place name, not the "36.1°, 120.4°" fallback the analyser stores when the system geocoder fails. */
    fun isName(s: String): Boolean = s.isNotEmpty() && s.any { it.isLetter() }

    /** Rough coordinate area when no offline name is known: "北纬 36.1° 东经 120.4° 附近". */
    fun area(lat: Double, lng: Double): String =
        "${if (lat >= 0) "北纬" else "南纬"} ${"%.1f".format(java.util.Locale.ROOT, abs(lat))}° ${if (lng >= 0) "东经" else "西经"} ${"%.1f".format(java.util.Locale.ROOT, abs(lng))}° 附近"

    /** Order-independent fingerprint of everything [cluster] reads; equal fingerprint = cached result still valid. */
    fun fingerprint(points: List<Point>): Long {
        var sum = 0L; var x = 0L
        for (p in points) {
            val h = ((p.key.hashCode().toLong() * 31 + p.time.hashCode()) * 31 + p.lat.toBits()) * 31 + p.lng.toBits() * 17 + p.place.hashCode()
            sum += h; x = x xor (h * -0x61c8864680b583ebL)
        }
        return sum * 31 + x + points.size
    }
}

/** Card / title date range: "10月1日–5日", "9月30日–10月2日", other years "2024年10月1日–5日", one day "10月1日". */
fun tripRange(start: LocalDate, end: LocalDate, today: LocalDate = LocalDate.now()): String {
    val y = if (start.year != today.year) "${start.year}年" else ""
    val a = "$y${start.monthValue}月${start.dayOfMonth}日"
    return when {
        start == end -> a
        start.year != end.year -> "$a–${end.year}年${end.monthValue}月${end.dayOfMonth}日"
        start.monthValue != end.monthValue -> "$a–${end.monthValue}月${end.dayOfMonth}日"
        else -> "$a–${end.dayOfMonth}日"
    }
}
