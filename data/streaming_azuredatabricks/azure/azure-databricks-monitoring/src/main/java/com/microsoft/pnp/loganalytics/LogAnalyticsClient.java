package com.microsoft.pnp.loganalytics;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.Closeable;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.security.GeneralSecurityException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import org.apache.commons.codec.binary.Base64;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClients;

public class LogAnalyticsClient implements Closeable {
    private static final String HashAlgorithm = "HmacSHA256";
    private static final String HttpVerb = "POST";
    private static final String ContentType = "application/json";
    private static final String Resource = "/api/logs";
    private static final String AuthorizationFormat = "SharedKey %s:%s";

    private static final String DEFAULT_URL_SUFFIX = "ods.opinsights.azure.com";
    private static final String DEFAULT_API_VERSION = "2016-04-01";
    private static final String URL_FORMAT = "https://%s.%s/api/logs?api-version=%s";

    @Override
    public void close() throws IOException {
        if (this.httpClient instanceof Closeable) {
            ((Closeable)this.httpClient).close();
        }
    }

    private static final class LogAnalyticsHttpHeaders {
        public static final String LOG_TYPE = "Log-Type";
        public static final String X_MS_DATE = "x-ms-date";
        public static final String TIME_GENERATED_FIELD = "time-generated-field";
    }

    private String workspaceId;
    private String workspaceKey;
    private String url;
    private HttpClient httpClient;

    public LogAnalyticsClient(String workspaceId, String workspaceKey) {
        this(workspaceId, workspaceKey, HttpClients.custom()
                .disableAuthCaching()
                .disableContentCompression()
                .disableCookieManagement()
                .build());
    }

    public LogAnalyticsClient(String workspaceId, String workspaceKey,
                              HttpClient httpClient) {
        this(workspaceId, workspaceKey, httpClient, DEFAULT_URL_SUFFIX);
    }

    public LogAnalyticsClient(String workspaceId, String workspaceKey,
                              HttpClient httpClient, String urlSuffix) {
        this(workspaceId, workspaceKey, httpClient, urlSuffix, DEFAULT_API_VERSION);
    }

    public LogAnalyticsClient(String workspaceId, String workspaceKey,
                              HttpClient httpClient, String urlSuffix, String apiVersion) {
        if (isNullOrWhitespace(workspaceId)) {
            throw new IllegalArgumentException("workspaceId cannot be null, empty, or only whitespace");
        }

        if (isNullOrWhitespace(workspaceKey)) {
            throw new IllegalArgumentException("workspaceKey cannot be null, empty, or only whitespace");
        }

        if (httpClient == null) {
            throw new IllegalArgumentException("httpClient cannot be null");
        }

        if (isNullOrWhitespace(urlSuffix)) {
            throw new IllegalArgumentException("urlSuffix cannot be null, empty, or only whitespace");
        }

        if (isNullOrWhitespace(apiVersion)) {
            throw new IllegalArgumentException("apiVersion cannot be null, empty, or only whitespace");
        }

        this.workspaceId = workspaceId;
        this.workspaceKey = workspaceKey;
        this.httpClient = httpClient;
        this.url = String.format(URL_FORMAT, this.workspaceId, urlSuffix, apiVersion);
    }

    public void send(String body, String logType) throws IOException {
        this.send(body, logType, null);
    }

    public void send(String body, String logType, String timestampFieldName) throws IOException {
        try {
            if (isNullOrWhitespace(body)) {
                throw new IllegalArgumentException("body cannot be null, empty, or only whitespace");
            }

            if (isNullOrWhitespace(logType)) {
                throw new IllegalArgumentException("logType cannot be null, empty, or only whitespace");
            }

            final Date xmsDate = Calendar.getInstance().getTime();

            SimpleDateFormat dateFormat = new SimpleDateFormat(
                    "EEE, dd MMM yyyy HH:mm:ss z", Locale.US);
            dateFormat.setTimeZone(TimeZone.getTimeZone("GMT"));
            String xmsDateString = dateFormat.format(xmsDate);

            HttpPost httpPost = new HttpPost(this.url);
            String signature = String.format(AuthorizationFormat, this.workspaceId,
                    buildSignature(this.workspaceKey,
                            xmsDateString,
                            body.length(),
                            HttpVerb,
                            ContentType,
                            Resource));

            httpPost.setEntity(new StringEntity(body));
            httpPost.setHeader(HttpHeaders.CONTENT_TYPE, ContentType);
            httpPost.setHeader(LogAnalyticsHttpHeaders.LOG_TYPE, logType);
            httpPost.setHeader(LogAnalyticsHttpHeaders.X_MS_DATE, xmsDateString);
            httpPost.setHeader(HttpHeaders.AUTHORIZATION, signature);
            if (timestampFieldName != null) {
                httpPost.setHeader(LogAnalyticsHttpHeaders.TIME_GENERATED_FIELD, timestampFieldName);
            }

            HttpResponse httpResponse = null;
            try {
                httpResponse = this.httpClient.execute(httpPost);
                if (httpResponse.getStatusLine().getStatusCode() != 200) {
                    throw new IOException(
                            String.format(
                                    "Error sending Log Analytics events: %s (%d)",
                                    httpResponse.getStatusLine().getReasonPhrase(),
                                    httpResponse.getStatusLine().getStatusCode()));
                }
            } finally {
                if (httpResponse instanceof Closeable) {
                    ((Closeable)httpResponse).close();
                }
            }
        } catch (Exception ex) {
            throw new IOException("Error sending to Log Analytics", ex);
        }
    }

    private static String buildSignature(
            String primaryKey,
            String xmsDate,
            int contentLength,
            String method,
            String contentType,
            String resource
    ) throws UnsupportedEncodingException, GeneralSecurityException {
        String result = null;
        String xHeaders = String.format("%s:%s", LogAnalyticsHttpHeaders.X_MS_DATE, xmsDate);
        //xHeaders = "Fri, 20 Jul 2018 16:28:59 GMT";
        String stringToHash = String.format(
                "%s\n%d\n%s\n%s\n%s",
                method,
                contentLength,
                contentType,
                xHeaders,
                resource);
        byte[] decodedBytes = Base64.decodeBase64(primaryKey);
        Mac mac = Mac.getInstance(HashAlgorithm);
        mac.init(new SecretKeySpec(decodedBytes, HashAlgorithm));
        byte[] hash = mac.doFinal(stringToHash.getBytes("UTF-8"));
        result = Base64.encodeBase64String(hash);
        return result;
    }

    private static boolean isNullOrWhitespace(String str) {
        int strLen;
        if (str == null || (strLen = str.length()) == 0) {
            return true;
        }

        for (int i = 0; i < strLen; i++) {
            if (!Character.isWhitespace(str.charAt(i))) {
                return false;
            }
        }

        return true;
    }
}
