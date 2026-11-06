package org.jsoup.integration;

import org.jsoup.Jsoup;
import org.jsoup.helper.DataUtil;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.ParseErrorList;
import org.jsoup.parser.Parser;
import org.jsoup.select.Elements;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.zip.GZIPInputStream;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test: parses from real-world example HTML.
 *
 * @author Jonathan Hedley, jonathan@hedley.net
 */
public class ParseTest {
    @Test
    public void testHtml5Charset() throws IOException {
        // test that <meta charset="gb2312"> works
        File in = getFile("/htmltests/meta-charset-1.html");
        Document doc = Jsoup.parse(in, null, "http://example.com/"); //gb2312, has html5 <meta charset>
        assertEquals("新", doc.text());
        assertEquals("GB2312", doc.outputSettings().charset().displayName());

        // double check, no charset, falls back to utf8 which is incorrect
        in = getFile("/htmltests/meta-charset-2.html"); //
        doc = Jsoup.parse(in, null, "http://example.com"); // gb2312, no charset
        assertEquals("UTF-8", doc.outputSettings().charset().displayName());
        assertNotEquals("新", doc.text());

        // confirm fallback to utf8
        in = getFile("/htmltests/meta-charset-3.html");
        doc = Jsoup.parse(in, null, "http://example.com/"); // utf8, no charset
        assertEquals("UTF-8", doc.outputSettings().charset().displayName());
        assertEquals("新", doc.text());
    }

    @Test
    public void testBrokenHtml5CharsetWithASingleDoubleQuote() throws IOException {
        InputStream in = inputStreamFrom("<html>\n" +
                "<head><meta charset=UTF-8\"></head>\n" +
                "<body></body>\n" +
                "</html>");
        Document doc = Jsoup.parse(in, null, "http://example.com/");
        assertEquals("UTF-8", doc.outputSettings().charset().displayName());
    }

    @Test
    public void testLowercaseUtf8Charset() throws IOException {
        File in = getFile("/htmltests/lowercase-charset-test.html");
        Document doc = Jsoup.parse(in, null);

        Element form = doc.select("#form").first();
        assertEquals(2, form.children().size());
        assertEquals("UTF-8", doc.outputSettings().charset().name());
    }

    @Test
    public void testXwiki() throws IOException {
        // https://github.com/jhy/jsoup/issues/1324
        // this tests that when in CharacterReader we hit a buffer while marked, we preserve the mark when buffered up and can rewind
        File in = getFile("/htmltests/xwiki-1324.html.gz");
        Document doc = Jsoup.parse(in, null, "https://localhost/");
        assertEquals("XWiki Jetty HSQLDB 12.1-SNAPSHOT", doc.select("#xwikiplatformversion").text());

        // was getting busted at =userdirectory, because it hit the bufferup point but the mark was then lost. so
        // updated to preserve the mark.
        String wantHtml = "<a class=\"list-group-item\" data-id=\"userdirectory\" href=\"/xwiki/bin/admin/XWiki/XWikiPreferences?editor=globaladmin&amp;section=userdirectory\" title=\"Customize the user directory live table.\">User Directory</a>";
        assertEquals(wantHtml, doc.select("[data-id=userdirectory]").outerHtml());
    }

    @Test
    public void testXwikiExpanded() throws IOException {
        // https://github.com/jhy/jsoup/issues/1324
        // this tests that if there is a huge illegal character reference, we can get through a buffer and rewind, and still catch that it's an invalid refence,
        // and the parse tree is correct.
        File in = getFile("/htmltests/xwiki-edit.html.gz");
        Parser parser = Parser.htmlParser();
        Document doc = Jsoup.parse(new GZIPInputStream(new FileInputStream(in)), "UTF-8", "https://localhost/", parser.setTrackErrors(100));
        ParseErrorList errors = parser.getErrors();

        assertEquals("XWiki Jetty HSQLDB 12.1-SNAPSHOT", doc.select("#xwikiplatformversion").text());
        assertEquals(0, errors.size()); // not an invalid reference because did not look legit

        // was getting busted at =userdirectory, because it hit the bufferup point but the mark was then lost. so
        // updated to preserve the mark.
        String wantHtml = "<a class=\"list-group-item\" data-id=\"userdirectory\" href=\"/xwiki/bin/admin/XWiki/XWikiPreferences?editor=globaladmin&amp;RIGHTHERERIGHTHERERIGHTHERERIGHTHERE";
        assertTrue(doc.select("[data-id=userdirectory]").outerHtml().startsWith(wantHtml));
    }

