package com.piaomiao.pilipala.scan;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.Toast;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * PiLiPala 扫码 1.0 —— 给 PiLiPala / PiliPlus 用的扫码模块。
 *
 * 与哔哩喵版最大的不同：这两个宿主都是 Flutter 应用，整个界面由 Flutter 自绘，
 * 拿不到「底栏那一排」的原生 View，所以入口做成贴边悬浮球（可拖动、位置记忆）。
 * 扫码页仍跑在模块自己的进程里，扫完把结果交回宿主：
 *   - BV / av / b23 短链 / bilibili://  → 通过 ACTION_VIEW 交给宿主自己路由
 *   - 登录二维码                        → 广播回宿主进程，用宿主当前账号确认登录
 */
public class ScanModule implements IXposedHookLoadPackage {

    public static final String TAG = "PiLiScan: ";

    /** PiLiPala 原版 */
    public static final String PKG_PILIPALA = "com.guozhigq.pilipala";
    /** PiliPlus（PiLiPala 的开源改版） */
    public static final String PKG_PILIPLUS = "com.example.piliplus";

    public static final String[] HOSTS = {PKG_PILIPALA, PKG_PILIPLUS};

    public static final String MODULE_PKG = "com.piaomiao.pilipala.scan";

    public static final String ACTION_RESULT = MODULE_PKG + ".QR_RESULT";
    public static final String PERMISSION = MODULE_PKG + ".permission.QR_RESULT";

    private static final String FAB_TAG = "pilipala_scan_fab_v1";
    private static final String PREFS = "pilipala_scan_mod";
    private static final String KEY_X = "fab_x";
    private static final String KEY_Y = "fab_y";

