package org.jsoup.nodes;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.jsoup.Connection;
import org.jsoup.Jsoup;

import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Fetches HTML entity names from w3.org json, and outputs data files for optimized used in Entities.
 * I refuse to believe that entity names like "NotNestedLessLess" are valuable or useful for HTML authors. Implemented
 * only to be complete.
 */
class BuildEntities {
    public static void main(String[] args) throws IOException {
        String url = "https://www.w3.org/TR/2012/WD-html5-20121025/entities.json";
        Connection.Response res = Jsoup.connect(url)
            .ignoreContentType(true)
            .execute();

        Gson gson = new Gson();
        Map<String, CharacterRef> input = gson.fromJson(res.body(),
            new TypeToken<Map<String, CharacterRef>>() {
            }.getType());


        // build name sorted base and full character lists:
        ArrayList<CharacterRef> base = new ArrayList<>();
        ArrayList<CharacterRef> full = new ArrayList<>();

        for (Map.Entry<String, CharacterRef> entry : input.entrySet()) {
            String name = entry.getKey().substring(1); // name is like &acute or &acute; , trim &
            CharacterRef ref = entry.getValue();
            if (name.endsWith(";")) {
                name = name.substring(0, name.length() - 1);
                full.add(ref);
            } else {
                base.add(ref);
            }
            ref.name = name;
        }
        base.sort(byName);
        full.sort(byName);

        // now determine code point order
        ArrayList<CharacterRef> baseByCode = new ArrayList<>(base);
        ArrayList<CharacterRef> fullByCode = new ArrayList<>(full);
        baseByCode.sort(byCode);
        fullByCode.sort(byCode);

        // and update their codepoint index.
        @SuppressWarnings("unchecked") ArrayList<CharacterRef>[] codelists = new ArrayList[]{baseByCode, fullByCode};
        for (ArrayList<CharacterRef> codelist : codelists) {
            for (int i = 0; i < codelist.size(); i++) {
                codelist.get(i).codeIndex = i;
            }
        }

        // now write them
        persist("entities-full", full);
        persist("entities-base", base);

        System.out.println("Full size: " + full.size() + ", base size: " + base.size());
    }

    private static void persist(String name, ArrayList<CharacterRef> refs) throws IOException {
        // Replaced hard-coded local temp file with Amazon S3 object storage (AWS SDK v2)
        StringWriter writer = new StringWriter();
        writer.append("static final String points = \"");
        for (CharacterRef ref : refs) {
            writer.append(ref.toString()).append('&');
        }
        writer.append("\";\n");

        String content = writer.toString();
        String bucketName = System.getenv().getOrDefault("AWS_S3_BUCKET", "jsoup-entities-output");
        String key = name + ".txt";

        try (S3Client s3 = S3Client.create()) {
            PutObjectRequest putRequest = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .build();
            s3.putObject(putRequest, RequestBody.fromString(content));
        }

        System.out.println("Wrote " + name + " to s3://" + bucketName + "/" + key);
    }


    private static class CharacterRef {
        int[] codepoints;
        String name;
        int codeIndex;

        @Override
        public String toString() {
            return name
                + "="
                + d(codepoints[0])
                + (codepoints.length > 1 ? "," + d(codepoints[1]) : "")
                + ";" + d(codeIndex);
        }
    }

    private static String d(int d) {
        return Integer.toString(d, Entities.codepointRadix);
    }

    private static class ByCode implements Comparator<CharacterRef> {
        public int compare(CharacterRef o1, CharacterRef o2) {
            int[] c1 = o1.codepoints;
            int[] c2 = o2.codepoints;
            int first = c1[0] - c2[0];
            if (first != 0)
                return first;
            if (c1.length == 1 && c2.length == 1) { // for the same code, use the shorter name
                int len = o2.name.length() - o1.name.length();
                if (len != 0)
                    return len;
                return o1.name.compareTo(o2.name);
            }
            if (c1.length == 2 && c2.length == 2)
                return c1[1] - c2[1];
            else
                return c2.length - c1.length; // pushes multi down the list so hits on singles first (don't support multi lookup by codepoint yet)
        }
    }

    private static final Comparator<CharacterRef> byName = Comparator.comparing(ref -> ref.name);
    private static final ByCode byCode = new ByCode();
}
