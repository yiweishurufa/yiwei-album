package com.hark.shiguang.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hark.shiguang.Analyzer
import com.hark.shiguang.DavLib
import com.hark.shiguang.FaceConfig
import com.hark.shiguang.FaceLib
import com.hark.shiguang.Faces
import com.hark.shiguang.ScanPolicy

/**
 * 用户 2026-10-23 反馈 #7：人脸识别不能再静默。放在「发现 → 人物」一栏下面，说清楚现在在干什么：
 *  - 出错：原因 + 「重试」；
 *  - 进行中：「人脸识别 已处理 x/y 张」（等待条件时加「· 充电时自动继续」）；
 *  - 没在跑但还有没识别的：「已处理 x/y 张 · 等待充电」+「立即识别」；暂停时说已暂停；
 *  - 都识别完但没有人物：识别了几张、找到几张脸、为什么没成人物（最少照片数）。
 * 已经有人物且一切正常时不显示。
 */
@Composable
fun FaceStatusLine(lib: DavLib, fl: FaceLib) {
    val ctx = LocalContext.current
    val imgs = lib.imagesCount()
    val pending = remember(fl.done, fl.running, lib.photos.size, lib.metaVersion, fl.scanned.size) { Faces.pending(lib, fl) }
    val handled = (imgs - pending).coerceIn(0, imgs)
    val faceCount = remember(fl.done, fl.running, fl.persons) { fl.faces.values.sumOf { it.size } }
    val progress = "已处理 $handled/$imgs 张"
    var action: Pair<String, () -> Unit>? = null
    var danger = false
    val text: String = when {
        fl.error.isNotEmpty() -> { danger = true; action = "重试" to { fl.error = ""; Faces.start(ctx, lib, true) }; fl.error }
        fl.running -> "人脸识别 " + (if (fl.total > 0) "${fl.done}/${fl.total} 张" else progress) + if (fl.status.isNotEmpty()) " · ${fl.status}" else ""
        pending > 0 && ScanPolicy.paused -> "人脸识别 $progress · 已暂停"
        pending > 0 && !Analyzer.canScan(ctx) -> {
            action = "立即识别" to { Faces.start(ctx, lib, true) }
            "人脸识别 $progress · " + waitingShort()
        }
        pending > 0 -> { action = "立即识别" to { Faces.start(ctx, lib, true) }; "人脸识别 $progress" }
        fl.persons.isNotEmpty() -> return
        fl.scanned.isEmpty() -> "识别后按人归类，长按人物可以命名或建相册。"
        faceCount == 0 -> "已识别 ${fl.scanned.size} 张，没有找到清晰的人脸。"
        else -> "已识别 ${fl.scanned.size} 张，找到 $faceCount 张脸，但还没有人出现在 ${FaceConfig.minPhotos} 张以上照片里，可以在识别设置里调低。"
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = if (danger) C.Danger else C.Sub, modifier = Modifier.weight(1f))
        action?.let { (label, go) ->
            Text(label, color = C.Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 8.dp).minimumInteractiveComponentSize().clip(RoundedCornerShape(8.dp))
                    .background(C.Surface2).clickable { go() }.padding(horizontal = 10.dp, vertical = 4.dp))
        }
    }
}

/** 「连上 Wi-Fi 并充电时自动继续」→ 卡片里更短的「等待 Wi-Fi 和充电」。 */
private fun waitingShort(): String = when {
    !ScanPolicy.allowCellular && !ScanPolicy.allowNoCharge -> "等待 Wi-Fi 和充电"
    !ScanPolicy.allowCellular -> "等待 Wi-Fi"
    !ScanPolicy.allowNoCharge -> "等待充电"
    else -> "稍后自动继续"
}
