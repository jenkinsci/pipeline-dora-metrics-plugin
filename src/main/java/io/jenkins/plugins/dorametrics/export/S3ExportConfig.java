package io.jenkins.plugins.dorametrics.export;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import hudson.Extension;
import hudson.model.Descriptor;
import hudson.security.ACL;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.verb.POST;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.TimeZone;
import java.util.logging.Logger;

/**
 * S3-compatible export configuration and upload.
 * Works with AWS S3, Backblaze B2, MinIO, DigitalOcean Spaces.
 * Uses AWS Signature V4 for authentication, no AWS SDK dependency.
 */
public class S3ExportConfig extends ExportStorageConfig {

    private static final Logger LOGGER = Logger.getLogger(S3ExportConfig.class.getName());
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private String endpoint;
    private String bucket;
    private String credentialsId;

    @DataBoundConstructor
    public S3ExportConfig() {}

    @Override
    public String getStorageType() { return "S3"; }

    public String getEndpoint() { return endpoint; }

    @DataBoundSetter
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }

    public String getBucket() { return bucket; }

    @DataBoundSetter
    public void setBucket(String bucket) { this.bucket = bucket; }

    @Override
    public String getCredentialsId() { return credentialsId; }

    @DataBoundSetter
    public void setCredentialsId(String credentialsId) { this.credentialsId = credentialsId; }

    @Override
    public void upload(String data, String fileName) throws IOException, InterruptedException {
        String accessKey = "";
        String secretKey = "";

        if (credentialsId != null && !credentialsId.isEmpty()) {
            StandardUsernamePasswordCredentials creds = CredentialsProvider.lookupCredentialsInItemGroup(
                            StandardUsernamePasswordCredentials.class, Jenkins.get(), ACL.SYSTEM2,
                            Collections.emptyList())
                    .stream()
                    .filter(c -> credentialsId.equals(c.getId()))
                    .findFirst().orElse(null);
            if (creds != null) {
                accessKey = creds.getUsername();
                secretKey = creds.getPassword().getPlainText();
            } else {
                throw new IOException("S3 export credentials not found: " + credentialsId);
            }
        }

        String region = extractRegion(endpoint);
        uploadS3(endpoint, bucket, fileName, data, accessKey, secretKey, region);
    }

    private void uploadS3(String endpoint, String bucket, String key,
                           String data, String accessKey, String secretKey,
                           String region) throws IOException, InterruptedException {
        byte[] payload = data.getBytes(StandardCharsets.UTF_8);
        String payloadHash = sha256Hex(payload);

        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'");
        dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
        String amzDate = dateFormat.format(new Date());
        String dateStamp = amzDate.substring(0, 8);

        String host = endpoint.replaceAll("https?://", "");
        String url = endpoint + "/" + bucket + "/" + key;

        String canonicalUri = "/" + bucket + "/" + key;
        String canonicalQueryString = "";
        String canonicalHeaders = "host:" + host + "\n" + "x-amz-content-sha256:" + payloadHash + "\n" + "x-amz-date:" + amzDate + "\n";
        String signedHeaders = "host;x-amz-content-sha256;x-amz-date";
        String canonicalRequest = "PUT\n" + canonicalUri + "\n" + canonicalQueryString + "\n" + canonicalHeaders + "\n" + signedHeaders + "\n" + payloadHash;

        String algorithm = "AWS4-HMAC-SHA256";
        String credentialScope = dateStamp + "/" + region + "/s3/aws4_request";
        String stringToSign = algorithm + "\n" + amzDate + "\n" + credentialScope + "\n" + sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));

        byte[] signingKey = getSignatureKey(secretKey, dateStamp, region, "s3");
        String signature = hmacSha256Hex(signingKey, stringToSign);

        String authorization = algorithm + " Credential=" + accessKey + "/" + credentialScope
                + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(payload))
                .header("x-amz-date", amzDate)
                .header("x-amz-content-sha256", payloadHash)
                .header("Authorization", authorization)
                .header("Content-Type", "application/json")
                .build();

        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            LOGGER.fine("S3 upload success: " + key);
        } else {
            throw new IOException("S3 upload failed: HTTP " + response.statusCode() + " - " + response.body());
        }
    }

    static String extractRegion(String endpoint) {
        if (endpoint == null || endpoint.isEmpty()) return "us-east-1";
        String host = endpoint.replaceAll("https?://", "");
        String[] parts = host.split("\\.");
        if (parts.length >= 3 && parts[0].equals("s3")) {
            return parts[1];
        }
        return "us-east-1";
    }

    // AWS SigV4 helpers
    private static byte[] hmacSha256(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException("HMAC-SHA256 failed", e);
        }
    }

    private static String hmacSha256Hex(byte[] key, String data) {
        return bytesToHex(hmacSha256(key, data));
    }

    private static byte[] getSignatureKey(String secret, String dateStamp, String region, String service) {
        byte[] kDate = hmacSha256(("AWS4" + secret).getBytes(StandardCharsets.UTF_8), dateStamp);
        byte[] kRegion = hmacSha256(kDate, region);
        byte[] kService = hmacSha256(kRegion, service);
        return hmacSha256(kService, "aws4_request");
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return bytesToHex(digest.digest(data));
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 failed", e);
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<ExportStorageConfig> {
        @Override
        public String getDisplayName() {
            return "S3-Compatible (AWS S3, Backblaze B2, MinIO)";
        }

        @POST
        public ListBoxModel doFillCredentialsIdItems(@QueryParameter String credentialsId) {
            if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
                return new StandardListBoxModel().includeCurrentValue(credentialsId);
            }
            return new StandardListBoxModel()
                    .includeEmptyValue()
                    .includeMatchingAs(
                            ACL.SYSTEM2,
                            Jenkins.get(),
                            StandardUsernamePasswordCredentials.class,
                            Collections.emptyList(),
                            CredentialsMatchers.always())
                    .includeCurrentValue(credentialsId);
        }
    }
}
