# RingNoSpeakerLSPosed

HyperOS 4 / Android 17 的实验性 LSPosed 模块。

连接 Bluetooth 或有线耳机后，尝试禁止电话铃声和通知音从手机 Speaker 播放；断开后撤销模块自己添加的规则。

实现思路：针对支持 `USAGE_NOTIFICATION` / `USAGE_NOTIFICATION_RINGTONE` 的 `AudioProductStrategy`，给 Speaker 添加 `DEVICE_ROLE_DISABLED`，而不是修改 `volume_ring_speaker`。

目标设备：Xiaomi 15 Pro / HyperOS 4 / Android 17。

GitHub Actions 会自动构建 `app-debug.apk`。

安装 APK 后，在 LSPosed 中启用模块并将作用域限定到 `android`，然后重启。

日志关键字：`RingNoSpeaker`

注意：这是 framework 反射 Hook 实验版，HyperOS 私有 framework 与 AOSP 可能存在差异，首次测试应通过 LSPosed 日志确认 Hook 是否成功。
