package de.robv.android.xposed;

import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** compile-time stub only - the real interface is provided by LSPosed at runtime */
public interface IXposedHookLoadPackage {
    void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable;
}
