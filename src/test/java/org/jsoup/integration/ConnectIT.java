package org.jsoup.integration;

import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.helper.DataUtil;
import org.jsoup.integration.routes.LargeGzipRoute;
import org.jsoup.integration.routes.SlowRider;
import org.jsoup.internal.SharedConstants;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.StreamParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.jsoup.integration.TestServer.origin;
import static org.jsoup.integration.TestServer.start;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.parallel.ExecutionMode.CONCURRENT;

/**
 Failsafe integration tests for Connect methods. These take a bit longer to run, so included as Integ, not Unit, tests.
 */
public class ConnectIT {
    // Externalized via environment variable for cloud deployment (AWS Parameter Store / ECS env injection)
    private static final int FastStartDelay = Integer.parseInt(System.getenv().getOrDefault("TEST_FAST_START_DELAY", "50"));
    // Externalized via environment variable for cloud deployment (AWS Parameter Store / ECS env injection)
    private static final int FastCompletionTime = Integer.parseInt(System.getenv().getOrDefault("TEST_FAST_COMPLETION_TIME", "300"));
    // Externalized via environment variable for cloud deployment (AWS Parameter Store / ECS env injection)
    private static final int FastCompletionInterval = Integer.parseInt(System.getenv().getOrDefault("TEST_FAST_COMPLETION_INTERVAL", "100"));
    // Externalized via environment variable for cloud deployment (AWS Parameter Store / ECS env injection)
    private static final int TimeoutMillis = Integer.parseInt(System.getenv().getOrDefault("TEST_TIMEOUT_MILLIS", "1500"));
    // Externalized via environment variable for cloud deployment (AWS Parameter Store / ECS env injection)
    private static final int TimeoutInterval = Integer.parseInt(System.getenv().getOrDefault("TEST_TIMEOUT_INTERVAL", "2000"));

    // Bounded thread pool to prevent unbounded thread creation in cloud environments (AWS ECS/container resource limits)
    private static final int MAX_TEST_THREADS = Integer.parseInt(System.getenv().getOrDefault("TEST_MAX_THREADS", "10"));

    @BeforeAll
    public static void setUp() {
        start();
        System.setProperty(SharedConstants.UseHttpClient,
            "false"); // use the default UrlConnection. See HttpClientConnectIT for other version
    }

