package de.robv.android.xposed;

/** compile-time stub only - the real class is provided by LSPosed at runtime */
public final class XposedHelpers {
    private XposedHelpers() {}

    public static XC_MethodHook.Unhook findAndHookMethod(String className, ClassLoader classLoader,
            String methodName, Object... parameterTypesAndCallback) {
        return null;
    }

    public static XC_MethodHook.Unhook findAndHookMethod(Class<?> clazz,
            String methodName, Object... parameterTypesAndCallback) {
        return null;
    }

    public static Object getObjectField(Object obj, String fieldName) { return null; }
    public static Object callMethod(Object obj, String methodName, Object... args) { return null; }
}
