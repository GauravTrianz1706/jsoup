package org.jsoup.helper;

/**
 * Utility class for retrieving secrets from AWS Secrets Manager.
 *
 * <p>This class provides a cloud-native approach to secret management, replacing
 * hard-coded credentials in source code. Secrets are retrieved from AWS Secrets Manager
 * at runtime, enabling:</p>
 * <ul>
 *   <li>Automatic credential rotation without redeployment</li>
 *   <li>Centralized, encrypted secret storage</li>
 *   <li>Compliance with cloud security requirements</li>
 *   <li>Prevention of credential exposure in version control or container images</li>
 * </ul>
 *
 * <p>Resolution order for a secret value:</p>
 * <ol>
 *   <li>AWS Secrets Manager (when {@code software.amazon.awssdk:secretsmanager} is on the classpath
 *       and the secret name is accessible in the current AWS region)</li>
 *   <li>Environment variable (identified by {@code envVarFallback})</li>
 *   <li>Provided default value</li>
 * </ol>
 *
 * <p>To configure AWS Secrets Manager, ensure the application has the appropriate IAM role
 * or credentials with {@code secretsmanager:GetSecretValue} permission for the target secret ARN.</p>
 */
public final class AwsSecretsUtil {

    private AwsSecretsUtil() {
        // utility class; not instantiable
    }

    /**
     * Retrieves a secret value by attempting AWS Secrets Manager first, then an environment
     * variable fallback, and finally the provided default value.
     *
     * <p>AWS Secrets Manager lookup is performed reflectively so that the AWS SDK dependency
     * remains optional. If the SDK is not present on the classpath, or if the secret cannot
     * be retrieved (e.g., due to missing IAM permissions or network issues), the method
     * silently falls through to the next resolution step.</p>
     *
     * @param secretName    the name or ARN of the secret in AWS Secrets Manager
     * @param envVarFallback the name of the environment variable to check when Secrets Manager
     *                       is unavailable
     * @param defaultValue  the value to return when neither Secrets Manager nor the environment
     *                       variable provides a value
     * @return the resolved secret value; never {@code null} when {@code defaultValue} is non-null
     */
    public static String getSecret(String secretName, String envVarFallback, String defaultValue) {
        // 1. Attempt AWS Secrets Manager retrieval via reflection (optional dependency)
        String secretsManagerValue = tryGetFromSecretsManager(secretName);
        if (secretsManagerValue != null && !secretsManagerValue.isEmpty()) {
            return secretsManagerValue;
        }

        // 2. Fall back to environment variable
        if (envVarFallback != null && !envVarFallback.isEmpty()) {
            String envValue = System.getenv(envVarFallback);
            if (envValue != null && !envValue.isEmpty()) {
                return envValue;
            }
        }

        // 3. Return the provided default value
        return defaultValue;
    }

    /**
     * Attempts to retrieve a secret from AWS Secrets Manager using reflection.
     * Returns {@code null} if the AWS SDK is not available or the retrieval fails.
     *
     * @param secretName the name or ARN of the secret
     * @return the secret string value, or {@code null} if unavailable
     */
    private static String tryGetFromSecretsManager(String secretName) {
        try {
            // Use reflection to avoid a hard compile-time dependency on the AWS SDK.
            // The AWS SDK (software.amazon.awssdk:secretsmanager) must be on the classpath
            // for this path to succeed.
            Class<?> clientBuilderClass = Class.forName(
                "software.amazon.awssdk.services.secretsmanager.SecretsManagerClient");
            Object client = clientBuilderClass.getMethod("create").invoke(null);

            Class<?> requestClass = Class.forName(
                "software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest");
            Object request = requestClass.getMethod("builder").invoke(null);
            Class<?> builderClass = request.getClass();
            request = builderClass.getMethod("secretId", String.class).invoke(request, secretName);
            request = builderClass.getMethod("build").invoke(request);

            Object response = clientBuilderClass
                .getMethod("getSecretValue", Class.forName(
                    "software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest"))
                .invoke(client, request);

            String secretString = (String) response.getClass()
                .getMethod("secretString")
                .invoke(response);

            // Close the client to release resources
            try {
                clientBuilderClass.getMethod("close").invoke(client);
            } catch (Exception ignored) {
                // best-effort close
            }

            return secretString;
        } catch (ClassNotFoundException e) {
            // AWS SDK not on classpath; fall through to environment variable
            return null;
        } catch (Exception e) {
            // Secret not found, permission denied, or other AWS error; fall through
            return null;
        }
    }
}