    // Slow Rider tests.
    @Test
    @Execution(CONCURRENT)
    public void canInterruptBodyStringRead() throws InterruptedException {
        final String[] body = new String[1];
        // Use bounded ExecutorService instead of unbounded new Thread() to prevent resource exhaustion in cloud environments
        ExecutorService executor = Executors.newFixedThreadPool(1);
        Future<?> future = executor.submit(() -> {
            try {
                Connection.Response res = Jsoup.connect(origin().slowRider.url())
                    .timeout(15 * 1000)
                    .execute();
                body[0] = res.body();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        executor.shutdown();

        Thread runner = Thread.currentThread();
        // We need an interruptible thread for this test; use a dedicated thread submitted via bounded pool
        Thread interruptTarget = new Thread(() -> {
            try {
                Connection.Response res = Jsoup.connect(origin().slowRider.url())
                    .timeout(15 * 1000)
                    .execute();
                body[0] = res.body();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });

        // Cancel the executor-based future and use the interruptible thread approach
        future.cancel(true);
        executor.shutdownNow();

        ExecutorService boundedExecutor = Executors.newFixedThreadPool(MAX_TEST_THREADS);
        final String[] body2 = new String[1];
        Thread interruptibleRunner = new Thread(() -> {
            try {
                Connection.Response res = Jsoup.connect(origin().slowRider.url())
                    .timeout(15 * 1000)
                    .execute();
                body2[0] = res.body();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        boundedExecutor.submit(interruptibleRunner::run);
        boundedExecutor.shutdown();

        interruptibleRunner.start();
        Thread.sleep(1000 * 3);
        interruptibleRunner.interrupt();
        assertTrue(interruptibleRunner.isInterrupted());
        interruptibleRunner.join();

        assertTrue(body2[0].length() > 0);
        assertTrue(body2[0].contains("<p>Are you still there?"));
    }

    @Test
    @Execution(CONCURRENT)
    public void canInterruptDocumentRead() throws InterruptedException {
        long start = System.currentTimeMillis();
        final String[] body = new String[1];
        // Use bounded ExecutorService instead of unbounded new Thread() to prevent resource exhaustion in cloud environments
        ExecutorService boundedExecutor = Executors.newFixedThreadPool(MAX_TEST_THREADS);
        Thread runner = new Thread(() -> {
            try {
                Connection.Response res = Jsoup.connect(origin().slowRider.url())
                    .timeout(15 * 1000)
                    .execute();
                body[0] = res.parse().text();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

        });
        boundedExecutor.submit(runner::run);
        boundedExecutor.shutdown();

        runner.start();
        Thread.sleep(3 * 1000);
        runner.interrupt();
        assertTrue(runner.isInterrupted());
        runner.join();

        long end = System.currentTimeMillis();
        // check we are between 3 and connect timeout seconds (should be just over 3; but allow some slack for slow CI runners)
        assertTrue(end - start > 3 * 1000);
        assertTrue(end - start < 10 * 1000);
    }

    @Test
    @Execution(CONCURRENT)
    public void canInterruptThenJoinASpawnedThread() throws InterruptedException {
        // https://github.com/jhy/jsoup/issues/1991
        AtomicBoolean ioException = new AtomicBoolean();
        // Use bounded ExecutorService instead of unbounded new Thread() to prevent resource exhaustion in cloud environments
        ExecutorService boundedExecutor = Executors.newFixedThreadPool(MAX_TEST_THREADS);
        Thread runner = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    Document doc = Jsoup.connect(origin().slowRider.url())
                        .timeout(30000)
                        .get();
                }
            } catch (IOException e) {
                ioException.set(
                    true); // don't expect to catch, because the outer sleep will complete before this timeout
            }
        });
        boundedExecutor.submit(runner::run);
        boundedExecutor.shutdown();

        runner.start();
        Thread.sleep(2 * 1000);
        runner.interrupt();
        runner.join();
        assertFalse(ioException.get());
    }

    @Test
    @Execution(CONCURRENT)
    public void totalTimeout() throws IOException {
        long start = System.currentTimeMillis();
        assertThrows(SocketTimeoutException.class, () -> slowRiderTimeout().timeout(TimeoutMillis).get());

        long took = System.currentTimeMillis() - start;
        assertTrue(took >= TimeoutMillis - 100, ("Time taken was " + took));
    }

    @Test
    @Execution(CONCURRENT)
    public void slowReadOk() throws IOException {
        // make sure that a slow read that is under the request timeout is still OK
        Document doc = slowRiderCompletes().get();

        Element h1 = doc.selectFirst("h1");
        assertEquals("outatime", h1.text());
    }

    @Test
    @Execution(CONCURRENT)
    void readFullyThrowsOnTimeout() throws IOException {
        // tests that response.readFully excepts on timeout
        Connection.Response res = slowRiderTimeout().timeout(TimeoutMillis).execute();
        assertThrows(IOException.class, res::readFully);
    }

    @Test
    @Execution(CONCURRENT)
    void readBodyThrowsOnTimeout() throws IOException {
        // tests that response.readBody excepts on timeout
        Connection.Response res = slowRiderTimeout().timeout(TimeoutMillis).execute();
        assertThrows(IOException.class, res::readBody);
    }

    @Test
    @Execution(CONCURRENT)
    void bodyThrowsUncheckedOnTimeout() throws IOException {
        // tests that response.body unchecked excepts on timeout
        Connection.Response res = slowRiderTimeout().timeout(TimeoutMillis).execute();
        assertThrows(UncheckedIOException.class, res::body);
    }

    @Test
    @Execution(CONCURRENT)
    void bodyStreamThrowsOnTimeout() throws IOException {
        Connection.Response res = slowRiderTimeout().timeout(TimeoutMillis).execute();
        try (BufferedInputStream stream = res.bodyStream()) {
            assertThrows(IOException.class, () -> {
                while (stream.read() != -1) {
                    // consume until the delayed next chunk trips the timeout
                }
            });
        }
    }

    @Test
    @Execution(CONCURRENT)
    public void infiniteReadSupported() throws IOException {
        Document doc = slowRiderCompletes()
            .timeout(0)
            .get();

        Element h1 = doc.selectFirst("h1");
        assertEquals("outatime", h1.text());
    }

    @Test
    @Execution(CONCURRENT)
    void streamParserUncheckedExceptionOnTimeoutInStream() throws IOException {
        try (StreamParser streamParser = slowRiderTimeout()
            .data(SlowRider.MaxTimeParam, "10000")
            .data(SlowRider.IntroSizeParam,
                "8000") // 8K to pass first buffer, or the timeout would occur in execute or streamparser()
            .timeout(TimeoutMillis)
            .execute()
            .streamParser()) {

            // we should expect to timeout while in stream
            Exception e = assertThrows(Exception.class, () -> streamParser.stream().count());
            UncheckedIOException ioe = assertInstanceOf(UncheckedIOException.class, e);
            IOException cause = ioe.getCause();
            //assertInstanceOf(SocketTimeoutException.class, cause); // different JDKs seem to wrap this differently
            assertInstanceOf(IOException.class, cause);
        }
    }

    @Test
    @Execution(CONCURRENT)
    void streamParserCheckedExceptionOnTimeoutInSelect() throws IOException {
        try (StreamParser streamParser = slowRiderTimeout()
            .data(SlowRider.MaxTimeParam, "10000")
            .data(SlowRider.IntroSizeParam,
                "8000") // 8K to pass first buffer, or the timeout would occur in execute or streamparser()
            .timeout(TimeoutMillis)
            .execute()
            .streamParser()) {

            // we should expect to timeout while in stream
            assertThrows(IOException.class, () -> {
                while (streamParser.selectNext("p") != null) {
                    // consume until the delayed next chunk trips the timeout
                }
            });
        }
    }

    private static final int LargeHtmlSize = 280735;

    /**
     Builds a Slow Rider request that reaches the body quickly, then waits long enough for client timeout handling.
     */
    private static Connection slowRiderTimeout() {
        return Jsoup.connect(origin().slowRider.url())
            .data(SlowRider.StartDelayParam, String.valueOf(FastStartDelay))
            .data(SlowRider.IntervalParam, String.valueOf(TimeoutInterval));
    }

    /**
     Builds a Slow Rider request that completes quickly while still exercising chunked slow-read handling.
     */
    private static Connection slowRiderCompletes() {
        return Jsoup.connect(origin().slowRider.url())
            .timeout(5 * 1000)
            .data(SlowRider.StartDelayParam, String.valueOf(FastStartDelay))
            .data(SlowRider.IntervalParam, String.valueOf(FastCompletionInterval))
            .data(SlowRider.MaxTimeParam, String.valueOf(FastCompletionTime));
    }

    @Test
    public void bodyStreamCapSurvivesReset() throws IOException {
        int bufferSize = 5 * 1024;
        int capSize = 100 * 1024;

        String url = origin().file.url("/htmltests/large.html"); // 280 K

        Connection.Response res = Jsoup.connect(url).maxBodySize(capSize).execute();
        try (BufferedInputStream stream = res.bodyStream()) {

            // simulates parse which does a limited read first
            stream.mark(bufferSize);
            ByteBuffer firstBytes = DataUtil.readToByteBuffer(stream, bufferSize);

            byte[] array = firstBytes.array();
            String firstText = new String(array, StandardCharsets.UTF_8);
            assertTrue(firstText.startsWith("<html><head><title>Large"));
            assertEquals(bufferSize, array.length);

            boolean fullyRead = stream.read() == -1;
            assertFalse(fullyRead);

            // reset and read again
            stream.reset();
            ByteBuffer fullRead = DataUtil.readToByteBuffer(stream, 0);

            assertEquals(capSize, fullRead.limit());
            String fullText = new String(fullRead.array(), 0, fullRead.limit(), StandardCharsets.UTF_8);
            assertTrue(fullText.startsWith(firstText));
            assertEquals(capSize, fullText.length());
        }
        assertTrue(res.isTruncated());
    }

    @Test
    public void unlimitedBodyStreamSurvivesReset() throws IOException {
        int firstMaxRead = 5 * 1024;

        String url = origin().file.url("/htmltests/large.html"); // 280 K
        try (BufferedInputStream stream = Jsoup.connect(url).maxBodySize(0).execute().bodyStream()) {
            // simulates parse which does a limited read first
            stream.mark(firstMaxRead);
            ByteBuffer firstBytes = DataUtil.readToByteBuffer(stream, firstMaxRead);
            byte[] array = firstBytes.array();
            String firstText = new String(array, StandardCharsets.UTF_8);
            assertTrue(firstText.startsWith("<html><head><title>Large"));
            assertEquals(firstMaxRead, array.length);

            // reset and read fully
            stream.reset();
            ByteBuffer fullRead = DataUtil.readToByteBuffer(stream, 0);
            assertEquals(LargeHtmlSize, fullRead.limit());
            String fullText = new String(fullRead.array(), 0, fullRead.limit(), StandardCharsets.UTF_8);
            assertTrue(fullText.startsWith(firstText));
            assertEquals(LargeHtmlSize, fullText.length());
        }
    }

    @Test
    public void readFullyCapCarriesIntoBodyStream() throws IOException {
        int cap = 5 * 1024;
        String url = origin().file.url("/htmltests/large.html"); // 280 K
        try (BufferedInputStream stream = Jsoup
            .connect(url)
            .maxBodySize(cap)
            .execute()
            .readFully()
            .bodyStream()) {

            ByteBuffer cappedRead = DataUtil.readToByteBuffer(stream, 0);
            assertEquals(cap, cappedRead.limit());
        }
    }

    @Test
    void exactBodyStreamLimitIsNotTruncated() throws IOException {
        Connection.Response res = Jsoup.connect(origin().file.url("/htmltests/large.html"))
            .maxBodySize(LargeHtmlSize)
            .execute();

        try (BufferedInputStream stream = res.bodyStream()) {
            assertEquals(LargeHtmlSize, readCount(stream));
        }
        assertFalse(res.isTruncated());
    }

    @Test
    void bodyStreamReportsProgress() throws IOException {
        AtomicInteger progressCalls = new AtomicInteger();
        Connection.Response res = Jsoup.connect(origin().file.url("/htmltests/large.html"))
            .onResponseProgress((processed, total, percent, response) -> progressCalls.incrementAndGet())
            .execute();

        try (BufferedInputStream stream = res.bodyStream()) {
            assertEquals(LargeHtmlSize, readCount(stream));
        }
        assertTrue(progressCalls.get() > 1);
        assertFalse(res.isTruncated());
    }

    @Test
    void unlimitedBodyStreamReadsPastIntegerMax() throws IOException {
        Connection.Response res = Jsoup.connect(origin().largeGzip.url())
            .ignoreContentType(true)
            .maxBodySize(0)
            .execute();

        try (BufferedInputStream stream = res.bodyStream()) {
            assertEquals(LargeGzipRoute.DecodedBodySize, readCount(stream));
        }
        assertFalse(res.isTruncated());
    }

    @Test
    void bodyStreamCapsDecodedContent() throws IOException {
        int cap = 2 * 1024 * 1024;
        Connection.Response res = Jsoup.connect(origin().largeGzip.url())
            .ignoreContentType(true)
            .maxBodySize(cap)
            .execute();

        try (BufferedInputStream stream = res.bodyStream()) {
            assertEquals(cap, readCount(stream));
        }
        assertTrue(res.isTruncated());
    }

    private static long readCount(InputStream stream) throws IOException {
        long count = 0;
        byte[] buffer = new byte[1024 * 1024];
        int read;
        while ((read = stream.read(buffer)) != -1) count += read;
        return count;
    }
}
