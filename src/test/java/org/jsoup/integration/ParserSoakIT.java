package org.jsoup.integration;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.parser.Parser;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.File;
import java.io.IOException;
import java.util.stream.Stream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 Long-running parser soak tests over known fuzz and regression fixtures. These repeat each fixture enough to catch
 timeout regressions when parser internals change; the long-running profile opts them in because timing varies by
 machine.
 <p>Run with {@code mvn -Plong-running -Dit.test=ParserSoakIT verify}</p>
 */
@Tag("long-running")
public class ParserSoakIT {
    static int numIters = 50;
    static int timeout = 30; // external fuzzer is set to 60 for 100 runs
    // Replaced hard-coded local File directory with Amazon S3 object storage (AWS SDK v2)
    private static final String S3_BUCKET = System.getenv().getOrDefault("AWS_S3_TEST_BUCKET", "jsoup-test-resources");
    private static final String S3_FUZZ_PREFIX = "fuzztests/";

    private static Stream<File> testFiles() {
        // List fuzz test objects from S3 and download each to a temp file for testing
        try (S3Client s3 = S3Client.create()) {
            ListObjectsV2Response listing = s3.listObjectsV2(
                ListObjectsV2Request.builder().bucket(S3_BUCKET).prefix(S3_FUZZ_PREFIX).build());
            java.util.List<S3Object> objects = listing.contents();
            assertNotNull(objects);
            assertTrue(objects.size() > 10);
            java.util.List<File> files = new java.util.ArrayList<>();
            for (S3Object obj : objects) {
                if (obj.key().equals(S3_FUZZ_PREFIX)) continue; // skip directory marker
                File tempFile = ParseTest.getFile("/" + obj.key());
                files.add(tempFile);
            }
            return files.stream();
        }
    }

    @ParameterizedTest
    @MethodSource("testFiles")
    void testHtmlParse(File file) throws IOException {
        long startTime = System.currentTimeMillis();
        long completeBy = startTime + timeout * 1000L;

        for (int i = 0; i < numIters; i++) {
            Document doc = Jsoup.parse(file, "UTF-8", "https://example.com/");
            assertNotNull(doc);
            if (System.currentTimeMillis() > completeBy)
                Assertions.fail(String.format("Timeout: only completed %d iters of [%s] in %d seconds", i, file.getName(), timeout));
        }
    }

    @ParameterizedTest
    @MethodSource("testFiles")
    void testXmlParse(File file) throws IOException {
        long startTime = System.currentTimeMillis();
        long completeBy = startTime + timeout * 1000L;

        for (int i = 0; i < numIters; i++) {
            Document doc = Jsoup.parse(file, "UTF-8", "https://example.com/", Parser.xmlParser());
            assertNotNull(doc);
            if (System.currentTimeMillis() > completeBy)
                Assertions.fail(String.format("Timeout: only completed %d iters of [%s] in %d seconds", i, file.getName(), timeout));
        }
    }
}
