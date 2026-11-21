# 纸港 · PaperHarbor

一款不需要账号、网络或 API Key 的 Android 笔记本。笔记、识别文本和录音保存在应用私有目录。没有服务器；应用不声明 `INTERNET` 或 `ACCESS_NETWORK_STATE`。Android 8.0（API 26）及以上。

## 用法

- **写笔记**：新建横线、方格或白纸笔记。支持笔、荧光笔、整笔橡皮、套索移动、直线、矩形、颜色、粗细、撤销和重做。双指平移/缩放；触控笔能提供压感时用于笔画宽度。页码按钮可浏览书签、复制、移动和删除页面。
- **读资料**：从本地导入 PDF 或图片，在其上书写。每页可添加键入文字。页内印刷文字 OCR 使用随 APK 分发的简中和英文模型，结果可核对后保存；书架可搜索标题、键入文字和已保存的识别文本。不保证能识别连笔手写。
- **听和复习**：在「更多」中建立双面闪卡并记录复习结果；录音只在主动开始时申请麦克风权限，并可在本地回听。不上传音频，也不做在线转写。
- **迁移和备份**：在「更多」中导出整个笔记的 `.phnote` 可编辑备份。在书架「导入文件」中恢复为一本新笔记，不覆盖原件。换设备时，用户自行拷贝备份。普通 PDF 导出用于阅读，页面和批注会合成，不能替代可编辑备份。

**卸载前请自行在本地导出备份。** 没有后台云备份，`.phnote` **不加密**，含笔记和录音，请自行妥善保管。系统选文件时使用 `EXTRA_LOCAL_ONLY`。无法控制用户之后用其他应用分享已导出的文件。

独立实现，与 Goodnotes 无从属或授权关系。没有使用原 IPA 的代码或素材，不读取或继承任何订阅。**不导入 `.goodnotes` 私有笔迹文档**；旧资料可先导出为 PDF 再批注。没有账号、云同步、多人协作、内容商店、远程 AI、Office 转换、笔迹同步录音回放或语音转写。OCR 文本不作为隐藏文字层写入导出的 PDF。

## 安装与签名

从仓库 Releases 下载 `PaperHarbor-0.1.0-debug.apk` 可直接侧载。这是测试签名版本，包名 `app.paperharbor.notes.debug`。`PaperHarbor-0.1.0-unsigned.apk` 是待**自行签名**的正式构建，包名 `app.paperharbor.notes`，未签名时不能安装。两者可并存，不共用数据：请用 `.phnote` 迁移笔记。

正式密钥只在自己的电脑生成、备份和使用；不要上传 keystore，密码由签名工具交互式读取。PowerShell 示例（从仓库根目录执行，自行替换路径）：

```powershell
keytool -genkeypair -keystore D:\PrivateKeys\paperharbor.jks -alias paperharbor -keyalg RSA -keysize 3072 -validity 10000
```

```powershell
.\tools\sign-local.ps1 -Keystore D:\PrivateKeys\paperharbor.jks -Alias paperharbor -Unsigned .\PaperHarbor-0.1.0-unsigned.apk
```

首次命令需要目标目录已经存在。脚本不存密码，不向任何网站发送文件；会验证签名、输出成品 SHA-256，并拒绝覆盖现有成品。后续更新应继续使用同一签名证书。

## 构建与复验

需要 JDK 17 或 21、Android SDK Platform 34、Build Tools 34 和可用的 Android SDK 路径（`ANDROID_HOME` 或本机 `local.properties`）。Gradle 包装器固定 8.9 并校验发行包摘要。首次准备构建工具/Android Gradle Plugin 可能需要联网；**这不是 APK 的运行要求**。构建工具缓存就绪后：

```powershell
.\gradlew.bat --offline assembleDebug assembleRelease assembleDebugAndroidTest
```

```powershell
python -I .\tools\verify-apk.py .\app\build\outputs\apk\debug\app-debug.apk --build-tools "$env:ANDROID_HOME\build-tools\34.0.0"
```

仪器测试使用合成笔记：笔迹提交/撤销、可编辑备份恢复、PDF 导出后独立重读和像素核对、随包 OCR、最终权限。可在专用 Android 测试设备上运行 `connectedDebugAndroidTest`；`tools/offline-smoke.py` 另测飞行模式下的界面操作、强停重启和搜索。不要在个人资料上做自动化测试。

第三方 OCR 引擎、语言数据及其依赖适用各自许可，见仓库中的第三方说明。本仓库不包含真实用户笔记、录音、签名密钥或原 IPA。
