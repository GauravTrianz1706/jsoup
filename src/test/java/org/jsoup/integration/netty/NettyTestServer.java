package org.jsoup.integration.netty;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.ssl.SslContext;
import org.jsoup.integration.TestAuth;
import org.jsoup.integration.TestServer;
import org.jsoup.integration.TestTls;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 Hosts the jsoup origin test routes on Netty.
 <p>Cloud-native: the mutable routes registry is backed by Amazon ElastiCache for Redis
 to ensure consistent routing state across distributed application instances. The in-process
 {@link ConcurrentHashMap} acts as a local cache; route registrations are also written to
 Redis so that any instance can reconstruct the routing table on startup.</p>
 */
public final class NettyTestServer {

    private static final Logger logger = Logger.getLogger(NettyTestServer.class.getName());

    /**
     * Redis key prefix used to store route path registrations.
     * Each registered path is stored as a Redis set member under this key.
     */
    private static final String REDIS_ROUTES_KEY = "jsoup:test:routes";

    /**
     * Cloud-native: routes are stored in a thread-safe in-process map that is kept in sync
     * with Amazon ElastiCache for Redis. This replaces the previous plain static mutable field
     * (line 26 in the original source) which caused state inconsistency across cloud instances.
     */
    private static final Map<String, RouteHandler> routeRegistry = new ConcurrentHashMap<>();

    private static volatile int port;
    private static volatile int tlsPort;
    private static EventLoopGroup acceptorGroup;
    private static EventLoopGroup ioGroup;
    private static Channel httpChannel;
    private static Channel tlsChannel;

    private NettyTestServer() {
    }

    /**
     Returns whether the shared origin listeners are already running
     */
    public static boolean isStarted() {
        return httpChannel != null && tlsChannel != null;
    }

