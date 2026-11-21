# 纸港 · PaperHarbor

**简体中文** · [English](README.md)

一款不需要账号、网络或密钥的 Android 笔记本。书写、输入、识别和录音全部在本机完成，应用不声明
`INTERNET` 或 `ACCESS_NETWORK_STATE`。

纸港 1.0 由 **kkkzheli** 独立实现，与 Goodnotes 无从属或授权关系，没有使用该产品的代码、素材，
也不读取或继承任何订阅。

## 功能

- **书架**：封面缩略图、标题、页数与日期；按标题、页内文字和识别文本搜索；导入 PDF、图片或
  纸港备份；可按最近修改、标题或页数排序。
- **画笔**：硬笔（压感收锋）、软笔（粗细随速度与压力变化）、半透明荧光笔。再次点击当前画笔按钮
  会打开选择框与笔尖滑杆，并实时预览笔触。
- **橡皮**：可按笔迹整条擦除，也可只擦掉扫过的范围；两种模式共用带预览的半径调节。
- **图形与魔法笔**：一个选择框提供直线、箭头、矩形、圆、椭圆、三角形、菱形、五边形、五角星和
  六边形；魔法笔把大致的手绘变成直线、圆、椭圆、矩形或三角形，认不出来时保留原笔迹。
- **套索**：选中后拖动移动，拖四角按比例缩放，用上方的手柄旋转；气泡里可以克隆或删除，更多面板
  里还有「全选笔迹」。
- **调色盘**：色相/饱和度色轮加独立亮度滑杆，8 个色位可长按保存当前颜色，另有最近使用颜色。
- **页面**：缩略图网格、书签、横线/方格/空白模板、复制、前后移动、删除，以及跳转到下一个书签页。
- **文字与识别**：页内文字可被书架搜索；横排印刷的简体中文与英文由 Tesseract4Android 在本机识别，
  语言数据随安装包提供。
- **闪卡与录音**：双面闪卡自评复习；录音保存为本机 AAC，可回听或删除。只有主动开始录音时才申请麦克风。
- **导出与备份**：PDF、单页 PNG，以及保留笔迹、文字、闪卡和录音的 `.phnote` 可编辑备份。恢复会
  新建一本笔记，不会覆盖原件。
- **设置**：浅色/深色/跟随系统、只用手写笔绘制、压感灵敏度、橡皮默认范围擦除、新笔记纸张、本机用量，
  以及作者与项目链接。
- **多语言**：简体中文与英文，跟随系统语言。

## 边界

- **不导入 `.goodnotes` 私有文档**。旧资料请先导出 PDF，再在其上批注。
- 不提供账号、云同步、多人协作、内容商店、远程 AI、Office 转换、笔迹同步录音回放或语音转写。
- PDF 导出会把页面与批注合成，不能替代可编辑的 `.phnote` 备份；OCR 文本不会写入导出 PDF 的隐藏文字层。
- **卸载前请先导出备份。** 没有云端副本，`.phnote` 也不加密，里面有你的笔迹和录音，请自行妥善保管。

## 安装

在 Releases 下载 `PaperHarbor-1.0.0.apk` 侧载安装。这是正式构建（`debuggable=false`），包名
`io.github.kkkzheli.paperharbor`，要求 Android 8.0（API 26）及以上。

签名密钥与密码不会上传，APK 里只有公开证书。想用自己的密钥重新构建，请见
[docs/building.md](docs/building.md) 与 Wiki 的「构建与签名」。

## 构建

需要 JDK 17 以上、Android SDK Platform 34 与 Build Tools 34，并设置 `ANDROID_HOME` 或在
`local.properties` 写入 `sdk.dir`。

```powershell
.\gradlew.bat --offline assembleDebug assembleRelease
```

```powershell
python -I .\tools\verify-apk.py .\app\build\outputs\apk\release\app-release.apk --build-tools "$env:ANDROID_HOME\build-tools\34.0.0"
```

`verify-apk.py` 会核对实际打包的权限是否恰好只有 `RECORD_AUDIO`、验证签名并输出 SHA-256。
仪器测试在专用设备或模拟器上用 `connectedDebugAndroidTest` 运行；`tools/offline-smoke.py`
在飞行模式下驱动真实界面，`tools/layout-check.py` 检查竖屏、横屏与 360 dp。本次构建的验收记录见
[docs/verification-1.0.0.md](docs/verification-1.0.0.md)。

## 许可与署名

应用代码、矢量图标与启动图由 **kkkzheli** 原创。文字识别引擎、语言数据及其依赖遵循各自的许可，
见 `app/src/main/assets/ocr/NOTICE.txt` 与 `licenses/` 目录。本仓库不包含真实用户笔记、录音、
签名密钥或原 iOS 应用的任何副本。
