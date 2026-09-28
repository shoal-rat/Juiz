# 特权安装（L1 语音代接）

> 这一页只适合**自己的、愿意折腾的**设备。普通用户用 L0（默认拨号器 + 短信代办）就够了。

## 为什么需要特权安装

Android 只允许**预装的特权应用**拿到通话音频（`CAPTURE_AUDIO_OUTPUT`、`CALL_AUDIO_INTERCEPTION`，保护级别 signature|privileged）。
这是系统的隐私设计，不是 Juiz 的 bug。L1 用到的系统接口都已在 AOSP `frameworks/base` 主干源码核对：

| 接口 | 用途 |
| --- | --- |
| `Call.enterBackgroundAudioProcessing()` / `exitBackgroundAudioProcessing(ring)` | 来电"接通但不连本机听筒麦克风"；交还时可让手机重新响铃 |
| `AudioManager.isPstnCallAudioInterceptable()` | 音频 HAL 是否允许拦截普通电话音频 |
| `AudioManager.getCallDownlinkExtractionAudioRecord()` | 取对方的声音 |
| `AudioManager.getCallUplinkInjectionAudioTrack()` | 把 Juiz 的声音送进通话 |
| `AudioManager.isCallScreeningModeSupported()`（公开 API） | 是否支持"来电筛选"音频模式 |

## 两条音频路径

| 路径 | 条件 | 代接时 | 交还给你 |
| --- | --- | --- | --- |
| **后台音频处理**（首选） | `isCallScreeningModeSupported()` 为真 | 本机听筒、麦克风都不接入 | 直接恢复通话，或"模拟响铃"让你接起 |
| **接听 + 静音**（备用） | 不支持来电筛选模式 | 接听并静音本机麦克风，只注入 Juiz 的声音 | 取消静音即可；无法"模拟响铃"，升级改为通知 |

能力检测页会自动判断走哪条路径。

## 安装方法 A：Magisk 模块（已 root 的手机）

1. 构建正式包：`./gradlew :app:assembleRelease`（约 5 MB）。
2. 复制到模块：`cp app/build/outputs/apk/release/app-release.apk tools/magisk-module/system/priv-app/Juiz/Juiz.apk`
3. 打包：`cd tools/magisk-module && zip -r ../juiz-privileged.zip .`
4. 在 Magisk 里安装这个 zip，重启。
5. 打开 Juiz → 设置 → 能力检测，确认"系统特权安装""CALL_AUDIO_INTERCEPTION""系统接口可访问""音频 HAL"都通过。
6. 运行**真机验证向导**（需要另一部手机打进来）。步骤 1–5 全部通过后，本机才会开放 L1 自动代接。

> ⚠️ 特权权限白名单（`privapp-permissions-app.juiz.xml`）必须覆盖清单里请求的全部特权权限，否则部分系统会拒绝开机。模块里的清单已经列全。

## 安装方法 B：AOSP 模拟器（只用于开发）

```bash
emulator -avd <AOSP 镜像> -writable-system
adb root && adb remount && adb reboot        # 首次需要
adb root && adb remount
adb shell mkdir -p /system/priv-app/Juiz
adb push app/build/outputs/apk/release/app-release.apk /system/priv-app/Juiz/Juiz.apk
adb push tools/magisk-module/system/etc/permissions/privapp-permissions-app.juiz.xml /system/etc/permissions/
adb reboot
```

模拟器上已验证过的结果见 [STATUS.md](STATUS.md)。模拟器的电话没有真实的对方声音，**不能代替真机验收**。

## 风险与说明

- root 和修改系统分区可能影响保修、OTA 更新和部分应用（例如银行类应用）。
- 同一系统版本在不同机型上，音频 HAL 是否支持拦截可能不同；系统更新后需要重新跑验证向导。
- 录音与通话代接涉及当地法律，请遵守所在地关于告知与录音的规定。Juiz 默认在开场白里披露 AI 身份，并在开启录音时告知对方。