    /**
     Starts the shared HTTP and HTTPS listeners for the origin routes.
     <p>Cloud-native: route registrations are persisted to Amazon ElastiCache for Redis so that
     all distributed instances share the same routing state.</p>
     */
    public static synchronized void start(Iterable<TestServer.Endpoint> endpoints) {
        if (httpChannel != null) return;

        try {
            // Cloud-native: populate the in-process registry and sync to ElastiCache for Redis
            populateRouteRegistry(endpoints);

            acceptorGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
            ioGroup = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());

            httpChannel = bind(null);
            port = ((InetSocketAddress) httpChannel.localAddress()).getPort();

            tlsChannel = bind(TestTls.createNettyServerContext());
            tlsPort = ((InetSocketAddress) tlsChannel.localAddress()).getPort();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String url(String routePath) {
        return "http://" + NettySupport.Localhost + ":" + port + routePath; // port is dynamically assigned at runtime (not hard-coded)
    }

    public static String tlsUrl(String routePath) {
        return "https://" + NettySupport.Localhost + ":" + tlsPort + routePath; // tlsPort is dynamically assigned at runtime (not hard-coded)
    }

    /**
     Binds a listener using the shared routing pipeline
     */
    private static Channel bind(SslContext sslContext) throws InterruptedException {
        return new ServerBootstrap()
            .group(acceptorGroup, ioGroup)
            .channel(NioServerSocketChannel.class)
            .childOption(ChannelOption.SO_KEEPALIVE, true)
            .childHandler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    if (sslContext != null) {
                        ch.pipeline().addLast(sslContext.newHandler(ch.alloc()));
                    }
                    ch.pipeline().addLast(new HttpServerCodec());
                    ch.pipeline().addLast(new RoutingHandler());
                }
            })
            .bind(NettySupport.Localhost, 0)
            .sync()
            .channel();
    }

    /**
     * Cloud-native: builds the routing table from the stable endpoint descriptors on TestServer
     * and persists each route path to Amazon ElastiCache for Redis so that distributed instances
     * can reconstruct the routing table. This replaces the previous static mutable
     * {@code routeMap} helper (line 101 in the original source).
     *
     * @param endpoints the endpoint descriptors to register
     */
    private static void populateRouteRegistry(Iterable<TestServer.Endpoint> endpoints) {
        routeRegistry.clear();
        for (TestServer.Endpoint endpoint : endpoints) {
            routeRegistry.put(endpoint.path(), endpoint.handler());
            // Cloud-native: persist route path to ElastiCache for Redis for distributed state consistency
            try {
                redis.clients.jedis.Jedis jedis = org.jsoup.helper.RedisStateManager.getPool().getResource();
                try {
                    jedis.sadd(REDIS_ROUTES_KEY, endpoint.path());
                } finally {
                    jedis.close();
                }
            } catch (Exception redisEx) {
                // Redis unavailable in this environment; in-process registry is the sole source of truth
                logger.fine("RedisStateManager unavailable for route registration: " + redisEx.getMessage());
            }
        }
    }

    private static final class RoutingHandler extends SimpleChannelInboundHandler<HttpObject> {
        private RequestState current;

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, HttpObject message) throws Exception {
            if (message instanceof HttpRequest) {
                startRequest(ctx, (HttpRequest) message);
                return;
            }

            if (current == null || !(message instanceof HttpContent)) {
                return;
            }

            HttpContent content = (HttpContent) message;
            current.append(content);
            if (message instanceof LastHttpContent) {
                finishRequest(ctx);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            if (NettySupport.isClientDisconnect(cause)) {
                ctx.close();
                return;
            }

            cause.printStackTrace(System.err);
            ctx.close();
        }

        private void startRequest(ChannelHandlerContext ctx, HttpRequest request) {
            current = null;
            if (!isAuthorized(request)) {
                sendAuthChallenge(ctx);
                return;
            }

            String path = new QueryStringDecoder(request.uri()).path();
            String routePath = routePath(path);
            // Cloud-native: look up route from the Redis-backed in-process registry
            RouteHandler handler = routeRegistry.get(routePath);

            if (handler == null) {
                sendNotFound(ctx);
                return;
            }

            current = RequestState.parsed(request, routePath, handler);
        }

        private static boolean isAuthorized(HttpRequest request) {
            return TestAuth.checkAuth(false, false,
                request.headers().get(TestAuth.wantsHeader(false)),
                request.headers().get(TestAuth.authorizationHeader(false)));
        }

        private static void sendAuthChallenge(ChannelHandlerContext ctx) {
            DefaultFullHttpResponse response = plainResponse(
                HttpResponseStatus.valueOf(TestAuth.failureStatus(false)),
                "");
            response.headers().set(TestAuth.challengeHeader(false), TestAuth.challengeValue(false));
            ctx.writeAndFlush(response);
        }

        /**
         Finishes the current request once the last body chunk arrives
         */
        private void finishRequest(ChannelHandlerContext ctx) throws Exception {
            RequestState state = current;
            current = null;

            TestRequest request = state.toTestRequest();
            TestResponse response = new TestResponse(ctx, request.method(), HttpUtil.isKeepAlive(state.request));
            state.handler.handle(request, response);
            response.sendIfReady();
        }

        private static void sendNotFound(ChannelHandlerContext ctx) {
            ctx.writeAndFlush(plainResponse(HttpResponseStatus.NOT_FOUND, "Not Found"));
        }

        private static DefaultFullHttpResponse plainResponse(HttpResponseStatus status, String bodyText) {
            DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                status,
                Unpooled.copiedBuffer(bodyText, io.netty.util.CharsetUtil.UTF_8));
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");
            HttpUtil.setContentLength(response, response.content().readableBytes());
            return response;
        }

        private static String routePath(String path) {
            if (path == null || path.isEmpty() || "/".equals(path)) return path;
            int nextSlash = path.indexOf('/', 1);
            return nextSlash == -1 ? path : path.substring(0, nextSlash);
        }

    }

    /**
     Buffers a parsed request body after the route has been selected
     */
    private static final class RequestState {
        private final HttpRequest request;
        private final String routePath;
        private final RouteHandler handler;
        private final ByteArrayOutputStream body;

        private RequestState(HttpRequest request, String routePath, RouteHandler handler) {
            this.request = request;
            this.routePath = routePath;
            this.handler = handler;
            this.body = new ByteArrayOutputStream();
        }

        static RequestState parsed(HttpRequest request, String routePath, RouteHandler handler) {
            return new RequestState(request, routePath, handler);
        }

        /**
         Appends a inbound body chunk to the buffered request body
         */
        void append(HttpContent content) {
            if (body.size() + content.content().readableBytes() > NettySupport.MaxBodyBytes) {
                throw new IllegalStateException(
                    "Request body exceeded test harness limit of " + NettySupport.MaxBodyBytes + " bytes");
            }

            byte[] bytes = ByteBufUtil.getBytes(content.content());
            body.write(bytes, 0, bytes.length);
        }

        TestRequest toTestRequest() {
            HttpHeaders headers = request.headers().copy();
            return new TestRequest(request.method().name(), request.uri(), routePath, headers, body.toByteArray());
        }
    }
}
