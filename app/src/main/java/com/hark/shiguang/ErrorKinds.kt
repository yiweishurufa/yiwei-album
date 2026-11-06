package com.hark.shiguang

/**
 * 1.1.0 统一错误提示：把各处抛出的原始错误文字归成几类，每类给一句说人话的标题、原因和一个处理按钮。
 * 纯逻辑（单元测试：ErrorKindsTest），界面在 ui/Components.kt 的 ErrorState。
 */
enum class ErrorKind(val title: String, val hint: String, val action: String) {
    NETWORK("连不上服务器", "检查手机网络，或确认 NAS / 网盘已开机、地址没写错。在外面时要用外网地址。", "重试"),
    AUTH("用户名或密码不对", "账号密码可能改过，或登录已过期。重新填写一次就好。", "修改账号"),
    CERT("证书不受信任", "服务器的 HTTPS 证书过期、自签名或被替换。自签名证书需先安装到手机，再在「设置 → 高级 → 自签名证书的 NAS 地址」里打开这个地址。", "打开证书设置"),
    SPACE("空间不足", "手机或服务器的存储快满了，清理一些空间后再试。", "清理缓存"),
    NOT_FOUND("找不到这个位置", "文件夹可能被移动、改名或删除了。", "重新选择"),
    OTHER("出了点问题", "可以重试一次；反复出现请在「设置 → 反馈与帮助」导出诊断日志发给开发者。", "重试");

    companion object {
        private val NET = listOf("无法解析服务器地址", "连接超时", "无法连接服务器", "网络错误", "网络连接失败", "连不上", "timeout", "timed out",
            "unknownhost", "unable to resolve", "connect failed", "connection refused", "network is unreachable", "failed to connect", "没连上局域网")
        private val AUTH_W = listOf("用户名或密码错误", "密码错误", "账号或密码", "登录已过期", "重新登录", "认证失败", "未授权", "无权限", "401", "unauthorized", "token 失效", "token失效")
        private val CERT_W = listOf("ssl", "证书", "certificate", "trust anchor", "handshake", "cert path")
        private val SPACE_W = listOf("空间不足", "no space left", "enospc", "disk full", "507", "insufficient storage", "存储已满")
        private val NF_W = listOf("路径不存在", "not found", "404", "不存在")

        /** Classifies a raw error message (null / blank → OTHER). Order matters: certificate before network ("SSL 连接失败"). */
        fun of(message: String?): ErrorKind {
            val m = message?.lowercase()?.trim().orEmpty()
            if (m.isEmpty()) return OTHER
            return when {
                CERT_W.any { m.contains(it) } -> CERT
                SPACE_W.any { m.contains(it) } -> SPACE
                AUTH_W.any { m.contains(it) } -> AUTH
                NET.any { m.contains(it) } -> NETWORK
                NF_W.any { m.contains(it) } -> NOT_FOUND
                else -> OTHER
            }
        }
    }
}

/**
 * B6：操作失败的 toast 只说一句中文，不再直接显示 timeout、Unable to resolve host 这类原文（原文另写进诊断日志）。
 * - 能归类的（网络 / 账号 / 证书 / 空间 / 找不到）→「删除失败：连不上服务器」
 * - 我们自己抛出的中文短句（如「网盘账户已移除」）→ 原样显示
 * - 其它（英文、异常类名、空）→「删除失败，可以重试一次」
 * [action] 是这次操作的失败说法，如「删除失败」；只写了「失败」时用「操作失败」。纯逻辑（单元测试：ErrorKindsTest）。
 */
object FriendlyError {
    private val CJK = Regex("[\\u4e00-\\u9fff]")
    private val TECH = Regex("(?i)exception|error:|\\bat [a-z]+\\.|http\\s?\\d{3}|java\\.|kotlin\\.|okhttp|null")

    fun message(raw: String?, action: String): String {
        val act = action.trim().ifEmpty { "操作失败" }.let { if (it == "失败") "操作失败" else it }
        val m = raw?.trim().orEmpty()
        val kind = ErrorKind.of(m)
        if (kind != ErrorKind.OTHER) return "$act：${kind.title}"
        if (m.isNotEmpty() && CJK.containsMatchIn(m) && m.length <= 40 && !TECH.containsMatchIn(m)) return m
        return "$act，可以重试一次"
    }
}

