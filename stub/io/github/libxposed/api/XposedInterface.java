package io.github.libxposed.api;

/** compile-only stub of the libxposed new API surface used by this module */
public interface XposedInterface {
    int PRIORITY_DEFAULT = 50;

    interface Chain {
        Object getArg(int index);
        java.util.List getArgs();
        java.lang.reflect.Executable getExecutable();
        Object getThisObject();
        Object proceed();
        Object proceed(Object[] args);
        Object proceedWith(Object result);
        Object proceedWith(Object result, Object[] args);
    }

    interface Hooker {
        Object intercept(Chain chain);
    }

    interface HookHandle {
        java.lang.reflect.Executable getExecutable();
        String getId();
        HookHandle replaceHook(Hooker hooker);
        void unhook();
    }

    interface HookBuilder {
        HookBuilder setPriority(int priority);
        HookBuilder setExceptionMode(ExceptionMode mode);
        HookBuilder setId(String id);
        HookHandle intercept(Hooker hooker);
    }

    enum ExceptionMode {
        DEFAULT,
        PROTECTIVE,
        PASSTHROUGH
    }
}
