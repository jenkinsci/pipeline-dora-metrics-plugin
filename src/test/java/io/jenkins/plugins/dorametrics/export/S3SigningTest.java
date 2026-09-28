package io.jenkins.plugins.dorametrics.export;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * Uploads to a local server that checks the AWS Signature V4 the way S3 does, from what
 * actually arrived: method, path, Host header and the signed headers.
 */
public class S3SigningTest {

    private static final String ACCESS_KEY = "AKIDEXAMPLE";
    private static final String SECRET_KEY = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY";

    @Rule
    public JenkinsRule j = new JenkinsRule();

    private HttpServer server;
    private final AtomicReference<String> verdict = new AtomicReference<>();
    private final AtomicReference<String> path = new AtomicReference<>();

    @Before
    public void setUp() throws Exception {
        SystemCredentialsProvider.getInstance().getCredentials().add(new UsernamePasswordCredentialsImpl(
                CredentialsScope.GLOBAL, "s3-creds", "", ACCESS_KEY, SECRET_KEY));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    @After
    public void tearDown() {
        server.stop(0);
    }

    private void handle(HttpExchange ex) throws java.io.IOException {
        byte[] body = ex.getRequestBody().readAllBytes();
        path.set(ex.getRequestURI().getRawPath());
        String result;
        try {
            result = check(ex, body);
        } catch (Exception e) {
            result = "error: " + e;
        }
        verdict.set(result);
        int status = "ok".equals(result) ? 200 : 403;
        ex.sendResponseHeaders(status, -1);
        ex.close();
    }

    private String check(HttpExchange ex, byte[] body) throws Exception {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        Matcher m = Pattern.compile("AWS4-HMAC-SHA256 Credential=([^/]+)/(\\d{8})/([^/]+)/s3/aws4_request, "
                + "SignedHeaders=([^,]+), Signature=([0-9a-f]{64})").matcher(auth);
        if (!m.matches()) return "malformed authorization: " + auth;
        String date = m.group(2), region = m.group(3), signed = m.group(4), signature = m.group(5);
        String amzDate = ex.getRequestHeaders().getFirst("x-amz-date");
        String payloadHash = ex.getRequestHeaders().getFirst("x-amz-content-sha256");
        if (!hex(MessageDigest.getInstance("SHA-256").digest(body)).equals(payloadHash)) return "payload hash";
        StringBuilder headers = new StringBuilder();
        for (String name : signed.split(";")) {
            headers.append(name).append(':').append(ex.getRequestHeaders().getFirst(name).trim()).append('\n');
        }
        String canonical = ex.getRequestMethod() + "\n" + ex.getRequestURI().getRawPath() + "\n\n"
                + headers + "\n" + signed + "\n" + payloadHash;
        String scope = date + "/" + region + "/s3/aws4_request";
        String toSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n"
                + hex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        byte[] key = hmac(("AWS4" + SECRET_KEY).getBytes(StandardCharsets.UTF_8), date);
        key = hmac(key, region);
        key = hmac(key, "s3");
        key = hmac(key, "aws4_request");
        String expected = hex(hmac(key, toSign));
        return expected.equals(signature) ? "ok" : "signature does not match the request";
    }

    private static byte[] hmac(byte[] key, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private S3ExportConfig s3(String endpoint) {
        S3ExportConfig s3 = new S3ExportConfig();
        s3.setEndpoint(endpoint);
        s3.setBucket("dora-snapshots");
        s3.setCredentialsId("s3-creds");
        return s3;
    }

    private String local() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Test
    public void theServerAcceptsTheSignature() throws Exception {
        s3(local()).upload("{\"builds\":[]}", "dora-metrics/2026-09-28/snapshot.json");
        assertEquals("ok", verdict.get());
        assertEquals("/dora-snapshots/dora-metrics/2026-09-28/snapshot.json", path.get());
    }

    @Test
    public void aTrailingSlashOnTheEndpointChangesNothing() throws Exception {
        s3(local() + "/").upload("{}", "dora-metrics/snapshot.json");
        assertNotNull(verdict.get());
        assertEquals("ok", verdict.get());
        assertEquals("/dora-snapshots/dora-metrics/snapshot.json", path.get());
    }

    @Test
    public void regionsAreReadFromTheCommonEndpointForms() {
        assertEquals("us-east-1", S3ExportConfig.extractRegion("https://s3.amazonaws.com"));
        assertEquals("eu-west-1", S3ExportConfig.extractRegion("https://s3.eu-west-1.amazonaws.com"));
        assertEquals("eu-west-1", S3ExportConfig.extractRegion("https://s3-eu-west-1.amazonaws.com"));
        assertEquals("eu-west-1", S3ExportConfig.extractRegion("https://s3.dualstack.eu-west-1.amazonaws.com"));
        assertEquals("ap-south-1", S3ExportConfig.extractRegion("https://my-bucket.s3.ap-south-1.amazonaws.com/"));
        assertEquals("us-east-005", S3ExportConfig.extractRegion("https://s3.us-east-005.backblazeb2.com/"));
        assertEquals("nyc3", S3ExportConfig.extractRegion("https://nyc3.digitaloceanspaces.com"));
        assertEquals("us-east-1", S3ExportConfig.extractRegion("http://minio.local:9000"));
        assertEquals("us-east-1", S3ExportConfig.extractRegion(""));
    }

    @Test
    public void anEmptyEndpointMeansAws() {
        assertEquals("https://s3.amazonaws.com", S3ExportConfig.endpointOrDefault(""));
        assertEquals("https://s3.amazonaws.com", S3ExportConfig.endpointOrDefault(null));
        assertEquals("https://s3.eu-west-1.amazonaws.com", S3ExportConfig.endpointOrDefault("https://s3.eu-west-1.amazonaws.com//"));
    }
}
