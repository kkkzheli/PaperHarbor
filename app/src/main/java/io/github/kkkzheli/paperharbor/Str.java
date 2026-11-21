/*
 * PaperHarbor — an offline notebook for Android.
 * Copyright (c) kkkzheli. Original implementation, not affiliated with Goodnotes.
 */
package io.github.kkkzheli.paperharbor;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Two-language text layer.
 *
 * <p>The Chinese wording is the source of truth and appears verbatim at each call site; this class
 * maps it to English when the device is not running a Chinese locale. Values that are assembled
 * from numbers use the small helpers below so word order stays natural in both languages.
 * Anything without a mapping falls through unchanged, which keeps model numbers, file names and
 * user content intact.
 */
final class Str {
  private static final Map<String, String> EN = new HashMap<>();

  private Str() {}

  static boolean english() {
    return !Locale.getDefault().getLanguage().startsWith("zh");
  }

  static String of(CharSequence chinese) {
    if (!english() || chinese == null) return chinese == null ? "" : chinese.toString();
    String value = EN.get(chinese.toString());
    return value == null ? chinese.toString() : value;
  }

  static String of(String chinese, Object... ignored) { return of((CharSequence) chinese); }

  // ---- composed values -------------------------------------------------------------------------

  static String pageOf(int number) { return english() ? "Page " + number : "第 " + number + " 页"; }

  static String pageFooter(int number, int total) {
    return english() ? "Page " + number + " of " + total : "页面：第 " + number + " 页，共 " + total + " 页";
  }

  static String pageCount(int total) { return english() ? total + (total == 1 ? " page" : " pages") : total + " 页"; }

  static String slot(int number) { return english() ? "Swatch " + number : "色位 " + number; }

  static String recentColor(int number) { return english() ? "Recent colour " + number : "最近颜色 " + number; }

  static String savedToSlot(int number) { return english() ? "Saved to swatch " + number : "已保存到色位 " + number; }

  static String size(String value) { return english() ? "Size " + value : "粗细 " + value; }

  static String eraserSize(String value) { return english() ? "Eraser " + value : "橡皮 " + value; }

  static String radius(String value, int mode) {
    if (english()) return "Radius " + value + "  ·  " + (mode == 1 ? "erase area" : "erase strokes");
    return "半径 " + value + "  ·  " + (mode == 1 ? "范围擦除" : "按笔迹擦除");
  }

  static String penLabel(String value, boolean soft) {
    if (english()) return value + "  ·  " + (soft ? "soft brush" : "hard pen");
    return value + "  ·  " + (soft ? "软笔" : "硬笔");
  }

  static String openNote(String title) { return english() ? "Open note: " + title : "打开笔记：" + title; }

  static String noteMeta(int pages, String date) {
    if (english()) return pages + (pages == 1 ? " page" : " pages") + (date.isEmpty() ? "" : " · " + date);
    return pages + " 页" + (date.isEmpty() ? "" : " · " + date);
  }

  static String cardBadge(int pages) { return english() ? pages + "p" : pages + " 页"; }

  static String storage(int notes, String bytes) {
    return english() ? notes + (notes == 1 ? " note · " : " notes · ") + bytes : notes + " 本笔记 · " + bytes;
  }

  static String version(String name) {
    return english() ? "Version " + name + " · no account · no network permission"
        : "版本 " + name + " · 无账号 · 无网络权限";
  }

  static String deleteNoteTitle(String title) {
    return english() ? "Delete “" + title + "”?" : "删除「" + title + "」";
  }

  static String deleteNoteBody(String title) {
    return english()
        ? "The ink, text and recordings in “" + title
            + "” will be removed from this device. This cannot be undone. Back up the .phnote first."
        : "「" + title + "」的笔迹、文字和录音会从本机删除，无法撤销。建议先备份 .phnote。";
  }

  static String reviewLine(int reviews) {
    return english() ? "Reviewed " + reviews + (reviews == 1 ? " time" : " times") : "复习 " + reviews + " 次";
  }

