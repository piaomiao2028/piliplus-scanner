import com.piaomiao.pilipala.scan.CookieScanner;

/**
 * CookieScanner 的离线自测：不碰 Android，只验证两种存储格式下能否抽对 SESSDATA / bili_jct。
 *   javac -d /tmp/cs tools/test/CookieScannerTest.java src/com/piaomiao/pilipala/scan/CookieScanner.java
 *   java -cp /tmp/cs CookieScannerTest
 */
public class CookieScannerTest {

    private static int failed = 0;

    public static void main(String[] args) {
        // 1) PiLiPala：PersistCookieJar 的文本文件，一行一条 JSON
        String pilipala = "[{\"domain\":\".bilibili.com\",\"expires\":1700000000000,\"httpOnly\":true,"
                + "\"name\":\"SESSDATA\",\"path\":\"/\",\"secure\":false,"
                + "\"value\":\"a1b2c3%2C1700000000%2Cabcd%2Aef12\"},"
                + "{\"domain\":\".bilibili.com\",\"expires\":1700000000000,\"httpOnly\":true,"
                + "\"name\":\"bili_jct\",\"path\":\"/\",\"secure\":false,"
                + "\"value\":\"0123456789abcdef0123456789abcdef\"}]";
        check("PiLiPala JSON", pilipala,
                "a1b2c3%2C1700000000%2Cabcd%2Aef12",
                "0123456789abcdef0123456789abcdef");

        // 2) PiliPlus：Hive 二进制，键和值连着写、没有分隔符（name→value 顺序）
        String hive = "\u0001\u0002domain.bilibili.comexpires\u0000httpOnly\u0001nameSESSDATApath/secure"
                + "\u0000valuea1b2c3%2C1700000000%2Cabcd%2Aef12\u0000\u0000namebili_jctvalue0123456789abcdef0123456789abcdef\u0000";
        check("PiliPlus Hive", hive,
                "a1b2c3%2C1700000000%2Cabcd%2Aef12",
                "0123456789abcdef0123456789abcdef");

        // 3) 调换键顺序：value 在 name 前面
        String reversed = "\u0001valuea1b2c3%2C1700000000%2Cabcd%2Aef12nameSESSDATAbili_jct"
                + "\u00000123456789abcdef0123456789abcdef\u0000";
        check("Hive 反序", reversed,
                "a1b2c3%2C1700000000%2Cabcd%2Aef12",
                "0123456789abcdef0123456789abcdef");

        // 4) 不相关的内容不应该抽到东西
        String junk = "hello world, no cookies here 1234567890abcdef1234567890abcdef";
        if (!CookieScanner.scanText(junk).isEmpty()) {
            System.out.println("FAIL 无关内容误判");
            failed++;
        } else {
            System.out.println("OK   无关内容不误判");
        }

        if (failed > 0) {
            System.out.println("共 " + failed + " 项失败");
            System.exit(1);
        }
        System.out.println("全部通过");
    }

    private static void check(String name, String hay, String expectSess, String expectCsrf) {
        java.util.List<CookieScanner.Pair> pairs = CookieScanner.scanText(hay);
        if (pairs.isEmpty()) {
            System.out.println("FAIL " + name + "：没抽到");
            failed++;
            return;
        }
        CookieScanner.Pair p = pairs.get(0);
        boolean ok = expectSess.equals(p.sessdata) && expectCsrf.equals(p.csrf);
        System.out.println((ok ? "OK   " : "FAIL ") + name + " → " + p);
        if (!ok) {
            System.out.println("     期望 " + expectSess + " / " + expectCsrf);
            failed++;
        }
    }
}
