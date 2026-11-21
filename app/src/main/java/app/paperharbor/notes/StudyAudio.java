package app.paperharbor.notes;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.Locale;

/** Main-thread, foreground-only tools for one note. The host owns JSON persistence. */
public final class StudyAudio {
    private static final int SA_MIC = 142, SA_CARDS = 500, SA_CLIPS = 100;
    private static final String SA_PART = ".paperharbor-recording.part";
    private final Activity a;
    private JSONObject doc;
    private File folder;
    private Runnable save;
    private String key;
    private AlertDialog panel, child;
    private LinearLayout body;
    private int index;
    private boolean back, requesting;
    private MediaRecorder recorder;
    private MediaPlayer player;
    private AudioFocusRequest focus;
    private SaDraft draft;
    private String recordName;

    public StudyAudio(Activity a) { this.a = a; }

    public void cards(JSONObject doc, Runnable save) { saOpen(doc, null, save, "cards"); }

    public void audio(JSONObject doc, File noteFolder, Runnable save) {
        if (noteFolder == null) { stop(); saTell("没有可用的笔记目录"); return; }
        saOpen(doc, noteFolder, save, "audio");
    }

    /** Forward only request 142. A grant never starts a microphone in a stale/new screen. */
    public void permissionResult(boolean granted) {
        if (!requesting) return;
        requesting = false;
        if (panel == null || !panel.isShowing() || folder == null) return;
        saTell(granted && saHasMic() ? "已允许使用麦克风，点击新录音即可录制"
                : "未获得麦克风权限，仍可播放已有录音");
        saRender();
    }

    /** Idempotent. Finalize a usable recording before dropping the note's save callback. */
    public void stop() {
        AlertDialog old = panel;
        panel = null;
        requesting = false;
        if (old != null) old.setOnDismissListener(null);
        if (child != null) { child.dismiss(); child = null; }
        saStopPlayback();
        if (recorder != null) saFinishRecording(true);
        if (old != null) old.dismiss();
        body = null; doc = null; folder = null; save = null; key = null;
    }

    private void saOpen(JSONObject d, File f, Runnable s, String k) {
        stop();
        if (a.isFinishing() || a.isDestroyed()) return;
        if (d == null || s == null || (d.has(k) && d.optJSONArray(k) == null)) {
            saTell("笔记数据无法读取，未作更改"); return;
        }
        doc = d; folder = f; save = s; key = k; back = false; index = 0;
        if (f != null) {
            // A killed process releases its lock. Discard its incomplete, unindexed file;
            // never touch a draft locked by another Activity or an unrelated file.
            try (SaDraft abandoned = SaDraft.saAcquire(saDirectory(false), false)) {
                if (abandoned != null) abandoned.saDiscard();
            } catch (IOException | RuntimeException ignored) { /* May not exist yet. */ }
        }
        ScrollView scroll = new ScrollView(a);
        body = saColumn(); scroll.addView(body);
        AlertDialog opened = new AlertDialog.Builder(a).setTitle(f == null ? "学习卡" : "笔记录音")
                .setView(scroll).setNegativeButton("关闭", null).create();
        panel = opened;
        opened.setOnDismissListener(v -> { if (panel == opened) stop(); });
        opened.show(); saRender();
    }

    private LinearLayout saColumn() {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = Math.round(16 * a.getResources().getDisplayMetrics().density);
        box.setPadding(p, p / 2, p, p / 2); return box;
    }

    private void saText(String text, int size) {
        TextView t = new TextView(a); t.setTextSize(size); t.setText(text); body.addView(t);
    }

    private Button saButton(LinearLayout box, String label, Runnable fn) {
        Button b = new Button(a); b.setText(label); b.setOnClickListener(v -> fn.run());
        if (box.getOrientation() == LinearLayout.HORIZONTAL)
            box.addView(b, new LinearLayout.LayoutParams(0, -2, 1));
        else box.addView(b);
        return b;
    }

    private LinearLayout saRow() {
        LinearLayout r = new LinearLayout(a); r.setOrientation(LinearLayout.HORIZONTAL);
        body.addView(r); return r;
    }

