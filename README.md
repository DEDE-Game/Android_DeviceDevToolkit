# DevAndroidKit: 安卓外设断触诊断与验证控制台
*(Android Input Diagnostics & Bypass Console)*

`DevAndroidKit` 是一个专门帮助开发者解决**“手机插上外接键鼠后，手指划屏就失灵（断触）”**这一冲突的免 Root 物理调试控制台与测试探针。

*A lightweight, non-root console developed to troubleshoot and bypass touchscreen conflicts or freezes caused by physical mice on custom Android ROMs.*

---

## 🔍 它解决什么问题？ / What Problem Does It Solve?

*   **痛点场景 (The Pain Point)**：在一些定制系统上，只要外接鼠标一动，屏幕触摸（手指手势）就会被系统强制停用，**无法实现“右手甩鼠标瞄准、左手按屏幕施法”的键鼠手势共存**。
*   **诊断目标 (The Diagnostic Goal)**：通过在用户态和内核态模拟“光标隐形锁态”，验证能否绕过系统的防误触检测，让**物理手指触摸与鼠标轨迹数据完美共存**。

---

## 🛠️ 核心功能模块 / Core Features

### 1. 指针捕获 / Pointer Capture (免 Root 验证方案)
*   **中文**：一键隐藏并锁死系统自带的鼠标光标。用来测试光标彻底消失后，手指摸屏是否能恢复正常，同时在下方实时接收高精度的鼠标位移数据。
*   **English**: *Hides and locks the system cursor. Used to test if touchscreen input is restored when the cursor is invisible, while outputting relative mouse movement coordinates.*

### 2. 硬件读取 / Linux Evdev Grab (内核层数据探针)
*   **中文**：利用 Shizuku 自动找出鼠标底层对应的物理路径（如 `/dev/input/event9`），测试从 Linux 内核驱动中直接读取最底层的光学位移信号（`EV_REL`）。
*   **English**: *Resolves the mouse driver path and attempts to capture raw low-latency relative coordinates directly from the Linux kernel event nodes.*

### 3. SELinux 安全审计 / SELinux AVC Audit
*   **中文**：一键抓取并翻译系统的安全维护日志。当某些操作失败时，直接找到系统安全机制（SELinux）拦截了我们的哪个读写指令，并转换成人类易读的错误报告。
*   **English**: *Filters and translates SELinux AVC denials in real-time, showing exactly which input operations were blocked by the OS.*

### 4. 硬件枚举与 dumpsys 诊断 / Hardware Scan & Dumpsys
*   **中文**：自动扫描手机连上的所有外设名称和 ID，并能一键导出系统内置的输入分发状态报文，便于对齐动态 ID 与物理驱动节点。
*   **English**: *Scans all connected hardware inputs and dumps the official input dispatcher reports to align virtual and physical IDs.*

---

## 📋 快速使用指南 / Quick Start

```
[ 物理鼠标连接 ] ➔ [ 1. 枚举/锁定鼠标 ]
                       │
                       ├─► [ 开启指针捕获 ] ➔ 测试手指触控共存 (Touch Coexistence)
                       │
                       └─► [ 启动物理霸占 ] ➔ 拦截并观测内核数据 (Evdev Raw Stream)
```

1.  **启动通信**：在手机上开启并授权 [Shizuku](https://shizuku.rikka.app/) 服务。
2.  **锁定鼠标**：晃动几下鼠标确保连接，点击 **“1. 枚举 InputDevice 并锁定物理鼠标”**。
3.  **方案验证**：点击 **“2. 启动指针捕获”**。
    *   *此时在屏幕上双指划动*，测试手指划屏是否已经恢复正常响应。
    *   *划动物理鼠标*，观察下方日志区是否在正常吞吐 `相对位移 ➔ DX/DY` 数据。
4.  **复制归档**：测试完毕后，点击 **“一键复制全部实验与诊断日志”** 即可直接粘贴保存。