package com.piaomiao.pilipala.scan;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从宿主私有目录里把登录态翻出来。
 *
 * 两个宿主的 cookie 都不在 Java 层：
 *  - PiLiPala：files/.plpl/ 下 PersistCookieJar 的文本文件（JSON，一行一条）
 *  - PiliPlus：files/hive/account.hive 里的登录账号（Hive 二进制，字符串是明文 UTF-8）
 * 所以统一按字节扫一遍，找 SESSDATA 的值和旁边的 bili_jct。
 * 抽出来的值还会被 network 校验（见 LoginConfirm），错值不会被采用。
 *
 * 这个类刻意不依赖 Android API，方便单独跑测试（tools/CookieScannerTest.java）。
 */
public final class CookieScanner {

    /** 文件扫描深度、单文件上限 */
    private static final int MAX_DEPTH = 3;
    private static final long MAX_FILE = 8L * 1024 * 1024;
    private static final int MAX_FILES = 80;
    private static final int MAX_CANDIDATES = 8;

    private static final String[] FIELD_NAMES = {
            "SESSDATA", "BiliCookieJar", "cookies", "value", "name", "domain",
            "path", "expires", "httpOnly", "secure", "Cookie",
            "bili_jct", "bili_ticket", "DedeUserID", "buvid3", "buvid4", "buvid"
    };

    private CookieScanner() {
    }

    /** 一对候选凭证 */
    public static final class Pair {
        public final String sessdata;
        public final String csrf;

        Pair(String sessdata, String csrf) {
            this.sessdata = sessdata;
            this.csrf = csrf;
        }

        public String header() {
            return "SESSDATA=" + sessdata + "; bili_jct=" + csrf;
        }

        @Override
        public String toString() {
            return "SESSDATA=" + sessdata + " bili_jct=" + csrf;
        }
    }

    /** 扫 root 下所有可能存放登录态的文件 */
    public static List<Pair> candidates(File root) {
        List<Pair> out = new ArrayList<Pair>();
        if (root == null) return out;
        List<File> files = new ArrayList<File>();
        walk(root, MAX_DEPTH, files);
        for (File f : files) {
            String hay = readText(f);
            if (hay == null || !hay.contains("SESSDATA")) continue;
            out.addAll(scanText(hay));
            if (out.size() >= MAX_CANDIDATES) break;
        }
        return out;
    }

    /** 从一段内容里抽候选（PiLiPala 的 JSON 和 PiliPlus 的 Hive 字节都走这里） */
    public static List<Pair> scanText(String hay) {
        List<Pair> out = new ArrayList<Pair>();
        if (hay == null) return out;
        int from = 0;
        while (true) {
            int idx = hay.indexOf("SESSDATA", from);
            if (idx < 0) break;
            from = idx + 8;

            String sess = valueNear(hay, idx, "%2C");
            if (sess == null) continue;
            String csrf = hexNear(hay, idx, 32);
            if (csrf == null) continue;

            out.add(new Pair(sess, csrf));
            if (out.size() >= MAX_CANDIDATES) break;
        }
        return out;
    }

    /* ---------------------------------------------------------------- 内部 */

    private static void walk(File file, int depth, List<File> out) {
        if (file == null || out.size() > MAX_FILES) return;
        if (file.isDirectory()) {
            if (depth <= 0) return;
            String name = file.getName();
            if ("cache".equals(name) || "code_cache".equals(name)
                    || "no_backup".equals(name) || "download".equals(name)
                    || "subtitles".equals(name) || "tmp".equals(name)) {
                return;
            }
            File[] kids = file.listFiles();
            if (kids == null) return;
            for (File kid : kids) walk(kid, depth - 1, out);
            return;
        }
        if (file.isFile() && file.length() > 0 && file.length() <= MAX_FILE) {
            out.add(file);
        }
    }

