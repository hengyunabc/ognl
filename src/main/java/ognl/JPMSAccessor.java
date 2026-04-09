package ognl;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * JPMS access helper that opens a package to OGNL's module at runtime.
 */
final class JPMSAccessor {

    private static final Class MODULE_CLASS = findClass("java.lang.Module");

    private static final Object UNSAFE_INSTANCE = instantiateUnsafeInstance();
    private static final Method UNSAFE_STATIC_FIELD_OFFSET_METHOD = findUnsafeMethod("staticFieldOffset", Field.class);
    private static final Method UNSAFE_STATIC_FIELD_BASE_METHOD = findUnsafeMethod("staticFieldBase", Field.class);
    private static final Method UNSAFE_GET_OBJECT_METHOD = findUnsafeMethod("getObject", Object.class, long.class);

    private static final Method CLASS_GET_MODULE_METHOD = findMethod(Class.class, "getModule");
    private static final Method MODULE_IS_NAMED_METHOD = findMethod(MODULE_CLASS, "isNamed");
    private static final Method MODULE_IS_OPEN_METHOD = findMethod(MODULE_CLASS, "isOpen", String.class, MODULE_CLASS);

    private static final MethodHandles.Lookup IMPL_LOOKUP = instantiateImplLookup();
    private static final MethodHandle MODULE_IMPL_ADD_OPENS_TO_ALL_UNNAMED =
            findModuleMethodHandle("implAddOpensToAllUnnamed", new Class[]{String.class});
    private static final MethodHandle MODULE_IMPL_ADD_OPENS =
            findModuleMethodHandle("implAddOpens", new Class[]{String.class, MODULE_CLASS});

    private static final Object OGNL_MODULE = getModule(OgnlRuntime.class);
    private static final boolean OGNL_MODULE_NAMED = isNamed(OGNL_MODULE);

    private JPMSAccessor() {
    }

    static boolean requiresOpenToOgnl(Class clazz) {
        return canOpen(clazz) && !isOpenToOgnl(clazz);
    }

    static boolean openToOgnl(Class clazz) {
        if (!canOpen(clazz)) {
            return false;
        }

        if (isOpenToOgnl(clazz)) {
            return true;
        }

        final Object module = getModule(clazz);
        final String packageName = getPackageName(clazz);
        if (module == null || packageName.length() == 0) {
            return false;
        }

        try {
            if (OGNL_MODULE_NAMED) {
                if (MODULE_IMPL_ADD_OPENS == null) {
                    return false;
                }
                MODULE_IMPL_ADD_OPENS.invoke(module, packageName, OGNL_MODULE);
            } else {
                if (MODULE_IMPL_ADD_OPENS_TO_ALL_UNNAMED == null) {
                    return false;
                }
                MODULE_IMPL_ADD_OPENS_TO_ALL_UNNAMED.invoke(module, packageName);
            }
            return isOpen(module, packageName, OGNL_MODULE);
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean canOpen(Class clazz) {
        return clazz != null
                && MODULE_CLASS != null
                && CLASS_GET_MODULE_METHOD != null
                && MODULE_IS_OPEN_METHOD != null
                && OGNL_MODULE != null
                && getModule(clazz) != null
                && isNamed(getModule(clazz));
    }

    private static boolean isOpenToOgnl(Class clazz) {
        final Object module = getModule(clazz);
        if (module == null) {
            return false;
        }
        return isOpen(module, getPackageName(clazz), OGNL_MODULE);
    }

    private static boolean isOpen(Object module, String packageName, Object targetModule) {
        if (module == null || packageName.length() == 0 || MODULE_IS_OPEN_METHOD == null || targetModule == null) {
            return false;
        }
        try {
            return ((Boolean) MODULE_IS_OPEN_METHOD.invoke(module, packageName, targetModule)).booleanValue();
        } catch (Throwable t) {
            return false;
        }
    }

    private static Object getModule(Class clazz) {
        if (clazz == null || CLASS_GET_MODULE_METHOD == null) {
            return null;
        }
        try {
            return CLASS_GET_MODULE_METHOD.invoke(clazz);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean isNamed(Object module) {
        if (module == null || MODULE_IS_NAMED_METHOD == null) {
            return false;
        }
        try {
            return ((Boolean) MODULE_IS_NAMED_METHOD.invoke(module)).booleanValue();
        } catch (Throwable t) {
            return false;
        }
    }

    private static String getPackageName(Class clazz) {
        while (clazz != null && clazz.isArray()) {
            clazz = clazz.getComponentType();
        }
        if (clazz == null) {
            return "";
        }

        final String className = clazz.getName();
        final int packageSeparator = className.lastIndexOf('.');
        return packageSeparator >= 0 ? className.substring(0, packageSeparator) : "";
    }

    private static MethodHandle findModuleMethodHandle(String methodName, Class[] parameterTypes) {
        if (MODULE_CLASS == null || IMPL_LOOKUP == null) {
            return null;
        }
        try {
            final Method method = MODULE_CLASS.getDeclaredMethod(methodName, parameterTypes);
            return IMPL_LOOKUP.unreflect(method);
        } catch (Throwable t) {
            return null;
        }
    }

    private static MethodHandles.Lookup instantiateImplLookup() {
        if (UNSAFE_INSTANCE == null || UNSAFE_STATIC_FIELD_OFFSET_METHOD == null
                || UNSAFE_STATIC_FIELD_BASE_METHOD == null || UNSAFE_GET_OBJECT_METHOD == null) {
            return null;
        }

        try {
            final Field implLookupField = MethodHandles.Lookup.class.getDeclaredField("IMPL_LOOKUP");
            final Object base = UNSAFE_STATIC_FIELD_BASE_METHOD.invoke(UNSAFE_INSTANCE, implLookupField);
            final long offset = ((Long) UNSAFE_STATIC_FIELD_OFFSET_METHOD.invoke(UNSAFE_INSTANCE, implLookupField)).longValue();
            return (MethodHandles.Lookup) UNSAFE_GET_OBJECT_METHOD.invoke(UNSAFE_INSTANCE, base, Long.valueOf(offset));
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object instantiateUnsafeInstance() {
        final Class unsafeClass = findClass("sun.misc.Unsafe");
        if (unsafeClass == null) {
            return null;
        }

        try {
            final Field unsafeField = unsafeClass.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            return unsafeField.get(null);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Method findUnsafeMethod(String methodName, Class... parameterTypes) {
        final Object unsafeInstance = UNSAFE_INSTANCE;
        if (unsafeInstance == null) {
            return null;
        }

        try {
            return unsafeInstance.getClass().getMethod(methodName, parameterTypes);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Method findMethod(Class clazz, String methodName, Class... parameterTypes) {
        if (clazz == null) {
            return null;
        }
        try {
            return clazz.getMethod(methodName, parameterTypes);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Class findClass(String className) {
        try {
            return Class.forName(className);
        } catch (Throwable t) {
            return null;
        }
    }
}
