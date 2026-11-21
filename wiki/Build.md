# 构建与签名

## 环境

- JDK 17 或更高（Android Studio 自带的 JBR 即可）
- Android SDK Platform 34、Build Tools 34
- 在 `local.properties` 写入 `sdk.dir=...`，或设置 `ANDROID_HOME`

Gradle 包装器固定 8.9 并校验发行包摘要。

## 编译

```powershell
.\gradlew.bat --offline assembleDebug assembleRelease
```

首次准备构建工具可能需要联网下载依赖；**这不是 APK 的运行要求**。

## 校验

```powershell
python -I .\tools\verify-apk.py .\app\build\outputs\apk\release\app-release.apk --build-tools "$env:ANDROID_HOME\build-tools\34.0.0"
```

脚本会确认实际权限恰好是 `RECORD_AUDIO`、验证签名，并打印 SHA-256。

## 自己签名

正式密钥只在本地生成和使用，**不要上传 keystore**。把路径写进 `local.properties`（该文件不会
被提交）：

```properties
ph.storeFile=D:/PrivateKeys/paperharbor.jks
ph.storePassword=你的密码
ph.keyAlias=paperharbor
ph.keyPassword=你的密码
```

然后 `assembleRelease` 就会产出已签名的 APK。也可以用 JDK 自带工具手动签名：

```powershell
.\tools\sign-local.ps1 -Keystore D:\PrivateKeys\paperharbor.jks -Alias paperharbor -Unsigned .\app-release-unsigned.apk
```

脚本不保存密码、拒绝覆盖已有成品，并在结束时验证签名。

> 后续更新必须继续使用同一张证书，否则系统会把它当成另一个应用，无法覆盖安装。
