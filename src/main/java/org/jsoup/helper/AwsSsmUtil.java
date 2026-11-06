package org.jsoup.helper;

/**
 * Utility class for retrieving configuration parameters from AWS Systems Manager (SSM) Parameter Store.
 *
 * <p>This class provides a cloud-native approach to configuration management, replacing
 * hard-coded environment-specific URLs and configuration values in source code. Parameters
 * are retrieved from AWS SSM Parameter Store at runtime, enabling:</p>
 * <ul>
 *   <li>Environment-agnostic deployments (dev, staging, production)</li>
 *   <li>Centralized, versioned configuration management</li>
 *   <li>Compliance with 12-factor app principles (externalized configuration)</li>
 *   <li>Prevention of environment-specific URLs being hard-coded in version control</li>
 * </ul>
 *
 * <p>Resolution order for a parameter value:</p>
 * <ol>
 *   <li>AWS SSM Parameter Store (when {@code software.amazon.awssdk:ssm} is on the classpath
 *       and the parameter name is accessible in the current AWS region)</li>
 *   <li>Environment variable (identified by {@code envVarFallback})</li>
 *   <li>Provided default value</li>
 * </ol>
 *
 * <p>To configure AWS SSM Parameter Store, ensure the application has the appropriate IAM role
 * or credentials with {@code ssm:GetParameter} permission for the target parameter ARN.</p>
 */
public final class AwsSsmUtil {

    private AwsSsmUtil() {
        // utility class; not instantiable
    }

    /**
     * Retrieves a parameter value by attempting AWS SSM Parameter Store first, then an environment
     * variable fallback, and finally the provided default value.
     *
     * <p>AWS SSM Parameter Store lookup is performed reflectively so that the AWS SDK dependency
     * remains optional. If the SDK is not present on the classpath, or if the parameter cannot
     * be retrieved (e.g., due to missing IAM permissions or network issues), the method
     * silently falls through to the next resolution step.</p>
     *
     * @param parameterName  the name or ARN of the parameter in AWS SSM Parameter Store
     * @param envVarFallback the name of the environment variable to check when SSM Parameter Store
     *                       is unavailable
     * @param defaultValue   the value to return when neither SSM Parameter Store nor the environment
     *                       variable provides a value
     * @return the resolved parameter value; never {@code null} when {@code defaultValue} is non-null
     */
    public static String getParameter(String parameterName, String envVarFallback, String defaultValue) {
        // 1. Attempt AWS SSM Parameter Store retrieval via reflection (optional dependency)
        String ssmValue = tryGetFromSsmParameterStore(parameterName);
        if (ssmValue != null && !ssmValue.isEmpty()) {
            return ssmValue;
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
     * Attempts to retrieve a parameter from AWS SSM Parameter Store using reflection.
     * Returns {@code null} if the AWS SDK is not available or the retrieval fails.
     *
     * @param parameterName the name or ARN of the parameter
     * @return the parameter string value, or {@code null} if unavailable
     */
    private static String tryGetFromSsmParameterStore(String parameterName) {
        try {
            // Use reflection to avoid a hard compile-time dependency on the AWS SDK.
            // The AWS SDK (software.amazon.awssdk:ssm) must be on the classpath
            // for this path to succeed.
            Class<?> clientClass = Class.forName(
                "software.amazon.awssdk.services.ssm.SsmClient");
            Object client = clientClass.getMethod("create").invoke(null);

            Class<?> requestClass = Class.forName(
                "software.amazon.awssdk.services.ssm.model.GetParameterRequest");
            Object requestBuilder = requestClass.getMethod("builder").invoke(null);
            Class<?> builderClass = requestBuilder.getClass();
            requestBuilder = builderClass.getMethod("name", String.class).invoke(requestBuilder, parameterName);
            requestBuilder = builderClass.getMethod("withDecryption", Boolean.class).invoke(requestBuilder, Boolean.TRUE);
            Object request = builderClass.getMethod("build").invoke(requestBuilder);

            Object response = clientClass
                .getMethod("getParameter", Class.forName(
                    "software.amazon.awssdk.services.ssm.model.GetParameterRequest"))
                .invoke(client, request);

            Object parameter = response.getClass()
                .getMethod("parameter")
                .invoke(response);

            String value = (String) parameter.getClass()
                .getMethod("value")
                .invoke(parameter);

            // Close the client to release resources
            try {
                clientClass.getMethod("close").invoke(client);
            } catch (Exception ignored) {
                // best-effort close
            }

            return value;
        } catch (ClassNotFoundException e) {
            // AWS SDK not on classpath; fall through to environment variable
            return null;
        } catch (Exception e) {
            // Parameter not found, permission denied, or other AWS error; fall through
            return null;
        }
    }
}
