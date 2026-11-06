package com.hark.shiguang.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import kotlinx.coroutines.delay

/**
 * 全面审查 B7：下拉刷新（原来只有 WebDAV 照片页有）。影视、音乐页用它包住列表。
 * [onRefresh] 只负责发起后台增量扫描（扫描进度仍由页内的「正在更新」一行显示），圆圈转一下就收起，不挡列表。
 * [enabled] 为 false（没有文件夹 / 正在扫描）时下拉不触发。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PullRefreshBox(enabled: Boolean, onRefresh: () -> Unit, content: @Composable BoxScope.() -> Unit) {
    val canRefresh by rememberUpdatedState(enabled)
    val refresh by rememberUpdatedState(onRefresh)
    val ptr = rememberPullToRefreshState(enabled = { canRefresh })
    if (ptr.isRefreshing) {
        LaunchedEffect(Unit) { refresh(); delay(700); ptr.endRefresh() }
    }
    Box(Modifier.fillMaxSize().nestedScroll(ptr.nestedScrollConnection)) {
        content()
        PullToRefreshContainer(state = ptr, modifier = Modifier.align(Alignment.TopCenter))
    }
}
