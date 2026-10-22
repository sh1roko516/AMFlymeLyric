package io.github.libxposed.api;

/** compile-only stub of the libxposed new API surface used by this module */
public abstract class XposedModule implements XposedModuleInterface {

    public final void log(int priority, String tag, String msg) { }

    public final void log(int priority, String tag, String msg, Throwable tr) { }

    public final XposedInterface.HookBuilder hook(java.lang.reflect.Executable executable) {
        throw new UnsupportedOperationException("compile-only stub");
    }

    public void onModuleLoaded(ModuleLoadedParam param) { }

    public void onPackageLoaded(PackageLoadedParam param) { }

    public void onPackageReady(PackageReadyParam param) { }
}
