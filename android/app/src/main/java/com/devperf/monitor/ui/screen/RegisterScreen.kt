package com.devperf.monitor.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.devperf.monitor.account.AccountManager
import com.devperf.monitor.ui.kit.GlassTextField
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 注册页：邮箱（真实验证码）+ 手机号 + 账号 + 密码 + 昵称，全字段必填。
 *
 * 验证码由后端真实 SMTP 发送（QQ 企业邮箱通道），60 秒内不可重发；
 * 全部校验通过后调 /api/register，成功即登录并进入主界面。
 */
@Composable
fun RegisterScreen(
    accountManager: AccountManager,
    onRegistered: () -> Unit,
    onBackToLogin: () -> Unit,
) {
    var email by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var account by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var nickname by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var messageError by remember { mutableStateOf(true) }
    var countdown by remember { mutableIntStateOf(0) }
    var sending by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // 验证码倒计时
    LaunchedEffect(countdown) {
        if (countdown > 0) {
            delay(1000)
            countdown -= 1
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("PulseGuard", style = MaterialTheme.typography.headlineLarge)
            Text(
                "创建新账号",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(4.dp))

            GlassTextField(
                value = email,
                onValueChange = { email = it },
                placeholder = "邮箱 *",
                label = "邮箱（用于接收验证码）",
                keyboardType = KeyboardType.Email,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlassTextField(
                    value = code,
                    onValueChange = { code = it },
                    placeholder = "验证码 *",
                    label = "邮箱验证码",
                    keyboardType = KeyboardType.Number,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = {
                        if (sending || countdown > 0) return@Button
                        scope.launch {
                            sending = true
                            val err = accountManager.sendCode(email)
                            sending = false
                            if (err == null) {
                                messageError = false
                                message = "验证码已发送，请查收邮箱（注意垃圾箱）"
                                countdown = 60
                            } else {
                                messageError = true
                                message = err
                            }
                        }
                    },
                    enabled = !sending && countdown <= 0 && email.isNotBlank(),
                    modifier = Modifier.padding(top = 22.dp),
                    colors = ButtonDefaults.buttonColors(),
                ) {
                    Text(
                        when {
                            sending -> "发送中…"
                            countdown > 0 -> "${countdown}s"
                            else -> "发送验证码"
                        },
                    )
                }
            }

            GlassTextField(
                value = phone,
                onValueChange = { if (it.length <= 11) phone = it.filter { c -> c.isDigit() } },
                placeholder = "手机号 *",
                label = "手机号",
                keyboardType = KeyboardType.Phone,
            )
            GlassTextField(
                value = account,
                onValueChange = { account = it },
                placeholder = "账号（登录用）*",
                label = "账号",
            )
            GlassTextField(
                value = password,
                onValueChange = { password = it },
                placeholder = "密码（至少 6 位）*",
                label = "密码",
                isPassword = true,
                keyboardType = KeyboardType.Password,
            )
            GlassTextField(
                value = nickname,
                onValueChange = { nickname = it },
                placeholder = "昵称 *",
                label = "昵称",
            )

            if (message.isNotEmpty()) {
                Text(
                    message,
                    color = if (messageError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Button(
                onClick = {
                    if (submitting) return@Button
                    scope.launch {
                        submitting = true
                        val err = accountManager.register(email, code, phone, account, password, nickname)
                        submitting = false
                        if (err == null) {
                            onRegistered()
                        } else {
                            messageError = true
                            message = err
                        }
                    }
                },
                enabled = !submitting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (submitting) "注册中…" else "注册并登录")
            }

            TextButton(onClick = onBackToLogin) {
                Text("已有账号？返回登录")
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "注册即表示同意：账号资料与使用数据将同步至管理端，用于账号服务与功能管控。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
