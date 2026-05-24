Android Input Framework Diagnostics Tool
一个用于诊断 Android 输入框架（Input Framework）行为与冲突的轻量级辅助工具。
本工具旨在帮助开发者和研究人员分析特定 Android 系统（如 EMUI / HarmonyOS 等定制 ROM）中，外接鼠标（SOURCE_MOUSE）接入时导致屏幕触摸（Touch）输入被挂起或失效的底层冲突机制。
项目背景
在部分 Android 定制系统中，接入特定 USB / 蓝牙鼠标设备时，系统会强制切换至“鼠标模式”，在某些场景下这会导致系统的触摸层发生挂起或异常行为。
本工具通过枚举输入源、提权动态禁用特定输入设备以及捕获应用级事件，来验证以下技术假设：
冲突是否由系统的 PointerController（指针控制器）或输入策略（Input Strategy）引起。
在系统层禁用物理鼠标设备的指针行为后，触摸屏功能是否能恢复。
在系统级禁用鼠标设备后，应用（Activity）是否依然能通过 dispatchGenericMotionEvent 接收到原始的鼠标事件坐标。
核心功能
输入设备枚举 (Input Device Enumeration)：获取当前挂载的所有物理与虚拟输入设备信息，包括设备 ID、硬件描述符（Descriptor）及输入源掩码（Sources）。
系统级输入禁用 (Runtime Device Disabling)：利用 Shizuku 获取系统 IInputManager Binder 代理，反射调用隐藏的 disableInputDevice / enableInputDevice 接口，在不拔插设备的前提下动态控制硬件启用状态。
实时事件拦截与分析 (Event Trapping)：重写 dispatchGenericMotionEvent，对 SOURCE_MOUSE 输入流进行实时捕获，并在界面上直观展示事件的 Action 属性及坐标（X, Y）。
技术架构与原理
本工具的诊断逻辑基于 Android 系统的输入事件分发链（Input Pipeline）：
code
Code
[ 物理硬件 ] -> [ Linux 内核 (Event Node) ] -> [ Android InputReader ] -> [ InputDispatcher ]
                                                                                   |
   +-------------------------------------------------------------------------------+
   |
   +--> [ PointerController (产生系统光标, 华为设备在此处可能挂起 Touch) ] <--- 可通过 disableInputDevice 动态切断
   |
   +--> [ App Window (dispatchGenericMotionEvent) ] <--- 观察在此状态下事件流是否依然可达
使用前提
运行环境：Android 8.0 (API 26) 及以上版本。
依赖服务：目标设备需安装并激活 Shizuku 服务，以提供调用系统隐藏 API（android.permission.DISABLE_INPUT_DEVICE）所需的 Shell 级权限。
快速开始
1. 克隆与编译
将本项目导入 Android Studio，并确保 app/build.gradle.kts 已配置 Shizuku 依赖：
code
Kotlin
dependencies {
    val shizukuVersion = "13.1.5"
    implementation("dev.rikka.shizuku:api:$shizukuVersion")
    implementation("dev.rikka.shizuku:provider:$shizukuVersion")
    // ... 其他基础组件依赖
}
2. 诊断步骤
启动 Shizuku：确保目标手机上的 Shizuku 服务已处于运行状态。
连接外接设备：连接会导致系统触摸冲突的物理鼠标或 HID 设备。
运行应用：
点击 "1. 枚举 InputDevice"：在下方日志区寻找外接鼠标的物理 ID（通常会被标记为 targetMouseId）。
点击 "2. Shizuku 禁用鼠标"：此时系统鼠标光标应消失。
测试 A（触摸恢复验证）：尝试用手指点击或滑动屏幕，观察触摸反馈是否已恢复正常。
测试 B（事件留存验证）：在屏幕上滑动已被禁用的鼠标，观察日志区是否仍在打印 👉 收到鼠标事件。
测试完成后，点击 "3. Shizuku 恢复鼠标" 恢复原始状态。
诊断日志说明
在诊断过程中，日志区域输出的特征具有明确的指向性：
若禁用后 Touch 恢复，且应用仍能收到鼠标事件：说明系统层的 PointerController 策略是导致触摸冲突的唯一原因。最佳解决方案为“Shizuku 提权禁用系统鼠标行为 + 应用层自行消费原始事件”。
若禁用后 Touch 恢复，但应用收不到鼠标事件：说明该 API 会彻底关闭 InputReader 对应的数据通道。后续需考虑绕过 framework，直接读取 /dev/input/ 节点。
若调用禁用方法时报错 NoSuchMethodException：说明当前定制系统修改了 IInputManager 内部的方法签名，需要根据具体的 ROM 版本适配反射参数。
免责声明
本项目仅用于技术诊断与学术研究。利用反射调用系统非公开接口（Hidden APIs）以及使用 Shizuku 提权可能会在不同厂商的定制 ROM 上表现出不同的兼容性，请勿将此诊断代码直接用于生产环境的稳定版应用中。
