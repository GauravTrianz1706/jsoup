package org.jsoup.integration;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.junit.jupiter.api.Test;
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
 Tests fixes for issues raised by the OSS Fuzz project @ https://oss-fuzz.com/testcases?project=jsoup. Contains inline
 string cases causing exceptions. Timeout tests are in ParserSoakIT.
 */
public class FuzzFixesTest {
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

    @Test
    public void blankAbsAttr() {
        // https://github.com/jhy/jsoup/issues/1541
        String html = "b<bodY abs: abs:abs: abs:abs:abs>";
        Document doc = Jsoup.parse(html);
        assertNotNull(doc);
    }

    @Test
    public void bookmark() {
        // https://github.com/jhy/jsoup/issues/1576
        String html = "<?a<U<P<A ";
        Document doc = Jsoup.parse(html);
        assertNotNull(doc);

        Document xmlDoc = Parser.xmlParser().parseInput(html, "");
        assertNotNull(xmlDoc);
    }

    @Test void fragment() {
        Parser.htmlParser().parseFragmentInput("<frameset>>l\u0000<\u0000<ditl>\u0000< \\", new Element("colgroup"), "");
    }

    @ParameterizedTest
    @MethodSource("testFiles")
    void testHtmlParse(File file) throws IOException {
        Document doc = Jsoup.parse(file, "UTF-8", "https://example.com/");
        assertNotNull(doc);
        doc = Jsoup.parse(file, "UTF-8", ""); // no base href attr; so same as a parse(string), which can have subtly different semantics
        assertNotNull(doc);
    }

    @ParameterizedTest
    @MethodSource("testFiles")
    void testHtmlFragmentParse(File file) throws IOException {
        String html = ParseTest.getFileAsString(file);
        Document doc = Jsoup.parseBodyFragment(html);
        assertNotNull(doc);
    }

    @ParameterizedTest
    @MethodSource("testFiles")
    void testXmlParse(File file) throws IOException {
        Document doc = Jsoup.parse(file, "UTF-8", "https://example.com/", Parser.xmlParser());
        assertNotNull(doc);
        doc = Jsoup.parse(file, "UTF-8", "", Parser.xmlParser()); // no base href attr
    }
}
