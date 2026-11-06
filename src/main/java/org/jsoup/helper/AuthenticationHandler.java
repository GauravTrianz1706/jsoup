package org.jsoup.helper;

import org.jspecify.annotations.Nullable;

import java.lang.reflect.Constructor;
import java.net.Authenticator;
import java.net.PasswordAuthentication;
import java.util.concurrent.ConcurrentHashMap;

/**
 Handles per request Authenticator-based authentication. Loads the class `org.jsoup.helper.RequestAuthHandler` if
 per-request Authenticators are supported (Java 9+), or installs a system-wide Authenticator that delegates to a request
 ThreadLocal.
 */
class AuthenticationHandler extends Authenticator {
    static final int MaxAttempts = 3; // max authentication attempts per request. allows for multiple auths (e.g. proxy and server) in one request, but saves otherwise 20 requests if credentials are incorrect.
    static AuthShim handler;

    static {
        try {
            Class<? extends AuthShim> perRequestClass =
                Class.forName("org.jsoup.helper.RequestAuthHandler").asSubclass(AuthShim.class);
            Constructor<? extends AuthShim> constructor = perRequestClass.getConstructor();
            handler = constructor.newInstance();
        } catch (ClassNotFoundException e) {
            handler = new GlobalHandler();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Nullable RequestAuthenticator auth;
    int attemptCount = 0;

    AuthenticationHandler() {}

    AuthenticationHandler(RequestAuthenticator auth) {
        this.auth = auth;
    }

    /**
     Authentication callback, called by HttpURLConnection - either as system-wide default (Java 8) or per HttpURLConnection (Java 9+)
     * @return credentials, or null if not attempting to auth.
     */
    @Nullable @Override public final PasswordAuthentication getPasswordAuthentication() {
        AuthenticationHandler delegate = handler.get(this);
        if (delegate == null) return null; // this request has no auth handler
        delegate.attemptCount++;
        // if the password returned fails, Java will repeatedly retry the request with a new password auth hit (because
        // it may be an interactive prompt, and the user could eventually get it right). But in Jsoup's context, the
        // auth will either be correct or not, so just abandon
        if (delegate.attemptCount > MaxAttempts)
            return null; // When using HttpClient, this will manifest as "No credentials provided" IOException; not ideal; would be clearer if we could then detach the authenticator which would bubble the 401, but there's no path for that
        if (delegate.auth == null)
            return null; // detached - would have been the Global Authenticator (not a delegate)

        RequestAuthenticator.Context ctx = new RequestAuthenticator.Context(
            this.getRequestingURL(), this.getRequestorType(), this.getRequestingPrompt());
        return delegate.auth.authenticate(ctx);
    }

    interface AuthShim {
        void enable(RequestAuthenticator auth, Object connOrHttp);

        void remove();

        @Nullable AuthenticationHandler get(AuthenticationHandler helper);
    }

    /**
     On Java 8 we install a system-wide Authenticator, which pulls the delegating Auth from a
     ConcurrentHashMap keyed by thread ID. This replaces the ThreadLocal pattern to prevent memory
     leaks in cloud environments with thread pooling (AWS ECS/EKS, etc.). Explicit remove() calls
     ensure entries are cleaned up after each request, preventing data corruption across reused threads.
     */
    static class GlobalHandler implements AuthShim {
        // Replaced ThreadLocal<AuthenticationHandler> with ConcurrentHashMap keyed by thread ID.
        // In cloud thread-pooled environments, ThreadLocal variables can cause memory leaks and
        // data corruption when threads are reused. ConcurrentHashMap with explicit cleanup is safe.
        static final ConcurrentHashMap<Long, AuthenticationHandler> authenticators = new ConcurrentHashMap<>();
        static {
            Authenticator.setDefault(new AuthenticationHandler());
        }

        @Override public void enable(RequestAuthenticator auth, Object ignored) {
            authenticators.put(Thread.currentThread().getId(), new AuthenticationHandler(auth));
        }

        @Override public void remove() {
            authenticators.remove(Thread.currentThread().getId());
        }

        @Override public AuthenticationHandler get(AuthenticationHandler helper) {
            return authenticators.get(Thread.currentThread().getId());
        }
    }
}
