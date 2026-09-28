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
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.TimeZone;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * S3-compatible export configuration and upload.
 * Works with AWS S3, Backblaze B2, MinIO, DigitalOcean Spaces.
 * Uses AWS Signature V4 for authentication, no AWS SDK dependency.
 */
public class S3ExportConfig extends ExportStorageConfig {

    private static final Logger LOGGER = Logger.getLogger(S3ExportConfig.class.getName());

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

        String base = endpointOrDefault(endpoint);
        uploadS3(base, bucket, fileName, data, accessKey, secretKey, extractRegion(base));
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

        // The signed host has to be exactly what goes out in the Host header: the endpoint's
        // host and, when it names one, its port. Any path on the endpoint prefixes the object.
        URI base = URI.create(endpoint);
        String host = base.getRawAuthority();
        String basePath = base.getRawPath() == null ? "" : base.getRawPath().replaceAll("/+$", "");
        String canonicalUri = basePath + "/" + bucket + "/" + key;
        String url = base.getScheme() + "://" + host + canonicalUri;

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

        HttpRequest request = ExportHttp.request(URI.create(url))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(payload))
                .header("x-amz-date", amzDate)
                .header("x-amz-content-sha256", payloadHash)
                .header("Authorization", authorization)
                .header("Content-Type", "application/json")
                .build();

        HttpResponse<String> response = ExportHttp.client().send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            LOGGER.fine("S3 upload success: " + key);
        } else {
            throw new IOException("S3 upload failed: HTTP " + response.statusCode() + " - " + response.body());
        }
    }

    /** AWS S3's global endpoint, used when none is configured. */
    static final String AWS_DEFAULT_ENDPOINT = "https://s3.amazonaws.com";

    private static final Pattern AWS_REGION = Pattern.compile(
            "(?:.*\\.)?s3[.-](?:dualstack\\.)?([a-z0-9-]+)\\.amazonaws\\.com(?:\\.cn)?");
    private static final Pattern SPACES_REGION = Pattern.compile("(?:.*\\.)?([a-z0-9-]+)\\.digitaloceanspaces\\.com");

    /** The configured endpoint without trailing slashes, or AWS's when none is configured. */
    static String endpointOrDefault(String endpoint) {
        if (endpoint == null || endpoint.trim().isEmpty()) return AWS_DEFAULT_ENDPOINT;
        return endpoint.trim().replaceAll("/+$", "");
    }

    /**
     * The signing region for an endpoint. AWS names it in the host in several ways
     * ({@code s3.eu-west-1}, {@code s3-eu-west-1}, {@code s3.dualstack.eu-west-1}, with or
     * without a bucket in front), and its global endpoint is us-east-1. DigitalOcean Spaces
     * puts it first, other S3-compatible services usually after {@code s3.}. Anything else,
     * MinIO for instance, accepts us-east-1.
     */
    static String extractRegion(String endpoint) {
        if (endpoint == null || endpoint.trim().isEmpty()) return "us-east-1";
        String host = hostOf(endpoint);
        if (host.endsWith(".amazonaws.com") || host.endsWith(".amazonaws.com.cn")) {
            Matcher aws = AWS_REGION.matcher(host);
            if (aws.matches() && !"external-1".equals(aws.group(1))) {
                return aws.group(1);
            }
            return "us-east-1";
        }
        Matcher spaces = SPACES_REGION.matcher(host);
        if (spaces.matches()) {
            return spaces.group(1);
        }
        String[] parts = host.split("\\.");
        if (parts.length >= 3 && parts[0].equals("s3")) {
            return parts[1];
        }
        return "us-east-1";
    }

    private static String hostOf(String endpoint) {
        String withScheme = endpoint.trim().contains("://") ? endpoint.trim() : "https://" + endpoint.trim();
        String host = URI.create(withScheme).getHost();
        return host == null ? "" : host.toLowerCase(java.util.Locale.ROOT);
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
