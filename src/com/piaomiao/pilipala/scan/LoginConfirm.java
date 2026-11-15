package com.piaomiao.pilipala.scan;

import android.content.Context;

import java.io.File;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 扫码登录：在宿主进程里用「当前登录的账号」确认这次登录。
 *
 * 支持两种码，和哔哩喵版一致：
 *  1) 网页/PC 登录二维码 —— qrcode_key → POST /x/passport-login/web/qrcode/confirm
 *  2) TV 版登录二维码     —— auth_code  → POST /x/passport-tv-login/h5/qrcode/confirm（按 App appkey 规则签名）
 *
 * 登录态从宿主私有目录里翻（见 CookieScanner），再用 nav 接口验一次，
 * 保证拿到的是真的登录态，而不是二进制文件里凑出来的别的东西。
 */
public final class LoginConfirm {

    private static final String APP_KEY = "27eb53fc9058f8c3";
    private static final String APP_SECRET = "c2ed53a74eeefe3cf99fbd01d8c9c375";

    private static final String UA =
            "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private LoginConfirm() {
    }

    /** 是不是登录二维码 */
    public static boolean isLoginQr(String text) {
        if (text == null) return false;
        if (!text.contains("passport.bilibili.com") && !text.contains("passport.bilibili")) return false;
        return text.contains("qrcode_key=") || text.contains("auth_code=");
    }

    public static void confirm(final Context context, final String text) {
        final String qrcodeKey = param(text, "qrcode_key");
        final String authCode = param(text, "auth_code");
        if (qrcodeKey == null && authCode == null) {
            toast(context, "这不是登录二维码");
            return;
        }
        final String kind = qrcodeKey != null ? "网页登录" : "TV 登录";
        toast(context, "正在用当前账号确认「" + kind + "」…");

        new Thread(new Runnable() {
            @Override
            public void run() {
                String result;
                try {
                    String[] who = new String[1];
                    CookieScanner.Pair cred = loadCredential(context, who);
                    if (cred == null) {
                        result = "没读到登录态：先在应用里登录，再扫一次";
                    } else if (qrcodeKey != null) {
                        result = confirmWeb(cred, qrcodeKey, who[0]);
                    } else {
                        result = confirmTv(cred, authCode, who[0]);
                    }
                } catch (Throwable t) {
                    result = "确认失败：" + t;
                }
                final String msg = result;
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        toast(context, msg);
                    }
                });
            }
        }, "pilipala-scan-login").start();
    }

    /* ------------------------------------------------------------ 确认接口 */

    private static String confirmWeb(CookieScanner.Pair cred, String qrcodeKey, String uname) {
        Map<String, String> form = new LinkedHashMap<String, String>();
        form.put("qrcode_key", qrcodeKey);
        form.put("csrf", cred.csrf);
        String resp = post("https://passport.bilibili.com/x/passport-login/web/qrcode/confirm",
                encode(form), cred.header());
        int code = code(resp);
        String who = uname != null && uname.length() > 0 ? "（" + uname + "）" : "";
        if (code == 0) return "已在网页登录成功" + who;
        if (code == 86038) return "二维码已过期，刷新后重试";
        if (code == 86090) return "二维码已被扫过，请在那台设备上点确认";
        if (code == -101) return "登录态失效，先在应用里重新登录";
        return "登录确认失败（code=" + code + "）";
    }

    private static String confirmTv(CookieScanner.Pair cred, String authCode, String uname) {
        Map<String, String> form = new LinkedHashMap<String, String>();
        form.put("auth_code", authCode);
        form.put("csrf", cred.csrf);
        form.put("build", "7082000");
        form.put("statistics", "{\"appId\":6,\"platform\":3,\"version\":\"7.82.0\",\"abtest\":\"\"}");
        form.put("appkey", APP_KEY);

        String query = encode(form);
        String sign = md5(query + APP_SECRET);
        String body = query + "&sign=" + sign;

        String resp = post("https://passport.bilibili.com/x/passport-tv-login/h5/qrcode/confirm", body, cred.header());
        int code = code(resp);
        String who = uname != null && uname.length() > 0 ? "（" + uname + "）" : "";
        if (code == 0) return "登录确认成功" + who;
        if (code == 86038) return "二维码已过期，刷新后重试";
        if (code == -101) return "登录态失效，先在应用里重新登录";
        return "登录确认失败（code=" + code + "）";
    }

    /** 用 nav 接口确认 cookie 真的属于一个已登录账号，顺便拿到用户名 */
    private static String verify(String cookieHeader) {
        String resp = get("https://api.bilibili.com/x/web-interface/nav", cookieHeader);
        if (resp == null || !resp.contains("\"isLogin\":true")) return null;
        Matcher m = Pattern.compile("\"uname\":\"(.*?)\"").matcher(resp);
        if (m.find()) return m.group(1);
        return "";
    }

    /* ------------------------------------------------------------ 读登录态 */

    private static CookieScanner.Pair loadCredential(Context ctx, String[] unameHolder) {
        File files = ctx.getFilesDir();
        List<CookieScanner.Pair> pairs = new ArrayList<CookieScanner.Pair>();

        // 先看宿主自己的登录态目录，省得在别人的缓存里瞎找
        String pkg = ctx.getPackageName();
        File preferred = null;
        if (ScanModule.PKG_PILIPALA.equals(pkg)) preferred = new File(files, ".plpl");
        else if (ScanModule.PKG_PILIPLUS.equals(pkg)) preferred = new File(files, "hive");
        if (preferred != null && preferred.isDirectory()) {
            pairs = CookieScanner.candidates(preferred);
        }
        if (pairs.isEmpty()) {
            pairs = CookieScanner.candidates(files);
        }

        // 最多验 3 组，网络慢的时候别让人干等
        int tries = 0;
        for (CookieScanner.Pair p : pairs) {
            if (tries++ >= 3) break;
            String uname = verify(p.header());
            if (uname != null) {
                unameHolder[0] = uname;
                return p;
            }
        }
        return null;
    }

    /* ------------------------------------------------------------------ HTTP */

    private static String get(String url, String cookie) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(6000);
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Referer", "https://www.bilibili.com/");
            if (cookie != null) conn.setRequestProperty("Cookie", cookie);
            int status = conn.getResponseCode();
            InputStream in = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            return readAll(in);
        } catch (Throwable t) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String post(String url, String body, String cookie) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Referer", "https://www.bilibili.com/");
            if (cookie != null) conn.setRequestProperty("Cookie", cookie);

            OutputStream os = conn.getOutputStream();
            os.write(body.getBytes("UTF-8"));
            os.flush();
            os.close();

            int status = conn.getResponseCode();
            InputStream in = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            return readAll(in);
        } catch (Throwable t) {
            return "";
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String readAll(InputStream in) {
        if (in == null) return "";
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            return sb.toString();
        } catch (Throwable t) {
            return "";
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Throwable ignore) {
                }
            }
        }
    }

    private static String encode(Map<String, String> form) {
        StringBuilder sb = new StringBuilder();
        try {
            List<String> keys = new ArrayList<String>(form.keySet());
            Collections.sort(keys);
            for (String k : keys) {
                if (sb.length() > 0) sb.append('&');
                sb.append(URLEncoder.encode(k, "UTF-8"))
                        .append('=')
                        .append(URLEncoder.encode(form.get(k) == null ? "" : form.get(k), "UTF-8"));
            }
        } catch (Throwable ignore) {
        }
        return sb.toString();
    }

    private static int code(String json) {
        if (json == null) return -1;
        int idx = json.indexOf("\"code\"");
        if (idx < 0) return -1;
        int colon = json.indexOf(':', idx);
        if (colon < 0) return -1;
        StringBuilder sb = new StringBuilder();
        for (int i = colon + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '-' || (c >= '0' && c <= '9')) sb.append(c);
            else if (sb.length() > 0) break;
        }
        try {
            return Integer.parseInt(sb.toString());
        } catch (Throwable t) {
            return -1;
        }
    }

    private static String param(String text, String key) {
        try {
            String prefix = key + "=";
            int start = text.indexOf(prefix);
            if (start < 0) return null;
            start += prefix.length();
            int end = start;
            while (end < text.length()) {
                char c = text.charAt(end);
                if (c == '&' || c == '#' || c == ' ' || c == '\n' || c == '\r' || c == '"') break;
                end++;
            }
            if (end == start) return null;
            return URLDecoder.decode(text.substring(start, end), "UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }

    private static String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] bytes = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    private static void toast(final Context ctx, final String msg) {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                try {
                    Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show();
                } catch (Throwable ignore) {
                }
            }
        });
    }
}
