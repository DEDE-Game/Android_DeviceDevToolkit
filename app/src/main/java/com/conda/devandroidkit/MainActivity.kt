package com.conda.devandroidkit

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.conda.devandroidkit.ui.theme.DevAndroidKitTheme
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.io.BufferedReader
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.*

private const val TAG = "InputDiagnostics"

class MainActivity : ComponentActivity() {

    private var targetMouseId by mutableStateOf(-1)
    private var targetMouseName by mutableStateOf("")
    private val logList = mutableStateListOf<String>()

    private var isShizukuActive by mutableStateOf(false)
    private var isPointerCaptured by mutableStateOf(false)
    private var grabProcess: Process? = null

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        isShizukuActive = true
        log("🟢 Shizuku Binder 已成功连接！")
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        isShizukuActive = false
        log("🔴 Shizuku Binder 连接已断开！")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 自动解锁系统隐藏 API 限制
        try {
            bypassHiddenApiRestrictions()
        } catch (e: Exception) {}

        try {
            Shizuku.addBinderReceivedListener(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            isShizukuActive = Shizuku.pingBinder()
        } catch (t: Throwable) {
            t.printStackTrace()
        }

        Shizuku.addRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == 1) {
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    log("🟢 Shizuku 授权成功！物理通道已全部打通。")
                } else {
                    log("❌ Shizuku 授权被拒绝！")
                }
            }
        }

        // 【焦点与坐标轴终极修复】：配置视图使其具备绝对焦点抢占权
        window.decorView.isFocusable = true
        window.decorView.isFocusableInTouchMode = true

        // 注册指针捕获事件监听器
        window.decorView.setOnCapturedPointerListener { _, ev ->
            // 【核心修复】：必须使用 AXIS_X / AXIS_Y 提取指针捕获模式下的相对位移增量！
            val dx = ev.getAxisValue(MotionEvent.AXIS_X)
            val dy = ev.getAxisValue(MotionEvent.AXIS_Y)
            val scrollY = ev.getAxisValue(MotionEvent.AXIS_VSCROLL) // 滚轮数据

            // 特征过滤：只有当鼠标确实发生位移或滚轮滚动时，才打印输出，防止静止空帧刷屏
            if (dx != 0f || dy != 0f || scrollY != 0f) {
                log("👉 [PointerCapture 物理回调] 相对位移 ➔ DX: [${"%.2f".format(dx)}px] , DY: [${"%.2f".format(dy)}px] | 滚轮: [${"%.1f".format(scrollY)}]")
            }
            true
        }

        setContent {
            DevAndroidKitTheme {
                val listState = rememberLazyListState()
                val context = LocalContext.current

                LaunchedEffect(logList.size) {
                    if (logList.isNotEmpty()) {
                        listState.animateScrollToItem(logList.size - 1)
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp)
                ) {
                    // 状态总线
                    Text(
                        text = "Shizuku: ${if (isShizukuActive) "🟢" else "🔴"} | 指针捕获: ${if (isPointerCaptured) "🟢 已开启" else "🔴 未开启"}",
                        fontSize = 14.sp,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )

                    Button(
                        onClick = { copyLogsToClipboard(context) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF009688)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("📋 一键复制全部实验与诊断日志", fontSize = 12.sp)
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // ================= 模块 1：系统指针捕获 (免Root终极方案) =================
                    Text("模块一: Pointer Capture 独占绕过 (免Root)", fontSize = 12.sp, color = Color.Gray)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(
                            onClick = { requestPointerGrab() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5722)),
                            modifier = Modifier.weight(1.3f)
                        ) {
                            Text("开启指针捕获", fontSize = 10.sp, color = Color.White)
                        }
                        Button(
                            onClick = { releasePointerGrab() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("释放捕获", fontSize = 10.sp, color = Color.White)
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // ================= 模块 2：物理设备霸占 (EVIOCGRAB 内核绕过) =================
                    Text("模块二: Linux Evdev 物理霸占 (EVIOCGRAB)", fontSize = 12.sp, color = Color.Gray)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(
                            onClick = { enumerateDevices() },
                            modifier = Modifier.weight(1.1f)
                        ) {
                            Text("1. 枚举并锁定鼠标", fontSize = 10.sp)
                        }
                        Button(
                            onClick = { startPhysicalGrab() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                            modifier = Modifier.weight(1.1f)
                        ) {
                            Text("2. 启动物理霸占", fontSize = 10.sp, color = Color.White)
                        }
                        Button(
                            onClick = { stopPhysicalGrab() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                            modifier = Modifier.weight(0.9f)
                        ) {
                            Text("3. 释放霸占", fontSize = 10.sp, color = Color.White)
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // ================= 模块 3：服务层干预 (Shizuku Binder) =================
                    Text("模块三: IInputManager 提权干预 (Shizuku)", fontSize = 12.sp, color = Color.Gray)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(
                            onClick = { toggleMouseInputDevice(true) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Shizuku 禁用鼠标", fontSize = 10.sp, color = Color.White)
                        }
                        Button(
                            onClick = { toggleMouseInputDevice(false) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Shizuku 恢复鼠标", fontSize = 10.sp, color = Color.White)
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // ================= 模块 4：全局指针样式修改 (Hiding Cursor) =================
                    Text("模块四: 全局指针样式隐形 (Hiding Cursor)", fontSize = 12.sp, color = Color.Gray)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(
                            onClick = { togglePointerVisibility(false) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE91E63)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("临时隐藏光标 (NULL)", fontSize = 10.sp, color = Color.White)
                        }
                        Button(
                            onClick = { togglePointerVisibility(true) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2196F3)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("恢复光标样式", fontSize = 10.sp, color = Color.White)
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // ================= 模块 5：系统级安全诊断与审计 =================
                    Text("模块五: 系统安全与性能诊断审计区", fontSize = 12.sp, color = Color.Gray)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(
                            onClick = { checkSELinuxDenials() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800)),
                            modifier = Modifier.weight(1.3f)
                        ) {
                            Text("诊断：检索 SELinux 拦截", fontSize = 10.sp, color = Color.White)
                        }
                        Button(
                            onClick = { runDumpsysInput() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF673AB7)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("运行 dumpsys 诊断", fontSize = 10.sp, color = Color.White)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = "物理诊断控制台深度输出日志:",
                        fontSize = 12.sp,
                        color = Color.Gray
                    )

                    Spacer(modifier = Modifier.height(4.dp))

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
                                modifier = Modifier.padding(vertical = 1.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    private fun bypassHiddenApiRestrictions() {
        try {
            val getDeclaredMethod = Class::class.java.getDeclaredMethod(
                "getDeclaredMethod",
                String::class.java,
                arrayOf<Class<*>>().javaClass
            )
            val vmRuntimeClass = Class.forName("dalvik.system.VMRuntime")
            val getRuntime = getDeclaredMethod.invoke(vmRuntimeClass, "getRuntime", null) as java.lang.reflect.Method
            val vmRuntime = getRuntime.invoke(null)
            val setHiddenApiExemptions = getDeclaredMethod.invoke(
                vmRuntimeClass,
                "setHiddenApiExemptions",
                arrayOf(arrayOf<String>().javaClass)
            ) as java.lang.reflect.Method
            setHiddenApiExemptions.invoke(vmRuntime, arrayOf(arrayOf("L")))
        } catch (e: Throwable) {}
    }

    override fun onResume() {
        super.onResume()
        isShizukuActive = Shizuku.pingBinder()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPhysicalGrab()
        releasePointerGrab()
        try {
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
        } catch (t: Throwable) {
            t.printStackTrace()
        }
    }

    private fun enumerateDevices() {
        log("--- 开始枚举 InputDevice ---")
        val deviceIds = InputDevice.getDeviceIds()
        targetMouseId = -1
        targetMouseName = ""

        for (id in deviceIds) {
            val device = InputDevice.getDevice(id) ?: continue
            val isMouse = (device.sources and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE
            val isTouchpad = (device.sources and InputDevice.SOURCE_TOUCHPAD) == InputDevice.SOURCE_TOUCHPAD

            log("ID: $id | Name: ${device.name}")
            log("Source: 0x${Integer.toHexString(device.sources)} (Mouse:$isMouse, Touchpad:$isTouchpad)")

            if (isMouse && !device.isVirtual) {
                targetMouseId = id
                targetMouseName = device.name
                log(">>> 发现目标物理鼠标，ID已记录: $targetMouseId | 名字: $targetMouseName <<<")
            }
        }
    }

    private fun autoDetectMousePathByDumpsys(targetName: String): String? {
        try {
            log("📡 正在调用系统级 dumpsys进行物理路径转译...")
            val dumpsysContent = executeShell("dumpsys input")
            if (dumpsysContent.trim().isEmpty() || dumpsysContent.startsWith("Error:")) {
                log("⚠️ 诊断接口回执为空，无法进行节点转译。")
                return null
            }

            val reader = BufferedReader(StringReader(dumpsysContent))
            var line: String?
            var isInsideTargetSection = false
            var matchedPath: String? = null

            while (reader.readLine().also { line = it } != null) {
                val trimmed = line.orEmpty().trim()

                if (trimmed.matches(Regex("^\\d+:.*")) && trimmed.contains(targetName, ignoreCase = true)) {
                    isInsideTargetSection = true
                    continue
                }

                if (isInsideTargetSection) {
                    if (trimmed.startsWith("Path:")) {
                        val path = trimmed.substringAfter("Path:").trim()
                        if (path.startsWith("/dev/input/")) {
                            matchedPath = path
                            break
                        }
                    }
                    if (trimmed.matches(Regex("^\\d+:.*")) || trimmed.isEmpty()) {
                        isInsideTargetSection = false
                    }
                }
            }
            reader.close()
            return matchedPath
        } catch (e: Exception) {
            log("⚠️ Dumpsys 匹配物理路径发生异常: ${e.message}")
        }
        return null
    }

    private fun startPhysicalGrab() {
        if (!checkShizuku()) return

        stopPhysicalGrab()

        if (targetMouseId == -1 || targetMouseName.isEmpty()) {
            log("❌ 错误：请先执行 [1. 枚举 InputDevice 并锁定物理鼠标]！")
            return
        }

        val mousePath = autoDetectMousePathByDumpsys(targetMouseName)
        if (mousePath == null) {
            log("❌ 无法为 [$targetMouseName] 定位对应的 /dev/input 节点。")
            return
        }
        log("🔍 锁定物理鼠标节点: $mousePath (对应系统 ID: $targetMouseId)")

        Thread {
            try {
                val appInfo = this.applicationInfo
                val baseApk = appInfo.sourceDir
                val splits = appInfo.splitSourceDirs ?: emptyArray<String>()
                val classPath = (listOf(baseApk) + splits.toList()).joinToString(":")

                val className = "com.conda.devandroidkit.EvdevGrabber"
                val cmd = "export CLASSPATH='$classPath' && exec app_process / '$className' '$mousePath' 2>&1"

                val shizukuClass = Class.forName("rikka.shizuku.Shizuku")
                val newProcessMethod = shizukuClass.getDeclaredMethods().find {
                    it.name == "newProcess" && it.parameterCount == 3
                } ?: throw Exception("未在 SDK 中检索到 newProcess 方法")
                newProcessMethod.isAccessible = true

                log("🔌 正在拉起 evdev 霸占进程...")
                val process = newProcessMethod.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
                grabProcess = process

                val reader = java.io.BufferedReader(java.io.InputStreamReader(process.inputStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val safeLine = line.orEmpty()

                    if (safeLine.startsWith("EVENT:")) {
                        val parts = safeLine.substringAfter("EVENT:").split(",")
                        if (parts.size == 3) {
                            val type = parts[0]
                            val code = parts[1]
                            val value = parts[2]
                            runOnUiThread {
                                log("👉 [内核数据流] Type: $type, Code: $code, Value: $value")
                            }
                        }
                    } else {
                        runOnUiThread { log(safeLine) }
                    }
                }

                process.waitFor()
            } catch (e: Exception) {
                runOnUiThread { log("❌ 独占通道启动异常: ${e.message}") }
            }
        }.start()
    }

    private fun stopPhysicalGrab() {
        grabProcess?.let {
            log("🔌 正在释放物理设备独占...")
            try {
                it.destroy()
                log("✅ 设备独占已安全解除。")
            } catch (e: Exception) {}
        }
        grabProcess = null
    }

    private fun toggleMouseInputDevice(disable: Boolean) {
        if (!checkShizuku()) return
        if (targetMouseId == -1) {
            log("❌ 错误：请先点击 [1. 枚举 InputDevice 并锁定物理鼠标]！")
            return
        }

        try {
            val binder = SystemServiceHelper.getSystemService("input") ?: return
            val shizukuBinder = ShizukuBinderWrapper(binder)

            val iimStubClass = Class.forName("android.hardware.input.IInputManager\$Stub")
            val asInterfaceMethod = iimStubClass.getMethod("asInterface", IBinder::class.java)
            val iInputManager = asInterfaceMethod.invoke(null, shizukuBinder)

            val iimClass = Class.forName("android.hardware.input.IInputManager")

            if (disable) {
                log("🔌 正在尝试禁用物理设备 (ID: $targetMouseId)...")
                val disableMethod = iimClass.getMethod("disableInputDevice", Int::class.javaPrimitiveType)
                disableMethod.invoke(iInputManager, targetMouseId)
                log("✅ [禁用指令发送成功！]")
            } else {
                log("🔌 正在尝试启用并恢复物理设备 (ID: $targetMouseId)...")
                val enableMethod = iimClass.getMethod("enableInputDevice", Int::class.javaPrimitiveType)
                enableMethod.invoke(iInputManager, targetMouseId)
                log("✅ [恢复指令发送成功！]")
            }

        } catch (e: java.lang.reflect.InvocationTargetException) {
            val rootCause = e.targetException ?: e.cause ?: e
            log("❌ 目标内部执行失败: ${rootCause.javaClass.simpleName} -> ${rootCause.message}")
            val sw = java.io.StringWriter()
            rootCause.printStackTrace(java.io.PrintWriter(sw))
            log("内部系统崩溃堆栈:\n$sw")
        } catch (e: Exception) {
            log("❌ 反射操作失败: ${e.javaClass.simpleName} -> ${e.message}")
        }
    }

    private fun togglePointerVisibility(visible: Boolean) {
        if (!checkShizuku()) return
        log("🔌 正在尝试${if(visible) "恢复" else "隐藏"}系统鼠标光标...")
        Thread {
            try {
                val binder = SystemServiceHelper.getSystemService("input") ?: return@Thread
                val shizukuBinder = ShizukuBinderWrapper(binder)

                val iimStubClass = Class.forName("android.hardware.input.IInputManager\$Stub")
                val asInterfaceMethod = iimStubClass.getMethod("asInterface", IBinder::class.java)
                val iInputManager = asInterfaceMethod.invoke(null, shizukuBinder)

                val iimClass = Class.forName("android.hardware.input.IInputManager")
                val setPointerIconTypeMethod = iimClass.getMethod("setPointerIconType", Int::class.javaPrimitiveType)

                val iconType = if (visible) 1000 else 0
                setPointerIconTypeMethod.invoke(iInputManager, iconType)

                runOnUiThread { log("✅ 光标样式设置成功！光标目前应已${if(visible) "显现" else "彻底隐形"}。") }
            } catch (e: Exception) {
                runOnUiThread { log("❌ 设置光标样式失败: ${e.javaClass.simpleName} -> ${e.message}") }
            }
        }.start()
    }

    /**
     * 【指针独占抓取：焦点锁定机制】
     * 1. 强制将 window.decorView 设为 Focusable
     * 2. 主动夺取焦点（requestFocus），防止系统将捕获事件静默挂起
     */
    private fun requestPointerGrab() {
        log("🔌 正在向系统申请 Pointer Capture 指针捕获...")

        // 【关键修复 1】：必须在请求捕获前强行抢占窗口焦点，否则系统不会路由高精度数据
        window.decorView.requestFocus()

        window.decorView.requestPointerCapture()
        isPointerCaptured = true
        log("🟢 指针已成功捕获！系统光标已隐形并锁死。请在桌面上划动鼠标：")
    }

    private fun releasePointerGrab() {
        log("🔌 正在释放 Pointer Capture 捕获...")
        window.decorView.releasePointerCapture()
        isPointerCaptured = false
        log("✅ 捕获已安全解除，系统鼠标重新复原。")
    }

    private fun checkSELinuxDenials() {
        if (!checkShizuku()) return
        log("📡 正在检索系统 AVC 审计日志...")
        Thread {
            try {
                val logcatOutput = executeShell("logcat -d -t 500 | grep -i -E 'avc|denied'")
                runOnUiThread {
                    log("============================= SELINUX AUDIT =============================")
                    if (logcatOutput.trim().isEmpty()) {
                        log("🟢 未在最近日志中发现 SELinux 拦截记录。")
                    } else if (logcatOutput.startsWith("Error:")) {
                        log("⚠️ 审计读取未成功，原因: $logcatOutput")
                    } else {
                        log(logcatOutput)
                    }
                    log("=========================================================================")
                }
            } catch (e: Exception) {
                runOnUiThread { log("❌ AVC 审计抓取失败: ${e.message}") }
            }
        }.start()
    }

    private fun runDumpsysInput() {
        if (!checkShizuku()) return
        log("📡 正在抓取系统级 dumpsys input 分析报文...")
        Thread {
            try {
                val output = executeShell("dumpsys input")
                runOnUiThread {
                    log("============================= DUMPSYS INPUT =============================")
                    if (output.startsWith("Error:")) {
                        log("⚠️ 诊断读取未成功，原因: $output")
                    } else {
                        log(output)
                    }
                    log("=========================================================================")
                }
            } catch (e: Exception) {
                runOnUiThread { log("❌ 诊断失败: ${e.message}") }
            }
        }.start()
    }

    private fun executeShell(cmd: String): String {
        val result = java.lang.StringBuilder()
        try {
            val shizukuClass = Class.forName("rikka.shizuku.Shizuku")
            val newProcessMethod = shizukuClass.getDeclaredMethod(
                "newProcess",
                arrayOf<String>().javaClass,
                arrayOf<String>().javaClass,
                String::class.java
            )
            newProcessMethod.isAccessible = true

            val process = newProcessMethod.invoke(
                null,
                arrayOf("sh", "-c", cmd),
                null,
                null
            ) as Process

            val reader = java.io.BufferedReader(java.io.InputStreamReader(process.inputStream))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                result.append(line).append("\n")
            }
            process.waitFor()
        } catch (e: java.lang.reflect.InvocationTargetException) {
            val root = e.targetException ?: e.cause ?: e
            result.append("Error (反射目标崩溃): ").append(root.javaClass.simpleName).append(" -> ").append(root.message)
        } catch (e: Exception) {
            result.append("Error (普通调用失败): ").append(e.javaClass.simpleName).append(" -> ").append(e.message)
        }
        return result.toString()
    }

    private fun checkShizuku(): Boolean {
        if (!isShizukuActive) {
            log("Shizuku 未就绪，请确保手机上的 Shizuku Manager 正在运行！")
            return false
        }
        try {
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                log("🔑 正在向系统发起 Shizuku 授权请求，请在手机弹出的授权窗口中选择“允许”...")
                Shizuku.requestPermission(1)
                return false
            }
        } catch (e: Exception) {
            log("❌ 检查权限时发生异常: ${e.message}")
            return false
        }
        return true
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (!isPointerCaptured && (ev.source and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE) {
            log("👉 [普通模式] 收到鼠标事件: X=${ev.x}, Y=${ev.y}")
        }
        return super.dispatchGenericMotionEvent(ev)
    }

    private fun copyLogsToClipboard(context: Context) {
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val combinedLogs = logList.joinToString("\n")
            val clip = ClipData.newPlainText("InputDiagnosticsLogs", combinedLogs)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(context, "📋 日志已复制，请直接粘贴发送！", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "❌ 复制失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun log(msg: String) {
        Log.d(TAG, msg)
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        logList.add("[$time] $msg")
    }
}

/**
 * 【免系统限制 Linux Evdev 霸占器】
 */
object EvdevGrabber {
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.isEmpty()) return
        val path = args[0]
        val stdout = System.out

        try {
            var fd: java.io.FileDescriptor? = null

            try {
                fd = android.system.Os.open(path, android.system.OsConstants.O_RDWR, 0)
            } catch (e: Throwable) {
                System.err.println("❌ 物理节点打开失败 (O_RDWR): ${e.javaClass.simpleName} -> ${e.message}")
                try {
                    fd = android.system.Os.open(path, android.system.OsConstants.O_RDONLY, 0)
                    System.err.println("⚠️ 降级警告: 物理节点目前仅能以 O_RDONLY 只读模式打开，EVIOCGRAB 独占操作有极高概率被内核拒绝！")
                } catch (e2: Throwable) {
                    System.err.println("❌ 降级失败: 物理节点无法在 shell 权限下被打开: ${e2.javaClass.simpleName} -> ${e2.message}")
                    return
                }
            }

            if (fd == null) return

            val grabIoctlCmd = 0x40044590.toInt()
            var grabSuccess = false

            // 方案 DirectInt
            try {
                val ioctlMethod = android.system.Os::class.java.getMethod(
                    "ioctlInt",
                    java.io.FileDescriptor::class.java,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType
                )
                val res = ioctlMethod.invoke(null, fd, grabIoctlCmd, 1) as Int
                if (res >= 0) {
                    grabSuccess = true
                    System.err.println("🟢 DirectInt 方案 ioctlInt 执行完毕，返回值: $res")
                }
            } catch (t: Throwable) {
                val root = t.cause ?: t
                System.err.println("⚠️ DirectInt 方案尝试失败: ${root.javaClass.simpleName} -> ${root.message}")
                logErrno(root)
            }

            // 方案 A: 尝试 Int32Ref (Android 13+) 作为 fallback
            if (!grabSuccess) {
                try {
                    val int32RefClass = Class.forName("android.system.Int32Ref")
                    val constructor = int32RefClass.getConstructor(Int::class.javaPrimitiveType)
                    val grabArg = constructor.newInstance(1)
                    val ioctlMethod = android.system.Os::class.java.getMethod(
                        "ioctlInt",
                        java.io.FileDescriptor::class.java,
                        Int::class.javaPrimitiveType,
                        int32RefClass
                    )
                    val res = ioctlMethod.invoke(null, fd, grabIoctlCmd, grabArg) as Int
                    if (res >= 0) {
                        grabSuccess = true
                        System.err.println("🟢 A 方案 ioctlInt 执行完毕，返回值: $res")
                    }
                } catch (t: Throwable) {
                    val root = t.cause ?: t
                    System.err.println("⚠️ A 方案尝试失败: ${root.javaClass.simpleName} -> ${root.message}")
                    logErrno(root)
                }
            }

            // 方案 B: 尝试 MutableInt (Android 12)
            if (!grabSuccess) {
                try {
                    val mutClass = Class.forName("android.util.MutableInt")
                    val constructor = mutClass.getConstructor(Int::class.javaPrimitiveType)
                    val grabArg = constructor.newInstance(1)
                    val ioctlMethod = android.system.Os::class.java.getMethod(
                        "ioctlInt",
                        java.io.FileDescriptor::class.java,
                        Int::class.javaPrimitiveType,
                        mutClass
                    )
                    val res = ioctlMethod.invoke(null, fd, grabIoctlCmd, grabArg) as Int
                    if (res >= 0) {
                        grabSuccess = true
                        System.err.println("🟢 B 方案 ioctlInt 执行完毕，返回值: $res")
                    }
                } catch (t: Throwable) {
                    val root = t.cause ?: t
                    System.err.println("⚠️ B 方案尝试失败: ${root.javaClass.simpleName} -> ${root.message}")
                    logErrno(root)
                }
            }

            // 方案 C: 尝试 Libcore (Android 9/10/11)
            if (!grabSuccess) {
                try {
                    val libcoreClass = Class.forName("libcore.io.Libcore")
                    val osField = libcoreClass.getDeclaredField("os")
                    osField.isAccessible = true
                    val osInstance = osField.get(null)
                    val mutClass = Class.forName("android.util.MutableInt")
                    val constructor = mutClass.getConstructor(Int::class.javaPrimitiveType)
                    val grabArg = constructor.newInstance(1)
                    val ioctlMethod = osInstance.javaClass.getMethod(
                        "ioctlInt",
                        java.io.FileDescriptor::class.java,
                        Int::class.javaPrimitiveType,
                        mutClass
                    )
                    val res = ioctlMethod.invoke(osInstance, fd, grabIoctlCmd, grabArg) as Int
                    if (res >= 0) {
                        grabSuccess = true
                        System.err.println("🟢 C 方案 ioctlInt 执行完毕，返回值: $res")
                    }
                } catch (t: Throwable) {
                    val root = t.cause ?: t
                    System.err.println("⚠️ C 方案尝试失败: ${root.javaClass.simpleName} -> ${root.message}")
                    logErrno(root)
                }
            }

            if (grabSuccess) {
                System.err.println("🟢 EVIOCGRAB_SUCCESS: 内核已成功将独占控制权赋予该进程！")
            } else {
                System.err.println("❌ EVIOCGRAB_FAILED: 所有 ioctl 反射调用均被内核拒绝或报错。")
            }

            val is64Bit = System.getProperty("os.arch")?.contains("64") == true
            val structSize = if (is64Bit) 24 else 16
            val offset = if (is64Bit) 16 else 8
            val buffer = ByteArray(structSize)

            while (true) {
                var bytesRead = 0
                while (bytesRead < structSize) {
                    val r = android.system.Os.read(fd, buffer, bytesRead, structSize - bytesRead)
                    if (r <= 0) break
                    bytesRead += r
                }
                if (bytesRead < structSize) break

                val bb = java.nio.ByteBuffer.wrap(buffer).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                bb.position(offset)
                val type = bb.short.toInt() and 0xFFFF
                val code = bb.short.toInt() and 0xFFFF
                val value = bb.int

                stdout.print("EVENT:$type,$code,$value\n")
                stdout.flush()
            }
        } catch (e: Throwable) {
            System.err.println("Grab exit Exception: ${e.javaClass.simpleName} -> ${e.message}")
        }
    }

    private fun logErrno(throwable: Throwable) {
        try {
            if (throwable.javaClass.name.contains("ErrnoException")) {
                val errnoField = throwable.javaClass.getField("errno")
                val errno = errnoField.get(throwable) as Int
                val errText = android.system.Os.strerror(errno)
                System.err.println("🚨 底层内核异常码 -> Errno: $errno ($errText)")
            }
        } catch (e: Exception) {
            System.err.println("读取 Errno 属性失败")
        }
    }
}