  static String duration(int minutes, int seconds) {
    return english() ? String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
        : String.format(Locale.ROOT, "时长 %d:%02d", minutes, seconds);
  }

  static String recordingName(String name) { return english() ? "Recording " + name : "录音 " + name; }

  static String recordingLive(String clock) { return english() ? "Recording " + clock : "正在录音：" + clock; }

  static String date(long millis) {
    String pattern = english() ? "MMM d" : "M月d日";
    return new SimpleDateFormat(pattern, english() ? Locale.US : Locale.CHINA).format(new Date(millis));
  }

  static String withCount(String chinese, int count) {
    String value = EN.get(chinese);
    if (!english() || value == null) return chinese + count;
    return value.replace("%d", String.valueOf(count));
  }

  static {
    put("返回书架", "Back to shelf");
    put("笔记标题", "Note title");
    put("本地笔记", "On this device");
    put("书签", "Bookmark");
    put("更多工具", "More tools");
    put("笔记画布", "Note canvas");
    put("克隆选区", "Duplicate selection");
    put("删除选区", "Delete selection");
    put("适配页面", "Fit page");
    put("正在准备页面…", "Preparing page…");
    put("撤销", "Undo");
    put("重做", "Redo");
    put("上一页", "Previous page");
    put("下一页", "Next page");
    put("页面列表", "Pages");
    put("画笔", "Pen");
    put("荧光笔", "Highlighter");
    put("橡皮擦", "Eraser");
    put("套索", "Lasso");
    put("图形", "Shapes");
    put("魔法笔", "Magic pen");
    put("颜色", "Colour");
    put("笔尖粗细", "Stroke width");
    put("无法载入页面", "Cannot load page");
    put("移除书签", "Remove bookmark");
    put("添加书签", "Add bookmark");
    put("保存中…", "Saving…");
    put("已存本机", "Saved on this device");
    put("保存失败", "Save failed");
    put("无法保存笔记", "Cannot save the note");
    put("无法保存，先不要离开", "Cannot save yet — stay on this page");
    put("调色盘", "Palette");
    put("关闭", "Close");
    put("色位 · 长按保存当前颜色", "SWATCHES · LONG-PRESS TO STORE THE CURRENT COLOUR");
    put("最近使用", "RECENT");
    put("硬笔", "Hard pen");
    put("软笔", "Soft brush");
    put("均匀压感", "Even pressure");
    put("毛笔粗细", "Brush taper");
    put("按笔迹擦除", "Erase strokes");
    put("范围擦除", "Erase area");
    put("整条删除", "Remove whole stroke");
    put("只擦碰到的地方", "Only what you touch");
    put("直线", "Line");
    put("箭头", "Arrow");
    put("矩形", "Rectangle");
    put("圆", "Circle");
    put("椭圆", "Ellipse");
    put("三角形", "Triangle");
    put("菱形", "Diamond");
    put("五边形", "Pentagon");
    put("五角星", "Star");
    put("六边形", "Hexagon");
    put("随手画个大概：直的变直线，圆的变圆，方框变矩形，三角形也会自动规整。认不出来时保留你的原笔迹。",
        "Sketch roughly: a straight scribble becomes a line, a loop becomes a circle, a box becomes a "
            + "rectangle and a triangle is tidied up. If nothing matches, your own stroke is kept.");
    put("自由", "Freehand");
    put("页面", "Pages");
    put("添加页面", "Add page");
    put("横线", "Ruled");
    put("方格", "Grid");
    put("空白", "Blank");
    put("每本最多 256 页", "256 pages maximum");
    put("无法添加页面", "Cannot add the page");
    put("复制此页", "Duplicate page");
    put("前移一页", "Move up");
    put("后移一页", "Move down");
    put("删除此页", "Delete page");
    put("无法复制页面", "Cannot duplicate the page");
    put("无法移动页面", "Cannot move the page");
    put("至少保留一页", "Keep at least one page");
    put("无法删除页面", "Cannot delete the page");
    put("更多", "More");
    put("页内文字", "Page text");
    put("识别文字", "Recognise text");
    put("闪卡", "Flashcards");
    put("录音", "Recordings");
    put("导出 PDF", "Export PDF");
    put("导出图片", "Export image");
    put("备份", "Backup");
    put("重命名", "Rename");
    put("删除", "Delete");
    put("全选笔迹", "Select all ink");
    put("页面排序", "Page order");
    put("书签页", "Bookmarked page");
    put("无法打开录音", "Cannot open recordings");
    put("还没有书签页", "No bookmarked page");
    put("无法读取笔记", "Cannot read the note");
    put("输入后随笔记保存，并可被书架搜索", "Saved with the note and searchable from the shelf");
    put("保存", "Save");
    put("识别印刷文字", "Recognise printed text");
    put("识别本页", "Scan this page");
    put("在本机识别横排印刷的简中与英文；连笔手写不保证识别。结果只保存在本机。",
        "Reads horizontal printed Chinese and English on this device. Joined-up handwriting is not "
            + "guaranteed. Results stay on this device.");
    put("识别结果会显示在这里", "Recognised text appears here");
    put("取消", "Cancel");
    put("未识别到印刷文字", "No printed text found");
    put("识别未完成", "Recognition did not finish");
    put("名称不能为空", "The name cannot be empty");
    put("删除这本笔记", "Delete this note");
    put("笔迹、文字和录音会从本机删除，无法撤销。建议先导出 .phnote 备份。",
        "The ink, text and recordings will be removed from this device. This cannot be undone. "
            + "Export a .phnote backup first.");
    put("删除失败", "Delete failed");
    put("知道了", "Got it");
    put("纸港", "PaperHarbor");
    put("离线笔记 · 仅此设备", "OFFLINE · THIS DEVICE ONLY");
    put("排序", "Sort");
    put("导入文件", "Import");
    put("设置", "Settings");
    put("搜索标题、页内文字或识别文本", "Search titles, page text or recognised text");
    put("还没有笔记", "No notes yet");
    put("点右下角开始书写，或从右上角导入 PDF", "Tap the button to start writing, or import a PDF");
    put("新建笔记", "New note");
    put("未命名笔记", "Untitled note");
    put("正在读取笔记…", "Reading notes…");
    put("没有找到匹配的笔记", "No matching notes");
    put("试试标题、页内文字或识别文本", "Try a title, page text or recognised text");
    put("书架排序", "Sort the shelf");
    put("最近修改", "Recently edited");
    put("标题 A→Z", "Title A→Z");
    put("页数多→少", "Most pages");
    put("笔记名称（可留空）", "Note name (optional)");
    put("纸张", "Paper");
    put("创建", "Create");
    put("无法创建笔记", "Cannot create the note");
    put("导入到书架", "Import to the shelf");
    put("PDF 文档", "PDF document");
    put("图片", "Image");
    put("图片或照片", "Image or photo");
    put("纸港备份", "PaperHarbor backup");
    put("备份可编辑笔记 .phnote", "Back up editable note (.phnote)");
    put("删除笔记", "Delete note");
    put("重命名失败", "Rename failed");
    put("横线纸", "Ruled paper");
    put("方格纸", "Grid paper");
    put("白纸", "Plain paper");
    put("正在打开笔记…", "Opening note…");
    put("无法打开笔记", "Cannot open the note");
    put("正在准备备份…", "Preparing backup…");
    put("正在准备导出…", "Preparing export…");
    put("笔记", "Note");
    put("没有可用的文件选择器", "No file picker available");
    put("正在恢复备份…", "Restoring backup…");
    put("正在导入图片…", "Importing image…");
    put("正在导入 PDF…", "Importing PDF…");
    put("恢复的笔记", "Restored note");
    put("导入的文档", "Imported document");
    put("无法读取文件", "Cannot read the file");
    put("恢复备份失败", "Restore failed");
    put("导入失败", "Import failed");
    put("正在写入备份…", "Writing backup…");
    put("正在渲染页面…", "Rendering the page…");
    put("正在写入 PDF…", "Writing PDF…");
    put("没有可导出的笔记", "Nothing to export");
    put("无法写入目标文件", "Cannot write the destination file");
    put("已备份可编辑笔记", "Editable note backed up");
    put("已导出页面图片", "Page image exported");
    put("已导出 PDF", "PDF exported");
    put("备份失败", "Backup failed");
    put("导出失败", "Export failed");
    put("无法编码 PNG", "Cannot encode the PNG");
    put("未知错误", "Unknown error");
    put("返回", "Back");
    put("全部资料只在本机", "Everything stays on this device");
    put("外观", "APPEARANCE");
    put("浅色", "Light");
    put("深色", "Dark");
    put("跟随系统", "System");
    put("书写", "WRITING");
    put("只用手写笔绘制", "Draw with the stylus only");
    put("手指只用来平移缩放，避免手掌误触", "Fingers only pan and zoom, so a resting palm cannot mark the page");
    put("压感灵敏度", "Pressure sensitivity");
    put("橡皮默认范围擦除", "Area eraser by default");
    put("开启后只擦掉笔迹上碰到的一段", "Only the part of a stroke you touch is removed");
    put("新笔记的纸张", "PAPER FOR NEW NOTES");
    put("数据", "DATA");
    put("正在统计本机用量…", "Measuring local storage…");
    put("清空最近使用颜色", "Clear recent colours");
    put("已清空最近颜色", "Recent colours cleared");
    put("关于", "ABOUT");
    put("纸港由 kkkzheli 独立设计与实现：不登录、不联网、不上传，笔迹、文字、闪卡和录音"
            + "只保存在这台设备上。它与 Goodnotes 无从属或授权关系。",
        "PaperHarbor is designed and built by kkkzheli: no sign-in, no network, no uploads — ink, "
            + "text, flashcards and recordings stay on this device. It is not affiliated with Goodnotes.");
    put("作者 kkkzheli", "Made by kkkzheli");
    put("项目主页 · GitHub", "Project on GitHub");
    put("离线与隐私", "Offline and privacy");
    put("第三方许可", "Third-party licences");
    put("纸港 1.0 · 由 kkkzheli 设计与实现 · 与 Goodnotes 无从属关系",
        "PaperHarbor 1.0 · designed and built by kkkzheli · not affiliated with Goodnotes");
    put("轻触即满", "Light touch");
    put("标准", "Standard");
    put("偏重", "Firm");
    put("需要较用力", "Heavy");
    put("纸港 · PaperHarbor", "PaperHarbor");
    put("没有可用的浏览器", "No browser available");
    put("应用清单里没有网络权限，安装包里也查不到 INTERNET。",
        "The manifest declares no network permission and the APK contains no INTERNET entry.");
    put("笔记、识别文本、闪卡和录音都在应用私有目录，其他应用读不到。",
        "Notes, recognised text, flashcards and recordings live in the app's private folder, "
            + "unreadable to other apps.");
    put("没有账号、云同步、统计或广告；断网时功能完全一样。",
        "No account, cloud sync, analytics or ads; everything works the same offline.");
    put("卸载会一并删除本机数据，请先用 .phnote 备份到自己的存储。",
        "Uninstalling removes the local data, so export a .phnote backup to your own storage first.");
    put("只有你点开 GitHub 链接时，才会交给系统浏览器处理。",
        "Only tapping a GitHub link hands anything to the system browser.");
    put("文字识别使用 Tesseract4Android（Apache License 2.0）与其语言数据 chi_sim、eng。",
        "Text recognition uses Tesseract4Android (Apache License 2.0) with the chi_sim and eng models.");
    put("语言数据随安装包提供，识别完全在本机完成。",
        "The models ship inside the APK and recognition runs entirely on this device.");
    put("完整声明见应用资源 assets/ocr/NOTICE.txt 与仓库 licenses/ 目录。\n\n",
        "See assets/ocr/NOTICE.txt inside the app and the licenses/ folder of the repository.\n\n");
    put("界面图标与图形均为本项目原创的矢量资源。",
        "Every UI icon and graphic here is an original vector asset of this project.");
    put("本项目与 Goodnotes 无从属或授权关系，包含的任何名称仅用于说明兼容范围。",
        "This project is not affiliated with or licensed by Goodnotes; any product name is used only "
            + "to describe compatibility.");
    put("没有可用的笔记目录", "No notebook folder available");
    put("已允许使用麦克风，点击新录音即可录制", "Microphone allowed — tap New recording to record");
    put("未获得麦克风权限，仍可播放已有录音", "No microphone permission; existing recordings still play");
    put("笔记数据无法读取，未作更改", "The notebook could not be read, so nothing was changed");
    put("学习卡", "Flashcards");
    put("笔记录音", "Recordings");
    put("保存失败，请检查存储空间后重试", "Save failed — check free space and try again");
    put("最长 10 分钟。关闭窗口或离开页面时会结束并保存有效录音。",
        "Up to 10 minutes. Closing this sheet or leaving the page ends and keeps the recording.");
    put("结束并保存", "Stop and save");
    put("放弃这段录音", "Discard this recording");
    put("新建学习卡", "New flashcard");
    put("等待麦克风授权…", "Waiting for microphone permission…");
    put("新录音", "New recording");
    put("启用麦克风", "Enable microphone");
    put("还没有学习卡。先写一个问题和答案吧。", "No flashcards yet. Write a question and an answer.");
    put("还没有录音。录制和播放均在本机完成。", "No recordings yet. Recording and playback stay on this device.");
    put("上一个", "Previous");
    put("下一个", "Next");
    put("此条目格式无效", "This entry is malformed");
    put("没记住", "Forgot");
    put("不熟悉", "Unsure");
    put("已掌握", "Known");
    put("未评价", "Not rated");
    put("上次自评：", "Last rating: ");
    put("背面 · 答案", "BACK · ANSWER");
    put("正面 · 问题", "FRONT · QUESTION");
    put("看问题", "Show question");
    put("翻看答案", "Show answer");
    put("这次记得怎么样？", "How well did you remember?");
    put("未命名录音", "Untitled recording");
    put("播放", "Play");
    put("停止播放", "Stop");
    put("删除这张卡", "Delete this card");
    put("删除这段录音", "Delete this recording");
    put("正面：问题", "Front: question");
    put("背面：答案", "Back: answer");
    put("请输入问题", "Enter a question");
    put("请输入答案", "Enter an answer");
    put("无法创建学习卡", "Cannot create the card");
    put("无法更新这张学习卡", "Cannot update this card");
    put("删除这张学习卡？", "Delete this flashcard?");
    put("删除这段录音？", "Delete this recording?");
    put("问题、答案和自评记录将一并删除。", "The question, answer and rating history are removed together.");
    put("将移除录音记录及其本地音频文件。", "The entry and its local audio file are removed.");
    put("记录已移除，但音频文件未能删除", "Entry removed, but the audio file could not be deleted");
    put("无法请求麦克风权限", "Cannot request microphone permission");
    put("录音名称（可选）", "Recording name (optional)");
    put("开始录制", "Start recording");
    put("请先允许使用麦克风", "Allow microphone access first");
    put("未能开始录音，请检查麦克风、可用空间或其他录音任务",
        "Could not start recording — check the microphone, free space or another recording in progress");
    put("录音已保存", "Recording saved");
    put("录音未能保存，可能时长过短或存储空间不足",
        "The recording was not saved; it may be too short or the storage is full");
    put("无法播放这段录音，文件可能缺失、损坏或音频正被占用",
        "Cannot play this recording; the file may be missing, damaged or in use");
  }

  private static void put(String chinese, String english) {
    EN.put(chinese, english);
  }
}
