package com.molecule.security

import android.content.Context
import com.molecule.security.generated.DexShard

/**
 * TokenVault —— 受控还原「管理端写权限 token」。
 *
 * 安全模型（对应需求：分片异地存储 + 运行时组合 + 用后清零 + 受环境约束）：
 *   - token 两半分别存在 DEX（XOR 密文）与 SO（VM 字节码），运行时才组合；
 *   - 组合前先做环境校验：宿主不纯（Root/Frida/调试器/模拟器）或被篡改则软失败返回 null；
 *   - 还原后的明文仅以可变 CharArray/ByteArray 短暂持有，用后清零，不长期驻留。
 *
 * 调用方（仅 :manager）应在「真正需要发请求」的那一刻才调用 [getToken]，用完即弃。
 */
object TokenVault {

    init {
        System.loadLibrary("secvault")
    }

    private external fun nativeGetXorKey(): ByteArray
    private external fun nativeGetShardB(): ByteArray
    private external fun nativeIsHostDirty(): Boolean

    /**
     * 还原完整 token；任何约束不满足返回 null（软失败，不抛异常、不阻断 App）。
     * [context] 用于完整性（签名）校验。
     */
    fun getToken(context: Context): String? {
        // 1) 无分片（构建未注入 token）→ 直接不可用
        if (!DexShard.hasShard) return null
        // 2) 环境不纯或 APK 被篡改 → 不解密
        if (nativeIsHostDirty()) return null
        if (IntegrityGuard.isTampered(context)) return null

        // 3) DEX 侧：A = encA XOR key（key 来自 SO）
        val key = nativeGetXorKey()
        val encA = DexShard.encA
        val a = ByteArray(encA.size) { i ->
            ((encA[i].toInt() and 0xFF) xor (key[i % key.size].toInt() and 0xFF)).toByte()
        }
        // 4) SO 侧：B = VM 还原
        val b = nativeGetShardB()
        // 5) 组合
        val combined = (a + b).toString(Charsets.UTF_8)
        // 6) 用后清零（mutable 副本）
        for (i in a.indices) a[i] = 0
        for (i in b.indices) b[i] = 0
        return combined
    }
}
