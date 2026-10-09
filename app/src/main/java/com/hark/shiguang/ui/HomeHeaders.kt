package com.hark.shiguang.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hark.shiguang.Nav
import com.hark.shiguang.Route
import com.hark.shiguang.data.FnClient
import com.hark.shiguang.data.Photo
import com.hark.shiguang.data.Repo
import java.time.LocalDate

/** "On this day" memories: same month-day in earlier years, newest year first. */
object Memories {
    var title by mutableStateOf("")
    var photos by mutableStateOf<List<Photo>>(emptyList())
    var loaded = false
    suspend fun load() {
        if (loaded) return; loaded = true
        val today = LocalDate.now()
        for (back in 1..12) {
            val d = today.minusYears(back.toLong())
            val day = "%04d:%02d:%02d".format(d.year, d.monthValue, d.dayOfMonth)
            val l = runCatching { Repo.photos("$day 00:00:00", "$day 23:59:59", 0, 30).first }.getOrDefault(emptyList()).filter { !it.isVideo }
            if (l.isNotEmpty()) { title = if (back == 1) "一年前的今天" else "${back}年前的今天"; photos = l; return }
        }
        // fall back to this week a year ago ±3 days
        val d = today.minusYears(1)
        val l = runCatching {
            Repo.photos("%04d:%02d:%02d 00:00:00".format(d.minusDays(3).year, d.minusDays(3).monthValue, d.minusDays(3).dayOfMonth),
                "%04d:%02d:%02d 23:59:59".format(d.plusDays(3).year, d.plusDays(3).monthValue, d.plusDays(3).dayOfMonth), 0, 30).first
        }.getOrDefault(emptyList()).filter { !it.isVideo }
        if (l.isNotEmpty()) { title = "去年这周"; photos = l }
    }
    fun open(i: Int = 0) { if (photos.isNotEmpty()) Nav.push(Route.Viewer(photos, i)) }
}

