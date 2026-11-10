package com.liuzh.z8transfer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.shape.RoundedCornerShape
import com.liuzh.z8transfer.core.WifiEndpoint

@Composable
internal fun WifiConnectionDialog(enabled: Boolean, initial: WifiEndpoint,
    onSettings: () -> Unit, onConnect: (WifiEndpoint) -> Unit, onDismiss: () -> Unit) {
    var host by rememberSaveable { mutableStateOf(initial.host) }
    var port by rememberSaveable { mutableStateOf(initial.port.toString()) }
    val validHost = host.isBlank() || WifiEndpoint.isLocalIpv4(host.trim())
    val parsedPort = port.toIntOrNull()
    val validPort = parsedPort != null && parsedPort in 1..65535
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = RoundedCornerShape(24.dp), modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth(0.94f)) {
            Column(Modifier.fillMaxWidth().heightIn(max = 720.dp).padding(20.dp)) {
                Text("连接 Z8 Wi-Fi", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(16.dp))
                Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("1. 相机：网络菜单 → 连接至智能设备 → Wi-Fi 连接（AP 模式）→ 建立 Wi-Fi 连接。旧固件可能只显示「Wi-Fi 连接」。")
                Text("2. 手机：打开 Wi-Fi 设置，连接相机显示的热点并输入密码。若提示无法上网，选择保持连接，然后返回此 App。")
                OutlinedButton(onClick = onSettings, modifier = Modifier.fillMaxWidth()) { Text("打开手机 Wi-Fi 设置") }
                Text("3. 退出占用相机连接的 SnapBridge 等 App，点击下方连接。连接成功后即可选片、全屏预览和下载原文件。")
                HorizontalDivider()
                Text("高级设置（一般不用修改）", style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(host, { host = it }, label = { Text("相机 IPv4 地址") },
                    placeholder = { Text("留空：自动使用热点网关") }, singleLine = true, isError = !validHost,
                    supportingText = { Text(if (validHost) "自动连接失败时可填写相机地址，如 192.168.1.1" else "请输入局域网 IPv4 地址") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(port, { port = it }, label = { Text("端口") }, singleLine = true,
                    isError = !validPort, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                Text("本版优先支持相机热点直连；电脑连接/PIN 配对模式暂不支持。Wi-Fi 下的相机文件删除暂未开放。", style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss) { Text("返回") }
                    TextButton(onClick = { onConnect(WifiEndpoint(host.trim(), parsedPort!!)) }, enabled = enabled && validHost && validPort) {
                        Text("连接 Wi-Fi 相机")
                    }
                }
            }
        }
    }
}
