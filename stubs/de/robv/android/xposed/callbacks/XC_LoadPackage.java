package de.robv.android.xposed.callbacks;

/** compile-time stub only - the real class is provided by LSPosed at runtime */
public final class XC_LoadPackage {
    private XC_LoadPackage() {}

    public static final class LoadPackageParam {
        public String packageName;
        public String processName;
        public ClassLoader classLoader;
        public boolean isFirstApplication;
    }
}
