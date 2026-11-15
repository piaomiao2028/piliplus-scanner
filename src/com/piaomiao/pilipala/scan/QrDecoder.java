package com.piaomiao.pilipala.scan;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.EnumMap;
import java.util.Map;

/**
 * 从相册图片里识别二维码。
 * 用 ZXing core（纯 Java，随模块打包，约 600KB），支持旋转 4 个方向 + 反色再试，
 * 截图、拍照、截图拼接都能尽量识别出来。
 */
public class QrDecoder {

    private static final int MAX_DIMENSION = 1600;

    public static String decode(Context context, Uri uri) {
        byte[] bytes = readAll(context, uri);
        if (bytes == null) return null;

        Bitmap bitmap = decodeBitmap(bytes);
        if (bitmap == null) return null;

        try {
            for (int rotation : new int[]{0, 90, 270, 180}) {
                Bitmap current = rotation == 0 ? bitmap : rotate(bitmap, rotation);
                try {
                    String text = decodeBitmap(current);
                    if (text != null) return text;
                } finally {
                    if (current != null && current != bitmap) current.recycle();
                }
            }
            return null;
        } finally {
            bitmap.recycle();
        }
    }

    private static byte[] readAll(Context context, Uri uri) {
        InputStream in = null;
        try {
            in = context.getContentResolver().openInputStream(uri);
            if (in == null) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
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

    private static Bitmap decodeBitmap(byte[] bytes) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

            int longest = Math.max(bounds.outWidth, bounds.outHeight);
            int sample = 1;
            while (longest / sample > MAX_DIMENSION) sample *= 2;

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Bitmap rotate(Bitmap src, int degrees) {
        try {
            Matrix m = new Matrix();
            m.postRotate(degrees);
            return Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String decodeBitmap(Bitmap bitmap) {
        if (bitmap == null) return null;
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        if (width <= 0 || height <= 0) return null;

        int[] pixels = new int[width * height];
        try {
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        } catch (Throwable t) {
            return null;
        }

        RGBLuminanceSource source = new RGBLuminanceSource(width, height, pixels);
        BinaryBitmap binaryBitmap = new BinaryBitmap(new HybridBinarizer(source));

        Map<DecodeHintType, Object> hints = new EnumMap<DecodeHintType, Object>(DecodeHintType.class);
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        hints.put(DecodeHintType.ALSO_INVERTED, Boolean.TRUE);
        hints.put(DecodeHintType.CHARACTER_SET, "UTF-8");

        MultiFormatReader reader = new MultiFormatReader();
        reader.setHints(hints);
        try {
            Result result = reader.decodeWithState(binaryBitmap);
            return result != null ? result.getText() : null;
        } catch (Throwable t) {
            return null;
        } finally {
            reader.reset();
        }
    }
}
