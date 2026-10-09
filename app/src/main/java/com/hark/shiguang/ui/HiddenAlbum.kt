package com.hark.shiguang.ui

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.hark.shiguang.Hidden
import com.hark.shiguang.Nav
import com.hark.shiguang.Route

private fun Context.activity(): Activity? { var c: Context? = this; while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }; return null }

/**
 * Fingerprint / face with the screen lock as fallback. API 30+: one BiometricPrompt that also accepts the
 * device credential; 29: setDeviceCredentialAllowed; 26–28: the keyguard "confirm credential" screen.
 * A phone without any screen lock is let in (nothing to check against) — the caller shows a hint.
 */
@Composable
fun rememberDeviceAuth(onResult: (Boolean) -> Unit): () -> Unit {
    val ctx = LocalContext.current
    val cb by rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r -> cb(r.resultCode == Activity.RESULT_OK) }
    return remember(ctx) {
        {
            val km = ctx.getSystemService(KeyguardManager::class.java)
            val act = ctx.activity()
            if (km == null || !km.isDeviceSecure) { toast(ctx, "手机没有设置锁屏，隐藏相册不受保护"); cb(true) }
            else if (android.os.Build.VERSION.SDK_INT >= 29 && act != null) {
                runCatching {
                    val b = android.hardware.biometrics.BiometricPrompt.Builder(act).setTitle("打开隐藏相册").setSubtitle("验证指纹或锁屏密码")
                    if (android.os.Build.VERSION.SDK_INT >= 30) b.setAllowedAuthenticators(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK or android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                    else @Suppress("DEPRECATION") b.setDeviceCredentialAllowed(true)
                    b.build().authenticate(android.os.CancellationSignal(), act.mainExecutor, object : android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: android.hardware.biometrics.BiometricPrompt.AuthenticationResult?) { cb(true) }
                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) { cb(false) }
                    })
                }.onFailure { cb(false) }
            } else {
                @Suppress("DEPRECATION")
                val i = km.createConfirmDeviceCredentialIntent("打开隐藏相册", "验证锁屏密码")
                if (i != null) launcher.launch(i) else cb(true)
            }
        }
    }
}

/** Unlocks once per visit: leaving the screen locks it again. */
@Composable
fun HiddenAlbumScreen() {
    var unlocked by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val auth = rememberDeviceAuth { ok -> unlocked = ok; failed = !ok }
    LaunchedEffect(Unit) { auth() }
    Box(Modifier.fillMaxSize().background(C.Bg)) {
        val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 64.dp
        if (!unlocked) Box(Modifier.fillMaxSize().padding(top = top)) {
            StateMessage(LI.lock(), "隐藏相册已锁定", if (failed) "验证没有通过" else "验证后查看", "解锁") { auth() }
        } else {
            val photos = remember(Hidden.version) { Hidden.photos() }
            DisposableEffect(Unit) {
                SelExtra.actions = listOf(SelExtraAction(Icons.Rounded.Visibility, "取消隐藏") { l -> Hidden.hide(l, false); Selection.clear() })
                onDispose { SelExtra.actions = emptyList() }
            }
            if (photos.isEmpty()) Box(Modifier.fillMaxSize().padding(top = top)) {
                StateMessage(LI.lock(), "没有隐藏的照片", "在大图「⋯」或多选后点「隐藏」，照片就只出现在这里。")
            } else {
                val entries = remember(photos) { buildEntries(photos, true) }
                PhotoGrid(entries, adaptiveCols(4), false, onEnd = {}, onOpen = { Nav.push(Route.Viewer(photos, it)) }, top = top, bottom = 40.dp)
            }
        }
        ExtTop("隐藏相册", if (unlocked) "${Hidden.count()} 项 · 只保存在本机" else "")
        if (unlocked) SelectionBar(Modifier.align(Alignment.BottomCenter))
    }
}
