# compile-only API stubs

`stub/` 里是 **libxposed 新 API（API 102）的最小声明**，只用于 `javac` 编译，不会打包进 APK：
运行时由 LSPosed 注入框架提供真正的 `io.github.libxposed.api.*` 实现。

声明是按本模块实际用到的成员手写的（`XposedModule.log/hook`、`XposedInterface.Chain/Hooker/HookBuilder`、
`XposedModuleInterface.PackageLoadedParam`），签名与 API 102 一致。

如果你的本地有 libxposed API 的 jar，可以用 `XPOSED_API_JAR=/path/to/libxposed-api.jar ./build.sh` 覆盖，跳过 stub 编译。
