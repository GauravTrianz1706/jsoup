package org.jsoup.integration;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

import java.io.InputStream;

/**
 * Helper class for accessing test resources stored in Amazon S3.
 * Replaces hard-coded local file path access with cloud-native S3 object storage.
 */
public class AwsS3TestHelper {

    /**
     * Retrieves an object from Amazon S3 as an InputStream.
     *
     * @param bucketName the S3 bucket name (can be configured via AWS_S3_TEST_BUCKET env var)
     * @param key        the S3 object key (path within the bucket)
     * @return an InputStream for the S3 object content
     */
    public static InputStream getObjectInputStream(String bucketName, String key) {
        S3Client s3 = S3Client.create();
        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
            .bucket(bucketName)
            .key(key)
            .build();
        return s3.getObject(getObjectRequest);
    }
}