    private EditText saInput(LinearLayout box, String hint, int max, boolean multiline) {
        EditText e = new EditText(a);
        e.setHint(hint); e.setSingleLine(!multiline);
        e.setInputType(InputType.TYPE_CLASS_TEXT | (multiline ? InputType.TYPE_TEXT_FLAG_MULTI_LINE : 0));
        e.setImeOptions(e.getImeOptions() | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        e.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        e.setFilters(new InputFilter[]{new InputFilter.LengthFilter(max)});
        box.addView(e); return e;
    }

    private void saChild(AlertDialog dialog) {
        child = dialog;
        dialog.setOnDismissListener(v -> { if (child == dialog) child = null; });
        dialog.show();
    }

    private void saTell(String msg) {
        Toast.makeText(a.getApplicationContext(), msg, Toast.LENGTH_SHORT).show();
    }

    private JSONArray saItems() {
        JSONArray items = doc.optJSONArray(key);
        return items != null ? items : new JSONArray();
    }

    private JSONArray saCopy() {
        JSONArray src = saItems(), result = new JSONArray();
        for (int i = 0; i < src.length(); ++i) result.put(src.opt(i));
        return result;
    }

    // Do not mutate the previous array or its objects: restore it on synchronous save failure.
    private boolean saPersist(JSONArray next) {
        JSONObject target = doc; String field = key; Object previous = target.opt(field);
        try { target.put(field, next); save.run(); return true; }
        catch (JSONException | RuntimeException failure) {
            try { target.put(field, previous); } catch (JSONException ignored) { }
            saTell("保存失败，请检查存储空间后重试"); return false;
        }
    }

    private void saRender() {
        if (panel == null || !panel.isShowing()) return;
        body.removeAllViews(); JSONArray items = saItems();
        int n = items.length(); index = Math.max(0, Math.min(index, n - 1));
        boolean card = folder == null;
        if (!card && recorder != null) {
            saText("正在录音：" + recordName, 20);
            saText("最长 10 分钟。关闭窗口或离开页面时会结束并保存有效录音。", 15);
            saButton(body, "结束并保存", () -> saFinishRecording(true));
            saButton(body, "放弃这段录音", () -> saFinishRecording(false)); return;
        }
        if (card) saButton(body, "新建学习卡", this::saNewCard).setEnabled(n < SA_CARDS);
        else saButton(body, requesting ? "等待麦克风授权…" : (saHasMic() ? "新录音" : "启用麦克风"),
                this::saRecordAction).setEnabled(!requesting && n < SA_CLIPS);
        if (n == 0) { saText(card ? "还没有学习卡。先写一个问题和答案吧。" : "还没有录音。录制和播放均在本机完成。", 16); return; }
        LinearLayout nav = saRow();
        saButton(nav, "上一个", () -> saSelect(index - 1)).setEnabled(index > 0);
        saButton(nav, "下一个", () -> saSelect(index + 1)).setEnabled(index + 1 < n);
        JSONObject item = items.optJSONObject(index);
        saText((index + 1) + " / " + n, 14);
        if (item == null) saText("此条目格式无效", 16);
        else if (card) {
            String rating = item.optString("rating", "new");
            String rated = "again".equals(rating) ? "没记住" : "hard".equals(rating) ? "不熟悉"
                    : "good".equals(rating) ? "已掌握" : "未评价";
            saText("上次自评：" + rated + "　复习 " + item.optInt("reviews") + " 次", 14);
            saText(back ? "背面 · 答案" : "正面 · 问题", 16);
            saText(item.optString(back ? "back" : "front", ""), 21);
            saButton(body, back ? "看问题" : "翻看答案", () -> { back = !back; saRender(); });
            if (back) {
                saText("这次记得怎么样？", 16); LinearLayout row = saRow();
                saButton(row, "没记住", () -> saRate(item, "again"));
                saButton(row, "不熟悉", () -> saRate(item, "hard"));
                saButton(row, "已掌握", () -> saRate(item, "good"));
            }
        } else {
            saText(item.optString("name", "未命名录音"), 20);
            long seconds = Math.max(0, item.optLong("durationMs") / 1000);
            saText(String.format(Locale.ROOT, "时长 %d:%02d", seconds / 60, seconds % 60), 16);
            saButton(body, player == null ? "播放" : "停止播放", () -> {
                if (player == null) saPlay(item); else { saStopPlayback(); saRender(); }
            });
        }
        saButton(body, card ? "删除这张卡" : "删除这段录音", () -> saDelete(item));
    }

    private void saSelect(int i) { saStopPlayback(); index = i; back = false; saRender(); }

    private void saNewCard() {
        LinearLayout fields = saColumn();
        EditText front = saInput(fields, "正面：问题", 2000, true);
        EditText answer = saInput(fields, "背面：答案", 2000, true);
        ScrollView scroll = new ScrollView(a); scroll.addView(fields);
        AlertDialog form = new AlertDialog.Builder(a).setTitle("新建学习卡").setView(scroll)
                .setNegativeButton("取消", null).setPositiveButton("保存", null).create();
        saChild(form);
        form.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String q = front.getText().toString().trim(), r = answer.getText().toString().trim();
            if (q.isEmpty()) { front.setError("请输入问题"); return; }
            if (r.isEmpty()) { answer.setError("请输入答案"); return; }
            JSONArray next = saCopy(); if (next.length() >= SA_CARDS) return;
            try { next.put(new JSONObject().put("front", q).put("back", r).put("rating", "new").put("reviews", 0)); }
            catch (JSONException ex) { saTell("无法创建学习卡"); return; }
            if (saPersist(next)) { form.dismiss(); saSelect(next.length() - 1); }
        });
    }

    private void saRate(JSONObject item, String rating) {
        JSONArray next = saCopy(); if (next.opt(index) != item) { saRender(); return; }
        try {
            JSONObject edited = new JSONObject(item.toString()); int count = Math.max(0, item.optInt("reviews"));
            edited.put("reviews", count == Integer.MAX_VALUE ? count : count + 1).put("rating", rating);
            next.put(index, edited);
            if (saPersist(next)) saSelect((index + 1) % next.length());
        } catch (JSONException ex) { saTell("无法更新这张学习卡"); }
    }

    private void saDelete(JSONObject item) {
        boolean card = folder == null; final int pos = index;
        saChild(new AlertDialog.Builder(a).setTitle(card ? "删除这张学习卡？" : "删除这段录音？")
                .setMessage(card ? "问题、答案和自评记录将一并删除。" : "将移除录音记录及其本地音频文件。")
                .setNegativeButton("取消", null).setPositiveButton("删除", (v, w) -> {
                    JSONArray next = saCopy();
                    if (pos >= next.length() || next.optJSONObject(pos) != item) { saRender(); return; }
                    saStopPlayback(); next.remove(pos);
                    File clip = null;
                    if (!card && item != null && !saReferences(next, item.optString("path"))) {
                        try { clip = saClip(item.optString("path")); }
                        catch (IOException | RuntimeException ignored) { /* Never follow invalid metadata. */ }
                    }
                    if (saPersist(next)) {
                        if (clip != null) try { Files.deleteIfExists(clip.toPath()); }
                        catch (IOException | RuntimeException ex) { saTell("记录已移除，但音频文件未能删除"); }
                        back = false;
                    }
                    saRender();
                }).create());
    }

    private boolean saHasMic() {
        return a.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void saRecordAction() {
        saStopPlayback(); saRender();
        if (!saHasMic()) {
            requesting = true; saRender();
            try { a.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, SA_MIC); }
            catch (RuntimeException ex) { requesting = false; saTell("无法请求麦克风权限"); saRender(); }
            return;
        }
        LinearLayout box = saColumn(); EditText title = saInput(box, "录音名称（可选）", 80, false);
        saChild(new AlertDialog.Builder(a).setTitle("新录音").setView(box).setNegativeButton("取消", null)
                .setPositiveButton("开始录制", (v, w) -> {
                    String name = title.getText().toString().trim();
                    saRecord(name.isEmpty() ? "录音 " + (saItems().length() + 1) : name);
                }).create());
    }

    private File saDirectory(boolean create) throws IOException {
        if (folder == null || Files.isSymbolicLink(folder.toPath()) || !folder.isDirectory())
            throw new IOException("note directory");
        File dir = new File(folder.getCanonicalFile(), "audio");
        if (create && !dir.exists()) dir.mkdir();
        if (!Files.isDirectory(dir.toPath(), LinkOption.NOFOLLOW_LINKS)) throw new IOException("audio directory");
        return dir;
    }

    private File saClip(String relative) throws IOException {
        if (!relative.matches("audio/clip-[1-9][0-9]{0,3}\\.m4a")) throw new IOException("audio path");
        File file = new File(saDirectory(false), relative.substring(6));
        if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) throw new IOException("audio file");
        return file;
    }

    private boolean saReferences(JSONArray items, String path) {
        for (int i = 0; i < items.length(); ++i) {
            JSONObject o = items.optJSONObject(i);
            if (o != null && path.equals(o.optString("path"))) return true;
        }
        return false;
    }

    /** One locked staging file per private note directory. No unfinished clip enters the JSON. */
    private static final class SaDraft implements AutoCloseable {
        final File path;
        final RandomAccessFile data;
        final FileLock lock;
        private SaDraft(File p, RandomAccessFile d, FileLock l) { path = p; data = d; lock = l; }

        static SaDraft saAcquire(File dir, boolean create) throws IOException {
            File p = new File(dir, SA_PART);
            if (!Files.isRegularFile(p.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                if (!create) return null;
                if (!p.createNewFile()) throw new IOException("invalid staging file");
            }
            RandomAccessFile data = new RandomAccessFile(p, "rw");
            try {
                FileLock l = data.getChannel().tryLock();
                if (l == null) throw new IOException("recording busy");
                return new SaDraft(p, data, l);
            } catch (IOException | RuntimeException ex) { data.close(); throw ex; }
        }
        void saDiscard() { try { Files.deleteIfExists(path.toPath()); } catch (IOException | RuntimeException ignored) { } }
        @Override public void close() {
            try { lock.release(); } catch (IOException ignored) { }
            try { data.close(); } catch (IOException ignored) { }
        }
    }

    @SuppressWarnings("deprecation") // The no-arg recorder also works on API 26–30.
    private void saRecord(String name) {
        if (panel == null || !panel.isShowing() || recorder != null || saItems().length() >= SA_CLIPS) return;
        if (!saHasMic()) { saTell("请先允许使用麦克风"); saRender(); return; }
        try {
            draft = SaDraft.saAcquire(saDirectory(true), true); draft.data.setLength(0);
            MediaRecorder r = new MediaRecorder(); recorder = r; recordName = name;
            if (!saTakeFocus(true, () -> { if (recorder == r) saFinishRecording(true); }))
                throw new IOException("audio busy");
            r.setAudioSource(MediaRecorder.AudioSource.MIC); r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC); r.setAudioChannels(1);
            r.setAudioSamplingRate(44100); r.setAudioEncodingBitRate(64000);
            r.setMaxDuration(600000); r.setMaxFileSize(12L * 1024 * 1024);
            r.setOutputFile(draft.data.getFD());
            r.setOnInfoListener((src, what, extra) -> {
                if (src == recorder && (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED
                        || what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED)) saFinishRecording(true);
            });
            r.setOnErrorListener((src, what, extra) -> { if (src == recorder) saFinishRecording(true); });
            r.prepare(); r.start();
        } catch (IOException | RuntimeException ex) {
            saFinishRecording(false); saTell("未能开始录音，请检查麦克风、可用空间或其他录音任务");
        }
        saRender();
    }

    private void saFinishRecording(boolean keep) {
        MediaRecorder r = recorder; SaDraft staged = draft; String name = recordName;
        recorder = null; draft = null; recordName = null;
        if (r != null) {
            try { r.stop(); } catch (RuntimeException ignored) { /* System may already have stopped it. */ }
            try { r.release(); } catch (RuntimeException ignored) { }
        }
        saDropFocus();
        if (staged == null) return;
        try (SaDraft owned = staged) {
            boolean moved = false;
            try {
                if (keep) {
                    long duration = saDuration(owned); JSONArray next = saCopy();
                    if (duration <= 0 || next.length() >= SA_CLIPS) throw new IOException("invalid clip");
                    File dir = saDirectory(false);
                    if (!dir.equals(owned.path.getParentFile())) throw new IOException("note moved");
                    File dest = null; String path = null;
                    for (int n = 1; n <= 9999; ++n) {
                        String base = "clip-" + n + ".m4a"; File candidate = new File(dir, base);
                        if (!Files.exists(candidate.toPath(), LinkOption.NOFOLLOW_LINKS) && !saReferences(next, "audio/" + base)) {
                            dest = candidate; path = "audio/" + base; break;
                        }
                    }
                    if (dest == null) throw new IOException("no filename available");
                    next.put(new JSONObject().put("name", name).put("path", path).put("durationMs", duration)
                            .put("createdAt", System.currentTimeMillis()));
                    Files.move(owned.path.toPath(), dest.toPath());
                    moved = true; // This staging name can now belong to a different recording.
                    if (saPersist(next)) { index = next.length() - 1; saTell("录音已保存"); }
                    else Files.deleteIfExists(dest.toPath());
                }
            } catch (IOException | JSONException | RuntimeException ex) { saTell("录音未能保存，可能时长过短或存储空间不足"); }
            finally { if (!moved) owned.saDiscard(); }
        }
        saRender();
    }

    private long saDuration(SaDraft staged) throws IOException {
        MediaMetadataRetriever meta = new MediaMetadataRetriever();
        try {
            meta.setDataSource(staged.data.getFD(), 0, staged.data.length());
            if (!"yes".equals(meta.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO))) return 0;
            return Long.parseLong(meta.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
        } finally { meta.release(); }
    }

    private AudioAttributes saAttributes() {
        return new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
    }

    private boolean saTakeFocus(boolean exclusive, Runnable onLoss) {
        AudioManager manager = (AudioManager) a.getSystemService(Context.AUDIO_SERVICE);
        if (manager == null) return false;
        focus = new AudioFocusRequest.Builder(exclusive ? AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
                : AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(saAttributes())
                .setWillPauseWhenDucked(true).setOnAudioFocusChangeListener(change -> {
                    if (change < 0) onLoss.run();
                }).build();
        return manager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    private void saDropFocus() {
        AudioFocusRequest previous = focus; focus = null;
        if (previous != null) try {
            AudioManager manager = (AudioManager) a.getSystemService(Context.AUDIO_SERVICE);
            if (manager != null) manager.abandonAudioFocusRequest(previous);
        } catch (RuntimeException ignored) { }
    }

    private void saPlay(JSONObject item) {
        if (recorder != null) return;
        saStopPlayback();
        try (FileInputStream in = new FileInputStream(saClip(item.optString("path")))) {
            MediaPlayer p = new MediaPlayer(); player = p;
            if (!saTakeFocus(false, () -> { if (player == p) { saStopPlayback(); saRender(); } }))
                throw new IOException("audio busy");
            p.setAudioAttributes(saAttributes()); p.setDataSource(in.getFD());
            p.setOnPreparedListener(src -> {
                if (player != src) return;
                try { src.start(); } catch (RuntimeException ex) { saPlayError(); }
            });
            p.setOnCompletionListener(src -> { if (player == src) { saStopPlayback(); saRender(); } });
            p.setOnErrorListener((src, what, extra) -> { if (player == src) saPlayError(); return true; });
            p.prepareAsync(); saRender();
        } catch (IOException | RuntimeException ex) { saPlayError(); }
    }

    private void saPlayError() { saStopPlayback(); saTell("无法播放这段录音，文件可能缺失、损坏或音频正被占用"); saRender(); }

    private void saStopPlayback() {
        MediaPlayer p = player; player = null;
        if (p != null) {
            try { p.release(); } catch (RuntimeException ignored) { }
            saDropFocus();
        }
    }
}
