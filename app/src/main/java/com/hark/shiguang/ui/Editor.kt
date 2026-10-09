package com.hark.shiguang.ui

import android.graphics.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hark.shiguang.Dav
import com.hark.shiguang.Nav
import com.hark.shiguang.cloud.CloudEntry
import com.hark.shiguang.data.FnClient
import com.hark.shiguang.data.NasX
import com.hark.shiguang.data.Photo
import com.hark.shiguang.data.UploadTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import kotlin.math.max
import kotlin.math.min

/**
 * 简单编辑 (1.0.9): crop (free / 1:1 / 4:3 / 16:9), rotate 90°, brightness, contrast.
 * Saves a copy next to the original (the original is never touched): WebDAV "<name>_edit.jpg" (numbered if taken),
 * 飞牛 "<name>_edit_yyyyMMddHHmmss.jpg" with overwrite off:
 * WebDAV = PUT into the same folder; 飞牛 = the official upload path into the same folder (folder view).
 */
@Composable
fun EditScreen(p: Photo) {
    val ctx = LocalContext.current
    var src by remember { mutableStateOf<Bitmap?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(p.key) { runCatching { withContext(Dispatchers.IO) { loadOriginal(p) } }.onSuccess { src = it }.onFailure { err = it.message ?: "读取原图失败" } }
    var rot by remember { mutableIntStateOf(0) }
    var bright by remember { mutableFloatStateOf(0f) }    // -1..1
    var contrast by remember { mutableFloatStateOf(1f) }  // 0.5..1.6
    var aspect by remember { mutableIntStateOf(0) }       // 0 free, 1 1:1, 2 4:3, 3 16:9
    var crop by remember { mutableStateOf(RectF(0f, 0f, 1f, 1f)) }   // fraction of the rotated image
    var tool by remember { mutableIntStateOf(0) }         // 0 crop, 1 adjust
    var saving by remember { mutableStateOf(false) }
    val rotated = remember(src, rot) { src?.let { rotate(it, rot) } }
    LaunchedEffect(rotated, aspect) { rotated?.let { crop = fitAspect(aspect, it.width.toFloat() / it.height) } }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundIcon(Icons.Rounded.Close, bg = Color(0x33FFFFFF), tint = Color.White) { Nav.pop() }
            Text("编辑", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).padding(start = 14.dp))
            TextButton(onClick = { rot = 0; bright = 0f; contrast = 1f; aspect = 0; rotated?.let { crop = RectF(0f, 0f, 1f, 1f) } }) { Text("还原", color = Color.White.copy(alpha = 0.7f)) }
            Box(Modifier.clip(RoundedCornerShape(14.dp)).background(C.Accent).clickable(enabled = !saving && rotated != null) {
                val r = rotated ?: return@clickable
                saving = true
                Ops.launch {
                    runCatching { withContext(Dispatchers.IO) { save(p, render(r, crop, bright, contrast)) } }
                        .onSuccess { toast(ctx, "已另存为 $it"); Nav.pop() }
                        .onFailure { toast(ctx, it.message ?: "保存失败") }
                    saving = false
                }
            }.padding(horizontal = 16.dp, vertical = 8.dp)) {
                if (saving) CircularProgressIndicator(Modifier.size(18.dp), color = C.OnAccent, strokeWidth = 2.dp)
                else Text("另存", color = C.OnAccent, fontWeight = FontWeight.SemiBold)
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
            val bmp = rotated
            when {
                err != null -> Text(err!!, color = Color.White)
                bmp == null -> CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp)
                else -> CropView(bmp, crop, aspect, bright, contrast, editable = tool == 0) { crop = it }
            }
        }
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 10.dp)) {
            if (tool == 0) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf("自由", "1:1", "4:3", "16:9").forEachIndexed { i, t ->
                    Text(t, color = if (aspect == i) C.OnAccent else Color.White, fontSize = 13.sp, modifier = Modifier.padding(end = 8.dp).clip(RoundedCornerShape(12.dp))
                        .background(if (aspect == i) C.Accent else Color(0x26FFFFFF)).clickable { aspect = i }.padding(horizontal = 14.dp, vertical = 8.dp))
                }
                Spacer(Modifier.width(8.dp))
                Row(Modifier.clip(RoundedCornerShape(12.dp)).background(Color(0x26FFFFFF)).clickable { rot = (rot + 90) % 360 }.padding(horizontal = 14.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.RotateRight, null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Text("旋转", color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp))
                }
            } else Column(Modifier.padding(horizontal = 20.dp)) {
                Text("亮度  ${(bright * 100).toInt()}", color = Color.White, fontSize = 13.sp)
                Slider(bright, { bright = it }, valueRange = -0.6f..0.6f)
                Text("对比度  ${(contrast * 100).toInt()}%", color = Color.White, fontSize = 13.sp)
                Slider(contrast, { contrast = it }, valueRange = 0.5f..1.6f)
            }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                listOf(Icons.Rounded.Crop to "裁剪旋转", Icons.Rounded.Tune to "调节").forEachIndexed { i, (ic, t) ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { tool = i }.padding(horizontal = 18.dp, vertical = 6.dp)) {
                        Icon(ic, null, tint = if (tool == i) C.Accent else Color.White.copy(alpha = 0.7f), modifier = Modifier.size(22.dp))
                        Text(t, color = if (tool == i) C.Accent else Color.White.copy(alpha = 0.7f), fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun CropView(bmp: Bitmap, crop: RectF, aspect: Int, bright: Float, contrast: Float, editable: Boolean, onCrop: (RectF) -> Unit) {
    val img = remember(bmp) { bmp.asImageBitmap() }
    val filter = remember(bright, contrast) { androidx.compose.ui.graphics.ColorFilter.colorMatrix(androidx.compose.ui.graphics.ColorMatrix(colorMatrix(bright, contrast).array)) }
    var box by remember { mutableStateOf(IntSize.Zero) }
    val cur by rememberUpdatedState(crop)
    val ratio = bmp.width.toFloat() / bmp.height
    BoxWithConstraints(Modifier.fillMaxSize().onSizeChanged { box = it }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().pointerInput(bmp, aspect, editable) {
            if (!editable) return@pointerInput
            var mode = 0 // 0 move, 1..4 corners tl,tr,bl,br
            detectDragGestures(onDragStart = { pos ->
                val (dx, dy, dw, dh) = fitRect(size.width.toFloat(), size.height.toFloat(), ratio)
                val c = cur
                val corners = listOf(Offset(dx + c.left * dw, dy + c.top * dh), Offset(dx + c.right * dw, dy + c.top * dh), Offset(dx + c.left * dw, dy + c.bottom * dh), Offset(dx + c.right * dw, dy + c.bottom * dh))
                val i = corners.indexOfFirst { (it - pos).getDistance() < 48.dp.toPx() }
                mode = if (i >= 0) i + 1 else 0
            }) { ch, d ->
                ch.consume()
                val (_, _, dw, dh) = fitRect(size.width.toFloat(), size.height.toFloat(), ratio)
                val fx = d.x / dw; val fy = d.y / dh
                val c = RectF(cur)
                val minF = 0.08f
                when (mode) {
                    0 -> { val w = c.width(); val h = c.height(); c.left = (c.left + fx).coerceIn(0f, 1f - w); c.top = (c.top + fy).coerceIn(0f, 1f - h); c.right = c.left + w; c.bottom = c.top + h }
                    1 -> { c.left = (c.left + fx).coerceIn(0f, c.right - minF); c.top = (c.top + fy).coerceIn(0f, c.bottom - minF) }
                    2 -> { c.right = (c.right + fx).coerceIn(c.left + minF, 1f); c.top = (c.top + fy).coerceIn(0f, c.bottom - minF) }
                    3 -> { c.left = (c.left + fx).coerceIn(0f, c.right - minF); c.bottom = (c.bottom + fy).coerceIn(c.top + minF, 1f) }
                    4 -> { c.right = (c.right + fx).coerceIn(c.left + minF, 1f); c.bottom = (c.bottom + fy).coerceIn(c.top + minF, 1f) }
                }
                if (aspect != 0 && mode != 0) {
                    // keep the ratio: height follows width, anchored on the opposite corner
                    val target = aspectValue(aspect) / ratio   // width / height in fraction units
                    val h = (c.width() / target).coerceAtMost(1f); val w = h * target
                    if (mode == 1 || mode == 3) c.left = c.right - w else c.right = c.left + w
                    if (mode == 1 || mode == 2) c.top = c.bottom - h else c.bottom = c.top + h
                    if (c.left < 0f || c.top < 0f || c.right > 1f || c.bottom > 1f) return@detectDragGestures
                }
                onCrop(c)
            }
        }) {
            val (dx, dy, dw, dh) = fitRect(size.width, size.height, ratio)
            drawImage(img, srcOffset = androidx.compose.ui.unit.IntOffset.Zero, srcSize = IntSize(bmp.width, bmp.height),
                dstOffset = androidx.compose.ui.unit.IntOffset(dx.toInt(), dy.toInt()), dstSize = IntSize(dw.toInt(), dh.toInt()), colorFilter = filter)
            val l = dx + crop.left * dw; val t = dy + crop.top * dh; val r = dx + crop.right * dw; val b = dy + crop.bottom * dh
            val shade = Color(0x99000000)
            drawRect(shade, Offset(dx, dy), Size(dw, t - dy)); drawRect(shade, Offset(dx, b), Size(dw, dy + dh - b))
            drawRect(shade, Offset(dx, t), Size(l - dx, b - t)); drawRect(shade, Offset(r, t), Size(dx + dw - r, b - t))
            drawRect(Color.White, Offset(l, t), Size(r - l, b - t), style = Stroke(1.5.dp.toPx()))
            for (k in 1..2) {
                drawLine(Color.White.copy(alpha = 0.35f), Offset(l + (r - l) * k / 3, t), Offset(l + (r - l) * k / 3, b))
                drawLine(Color.White.copy(alpha = 0.35f), Offset(l, t + (b - t) * k / 3), Offset(r, t + (b - t) * k / 3))
            }
            if (editable) listOf(Offset(l, t), Offset(r, t), Offset(l, b), Offset(r, b)).forEach { drawCircle(Color.White, 7.dp.toPx(), it) }
        }
    }
}

private data class Fit(val x: Float, val y: Float, val w: Float, val h: Float)
private fun fitRect(W: Float, H: Float, ratio: Float): Fit = if (W / H > ratio) Fit((W - H * ratio) / 2, 0f, H * ratio, H) else Fit(0f, (H - W / ratio) / 2, W, W / ratio)
private fun aspectValue(a: Int) = when (a) { 1 -> 1f; 2 -> 4f / 3f; 3 -> 16f / 9f; else -> 0f }

/** Largest centred crop with the chosen aspect (portrait images get the portrait version of 4:3 / 16:9). */
private fun fitAspect(a: Int, imgRatio: Float): RectF {
    if (a == 0) return RectF(0f, 0f, 1f, 1f)
    var ar = aspectValue(a); if (imgRatio < 1f && ar > 1f) ar = 1f / ar
    val wFrac: Float; val hFrac: Float
    if (imgRatio > ar) { hFrac = 1f; wFrac = ar / imgRatio } else { wFrac = 1f; hFrac = imgRatio / ar }
    return RectF((1 - wFrac) / 2, (1 - hFrac) / 2, (1 + wFrac) / 2, (1 + hFrac) / 2)
}

private fun colorMatrix(bright: Float, contrast: Float): ColorMatrix {
    val t = (1f - contrast) * 128f + bright * 255f
    return ColorMatrix(floatArrayOf(contrast, 0f, 0f, 0f, t, 0f, contrast, 0f, 0f, t, 0f, 0f, contrast, 0f, t, 0f, 0f, 0f, 1f, 0f))
}

private fun rotate(b: Bitmap, deg: Int): Bitmap = if (deg == 0) b else Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(deg.toFloat()) }, true)

