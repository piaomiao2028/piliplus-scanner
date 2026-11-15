# PiLiPala 扫码（PiliPlus 也能用）

给 [PiLiPala](https://github.com/guozhigq/pilipala) 和它的开源改版
[PiliPlus](https://github.com/bggRGjQaUbCoE/PiliPlus) 用的 Xposed / LSPosed 扫码模块：
扫到 B 站二维码直接进对应页面，扫到登录二维码就用应用里当前登录的账号确认登录。

> 目标应用：`com.guozhigq.pilipala`（PiLiPala）、`com.example.piliplus`（PiliPlus）
> 模块包名：`com.piaomiao.pilipala.scan`

## 和哔哩喵版的区别

哔哩喵版（[piaomiao2028/bilimiao-saoma](https://github.com/piaomiao2028/bilimiao-saoma)）是往宿主底栏插一个原生按钮，因为哔哩喵是原生 View 界面。

这两个宿主都是 **Flutter 应用**，整个界面由 Flutter 自绘，根本没有「底栏那一排 View」可以插。所以这一版换了三个地方：

| | 哔哩喵版 | 这一版 |
| --- | --- | --- |
| 入口 | 底栏图标（插原生 View） | **贴边悬浮球**（可拖动 / 吸附 / 记忆位置，横屏自动隐藏） |
| 结果落地 | `bilimiao://video/...` | `bilibili://video/...` 交给宿主自己的路由（PiLiPala 走 `appscheme`，PiliPlus 走 `app_links`） |
| 登录确认 | 反射拿宿主 `CookieProvider` | 扫宿主私有目录里的 cookie（Dart / Hive 存的），`nav` 接口验过再用 |

扫码页仍然跑在**模块自己的进程**里，所以相机权限归模块自己管，宿主不用加权限。

## 功能

- **悬浮球**：点一下开相机扫码；长按从相册识别；按住能拖、松手自动吸边并记住位置；横屏（多半在看视频）自动收起；首次使用弹一次提示。
- **扫到东西直接进宿主**：
  - `BV号` / `av号` / `b23.tv` 短链 → 解析成 `bilibili://video/...`
  - `bilibili://` 开头的链接原样转交
  - 视频页、直播间、UP 空间、番剧 / 课程（`ss`/`ep`）、专栏（`cv`）都能对上宿主的路由
  - 短链先在模块里跟一次 302，拿到最终地址再转，避免宿主不认短链
- **扫码登录**：扫到登录二维码时，用宿主当前账号确认
  - 网页 / PC 登录二维码（带 `qrcode_key`）→ `POST /x/passport-login/web/qrcode/confirm`
  - TV 版登录二维码（带 `auth_code`）→ `POST /x/passport-tv-login/h5/qrcode/confirm`（按 App 的 appkey 签名）
- **没装 LSPosed 也能用**：模块有桌面图标，直接当独立扫码器，扫完弹选择框（PiLiPala / PiliPlus / 浏览器 / 只复制）。
- 扫到的原文一律先拷进剪贴板，识别失败也不会丢。

## 安装

1. 手机装好 PiLiPala 或 PiliPlus。
2. 装本模块 `PiLiPala扫码-1.0.apk`。
3. LSPosed 里启用模块，**作用域勾选 PiLiPala 和 PiliPlus**（模块内已用 `xposedscope` 声明，通常会自动勾上）。
4. 强制停止宿主再打开，进度会出现在右下角（半透明粉色小球）。

不装 LSPosed 也能用：直接点桌面上的「PiLiPala 扫码」图标。

## 使用

- 点悬浮球 → 相机扫码，扫到就自动跳。
- 长按悬浮球 → 从相册挑图识别（截图里的二维码也能认，4 个方向 + 反色都试）。
- 拖动悬浮球 → 换个位置，下次还在那儿；横屏时自动隐藏，转回竖屏再出现。
- 扫登录二维码（比如电脑网页上那个「扫码登录」）→ 会直接用你在这两个应用里登录的账号确认，屏幕上有结果提示。

## 加别的改版（PiliNara 之类）

改两处就行：

1. `res/values/values.xml` 里的 `xposedscope` 数组加一行包名；
2. `src/com/piaomiao/pilipala/scan/ScanModule.java` 的 `HOSTS` 数组加同一条包名。

（改完要重新编，LSPosed 作用域是按包名写死的，不能自动识别「长得像的」应用。）

## 构建

不需要 Gradle，纯命令行（JDK 17 + Android SDK：build-tools 34、platform android-34）：

```bash
export ANDROID_HOME=/path/to/android-sdk
./build.sh
```

脚本做的事：`javac`(release 8) → 桩类打 lib jar → `d8`（含 `libs/zxing-core-3.5.3.jar`）→
`aapt2 compile/link` → 打包 dex/assets/module.prop → `zipalign` → `apksigner` 签名。
产物是 `PiLiPala扫码-1.0.apk`（debug 签名，装的时候覆盖旧版要卸载重装）。

单元测试（cookie 解析，不需要 Android）：

```bash
javac -d /tmp/cs tools/test/CookieScannerTest.java src/com/piaomiao/pilipala/scan/CookieScanner.java
java -cp /tmp/cs CookieScannerTest
```

## 目录

```
src/com/piaomiao/pilipala/scan/
├── ScanModule.java      # 入口：hook 生命周期、挂悬浮球、注册回传广播
├── FloatScanButton.java # 悬浮球（自绘 / 拖动 / 吸边 / 长按）
├── ScannerActivity.java # 相机扫码页 + 相册识别 + 结果分发
├── QrDecoder.java       # 相册图片二维码解码（ZXing）
├── LinkRouter.java      # BV/av/短链 → bilibili:// 路由表
├── LoginConfirm.java    # 扫码登录确认（web / TV 两种）
└── CookieScanner.java   # 从宿主私有目录里翻 SESSDATA / bili_jct
stubs/de/...             # XposedBridge 等编译期桩类（不进 dex）
libs/zxing-core-3.5.3.jar
assets/xposed_init
META-INF/xposed/module.prop
tools/make_icon.py       # 生成图标
tools/test/              # cookie 解析离线测试
```

## 实现要点

- **为什么用悬浮球**：Flutter 的 `FlutterView` 是一个 SurfaceView，界面内容对 Android 侧完全不透明。往 `android.R.id.content` 里加 View 只能得到「浮在上面」的效果，没法排进 Flutter 的布局。悬浮球是这条路里最不别扭的做法。
- **结果怎么交回宿主**：不依赖 `onActivityResult`（Flutter 那边会重写，容易吞掉）。链接直接 `ACTION_VIEW` + `setPackage(宿主)`，宿主的 `singleTask/singleTop` Activity 会收到 `onNewIntent`，再由它自己的 scheme 监听器跳转。
- **登录码为什么走广播**：读登录态要访问宿主私有目录，只能在宿主进程里干。所以扫码页把原文用**签名级权限保护的广播**发回宿主进程（`registerReceiver(..., PERMISSION, ...)` 的 4 参版本校验的是**发送方**，别的应用伪造不了这条广播，不会被人借你的账号去确认别人的登录）。
- **cookie 怎么读**：两个宿主的登录态都在 Dart 侧——PiLiPala 是 `files/.plpl/` 下 `PersistCookieJar` 的文本；PiliPlus 是 `files/hive/account.hive` 里的登录账号（Hive 二进制，但字符串是明文）。统一按字节扫 `SESSDATA` + 就近的 32 位 `bili_jct`，再用 `nav` 接口验一遍（`isLogin:true` 才用），抽错了会被自测挡掉。
- **ZXing 只在必要的时候用**：相机预览走 `PlanarYUVLuminanceSource` 直解，相册图才用 `RGBLuminanceSource`，先按 1600px 缩一遍防 OOM。

## 已知限制

- 悬浮球位置是按「竖屏右下」算的，个别机型（折叠屏、超宽屏）可能需要拖一下。
- 只适配 PiLiPala / PiliPlus 当前的 scheme 路由表；宿主大改路由（比如换掉 `appscheme`）后可能需要跟进。
- PiliPlus 的登录态是 Hive 二进制，靠字符串特征抽取 + `nav` 校验，极端情况（多账号、刚登录还没落盘）可能读不到，提示「没读到登录态」时在该应用里重新登录一次即可。
- 登录确认用的是应用里的 cookie，所以宿主本身必须已登录。
- 只做了 B 站相关路由；扫到无关文本只复制到剪贴板。

## 致谢

- [bilimiao-saoma](https://github.com/piaomiao2028/bilimiao-saoma) —— 本项目的直接来源（扫码页、ZXing 解码、登录确认的签名逻辑都沿用自它）
- [bilimiao_scanner](https://github.com/10miaomiao/bilimiao_scanner) —— 更上游的扫码插件
- [PiLiPala](https://github.com/guozhigq/pilipala) / [PiliPlus](https://github.com/bggRGjQaUbCoE/PiliPlus) —— 宿主
- [ZXing](https://github.com/zxing/zxing) —— 图像二维码解码

## License

GPL-3.0（与 PiLiPala / 哔哩喵保持一致）。
