package com.molecule.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

/**
 * IntegrityGuard —— APK 完整性校验（Anti-Tamper）。
 *
 * 比对当前安装包的签名证书 SHA-256 与发布证书指纹 [EXPECTED_SIGNATURE]。
 * 若 APK 被重打包 / 篡改，签名必然变化 → 判定为篡改，TokenVault 据此不解密。
 *
 * [EXPECTED_SIGNATURE] 是发布证书（v1.0.27 起的正式 keystore）指纹，以字节数组存放
 * （非字符串常量，降低可读性；且属公开证书指纹，泄露无安全影响）。
 */
object IntegrityGuard {

    // 发布证书 SHA-256: 57:9B:23:F7:65:67:F8:8A:9E:CE:A4:91:F7:01:3E:58:
    //                  D2:A0:9F:35:A3:4D:13:B4:C9:FA:64:49:5A:66:4E:D1
    private val EXPECTED_SIGNATURE = byteArrayOf(
        0x57, 0x9B.toByte(), 0x23, 0xF7.toByte(), 0x65, 0x67, 0xF8.toByte(), 0x8A.toByte(),
        0x9E.toByte(), 0xCE.toByte(), 0xA4.toByte(), 0x91.toByte(), 0xF7.toByte(), 0x01, 0x3E.toByte(), 0x58,
        0xD2.toByte(), 0xA0.toByte(), 0x9F.toByte(), 0x35, 0xA3.toByte(), 0x4D, 0x13, 0xB4.toByte(),
        0xC9.toByte(), 0xFA.toByte(), 0x64, 0x49, 0x5A, 0x66, 0x4E.toByte(), 0xD1.toByte(),
    )

    /** 计算当前 APK 签名证书 SHA-256；取首个签名。 */
    private fun currentSignatureSha256(context: Context): ByteArray? {
        return try {
            val pm = context.packageManager
            val pkg = context.packageName
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                @Suppress("DEPRECATION")
                PackageManager.GET_SIGNATURES
            }
            val info = pm.getPackageInfo(pkg, flags)
            val sigBytes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
            } else {
                @Suppress("DEPRECATION")
                info.signatures?.firstOrNull()?.toByteArray()
            }
            sigBytes?.let { MessageDigest.getInstance("SHA-256").digest(it) }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 是否被篡改：签名哈希与发布证书不一致、或无法读取签名时返回 true。
     * 返回 true 即「不可信」，TokenVault 不解密。
     */
    fun isTampered(context: Context): Boolean {
        val actual = currentSignatureSha256(context) ?: return true
        if (actual.size != EXPECTED_SIGNATURE.size) return true
        for (i in actual.indices) {
            if (actual[i] != EXPECTED_SIGNATURE[i]) return true
        }
        return false
    }
}
