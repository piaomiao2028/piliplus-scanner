package com.piaomiao.pilipala.scan;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把扫到的东西翻译成宿主认识的链接。
 *
 * PiLiPala 走 appscheme、PiliPlus 走 app_links，两者都只认 bilibili:// 那一套，
 * 所以这里统一转成 bilibili://video|live|space|bangumi|pgc|article，再交给宿主自己的路由。
 */
public final class LinkRouter {

    /** 哔哩哔哩粉 */
    private static final Pattern BV = Pattern.compile("BV[0-9A-Za-z]{10}");
    private static final Pattern AV = Pattern.compile("(?:^|[^0-9A-Za-z])av(\\d{3,})", Pattern.CASE_INSENSITIVE);
    private static final Pattern LIVE = Pattern.compile("live\\.bilibili\\.com/(?:live/|h5/|blanc/)?(\\d{3,})");
    private static final Pattern SPACE = Pattern.compile("space\\.bilibili\\.com/(\\d{3,})");
    private static final Pattern EP = Pattern.compile("/bangumi/play/ep(\\d+)");
    private static final Pattern SS = Pattern.compile("/bangumi/play/ss(\\d+)");
    private static final Pattern CHEESE_EP = Pattern.compile("/cheese/play/ep(\\d+)");
    private static final Pattern CHEESE_SS = Pattern.compile("/cheese/play/ss(\\d+)");
    private static final Pattern CV = Pattern.compile("/read/cv(\\d+)");
    private static final Pattern SHORT_HOST = Pattern.compile("b23\\.tv|bili2233\\.cn|bili22\\.cn|bili33\\.cn");

    private static final String UA =
            "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    private LinkRouter() {
    }

    public static boolean isShortLink(String url) {
        return url != null && SHORT_HOST.matcher(url).find();
    }

    /** 扫到的原文 → 宿主能直接开的链接；认不出来返回 null */
    public static String toSchemeUrl(String raw) {
        if (raw == null) return null;
        String t = raw.trim();
        if (t.isEmpty()) return null;
        if (t.startsWith("bilibili://") || t.startsWith("bilimiao://")) return t;

        boolean isHttp = t.startsWith("http://") || t.startsWith("https://");

        if (isHttp) {
            Matcher live = LIVE.matcher(t);
            if (live.find()) return "bilibili://live/" + live.group(1);

            Matcher space = SPACE.matcher(t);
            if (space.find()) return "bilibili://space/" + space.group(1);

            Matcher cheeseEp = CHEESE_EP.matcher(t);
            if (cheeseEp.find()) return "bilibili://pgc/season/ep/" + cheeseEp.group(1);
            Matcher cheeseSs = CHEESE_SS.matcher(t);
            if (cheeseSs.find()) return "bilibili://bangumi/season/" + cheeseSs.group(1);

            Matcher ep = EP.matcher(t);
            if (ep.find()) return "bilibili://pgc/season/ep/" + ep.group(1);
            Matcher ss = SS.matcher(t);
            if (ss.find()) return "bilibili://bangumi/season/" + ss.group(1);

            Matcher cv = CV.matcher(t);
            if (cv.find()) return "bilibili://article/" + cv.group(1);
        }

        Matcher bv = BV.matcher(t);
        if (bv.find()) return "bilibili://video/" + bv.group();

        Matcher av = AV.matcher(t);
        if (av.find()) return "bilibili://video/" + av.group(1);

        if (isHttp) return t;
        return null;
    }

    /** 短链先跟一次跳转，再把最终地址翻成 bilibili:// */
    public static String resolveShortLink(String url) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(4000);
            conn.setRequestProperty("User-Agent", UA);
            conn.getResponseCode();
            String finalUrl = conn.getURL() != null ? conn.getURL().toString() : null;
            if (finalUrl != null && !finalUrl.equals(url)) {
                String mapped = toSchemeUrl(finalUrl);
                if (mapped != null) return mapped;
            }
            return toSchemeUrl(url);
        } catch (Throwable t) {
            return null;
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignore) {
                }
            }
        }
    }

    /** 在指定宿主里打开；pkg 传 null 就用系统默认（浏览器） */
    public static boolean open(Context ctx, String pkg, String url) {
        if (url == null) return false;
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            if (pkg != null) intent.setPackage(pkg);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(intent);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean installed(Context ctx, String pkg) {
        try {
            ctx.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
