package com.devperf.monitor.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import kotlinx.coroutines.launch

/**
 * 登录页：未登录时作为 App 入口门禁。
 * 走服务端账号系统（账号 + 密码），登录态（token）持久化于本机；
 * 注册入口跳转 [RegisterScreen]（邮箱真实验证码 + 全字段必填）。
 */
@Composable
fun LoginScreen(
    accountManager: AccountManager,
    onOpenRegister: () -> Unit = {},
) {
    var account by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("PulseGuard", style = MaterialTheme.typography.headlineLarge)
            Text(
                "设备性能监测 · 请登录后使用",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(8.dp))

            GlassTextField(
                value = account,
                onValueChange = { account = it },
                placeholder = "账号",
                label = "账号",
            )
            GlassTextField(
                value = password,
                onValueChange = { password = it },
                placeholder = "密码",
                label = "密码",
                isPassword = true,
                keyboardType = KeyboardType.Password,
            )

            if (message.isNotEmpty()) {
                Text(
                    message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Button(
                onClick = {
                    if (submitting) return@Button
                    scope.launch {
                        submitting = true
                        val err = accountManager.login(account, password)
                        submitting = false
                        message = err ?: ""
                    }
                },
                enabled = !submitting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (submitting) "登录中…" else "登录")
            }

            TextButton(onClick = onOpenRegister) {
                Text("没有账号？注册一个")
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "账号资料用于登录与数据同步，使用数据会同步至管理端。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