    private static volatile boolean sReceiverRegistered;

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {
        if (lpparam == null || !isHost(lpparam.packageName)) return;
        XposedBridge.log(TAG + "loaded in " + lpparam.packageName);

        try {
            XposedBridge.hookAllMethods(Instrumentation.class, "callActivityOnResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.args == null || param.args.length < 1) return;
                    Object arg = param.args[0];
                    if (!(arg instanceof Activity)) return;
                    Activity activity = (Activity) arg;
                    try {
                        ensureReceiver(activity.getApplicationContext());
                        attachFab(activity);
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + "attach: " + t);
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + "hook resume failed: " + t);
        }

        // 横屏（多半在看视频 / 全屏）时把悬浮球收起来，免得挡画面
        try {
            XposedBridge.hookAllMethods(Activity.class, "onConfigurationChanged", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object self = param.thisObject;
                    if (!(self instanceof Activity)) return;
                    Activity activity = (Activity) self;
                    try {
                        ViewGroup content = activity.findViewById(android.R.id.content);
                        if (content == null) return;
                        android.view.View fab = content.findViewWithTag(FAB_TAG);
                        if (fab == null) return;
                        boolean landscape = activity.getResources().getConfiguration().orientation
                                == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
                        fab.setVisibility(landscape ? android.view.View.GONE : android.view.View.VISIBLE);
                    } catch (Throwable ignore) {
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + "hook config failed: " + t);
        }
    }

    public static boolean isHost(String pkg) {
        if (pkg == null) return false;
        for (String host : HOSTS) {
            if (host.equals(pkg)) return true;
        }
        return false;
    }

    /* ------------------------------------------------------------- 回传通道 */

    /**
     * 宿主进程里注册接收器，接扫码页发回来的登录二维码。
     * 发送方必须持有 MODULE_PKG 的签名级权限，别的应用伪造不了这条广播。
     */
    private static void ensureReceiver(final Context app) {
        if (sReceiverRegistered || app == null) return;
        sReceiverRegistered = true;
        try {
            IntentFilter filter = new IntentFilter(ACTION_RESULT);
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    try {
                        if (intent == null) return;
                        String text = intent.getStringExtra(ScannerActivity.EXTRA_RESULT);
                        if (text == null || text.trim().isEmpty()) return;
                        XposedBridge.log(TAG + "login qr from scanner");
                        LoginConfirm.confirm(app, text.trim());
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + "receiver: " + t);
                    }
                }
            };
            if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(receiver, filter, PERMISSION, null, Context.RECEIVER_EXPORTED);
            } else {
                app.registerReceiver(receiver, filter, PERMISSION, null);
            }
            XposedBridge.log(TAG + "receiver registered");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "register receiver failed: " + t);
        }
    }

    /* -------------------------------------------------------------- 悬浮球 */

    private void attachFab(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.getWindow() == null) return;
        if (!isLauncherActivity(activity)) return;

        ViewGroup content = activity.findViewById(android.R.id.content);
        if (content == null) return;
        if (content.findViewWithTag(FAB_TAG) != null) return;

        final FloatScanButton fab = new FloatScanButton(activity);
        fab.setTag(FAB_TAG);

        final int size = FloatScanButton.sizePx(activity);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size);
        lp.gravity = Gravity.BOTTOM | Gravity.END;
        lp.rightMargin = dp(activity, 12);
        lp.bottomMargin = bottomInset(activity) + dp(activity, 96);

        // 记忆上次拖到的位置
        SharedPreferences sp = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int sx = sp.getInt(KEY_X, -1);
        int sy = sp.getInt(KEY_Y, -1);
        if (sx >= 0 && sy >= 0) {
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.leftMargin = sx;
            lp.topMargin = sy;
            fab.setAlpha(0.55f);
        }

        fab.setOnActionListener(new FloatScanButton.OnActionListener() {
            @Override
            public void onScan() {
                if (!startScanner(activity, false)) {
                    toast(activity, "打开扫码页失败");
                }
            }

            @Override
            public void onGallery() {
                if (!startScanner(activity, true)) {
                    toast(activity, "打开相册失败");
                }
            }

            @Override
            public void onMoved(int left, int top) {
                try {
                    activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                            .edit().putInt(KEY_X, left).putInt(KEY_Y, top).apply();
                } catch (Throwable ignore) {
                }
            }
        });

        content.addView(fab, lp);
        XposedBridge.log(TAG + "fab attached to " + activity.getClass().getName());

        // 首次使用提示（只提示一次）
        try {
            if (!sp.getBoolean("hinted", false)) {
                sp.edit().putBoolean("hinted", true).apply();
                toast(activity, "点悬浮球扫码，长按从相册识别，可以拖动");
            }
        } catch (Throwable ignore) {
        }
    }

    private boolean startScanner(Activity activity, boolean gallery) {
        try {
            Intent intent = new Intent();
            intent.setClassName(MODULE_PKG, MODULE_PKG + ".ScannerActivity");
            intent.putExtra(ScannerActivity.EXTRA_TARGET, activity.getPackageName());
            intent.putExtra(ScannerActivity.EXTRA_GALLERY, gallery);
            activity.startActivity(intent);
            return true;
        } catch (Throwable t) {
            XposedBridge.log(TAG + "start scanner failed: " + t);
            return false;
        }
    }

    /** 只往宿主的「启动页」挂一次，别的 Activity 不管（Flutter 里也只有这一个原生 Activity） */
    private static boolean isLauncherActivity(Activity activity) {
        String cls = activity.getClass().getName();
        try {
            Intent launcher = new Intent(Intent.ACTION_MAIN);
            launcher.addCategory(Intent.CATEGORY_LAUNCHER);
            launcher.setPackage(activity.getPackageName());
            ResolveInfo ri = activity.getPackageManager().resolveActivity(launcher, 0);
            if (ri != null && ri.activityInfo != null && ri.activityInfo.name != null) {
                return cls.equals(ri.activityInfo.name);
            }
        } catch (Throwable ignore) {
        }
        return cls.contains("MainActivity");
    }

    private int bottomInset(Activity activity) {
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                WindowInsets insets = activity.getWindow().getDecorView().getRootWindowInsets();
                if (insets != null) {
                    if (Build.VERSION.SDK_INT >= 30) {
                        android.graphics.Insets nav = insets.getInsets(WindowInsets.Type.navigationBars());
                        if (nav != null) return nav.bottom;
                    }
                    return insets.getSystemWindowInsetBottom();
                }
            }
            int id = activity.getResources().getIdentifier("navigation_bar_height", "dimen", "android");
            if (id > 0) return activity.getResources().getDimensionPixelSize(id);
        } catch (Throwable ignore) {
        }
        return 0;
    }

    private static int dp(Context ctx, int value) {
        return (int) (value * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static void toast(Context ctx, String msg) {
        try {
            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignore) {
        }
    }
}
