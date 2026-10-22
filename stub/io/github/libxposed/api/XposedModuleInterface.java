package io.github.libxposed.api;

/** compile-only stub of the libxposed new API surface used by this module */
public interface XposedModuleInterface {

    interface ModuleLoadedParam {
        boolean isSystemServer();
    }

    interface PackageLoadedParam {
        String getPackageName();
        ClassLoader getDefaultClassLoader();
        ClassLoader getClassLoader();
        boolean isFirstPackage();
    }

    interface PackageReadyParam extends PackageLoadedParam {
        boolean isFirstPackage();
    }
}
