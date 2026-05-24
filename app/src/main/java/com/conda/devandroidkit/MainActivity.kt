package com.conda.devandroidkit

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.view.InputDevice
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.conda.devandroidkit.ui.theme.DevAndroidKitTheme
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {

    private var targetMouseId by mutableStateOf(-1)
    private val logList = mutableStateListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Shizuku 权限监听
        Shizuku.addRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == 1 && grantResult == PackageManager.PERMISSION_GRANTED) {
                log("Shizuku 授权成功！")
            } else {
                log("Shizuku 授权失败！")
            }
        }

        setContent {
            DevAndroidKitTheme {
                val listState = rememberLazyListState()

                // 当日志更新时，自动滚动到最底部
                LaunchedEffect(logList.size) {
                    if (logList.isNotEmpty()) {
                        listState.animateScrollToItem(logList.size - 1)
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    Button(
                        onClick = { enumerateDevices() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("1. 枚举 InputDevice (步骤7-9)")
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = { disableMouseViaShizuku() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("2. Shizuku 禁用鼠标 (步骤10)", color = Color.White)
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = { enableMouseViaShizuku() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("3. Shizuku 恢复鼠标")
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "设备状态与事件日志:",
                        fontSize = 14.sp,
                        color = Color.Gray
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // 日志输出区域
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .background(Color(0xFFF0F0F0))
                            .padding(8.dp)
                    ) {
                        items(logList) { logItem ->
                            Text(
                                text = logItem,
                                fontSize = 11.sp,
                                color = Color.Black,
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    // ================= 实验一：枚举设备并识别 Source =================
    private fun enumerateDevices() {
        log("--- 开始枚举 InputDevice ---")
        val deviceIds = InputDevice.getDeviceIds()
        targetMouseId = -1

        for (id in deviceIds) {
            val device = InputDevice.getDevice(id) ?: continue
            val isMouse = (device.sources and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE
            val isTouchpad = (device.sources and InputDevice.SOURCE_TOUCHPAD) == InputDevice.SOURCE_TOUCHPAD

            log("ID: $id | Name: ${device.name}")
            log("Descriptor: ${device.descriptor}")
            log("Source: 0x${Integer.toHexString(device.sources)} (Mouse:$isMouse, Touchpad:$isTouchpad)")

            if (isMouse && !device.isVirtual) {
                targetMouseId = id
                log(">>> 发现目标物理鼠标，ID已记录: $targetMouseId <<<")
            }
            log("-")
        }
    }

    // ================= 实验二：通过 Shizuku 调用 IInputManager 禁用设备 =================
    private fun disableMouseViaShizuku() {
        if (!checkShizuku()) return
        if (targetMouseId == -1) {
            log("错误：没有找到目标物理鼠标 ID，请先点击枚举。")
            return
        }
        invokeInputManagerDisable(targetMouseId, true)
    }

    private fun enableMouseViaShizuku() {
        if (!checkShizuku()) return
        if (targetMouseId == -1) return
        invokeInputManagerDisable(targetMouseId, false)
    }

    @SuppressLint("DiscouragedPrivateApi")
    private fun invokeInputManagerDisable(deviceId: Int, disable: Boolean) {
        try {
            log("尝试通过 Shizuku ${if (disable) "禁用" else "启用"} 设备 ID: $deviceId ...")

            // 获取系统级 InputService Binder
            val binder = SystemServiceHelper.getSystemService(Context.INPUT_SERVICE)
            val shizukuBinder = ShizukuBinderWrapper(binder)

            // 反射获取 IInputManager$Stub
            val iimStubClass = Class.forName("android.hardware.input.IInputManager\$Stub")
            val asInterfaceMethod = iimStubClass.getMethod("asInterface", IBinder::class.java)
            val iInputManager = asInterfaceMethod.invoke(null, shizukuBinder)

            // 反射调用隐藏的方法
            val iimClass = Class.forName("android.hardware.input.IInputManager")

            if (disable) {
                val disableMethod = iimClass.getMethod("disableInputDevice", Int::class.javaPrimitiveType)
                disableMethod.invoke(iInputManager, deviceId)
                log("✅ 禁用指令已发送。请测试：1) 屏幕触摸是否恢复；2) 滑动鼠标时下方是否有事件打印。")
            } else {
                val enableMethod = iimClass.getMethod("enableInputDevice", Int::class.javaPrimitiveType)
                enableMethod.invoke(iInputManager, deviceId)
                log("✅ 恢复指令已发送。")
            }

        } catch (e: Exception) {
            log("❌ 反射调用失败: ${e.message}")
            e.printStackTrace()
        }
    }

    // ================= 实验三：拦截底层事件 =================
    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if ((ev.source and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE) {
            log("👉 收到鼠标事件: Action=${ev.actionMasked}, X=${ev.x}, Y=${ev.y}")
        }
        return super.dispatchGenericMotionEvent(ev)
    }

    // ================= 工具方法 =================
    private fun checkShizuku(): Boolean {
        if (!Shizuku.pingBinder()) {
            log("Shizuku 未运行，请先启动 Shizuku Manager！")
            return false
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Shizuku.requestPermission(1)
            return false
        }
        return true
    }

    private fun log(msg: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        logList.add("[$time] $msg")
    }
}