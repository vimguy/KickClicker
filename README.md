# KickClicker

<p align="center">
  <strong>免 Root 高精度安卓悬浮连点器 · 用完即走 · 零后台常驻</strong>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android-green.svg" alt="Platform" />
  <img src="https://img.shields.io/badge/Language-Kotlin-blue.svg" alt="Language" />
  <img src="https://img.shields.io/badge/MinSdk-26%2B-orange.svg" alt="MinSdk" />
  <img src="https://img.shields.io/badge/TargetSdk-35-brightgreen.svg" alt="TargetSdk" />
  <img src="https://img.shields.io/badge/Arch-arm64--v8a-lightgrey.svg" alt="Arch" />
  <img src="https://img.shields.io/badge/License-MIT-blue.svg" alt="License" />
</p>

---

## 📖 项目简介

**KickClicker** 是一款专为安卓设备打造的免 Root 高精度悬浮连点器与整点自动化抢购辅助工具。针对传统连点器容易出现“首击延迟、多标靶逻辑混乱、悬浮窗拦截自身点击、防封抖动过大致使点偏、后台死板常驻耗电”等痛点进行了深度重构与底层手势优化。

适用于整点秒杀、演唱会/话剧/高铁抢票、应用自动化测试及高频重复点击等场景。

---

## ✨ 核心特性

- 🎯 **微米级准星与穿透派发**
  - 采用 48dp 悬浮准星窗口配合 4dp 中心粉点标靶，采用全屏绝对坐标系统精准对齐。
  - 启动连点瞬间，标靶窗口自动同步切换为 `FLAG_NOT_TOUCHABLE` 绝对穿透模式，100% 杜绝自身悬浮窗吃掉点击的偶发失灵问题。

- ⚡ **双层连发策略（Sequence Burst）**
  - **点内极速连发**：默认每个标靶连发 3 次（连发间隔 15ms，按下时长 15ms），大幅提升整点页面切换、弹窗渲染时的有效命中率。
  - **点间多靶顺序循环**：支持在悬浮条动态添加任意多个标靶，系统严格按 ① ➔ ② ➔ ③ 顺序推进，各标靶均支持独立配置等待间隔。

- 🎲 **靶心内物理级微抖动防封**
  - 防封微抖动半径严格收敛在 **1.2dp（约 ±3.6px）**，完全限制在中心粉点内部，绝不偏移到目标按钮之外。
  - 时间间隔融入 ±10% 动态浮动算法，有效打破脚本固定特征检测。

- ⏱️ **毫秒级 SNTP 网络对时与整点倒计时**
  - 内置 SNTP 高精度对时模块，直连国家授时中心/阿里云/Apple 等授时源，自动测算本机硬件时钟偏差。
  - 支持设定 `HH:mm:ss.SSS` 目标时刻。倒计时前段使用轻量协程调度省电，最后 30ms 自动转入单调纳秒自旋，**零延迟在整点 00:00.000 瞬间触发首击**。

- 🪟 **悬浮毫秒时钟**
  - 一键开启桌面悬浮时钟，毫秒级跳动（HH:mm:ss.SSS），抢购时刻清晰掌控。优化主线程刷新负荷，连点期间不丢帧。

- 🛡️ **即用即走 · 零后台常驻**
  - 不注册常驻后台保活机制；关闭悬浮控制台即彻底释放无障碍与前台服务资源，纯净不耗电。
  - 提供长按停止与顶部系统通知栏一键急停，保障安全可控。

---

## 📱 核心点击策略与预设场景

### 1. 点击逻辑模式

```
[标靶 ①] 连发 3 次 (每发间隔 15ms)
   ↓ 等待标靶 ① 间隔 (默认 50ms)
[标靶 ②] 连发 3 次 (每发间隔 15ms)
   ↓ 等待标靶 ② 间隔 (默认 50ms)
[标靶 ③] 连发 3 次 (每发间隔 15ms)
   ↓ 循环回到标靶 ①
```

### 2. 推荐抢购配置

| 场景类型 | 标靶摆放方案 | 参数推荐 | 场景作用 |
| :--- | :--- | :--- | :--- |
| **单点整点秒杀** | **标靶 ①** 对准“立即购买/预约”按钮 | 连发 3 次 · 间隔 30ms ~ 50ms | 极致连点手速，开抢瞬间高密度击穿 |
| **双点联动下单** | **标靶 ①** 对准“场次/票档”<br>**标靶 ②** 对准底部“确认/提交订单” | 连发 3 次 · 靶 ① 50ms · 靶 ② 80ms~100ms | 靶 ① 快速选档，靶 ② 预留页面弹窗渲染时间并完成提交 |
| **多级级联选择** | **标靶 ①** 日期 ➔ **标靶 ②** 票档 ➔ **标靶 ③** 立即支付 | 连发 3 次 · 各靶 50ms 依次推进 | 环环相扣，顺序循环推进 |

---

## 🚀 下载与安装

请前往 [GitHub Releases](https://github.com/) 下载最新打包产物：

- **最新安装包**：`KickClicker-v1.0.8.apk`
- **支持架构**：`arm64-v8a`（极简打包体积，约 3.5 MB）
- **最低系统要求**：Android 8.0（API 26）及以上

---

## 🛠️ 必要权限说明

为了正常实现悬浮与模拟点击，KickClicker 需要以下系统权限：

1. **悬浮窗权限（Display Over Other Apps）**：用于在抢票软件上层展示悬浮操作条、准星标靶与悬浮毫秒时钟。
2. **无障碍服务（Accessibility Service）**：用于向屏幕派发免 Root 毫秒级手势点击与智能文本识别。
3. **忽略电池优化（可选）**：防止手机系统在息屏或切入后台时误杀定时倒计时任务。

> 🔒 **隐私声明**：本应用所有连点与对时操作均在本地执行，无需联网上传任何用户屏幕内容、按键记录或隐私数据。

---

## 🏗️ 源码构建

本项目采用标准 Android Gradle 构建系统，开发与编译环境：
- JDK 17
- Android SDK 35 (Build-Tools 35.0.0 / 36.0.0)
- Gradle 9.x

克隆仓库并编译 Debug 包：

```bash
git clone https://github.com/<your-username>/KickClicker.git
cd KickClicker
./gradlew assembleDebug
```

编译输出目录位于：`app/build/outputs/apk/debug/app-debug.apk`。

---

## 📄 开源许可证

本项目基于 [MIT License](LICENSE) 开源。

---

## ⚠️ 免责声明

本项目仅供个人学习、Android 辅助功能开发研究及软件自动化测试使用。请勿将本软件用于恶意抢购、倒卖囤积或任何违反法律法规及目标服务条款的场景。使用本软件产生的任何后果由使用者自行承担，开发者概不负责。