    private static String readText(File file) {
        InputStream in = null;
        try {
            in = new FileInputStream(file);
            int size = (int) Math.min(file.length(), MAX_FILE);
            byte[] buf = new byte[size];
            int n = 0;
            int r;
            while (n < size && (r = in.read(buf, n, size - n)) > 0) n += r;
            // 按字节读（ISO-8859-1），UTF-8 解码会把二进制里的控制字节揉掉，正则只关心 ASCII
            return new String(buf, 0, n, "ISO-8859-1");
        } catch (Throwable t) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignore) {
                }
            }
        }
    }

    /** 在 SESSDATA 附近找包含 %2C 的值，前后都试（JSON 和 Hive 的键值顺序不一样） */
    private static String valueNear(String hay, int anchor, String marker) {
        int idx = hay.indexOf(marker, anchor);
        if (idx >= 0 && idx - anchor <= 300) {
            String t = sanitize(expand(hay, idx, marker.length()));
            if (t != null) return t;
        }
        int back = hay.lastIndexOf(marker, anchor);
        if (back >= 0 && anchor - back <= 300) {
            String t = sanitize(expand(hay, back, marker.length()));
            if (t != null) return t;
        }
        return null;
    }

    /** 离 SESSDATA 最近的 32 位十六进制串就是 bili_jct */
    private static String hexNear(String hay, int anchor, int len) {
        Matcher m = Pattern.compile("[0-9a-f]{" + len + "}").matcher(hay);
        String best = null;
        int bestDist = Integer.MAX_VALUE;
        while (m.find()) {
            int dist = Math.abs(m.start() - anchor);
            if (dist > 900) continue;
            String token = sanitizeHex(expandAt(hay, m.start() + len / 2));
            if (token == null && m.group().length() == len) token = m.group();
            if (token == null) continue;
            if (dist < bestDist) {
                bestDist = dist;
                best = token;
            }
        }
        return best;
    }

    /** 从任意位置向左右扩成完整 token */
    private static String expandAt(String hay, int pos) {
        if (pos < 0 || pos >= hay.length()) return null;
        int l = pos;
        while (l > 0 && tokenChar(hay.charAt(l - 1))) l--;
        int r = pos;
        while (r < hay.length() && tokenChar(hay.charAt(r))) r++;
        if (r - l <= 0 || r - l > 500) return null;
        return hay.substring(l, r);
    }

    /** 去掉两边的字段名，剩下的必须正好是 32 位十六进制 */
    private static String sanitizeHex(String raw) {
        if (raw == null) return null;
        String t = raw;
        boolean changed = true;
        while (changed && t.length() > 0) {
            changed = false;
            for (String w : FIELD_NAMES) {
                if (t.startsWith(w)) {
                    t = t.substring(w.length());
                    changed = true;
                }
                if (t.endsWith(w) && t.length() >= w.length()) {
                    t = t.substring(0, t.length() - w.length());
                    changed = true;
                }
            }
        }
        if (t.length() != 32) return null;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return null;
        }
        return t;
    }

    /** 以 marker 为锚点向左右扩到分隔符 */
    private static String expand(String hay, int markerIdx, int markerLen) {
        int l = markerIdx;
        while (l > 0 && tokenChar(hay.charAt(l - 1))) l--;
        int r = markerIdx + markerLen;
        while (r < hay.length() && tokenChar(hay.charAt(r))) r++;
        if (r - l <= 0 || r - l > 500) return null;
        return hay.substring(l, r);
    }

    private static boolean tokenChar(char c) {
        if (c <= 0x20 || c >= 0x7F) return false;
        switch (c) {
            case '"':
            case '\\':
            case '{':
            case '}':
            case '[':
            case ']':
            case ',':
            case ';':
            case '<':
            case '>':
                return false;
            default:
                return true;
        }
    }

    /**
     * 去掉粘连在值两边的字段名（Hive 里键和值是连着写的，没有分隔符），再做基本格式校验。
     */
    static String sanitize(String raw) {
        if (raw == null) return null;
        String t = raw;
        boolean changed = true;
        while (changed && t.length() > 0) {
            changed = false;
            for (String w : FIELD_NAMES) {
                if (t.startsWith(w)) {
                    t = t.substring(w.length());
                    changed = true;
                }
                if (t.endsWith(w) && t.length() >= w.length()) {
                    t = t.substring(0, t.length() - w.length());
                    changed = true;
                }
            }
        }
        int cut = -1;
        for (String w : FIELD_NAMES) {
            int i = t.indexOf(w);
            if (i >= 0 && (cut < 0 || i < cut)) cut = i;
        }
        if (cut > 20) t = t.substring(0, cut);

        if (t.length() < 20 || t.length() > 250) return null;
        if (!t.contains("%2C")) return null;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c <= 0x20 || c >= 0x7F) return null;
        }
        return t;
    }
}
