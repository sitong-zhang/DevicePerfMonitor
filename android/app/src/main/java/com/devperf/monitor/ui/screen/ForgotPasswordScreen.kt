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
import com.devperf.monitor.ui.kit.GlassButton
import com.devperf.monitor.ui.kit.GlassTextField
import com.devperf.monitor.ui.kit.PasswordSavedDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 找回密码（1.0.46）：邮箱验证码 → 设置新密码（含确认密码）。
 *
 * 三步同页推进：
 * 1) 输入注册邮箱 → 发送验证码（后端真实 SMTP，免邮件模式下验证码自动填入）
 * 2) 输入 6 位邮箱验证码
 * 3) 设置新密码并确认 → 调 /api/reset_password → 成功后弹「请务必记好您的密码」→ 回登录页
 *
 * 所有按钮走 GlassButton（内部即上游 Kyant0/AndroidLiquidGlass catalog 的 LiquidButton）。
 */
@Composable
fun ForgotPasswordScreen(
    accountManager: AccountManager,
    onBackToLogin: () -> Unit,
) {
    var step by remember { mutableStateOf(0) }
    var email by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var messageError by remember { mutableStateOf(true) }
    var countdown by remember { mutableIntStateOf(0) }
    var sending by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    var showReminder by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

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
                "找回密码",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                when (step) {
                    0 -> "第 1 步 / 共 3 步：输入注册时使用的邮箱"
                    1 -> "第 2 步 / 共 3 步：输入邮箱验证码"
                    else -> "第 3 步 / 共 3 步：设置新密码"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(4.dp))

            when (step) {
                0 -> {
                    GlassTextField(
                        value = email,
                        onValueChange = { email = it },
                        placeholder = "注册邮箱 *",
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
                        GlassButton(
                            text = when {
                                sending -> "发送中…"
                                countdown > 0 -> "${countdown}s"
                                else -> "发送验证码"
                            },
                            onClick = {
                                if (sending || countdown > 0) return@GlassButton
                                scope.launch {
                                    sending = true
                                    val result = accountManager.sendCode(email)
                                    sending = false
                                    if (result.error == null) {
                                        messageError = false
                                        countdown = 60
                                        if (result.devCode != null) {
                                            code = result.devCode
                                            message = "验证码已自动填入：${result.devCode}"
                                        } else {
                                            message = "验证码已发送，请查收邮箱（注意垃圾箱）"
                                        }
                                    } else {
                                        messageError = true
                                        message = result.error
                                    }
                                }
                            },
                            enabled = !sending && countdown <= 0 && email.isNotBlank(),
                            modifier = Modifier.padding(top = 22.dp),
                            compact = true,
                        )
                    }
                }

                1 -> {
                    GlassTextField(
                        value = code,
                        onValueChange = { code = it },
                        placeholder = "验证码 *",
                        label = "邮箱验证码",
                        keyboardType = KeyboardType.Number,
                    )
                }

                else -> {
                    GlassTextField(
                        value = newPassword,
                        onValueChange = { newPassword = it },
                        placeholder = "新密码（至少 6 位）*",
                        label = "新密码",
                        isPassword = true,
                        keyboardType = KeyboardType.Password,
                    )
                    GlassTextField(
                        value = confirmPassword,
                        onValueChange = { confirmPassword = it },
                        placeholder = "请再次输入新密码 *",
                        label = "确认新密码",
                        isPassword = true,
                        keyboardType = KeyboardType.Password,
                    )
                }
            }

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

            GlassButton(
                text = if (submitting) "处理中…" else if (step == 2) "重设密码" else "下一步",
                onClick = {
                    if (submitting) return@GlassButton
                    when (step) {
                        0 -> {
                            if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()) {
                                messageError = true
                                message = "邮箱格式不正确"
                                return@GlassButton
                            }
                            if (code.isBlank()) {
                                messageError = true
                                message = "请先获取并填写邮箱验证码"
                                return@GlassButton
                            }
                            step = 1
                        }

                        1 -> {
                            if (code.trim().length != 6) {
                                messageError = true
                                message = "验证码为 6 位数字"
                                return@GlassButton
                            }
                            step = 2
                        }

                        else -> {
                            if (newPassword.length < 6) {
                                messageError = true
                                message = "新密码至少 6 位"
                                return@GlassButton
                            }
                            if (newPassword != confirmPassword) {
                                messageError = true
                                message = "两次输入的新密码不一致"
                                return@GlassButton
                            }
                            scope.launch {
                                submitting = true
                                val err = accountManager.resetPassword(email, code, newPassword)
                                submitting = false
                                if (err == null) {
                                    showReminder = true
                                } else {
                                    messageError = true
                                    message = err
                                }
                            }
                        }
                    }
                },
                enabled = !submitting,
                modifier = Modifier.fillMaxWidth(),
            )

            TextButton(onClick = onBackToLogin) {
                Text("返回登录")
            }
        }
    }

    PasswordSavedDialog(
        show = showReminder,
        account = email,
        onConfirm = {
            showReminder = false
            onBackToLogin()
        },
        onDismiss = { showReminder = false },
    )
}
