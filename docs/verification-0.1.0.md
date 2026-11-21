# 0.1.0 离线验收

测试对象是 Release 中可直接安装的 debug APK，而非在模拟器上运行未签名正式版。设备为独立 AOSP Android 12（API 31，x86_64）模拟器，安装前已开启飞行模式、关闭 Wi-Fi，不使用宿主机麦克风。

| 验收 | 结果 |
|---|---|
| 离线 Gradle 构建 `assembleDebug assembleRelease assembleDebugAndroidTest` | 通过。构建时校验 OCR 引擎和两个随包模型摘要 |
| `lintDebug`、发布版关键 Lint | 通过（无错误；仍有非阻塞警告） |
| 两个 APK 的实际权限 | `aapt dump badging` 仅列出 `RECORD_AUDIO`，没有 `INTERNET` 或 `ACCESS_NETWORK_STATE`；模拟器也验证两个网络权限未获授 |
| 包内容与测试签名 | 两个 APK 内全部 10 项 OCR 资源与源码一致（含模型、许可证）；`apksigner verify --verbose` 验证 debug APK 通过 |
| 系统设备测试 | `OK (8 tests)`：验证无网络权限、笔迹/撤销/重做和 PDF 像素回读、中/英文印刷体 OCR、含合成 AAC 片段和复习卡的备份恢复、原子保存和新副本恢复、拒绝不完整或不合规备份、混合页尺寸 PDF 的比例与背景 |
| 独立界面冷启动测试 | 通过。只用界面新建笔记、绘制笔迹、保存页内文字、新增一页，强停应用后重开，通过搜索找回第 1 页并核对文字。通过界面清理测试笔记 |

不据此声称所有 Android 厂商或版本均已实测。没有用实体触控笔/麦克风验证笔感、掌托或采音质量。音频测试使用合成静音，校验备份后字节一致且系统播放器可打开；没有采集真实用户内容。正式签名密钥由使用者保管，本次没有生成或上传正式私钥。
