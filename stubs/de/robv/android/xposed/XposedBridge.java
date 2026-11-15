package de.robv.android.xposed;

import java.util.Set;

import de.robv.android.xposed.XC_MethodHook.Unhook;

public class XposedBridge {

    public static void log(String text) {
    }

    public static void log(Throwable t) {
    }

    public static Set<Unhook> hookAllMethods(Class<?> hookClass, String methodName, XC_MethodHook callback) {
        return null;
    }

    public static Unhook hookAllConstructors(Class<?> hookClass, XC_MethodHook callback) {
        return null;
    }
}
