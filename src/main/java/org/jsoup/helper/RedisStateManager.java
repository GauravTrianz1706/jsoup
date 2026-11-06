package org.jsoup.helper;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.util.logging.Logger;

/**
 * Cloud-native state manager backed by Amazon ElastiCache for Redis.
 * <p>
 * Replaces static mutable variables with a distributed, synchronized state store
 * to ensure consistency across multiple application instances in cloud deployments.
 * </p>
 * <p>
 * Connection parameters are read from environment variables:
 * <ul>
 *   <li>{@code REDIS_HOST} – ElastiCache primary endpoint (default: {@code localhost})</li>
 *   <li>{@code REDIS_PORT} – ElastiCache port (default: {@code 6379})</li>
 *   <li>{@code REDIS_PASSWORD} – optional AUTH password</li>
 * </ul>
 * </p>
 */
public final class RedisStateManager {

    private static final Logger logger = Logger.getLogger(RedisStateManager.class.getName());

    private static final String REDIS_HOST =
        System.getenv("REDIS_HOST") != null ? System.getenv("REDIS_HOST") : "localhost";
    private static final int REDIS_PORT;
    private static final String REDIS_PASSWORD = System.getenv("REDIS_PASSWORD");

    static {
        String portEnv = System.getenv("REDIS_PORT");
        int port = 6379;
        if (portEnv != null) {
            try {
                port = Integer.parseInt(portEnv);
            } catch (NumberFormatException e) {
                logger.warning("Invalid REDIS_PORT value '" + portEnv + "', using default 6379");
            }
        }
        REDIS_PORT = port;
    }

    private static volatile JedisPool pool;

    private RedisStateManager() {}

    /**
     * Returns the shared {@link JedisPool}, initializing it on first use.
     *
     * @return the shared connection pool
     */
    public static JedisPool getPool() {
        if (pool == null) {
            synchronized (RedisStateManager.class) {
                if (pool == null) {
                    JedisPoolConfig config = new JedisPoolConfig();
                    config.setMaxTotal(16);
                    config.setMaxIdle(8);
                    config.setMinIdle(2);
                    config.setTestOnBorrow(true);
                    config.setTestOnReturn(true);
                    if (REDIS_PASSWORD != null && !REDIS_PASSWORD.isEmpty()) {
                        pool = new JedisPool(config, REDIS_HOST, REDIS_PORT, 2000, REDIS_PASSWORD);
                    } else {
                        pool = new JedisPool(config, REDIS_HOST, REDIS_PORT, 2000);
                    }
                    logger.info("RedisStateManager: connected to ElastiCache at " + REDIS_HOST + ":" + REDIS_PORT);
                }
            }
        }
        return pool;
    }

    /**
     * Stores a string value in Redis under the given key.
     *
     * @param key   the Redis key
     * @param value the value to store
     */
    public static void set(String key, String value) {
        try (Jedis jedis = getPool().getResource()) {
            jedis.set(key, value);
        }
    }

    /**
     * Retrieves a string value from Redis.
     *
     * @param key the Redis key
     * @return the stored value, or {@code null} if absent
     */
    public static String get(String key) {
        try (Jedis jedis = getPool().getResource()) {
            return jedis.get(key);
        }
    }

    /**
     * Stores a string value in Redis with a TTL (time-to-live) in seconds.
     *
     * @param key     the Redis key
     * @param value   the value to store
     * @param ttlSecs time-to-live in seconds
     */
    public static void setEx(String key, String value, int ttlSecs) {
        try (Jedis jedis = getPool().getResource()) {
            jedis.setex(key, ttlSecs, value);
        }
    }

    /**
     * Deletes a key from Redis.
     *
     * @param key the Redis key to delete
     */
    public static void delete(String key) {
        try (Jedis jedis = getPool().getResource()) {
            jedis.del(key);
        }
    }

    /**
     * Checks whether a key exists in Redis.
     *
     * @param key the Redis key
     * @return {@code true} if the key exists
     */
    public static boolean exists(String key) {
        try (Jedis jedis = getPool().getResource()) {
            return jedis.exists(key);
        }
    }

    /**
     * Stores a hash field value in Redis.
     *
     * @param hashKey the Redis hash key
     * @param field   the hash field
     * @param value   the value to store
     */
    public static void hset(String hashKey, String field, String value) {
        try (Jedis jedis = getPool().getResource()) {
            jedis.hset(hashKey, field, value);
        }
    }

    /**
     * Retrieves a hash field value from Redis.
     *
     * @param hashKey the Redis hash key
     * @param field   the hash field
     * @return the stored value, or {@code null} if absent
     */
    public static String hget(String hashKey, String field) {
        try (Jedis jedis = getPool().getResource()) {
            return jedis.hget(hashKey, field);
        }
    }

    /**
     * Closes the shared connection pool. Should be called on application shutdown.
     */
    public static void shutdown() {
        if (pool != null) {
            pool.close();
            pool = null;
        }
    }
}
