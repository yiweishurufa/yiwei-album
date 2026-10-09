package com.hark.shiguang

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.RemoteViews
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.hark.shiguang.data.Photo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

/**
 * 桌面小组件 (1.0.9): 那年今天, or a random older photo when today has none.
 * The app writes a small feed (≤ 8 JPEGs + feed.json in filesDir/widget) while it already has the photos
 * on screen; the widget only rotates through that feed (system updatePeriod, every 6 h -> new pick, new
 * day -> "回忆"), so it never needs a login or network. Tap opens the app.
 */
class MemoryWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) { render(c, m, ids) }

    companion object {
        private fun dir(c: Context) = File(c.filesDir, "widget").apply { mkdirs() }

        fun refreshAll(c: Context) {
            val m = AppWidgetManager.getInstance(c)
            val ids = m.getAppWidgetIds(ComponentName(c, MemoryWidget::class.java))
            if (ids.isNotEmpty()) render(c, m, ids)
        }

        private fun render(c: Context, m: AppWidgetManager, ids: IntArray) {
            val feed = runCatching { JSONObject(File(dir(c), "feed.json").readText()) }.getOrNull()
            val items = feed?.optJSONArray("items")
            val today = LocalDate.now().toString()
            val open = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            ids.forEachIndexed { k, id ->
                val v = RemoteViews(c.packageName, R.layout.widget_memory)
                v.setOnClickPendingIntent(R.id.w_root, open)
                if (items != null && items.length() > 0) {
                    // a different pick every 6 hours, different per widget
                    val slot = (System.currentTimeMillis() / (6 * 3600_000L)).toInt()
                    val it = items.getJSONObject(((slot + k) % items.length() + items.length()) % items.length())
                    val bmp = runCatching { decode(File(dir(c), it.getString("f"))) }.getOrNull()
                    if (bmp != null) v.setImageViewBitmap(R.id.w_img, bmp)
                    val sameDay = feed.optString("day") == today && feed.optBoolean("onThisDay")
                    v.setTextViewText(R.id.w_title, if (sameDay) it.optString("title", "那年今天") else "回忆")
                    v.setTextViewText(R.id.w_sub, it.optString("sub"))
                } else {
                    v.setTextViewText(R.id.w_title, "那年今天")
                    v.setTextViewText(R.id.w_sub, "打开一维相册加载回忆")
                }
                m.updateAppWidget(id, v)
            }
        }

        private fun decode(f: File): Bitmap? {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, o)
            var s = 1; while (maxOf(o.outWidth, o.outHeight) / s > 720) s *= 2
            return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })
        }

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        @Volatile private var lastKey = ""

        /**
         * Called by the photo timeline. [memories] = (title, photos) of 那年今天; when empty, [pool] gives
         * random photos older than a year. Cheap when nothing changed: one feed per source per day.
         */
        fun feed(c: Context, sourceKey: String, memories: List<Pair<String, List<Photo>>>, pool: () -> List<Photo>) {
            val app = c.applicationContext
            val m = AppWidgetManager.getInstance(app)
            if (m.getAppWidgetIds(ComponentName(app, MemoryWidget::class.java)).isEmpty()) return
            val key = "$sourceKey@${LocalDate.now()}@${memories.sumOf { it.second.size }}"
            if (key == lastKey) return
            lastKey = key
            scope.launch {
                val picks: List<Triple<Photo, String, String>> = if (memories.isNotEmpty())
                    memories.flatMap { (t, ps) -> ps.shuffled().take(3).map { Triple(it, t, it.time.take(10).replace(':', '.')) } }.take(8)
                else {
                    val cut = LocalDate.now().minusYears(1).toString().replace('-', ':')
                    pool().filter { !it.isVideo && it.time < cut && it.time > "1971" }.shuffled().take(8).map { Triple(it, "回忆", it.time.take(10).replace(':', '.')) }
                }
                if (picks.isEmpty()) return@launch
                val d = dir(app)
                val arr = JSONArray()
                picks.forEachIndexed { i, (p, t, s) ->
                    val r = runCatching { app.imageLoader.execute(ImageRequest.Builder(app).data(p.thumbM).size(720).allowHardware(false).build()) }.getOrNull()
                    val b = ((r as? SuccessResult)?.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap ?: return@forEachIndexed
                    val f = File(d, "w$i.jpg")
                    runCatching { f.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 85, it) } }.onSuccess {
                        arr.put(JSONObject().put("f", f.name).put("title", t).put("sub", s))
                    }
                }
                if (arr.length() == 0) return@launch
                File(d, "feed.json").writeText(JSONObject().put("day", LocalDate.now().toString()).put("onThisDay", memories.isNotEmpty()).put("items", arr).toString())
                refreshAll(app)
            }
        }
    }
}
