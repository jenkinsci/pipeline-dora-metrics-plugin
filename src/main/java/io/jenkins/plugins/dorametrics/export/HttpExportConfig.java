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
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.verb.POST;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.logging.Logger;

/**
 * HTTP endpoint export configuration and upload.
 * Sends JSON snapshots to any HTTP endpoint via POST.
 * Supports Bearer token (StringCredentials) and Basic auth (UsernamePassword).
 */
public class HttpExportConfig extends ExportStorageConfig {

    private static final Logger LOGGER = Logger.getLogger(HttpExportConfig.class.getName());
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private String url;
    private String credentialsId;

    @DataBoundConstructor
    public HttpExportConfig() {}

    @Override
    public String getStorageType() { return "HTTP"; }

    public String getUrl() { return url; }

    @DataBoundSetter
    public void setUrl(String url) { this.url = url; }

    @Override
    public String getCredentialsId() { return credentialsId; }

    @DataBoundSetter
    public void setCredentialsId(String credentialsId) { this.credentialsId = credentialsId; }

    @Override
    public void upload(String data, String fileName) throws IOException, InterruptedException {
        String authHeader = resolveAuth();

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .POST(HttpRequest.BodyPublishers.ofString(data))
                .header("Content-Type", "application/json");

        if (authHeader != null && !authHeader.isEmpty()) {
            builder.header("Authorization", authHeader);
        }

        HttpResponse<String> response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            LOGGER.fine("HTTP export success");
        } else {
            throw new IOException("HTTP export failed: HTTP " + response.statusCode());
        }
    }

    private String resolveAuth() throws IOException {
        if (credentialsId == null || credentialsId.isEmpty()) return null;

        // Try StringCredentials first (secret text -> Bearer token)
        StringCredentials secretCreds = CredentialsProvider.lookupCredentialsInItemGroup(
                        StringCredentials.class, Jenkins.get(), ACL.SYSTEM2,
                        Collections.emptyList())
                .stream()
                .filter(c -> credentialsId.equals(c.getId()))
                .findFirst().orElse(null);
        if (secretCreds != null) {
            return "Bearer " + secretCreds.getSecret().getPlainText();
        }

        // Try UsernamePassword (-> Basic auth)
        StandardUsernamePasswordCredentials basicCreds = CredentialsProvider.lookupCredentialsInItemGroup(
                        StandardUsernamePasswordCredentials.class, Jenkins.get(), ACL.SYSTEM2,
                        Collections.emptyList())
                .stream()
                .filter(c -> credentialsId.equals(c.getId()))
                .findFirst().orElse(null);
        if (basicCreds != null) {
            String raw = basicCreds.getUsername() + ":" + basicCreds.getPassword().getPlainText();
            return "Basic " + java.util.Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        }

        throw new IOException("HTTP export credentials not found: " + credentialsId);
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<ExportStorageConfig> {
        @Override
        public String getDisplayName() {
            return "HTTP Endpoint";
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
                    .includeMatchingAs(
                            ACL.SYSTEM2,
                            Jenkins.get(),
                            StringCredentials.class,
                            Collections.emptyList(),
                            CredentialsMatchers.always())
                    .includeCurrentValue(credentialsId);
        }
    }
}
