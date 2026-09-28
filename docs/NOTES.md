# 开发笔记

## 构建环境

- JDK 21（Gradle 8.14.3 不支持在 JDK 26 上运行）、Android SDK 36、AGP 8.13.2、Kotlin 2.2.21。
- `local.properties` 里写 `sdk.dir=…`。
- Android 10 自带 SQLite 3.22，不支持 UPSERT，计数器用"INSERT OR IGNORE + UPDATE"两步。

## 常见坑

- **CallStyle 通知**只能由前台服务发出（或带全屏意图的来电通知），否则系统直接抛异常导致应用崩溃。通话接通后让 InCallService 进入前台（`phoneCall` 类型）。
- 默认拨号应用在解锁使用中收到来电，只显示横幅；全屏来电界面由锁屏时的全屏意图拉起。
- `Call.getState()` 在 API 31 起改由 `Call.Details.getState()` 提供，minSdk 29 需要两条路（见 `CallRegistry.currentState`）。
- 隐藏系统接口通过 HiddenApiBypass 调用，异常要解开 `InvocationTargetException` 才看得到真实原因。
- 模拟器的系统分区放不下 68 MB 的调试包；特权安装用 R8 压缩后的正式包（约 5 MB）。
- ollama 上的 qwen3.5 必须传 `reasoning_effort: "none"`，否则会把 token 全花在思考上、正文为空。
- 小模型偶尔把 JSON 结尾写成全角引号"”"或全角冒号，解析要宽松。

## 目录约定

- 执行方交换目录：`<任务ID>/card.md`、`card.json`（手机写）；`claimed.json`、`result.json`（执行器写）；`out/` 交付物；`drafts/*.json` 邮件草稿。
- 云端执行与导入的成品：应用私有目录 `files/cloud/<任务ID>/`。

## 调试命令

```bash
juiz-sim vad-debug shield-2          # 看某段测试音频的断句时间点
juiz-sim buddy --model compat         # 试听伙伴台词
adb emu gsm call 13800138000          # 模拟来电
adb emu sms send 13800138000 "你好"    # 模拟短信
adb shell "run-as app.juiz cat databases/juiz.db" > juiz.db   # 取出应用数据库（调试包）
```