private fun render(b: Bitmap, crop: RectF, bright: Float, contrast: Float): Bitmap {
    val x = (crop.left * b.width).toInt().coerceIn(0, b.width - 1); val y = (crop.top * b.height).toInt().coerceIn(0, b.height - 1)
    val w = (crop.width() * b.width).toInt().coerceIn(1, b.width - x); val h = (crop.height() * b.height).toInt().coerceIn(1, b.height - y)
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    Canvas(out).drawBitmap(b, Rect(x, y, x + w, y + h), Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(colorMatrix(bright, contrast)) })
    return out
}

/** Downloads the original (max ~4096 px long side, EXIF orientation applied). */
private suspend fun loadOriginal(p: Photo): Bitmap {
    val (url, headers) = if (p.isCloud) {
        val s = CloudPhotos.source(p.source) ?: error("网盘账户已移除")
        val e = CloudPhotos.entryOf(p) ?: CloudEntry(p.fileName, p.cloudPath, false, p.size, 0, null, true, false)
        val u = s.rawUrl(e); u to s.headers(u)
    } else p.original to NasX.streamHeaders(p.original)
    val bytes = FnClient.http.newCall(Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()).execute().use { r ->
        if (!r.isSuccessful) error("读取原图失败（HTTP ${r.code}）"); r.body!!.bytes()
    }
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
    if (o.outWidth <= 0) error("这个格式暂不支持编辑")
    var s = 1; while (max(o.outWidth, o.outHeight) / s > 4096) s *= 2
    var b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s }) ?: error("解码失败")
    runCatching {
        val ori = android.media.ExifInterface(java.io.ByteArrayInputStream(bytes)).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)
        val deg = when (ori) { 6 -> 90; 3 -> 180; 8 -> 270; else -> 0 }
        if (deg != 0) b = rotate(b, deg)
    }
    return b
}

