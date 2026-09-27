package org.mockserver.mock.action.http;

/**
 * Resolves the {@link ClassLoader} used to load user-supplied callback classes for both response and
 * forward class callbacks.
 *
 * <p>An explicit override (set by the Maven plugin / Spring Boot integration via
 * {@link #setContextClassLoader(ClassLoader)}) takes precedence over the thread-context classloader.
 * Resolution happens per invocation so a stale classloader (for example an undeployed WAR's
 * {@code WebappClassLoaderBase}) is never cached.</p>
 *
 * @author jamesdbloom
 */
public final class CallbackClassLoaderResolver {

    private static volatile ClassLoader contextClassLoaderOverride;

    private CallbackClassLoaderResolver() {
    }

    public static void setContextClassLoader(ClassLoader contextClassLoader) {
        CallbackClassLoaderResolver.contextClassLoaderOverride = contextClassLoader;
    }

    public static ClassLoader resolveClassLoader() {
        ClassLoader override = contextClassLoaderOverride;
        if (override != null) {
            return override;
        }
        ClassLoader threadContextClassLoader = Thread.currentThread().getContextClassLoader();
        return threadContextClassLoader != null ? threadContextClassLoader : ClassLoader.getSystemClassLoader();
    }
}
