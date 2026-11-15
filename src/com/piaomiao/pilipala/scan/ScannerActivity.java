package com.piaomiao.pilipala.scan;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.hardware.Camera;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 扫码页，跑在模块自己的进程里（这样相机权限归模块自己管）。
 *
 * 两种进入方式：
 *  1) 宿主里的悬浮球带 EXTRA_TARGET 进来 —— 扫完直接交回宿主：链接交给它自己路由，登录码广播回宿主确认
 *  2) 桌面图标直接打开（没装 LSPosed 也能用） —— 扫完给一个选择框，挑用哪个应用打开
 */
public class ScannerActivity extends Activity implements SurfaceHolder.Callback, Camera.PreviewCallback {

    public static final String EXTRA_TARGET = "extra_target_package";
    public static final String EXTRA_GALLERY = "extra_gallery_mode";
    public static final String EXTRA_RESULT = "extra_qrcode_text_result";

    private static final int REQ_PERMISSION = 1;
    private static final int REQ_PICK = 2;

    private SurfaceView surfaceView;
    private OverlayView overlayView;
    private TextView tipView;
    private TextView torchBtn;

    private Camera camera;
    private boolean decoding;
    private boolean torchOn;
    private boolean finished;

    private String target;
    private boolean galleryMode;

    private final Handler main = new Handler(Looper.getMainLooper());
    private ToneGenerator tone;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        target = getIntent().getStringExtra(EXTRA_TARGET);
        galleryMode = getIntent().getBooleanExtra(EXTRA_GALLERY, false);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        surfaceView = new SurfaceView(this);
        root.addView(surfaceView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        surfaceView.getHolder().addCallback(this);

        overlayView = new OverlayView(this);
        root.addView(overlayView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        tipView = new TextView(this);
        tipView.setText(galleryMode ? "从相册选一张带二维码的图片" : "把二维码放进框里 · 长按悬浮球可走相册");
        tipView.setTextColor(0xCCFFFFFF);
        tipView.setTextSize(13);
        FrameLayout.LayoutParams tipLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tipLp.gravity = Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM;
        tipLp.bottomMargin = dp(150);
        root.addView(tipView, tipLp);

        root.addView(button("✕", BTN_CLOSE), buttonLp(Gravity.START | Gravity.TOP, 16, 0));
        torchBtn = button("手电", BTN_TORCH);
        root.addView(torchBtn, buttonLp(Gravity.END | Gravity.TOP, 16, 0));
        root.addView(button("相册识别", BTN_GALLERY), buttonLp(Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM, 16, 0));

        setContentView(root);
        setResult(RESULT_CANCELED);

        if (galleryMode) {
            pickImage();
            return;
        }
        if (Build.VERSION.SDK_INT >= 23 && !hasCameraPermission()) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_PERMISSION);
        }
    }

    /* ---------------------------------------------------------------- 顶栏按钮 */

    private static final int BTN_CLOSE = 0;
    private static final int BTN_TORCH = 1;
    private static final int BTN_GALLERY = 2;

    private FrameLayout.LayoutParams buttonLp(int gravity, int marginDp, int topDp) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, gravity);
        lp.setMargins(dp(marginDp), dp(24), dp(marginDp), dp(40));
        return lp;
    }

    private TextView button(String text, int kind) {
        final TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(14);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(16), dp(10), dp(16), dp(10));
        tv.setBackgroundColor(0x33FFFFFF);
        switch (kind) {
            case BTN_CLOSE:
                tv.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        finish();
                    }
                });
                break;
            case BTN_GALLERY:
                tv.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        pickImage();
                    }
                });
                break;
            default:
                tv.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        toggleTorch();
                        tv.setText(torchOn ? "手电开" : "手电");
                    }
                });
                break;
        }
        return tv;
    }

    /* ---------------------------------------------------------------- 相机 */

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_PERMISSION) return;
        if (grantResults != null && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            openCamera();
        } else {
            Toast.makeText(this, "没有相机权限，点下面「相册识别」也能扫", Toast.LENGTH_LONG).show();
        }
    }

    /** API 21/22 没有运行时权限，直接算有 */
    private boolean hasCameraPermission() {
        if (Build.VERSION.SDK_INT < 23) return true;
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private void openCamera() {
        if (camera != null) return;
        if (surfaceView == null || !surfaceView.getHolder().getSurface().isValid()) return;
        try {
            camera = Camera.open();
            Camera.Parameters p = camera.getParameters();
            try {
                p.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            } catch (Throwable ignore) {
            }
            List<Camera.Size> sizes = p.getSupportedPreviewSizes();
            Camera.Size best = null;
            for (Camera.Size s : sizes) {
                int px = Math.max(s.width, s.height);
                if (best == null || (Math.abs(px - 1280) < Math.abs(Math.max(best.width, best.height) - 1280))) {
                    best = s;
                }
            }
            if (best != null) p.setPreviewSize(best.width, best.height);
            camera.setParameters(p);
            camera.setDisplayOrientation(90);
            camera.setPreviewDisplay(surfaceView.getHolder());
            camera.setPreviewCallback(this);
            camera.startPreview();
        } catch (Throwable t) {
            Toast.makeText(this, "相机打不开：" + t, Toast.LENGTH_LONG).show();
        }
    }

    private void toggleTorch() {
        if (camera == null) return;
        try {
            Camera.Parameters p = camera.getParameters();
            torchOn = !torchOn;
            p.setFlashMode(torchOn ? Camera.Parameters.FLASH_MODE_TORCH : Camera.Parameters.FLASH_MODE_OFF);
            camera.setParameters(p);
        } catch (Throwable ignore) {
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        openCamera();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        releaseCamera();
    }

    private void releaseCamera() {
        if (camera != null) {
            try {
                camera.setPreviewCallback(null);
                camera.stopPreview();
                camera.release();
            } catch (Throwable ignore) {
            }
            camera = null;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (galleryMode) return;
        if (hasCameraPermission()) {
            openCamera();
        }
    }

    @Override
    protected void onPause() {
        releaseCamera();
        super.onPause();
    }

    /* ---------------------------------------------------------------- 解码 */

    @Override
    public void onPreviewFrame(final byte[] data, Camera cam) {
        if (decoding || finished || data == null) return;
        decoding = true;
        final int width = cam.getParameters().getPreviewSize().width;
        final int height = cam.getParameters().getPreviewSize().height;
        final byte[] frame = data.clone();

        new Thread(new Runnable() {
            @Override
            public void run() {
                String text = null;
                try {
                    PlanarYUVLuminanceSource source =
                            new PlanarYUVLuminanceSource(frame, width, height, 0, 0, width, height, false);
                    BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));

                    Map<DecodeHintType, Object> hints = new EnumMap<DecodeHintType, Object>(DecodeHintType.class);
                    List<BarcodeFormat> formats = new ArrayList<BarcodeFormat>();
                    formats.add(BarcodeFormat.QR_CODE);
                    hints.put(DecodeHintType.POSSIBLE_FORMATS, formats);
                    hints.put(DecodeHintType.CHARACTER_SET, "UTF-8");
                    hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);

                    MultiFormatReader reader = new MultiFormatReader();
                    reader.setHints(hints);
                    Result result = reader.decodeWithState(bitmap);
                    if (result != null) text = result.getText();
                } catch (Throwable ignore) {
                } finally {
                    decoding = false;
                }
                if (text != null) {
                    final String t = text;
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            onDecoded(t);
                        }
                    });
                }
            }
        }, "pilipala-scan-decode").start();
    }

    /* ---------------------------------------------------------------- 相册 */

    private void pickImage() {
        Intent doc = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        doc.addCategory(Intent.CATEGORY_OPENABLE);
        doc.setType("image/*");
        try {
            startActivityForResult(doc, REQ_PICK);
            return;
        } catch (Throwable ignore) {
        }
        Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
        pick.setType("image/*");
        try {
            startActivityForResult(pick, REQ_PICK);
        } catch (Throwable t) {
            Toast.makeText(this, "打不开相册", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK) return;
        if (resultCode != RESULT_OK || data == null) {
            if (galleryMode) finish();
            return;
        }
        Uri uri = data.getData();
        if (uri == null) {
            if (galleryMode) finish();
            return;
        }
        Toast.makeText(this, "正在识别…", Toast.LENGTH_SHORT).show();
        final Uri finalUri = uri;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String text = QrDecoder.decode(ScannerActivity.this, finalUri);
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (text == null) {
                            Toast.makeText(ScannerActivity.this, "图里没识别到二维码", Toast.LENGTH_SHORT).show();
                            if (galleryMode) finish();
                        } else {
                            onDecoded(text);
                        }
                    }
                });
            }
        }, "pilipala-scan-gallery").start();
    }

    /* ---------------------------------------------------------------- 结果分发 */

    private void onDecoded(String text) {
        if (finished) return;
        finished = true;
        String t = text.trim();
        if (t.isEmpty()) return;
        beep();
        copy(t);

        if (target != null && ScanModule.isHost(target)) {
            deliverToHost(t);
        } else {
            showChooser(t, LinkRouter.toSchemeUrl(t), false);
        }
    }

    /** 从宿主进来：链接直接交给宿主路由，登录码回宿主进程确认 */
    private void deliverToHost(final String text) {
        if (LoginConfirm.isLoginQr(text)) {
            Intent intent = new Intent(ScanModule.ACTION_RESULT);
            intent.setPackage(target);
            intent.putExtra(EXTRA_RESULT, text);
            try {
                sendBroadcast(intent);
                toast("正在用当前账号确认登录…");
            } catch (Throwable t) {
                toast("回传失败：" + t);
            }
            finish();
            return;
        }

        String url = LinkRouter.toSchemeUrl(text);
        if (url == null) {
            toast("不是 B 站链接，已经复制到剪贴板");
            finish();
            return;
        }
        if (LinkRouter.isShortLink(url)) {
            toast("正在解析短链…");
            resolveThen(url, new UrlCallback() {
                @Override
                public void onUrl(String resolved) {
                    openInHost(resolved != null ? resolved : url);
                }
            });
            return;
        }
        openInHost(url);
    }

    private void openInHost(String url) {
        if (LinkRouter.open(this, target, url)) {
            finish();
        } else {
            toast("打不开目标应用，链接已复制");
            finish();
        }
    }

    private interface UrlCallback {
        void onUrl(String url);
    }

    private void resolveThen(final String url, final UrlCallback cb) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String resolved = LinkRouter.resolveShortLink(url);
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        cb.onUrl(resolved);
                    }
                });
            }
        }, "pilipala-scan-resolve").start();
    }

    /** 桌面图标直接打开时：给一个选择框 */
    private void showChooser(final String text, final String url, final boolean resolved) {
        if (!resolved && url != null && LinkRouter.isShortLink(url)) {
            toast("正在解析短链…");
            resolveThen(url, new UrlCallback() {
                @Override
                public void onUrl(String result) {
                    showChooser(text, result != null ? result : url, true);
                }
            });
            return;
        }

        final List<String> labels = new ArrayList<String>();
        final List<String> acts = new ArrayList<String>();

        if (url != null) {
            if (LinkRouter.installed(this, ScanModule.PKG_PILIPALA)) {
                labels.add("在 PiLiPala 中打开");
                acts.add(ScanModule.PKG_PILIPALA);
            }
            if (LinkRouter.installed(this, ScanModule.PKG_PILIPLUS)) {
                labels.add("在 PiliPlus 中打开");
                acts.add(ScanModule.PKG_PILIPLUS);
            }
            if (url.startsWith("http")) {
                labels.add("用浏览器打开");
                acts.add("");
            }
        }
        labels.add("只复制文本");
        acts.add(null);

        try {
            new AlertDialog.Builder(this)
                    .setTitle("识别结果")
                    .setMessage(text.length() > 240 ? text.substring(0, 240) + "…" : text)
                    .setItems(labels.toArray(new String[0]), new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            String act = acts.get(which);
                            if (act == null) {
                                toast("已复制到剪贴板");
                                finish();
                            } else if (act.isEmpty()) {
                                LinkRouter.open(ScannerActivity.this, null, url);
                                finish();
                            } else if (!LinkRouter.open(ScannerActivity.this, act, url)) {
                                toast("打不开 " + act + "，链接已复制");
                                finish();
                            } else {
                                finish();
                            }
                        }
                    })
                    .setOnCancelListener(new DialogInterface.OnCancelListener() {
                        @Override
                        public void onCancel(DialogInterface dialog) {
                            finish();
                        }
                    })
                    .show();
        } catch (Throwable t) {
            toast("已复制到剪贴板");
            finish();
        }
    }

    /* ---------------------------------------------------------------- 工具 */

    private void beep() {
        try {
            if (tone == null) tone = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80);
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, 120);
        } catch (Throwable ignore) {
        }
    }

    private void copy(String text) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("scan", text));
        } catch (Throwable ignore) {
        }
    }

    private void toast(final String msg) {
        main.post(new Runnable() {
            @Override
            public void run() {
                try {
                    Toast.makeText(ScannerActivity.this, msg, Toast.LENGTH_SHORT).show();
                } catch (Throwable ignore) {
                }
            }
        });
    }

    @Override
    protected void onDestroy() {
        releaseCamera();
        if (tone != null) {
            try {
                tone.release();
            } catch (Throwable ignore) {
            }
            tone = null;
        }
        super.onDestroy();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    /* ------------------------------------------------------------------ 蒙层 */

    private static class OverlayView extends View {
        private final Paint dim = new Paint();
        private final Paint frame = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF hole = new RectF();

        OverlayView(Activity context) {
            super(context);
            dim.setColor(0x99000000);
            frame.setColor(0xFFFB7299);
            frame.setStyle(Paint.Style.STROKE);
            frame.setStrokeCap(Paint.Cap.ROUND);
            frame.setStrokeWidth(getResources().getDisplayMetrics().density * 3f);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            DisplayMetrics dm = getResources().getDisplayMetrics();
            float size = Math.min(getWidth(), getHeight()) * 0.62f;
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            hole.set(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2);

            canvas.drawRect(0, 0, getWidth(), hole.top, dim);
            canvas.drawRect(0, hole.bottom, getWidth(), getHeight(), dim);
            canvas.drawRect(0, hole.top, hole.left, hole.bottom, dim);
            canvas.drawRect(hole.right, hole.top, getWidth(), hole.bottom, dim);

            float arm = size * 0.12f;
            float r = size * 0.06f;
            canvas.drawLine(hole.left, hole.top + arm, hole.left, hole.top + r, frame);
            canvas.drawLine(hole.left, hole.top, hole.left + arm, hole.top, frame);
            canvas.drawLine(hole.right - arm, hole.top, hole.right, hole.top, frame);
            canvas.drawLine(hole.right, hole.top, hole.right, hole.top + arm, frame);
            canvas.drawLine(hole.right, hole.bottom - arm, hole.right, hole.bottom, frame);
            canvas.drawLine(hole.right, hole.bottom, hole.right - arm, hole.bottom, frame);
            canvas.drawLine(hole.left + arm, hole.bottom, hole.left, hole.bottom, frame);
            canvas.drawLine(hole.left, hole.bottom, hole.left, hole.bottom - arm, frame);
        }
    }
}
