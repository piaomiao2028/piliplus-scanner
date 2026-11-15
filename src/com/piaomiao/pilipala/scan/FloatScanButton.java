package com.piaomiao.pilipala.scan;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

/**
 * 宿主界面是 Flutter 自绘的，插不进原生 View，入口只能做成贴边悬浮球：
 * 点一下扫码、长按从相册识别、按住可以拖，松手自动吸边并记住位置。
 */
public class FloatScanButton extends View {

    public interface OnActionListener {
        void onScan();

        void onGallery();

        void onMoved(int left, int top);
    }

    private static final int SIZE_DP = 46;
    private static final int LONG_PRESS_MS = 550;
    private static final float IDLE_ALPHA = 0.55f;
    /** 哔哩哔哩粉 */
    private static final int ACCENT = 0xFFFB7299;

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF iconRect = new RectF();

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable longPressRunnable = new Runnable() {
        @Override
        public void run() {
            if (pressed && !moved && listener != null) {
                longPressed = true;
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                listener.onGallery();
            }
        }
    };

    private final Runnable dimRunnable = new Runnable() {
        @Override
        public void run() {
            if (!pressed) animate().alpha(IDLE_ALPHA).setDuration(400).start();
        }
    };

    private OnActionListener listener;

    private boolean pressed;
    private boolean moved;
    private boolean longPressed;
    private float downRawX;
    private float downRawY;
    private float lastRawX;
    private float lastRawY;
    private final int touchSlop;

    public FloatScanButton(Context context) {
        super(context);
        touchSlop = Math.max(8, (int) (8 * context.getResources().getDisplayMetrics().density));
        bgPaint.setColor(ACCENT);
        bgPaint.setAlpha(230);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(dp(1));
        ringPaint.setColor(0x33FFFFFF);
        iconPaint.setStyle(Paint.Style.STROKE);
        iconPaint.setStrokeWidth(dp(2));
        iconPaint.setColor(0xFFFFFFFF);
        iconPaint.setStrokeCap(Paint.Cap.ROUND);
        setClickable(true);
        setFocusable(true);
        setAlpha(IDLE_ALPHA);
        setContentDescription("扫码");
    }

    public static int sizePx(Context context) {
        return (int) (SIZE_DP * context.getResources().getDisplayMetrics().density + 0.5f);
    }

    public void setOnActionListener(OnActionListener l) {
        listener = l;
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int size = sizePx(getContext());
        setMeasuredDimension(size, size);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        float cx = w / 2f;
        float cy = h / 2f;
        float r = Math.min(w, h) / 2f - dp(1);

        canvas.drawCircle(cx, cy, r, bgPaint);
        canvas.drawCircle(cx, cy, r, ringPaint);

        // 扫码图标：四个角括号 + 中间横线
        float inset = Math.min(w, h) * 0.26f;
        iconRect.set(inset, inset, w - inset, h - inset);
        float arm = iconRect.width() * 0.42f;

        // 左上
        canvas.drawLine(iconRect.left, iconRect.top + arm, iconRect.left, iconRect.top, iconPaint);
        canvas.drawLine(iconRect.left, iconRect.top, iconRect.left + arm, iconRect.top, iconPaint);
        // 右上
        canvas.drawLine(iconRect.right - arm, iconRect.top, iconRect.right, iconRect.top, iconPaint);
        canvas.drawLine(iconRect.right, iconRect.top, iconRect.right, iconRect.top + arm, iconPaint);
        // 右下
        canvas.drawLine(iconRect.right, iconRect.bottom - arm, iconRect.right, iconRect.bottom, iconPaint);
        canvas.drawLine(iconRect.right, iconRect.bottom, iconRect.right - arm, iconRect.bottom, iconPaint);
        // 左下
        canvas.drawLine(iconRect.left + arm, iconRect.bottom, iconRect.left, iconRect.bottom, iconPaint);
        canvas.drawLine(iconRect.left, iconRect.bottom, iconRect.left, iconRect.bottom - arm, iconPaint);

        float midY = cy;
        canvas.drawLine(cx - iconRect.width() * 0.22f, midY, cx + iconRect.width() * 0.22f, midY, iconPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pressed = true;
                moved = false;
                longPressed = false;
                downRawX = event.getRawX();
                downRawY = event.getRawY();
                lastRawX = downRawX;
                lastRawY = downRawY;
                handler.removeCallbacks(dimRunnable);
                setAlpha(1f);
                handler.postDelayed(longPressRunnable, LONG_PRESS_MS);
                return true;

            case MotionEvent.ACTION_MOVE: {
                float dx = event.getRawX() - downRawX;
                float dy = event.getRawY() - downRawY;
                if (!moved && Math.sqrt(dx * dx + dy * dy) > touchSlop) {
                    moved = true;
                    handler.removeCallbacks(longPressRunnable);
                }
                if (moved) {
                    moveBy(event.getRawX() - lastRawX, event.getRawY() - lastRawY);
                    lastRawX = event.getRawX();
                    lastRawY = event.getRawY();
                }
                return true;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                pressed = false;
                handler.removeCallbacks(longPressRunnable);
                handler.postDelayed(dimRunnable, 1500);
                if (longPressed) return true;
                if (moved) {
                    snapToEdge();
                    return true;
                }
                performClick();
                return true;

            default:
                return super.onTouchEvent(event);
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        if (listener != null) listener.onScan();
        return true;
    }

    /** 拖动时把布局参数换成左上角定位，跟随手指移动 */
    private void moveBy(float dx, float dy) {
        ViewGroup.LayoutParams raw = getLayoutParams();
        if (!(raw instanceof FrameLayout.LayoutParams)) return;
        if (!(getParent() instanceof View)) return;

        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) raw;
        int parentW = ((View) getParent()).getWidth();
        int parentH = ((View) getParent()).getHeight();

        int left;
        int top;
        if (lp.gravity == (Gravity.TOP | Gravity.START)) {
            left = lp.leftMargin;
            top = lp.topMargin;
        } else {
            left = getLeft();
            top = getTop();
        }

        left += (int) dx;
        top += (int) dy;
        left = Math.max(0, Math.min(left, parentW - getWidth()));
        top = Math.max(0, Math.min(top, parentH - getHeight()));

        lp.gravity = Gravity.TOP | Gravity.START;
        lp.leftMargin = left;
        lp.topMargin = top;
        setLayoutParams(lp);
    }

    /** 松手后吸到左右最近的一边 */
    private void snapToEdge() {
        ViewGroup.LayoutParams raw = getLayoutParams();
        if (!(raw instanceof FrameLayout.LayoutParams)) return;
        if (!(getParent() instanceof View)) return;

        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) raw;
        View parent = (View) getParent();
        int parentW = parent.getWidth();
        int parentH = parent.getHeight();

        int left = lp.leftMargin;
        int top = lp.topMargin;
        int margin = (int) dp(10);

        if (left + getWidth() / 2f < parentW / 2f) {
            left = margin;
        } else {
            left = parentW - getWidth() - margin;
        }
        int minTop = (int) dp(40);
        int maxTop = parentH - getHeight() - (int) dp(40);
        if (maxTop < minTop) maxTop = minTop;
        top = Math.max(minTop, Math.min(top, maxTop));

        lp.gravity = Gravity.TOP | Gravity.START;
        lp.leftMargin = left;
        lp.topMargin = top;
        setLayoutParams(lp);

        if (listener != null) listener.onMoved(left, top);
    }
}