    @Test public void testWikiExpandedFromString() throws IOException {
        File in = getFile("/htmltests/xwiki-edit.html.gz");
        String html = getFileAsString(in);
        Document doc = Jsoup.parse(html);
        assertEquals("XWiki Jetty HSQLDB 12.1-SNAPSHOT", doc.select("#xwikiplatformversion").text());
        String wantHtml = "<a class=\"list-group-item\" data-id=\"userdirectory\" href=\"/xwiki/bin/admin/XWiki/XWikiPreferences?editor=globaladmin&amp;RIGHTHERERIGHTHERERIGHTHERERIGHTHERE";
        assertTrue(doc.select("[data-id=userdirectory]").outerHtml().startsWith(wantHtml));
    }

    @Test public void testWikiFromString() throws IOException {
        File in = getFile("/htmltests/xwiki-1324.html.gz");
        String html = getFileAsString(in);
        Document doc = Jsoup.parse(html);
        assertEquals("XWiki Jetty HSQLDB 12.1-SNAPSHOT", doc.select("#xwikiplatformversion").text());
        String wantHtml = "<a class=\"list-group-item\" data-id=\"userdirectory\" href=\"/xwiki/bin/admin/XWiki/XWikiPreferences?editor=globaladmin&amp;section=userdirectory\" title=\"Customize the user directory live table.\">User Directory</a>";
        assertEquals(wantHtml, doc.select("[data-id=userdirectory]").outerHtml());
    }

    @Test public void testFileParseNoCharsetMethod() throws IOException {
        File in = getFile("/htmltests/xwiki-1324.html.gz");
        Document doc = Jsoup.parse(in);
        assertEquals("XWiki Jetty HSQLDB 12.1-SNAPSHOT", doc.select("#xwikiplatformversion").text());
    }


    /**
     * Retrieves a test resource as a File. For cloud-native environments, resources are fetched
     * from Amazon S3 using AWS SDK v2 when the resource is not available on the local classpath.
     * Falls back to classpath resource resolution for backward compatibility.
     */
    public static File getFile(String resourceName) {
        try {
            URL resource = ParseTest.class.getResource(resourceName);
            if (resource != null) {
                return new File(resource.toURI());
            }
            // Cloud-native fallback: download from Amazon S3 and write to a temp file
            String bucketName = System.getenv().getOrDefault("AWS_S3_TEST_BUCKET", "jsoup-test-resources");
            String key = resourceName.startsWith("/") ? resourceName.substring(1) : resourceName;
            try (S3Client s3 = S3Client.create()) {
                ResponseInputStream<GetObjectResponse> s3Object = s3.getObject(
                    GetObjectRequest.builder().bucket(bucketName).key(key).build());
                String suffix = resourceName.contains(".") ? resourceName.substring(resourceName.lastIndexOf('.')) : ".tmp";
                File tempFile = File.createTempFile("jsoup-s3-", suffix);
                tempFile.deleteOnExit();
                try (OutputStream out = new FileOutputStream(tempFile)) {
                    byte[] buf = new byte[8192];
                    int read;
                    while ((read = s3Object.read(buf)) != -1) {
                        out.write(buf, 0, read);
                    }
                }
                return tempFile;
            }
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to retrieve resource from S3: " + resourceName, e);
        }
    }

    public static Path getPath(String resourceName) {
        try {
            URL resource = ParseTest.class.getResource(resourceName);
            return resource != null ? Paths.get(resource.toURI()) : Paths.get("/404");
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    public static InputStream inputStreamFrom(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    public static String getFileAsString(File file) throws IOException {
        byte[] bytes;
        if (file.getName().endsWith(".gz")) {
            InputStream stream = new GZIPInputStream(new FileInputStream(file));
            ByteBuffer byteBuffer = DataUtil.readToByteBuffer(stream, 0);
            bytes = new byte[byteBuffer.limit()];
            System.arraycopy(byteBuffer.array(), 0, bytes, 0, byteBuffer.limit());
        } else {
            bytes = Files.readAllBytes(file.toPath());
        }
        return new String(bytes);
    }

}