/** Writes the JPEG next to the original. Returns the new file name. */
private suspend fun save(p: Photo, out: Bitmap): String {
    val bos = java.io.ByteArrayOutputStream(); out.compress(Bitmap.CompressFormat.JPEG, 92, bos)
    val bytes = bos.toByteArray()
    val stem = p.fileName.substringBeforeLast('.').ifEmpty { "photo" }
    if (p.isCloud) {
        val s = CloudPhotos.source(p.source) ?: error("网盘账户已移除")
        val dir = p.cloudPath.substringBeforeLast('/', "").ifEmpty { "/" }
        val lib = Dav.lib(p.source)
        var name = "${stem}_edit.jpg"; var i = 2
        while (lib.photos.any { it.cloudPath == "${dir.trimEnd('/')}/$name" }) name = "${stem}_edit$i.jpg".also { i++ }
        s.upload(dir, name, bytes.size.toLong()) { java.io.ByteArrayInputStream(bytes) }
        runCatching { lib.addLocal(CloudEntry(name, "${dir.trimEnd('/')}/$name", false, bytes.size.toLong(), System.currentTimeMillis(), null, true, false)) }
        return name
    }
    // 1.0.10 飞牛 save-as — the original is never overwritten:
    //  * always a NEW name "<stem>_edit_yyyyMMddHHmmss.jpg" (unique per second) and Trim-Overwrite=0, so even a name clash cannot replace a file;
    //  * into the photo's own folder through the folder-view upload (same path the share-in upload uses for folders).
    // UNVERIFIED (no NAS reachable, no packet capture): p.path is showFilePath, assumed to be the real NAS path; the folder-view
    // upload writes ".<name>_<taskId>.jpg" (copied from the web upload worker) and we assume folder_view/upload/notice turns it
    // into the visible original_name server-side. No fnOS file-rename API is known in this codebase (NasX/FnFiles have none),
    // so if the server keeps the ".xxx_taskId" name there is nothing to call yet — needs a capture of the web 文件管理 rename.
    val folder = p.path.substringBeforeLast('/', "")
    val ts = java.text.SimpleDateFormat("yyyyMMddHHmmss", java.util.Locale.US).format(java.util.Date())
    val name = "${stem}_edit_$ts.jpg"
    require(name != p.fileName) { "新文件名和原图相同" }
    val target = if (folder.isNotEmpty()) UploadTarget.Folder(folder) else UploadTarget.Library()
    val res = NasX.upload(name, bytes.size.toLong(), "image/jpeg", { java.io.ByteArrayInputStream(bytes) }, target,
        // Folder: Trim-Overwrite=0. Library keeps 1 because NasX.upload uses overwrite==1 to pick the photo/upload/notice flow; the name is unique anyway.
        overwrite = if (target is UploadTarget.Folder) 0 else 1)
    com.hark.shiguang.Diag.log("EDIT", "fn save-as stored=${res.storedPath.substringAfterLast('/')} id=${res.photoId}")
    HomeState.dirty = true
    return name
}
