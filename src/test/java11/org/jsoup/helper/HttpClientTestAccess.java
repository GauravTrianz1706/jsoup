package org.jsoup.helper;

import org.jsoup.Connection;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.http.HttpRequest;
import java.util.concurrent.ConcurrentHashMap;

/**
 Test access shim for the Java 11 HTTP client classes.
 <p>Maven compiles the production class as a multi-release JAR overlay, so the base test compilation cannot bind to it directly.
 Reflection lets the tests access that overlay under Maven and the same Java 11 implementation from an IDE's flat output.</p>
 */
final class HttpClientTestAccess {
    private static final String ExecutorClassName = "org.jsoup.helper.HttpClientExecutor";
    private static final String ProxyWrapClassName = ExecutorClassName + "$ProxyWrap";
    private HttpClientTestAccess() {}

    static boolean isHttpClientExecutor(RequestExecutor executor) {
        return executorClass().isInstance(executor);
    }

    static HttpRequest.BodyPublisher requestBody(HttpConnection.Request request) {
        // expose the publisher so we can verify that streamed bodies are created lazily
        try {
            return (HttpRequest.BodyPublisher) executorClass()
                    .getDeclaredMethod("requestBody", HttpConnection.Request.class)
                    .invoke(null, request);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not invoke HttpClientExecutor.requestBody", e);
        }
    }

    static Object client(Connection connection) {
        try {
            HttpConnection.Request request = (HttpConnection.Request) connection.request();
            RequestExecutor executor = RequestDispatch.get(request, null);
            Method method = executorClass().getDeclaredMethod("client");
            method.setAccessible(true);
            return method.invoke(executor);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not retrieve the configured HttpClient", e);
        }
    }

    static void resetSharedClient() {
        try {
            Field field = executorClass().getDeclaredField("sharedClientState");
            field.setAccessible(true);
            field.set(null, null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not reset the shared HttpClient", e);
        }
    }

    static ProxySelector newProxyWrap() {
        try {
            Constructor<?> constructor = loadClass(ProxyWrapClassName).getDeclaredConstructor();
            constructor.setAccessible(true);
            return (ProxySelector) constructor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not construct HttpClientExecutor.ProxyWrap", e);
        }
    }

    static void setPerRequestProxy(Proxy proxy) {
        // Updated to use ConcurrentHashMap keyed by thread ID instead of ThreadLocal.
        // ThreadLocal variables cause memory leaks in cloud thread-pooled environments (AWS ECS/EKS).
        perRequestProxyMap().put(Thread.currentThread().getId(), proxy);
    }

    static void clearPerRequestProxy() {
        // Updated to use ConcurrentHashMap keyed by thread ID instead of ThreadLocal.
        perRequestProxyMap().remove(Thread.currentThread().getId());
    }

    @SuppressWarnings("unchecked")
    private static ConcurrentHashMap<Long, Proxy> perRequestProxyMap() {
        // Accesses the ConcurrentHashMap<Long, Proxy> field that replaced the ThreadLocal<Proxy>.
        // The field type changed from ThreadLocal to ConcurrentHashMap to prevent memory leaks
        // in cloud thread-pooled environments (AWS ECS/EKS).
        try {
            Field field = executorClass().getDeclaredField("perRequestProxy");
            field.setAccessible(true);
            return (ConcurrentHashMap<Long, Proxy>) field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not access HttpClientExecutor.perRequestProxy", e);
        }
    }

    private static Class<?> executorClass() {
        return loadClass(ExecutorClassName);
    }

    private static Class<?> loadClass(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Could not load " + className, e);
        }
    }
}
