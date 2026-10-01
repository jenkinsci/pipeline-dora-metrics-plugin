package io.jenkins.plugins.dorametrics.export;

import hudson.model.Descriptor;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static org.junit.jupiter.api.Assertions.*;

@WithJenkins
class ExportConfigTest {

    private JenkinsRule j;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        j = rule;
    }

    @Test
    void s3DescriptorRegistered() {
        Descriptor<?> d = Jenkins.get().getDescriptor(S3ExportConfig.class);
        assertNotNull(d, "S3ExportConfig descriptor should be registered");
        assertEquals("S3-Compatible (AWS S3, Backblaze B2, MinIO)", d.getDisplayName());
    }

    @Test
    void httpDescriptorRegistered() {
        Descriptor<?> d = Jenkins.get().getDescriptor(HttpExportConfig.class);
        assertNotNull(d, "HttpExportConfig descriptor should be registered");
        assertEquals("HTTP Endpoint", d.getDisplayName());
    }

    @Test
    void s3FillCredentialsReturnsNonNull() {
        S3ExportConfig.DescriptorImpl d = (S3ExportConfig.DescriptorImpl)
                Jenkins.get().getDescriptor(S3ExportConfig.class);
        assertNotNull(d);

        ListBoxModel items = d.doFillCredentialsIdItems("");
        assertNotNull(items, "Credentials list should not be null");
        // Should have at least the empty value option
        assertFalse(items.isEmpty(), "Should include empty value option");
        assertEquals("", items.get(0).value, "First option should be empty value");
    }

    @Test
    void httpFillCredentialsReturnsNonNull() {
        HttpExportConfig.DescriptorImpl d = (HttpExportConfig.DescriptorImpl)
                Jenkins.get().getDescriptor(HttpExportConfig.class);
        assertNotNull(d);

        ListBoxModel items = d.doFillCredentialsIdItems("");
        assertNotNull(items, "Credentials list should not be null");
        assertFalse(items.isEmpty(), "Should include empty value option");
        assertEquals("", items.get(0).value, "First option should be empty value");
    }

    @Test
    void s3ConfigFieldsWork() {
        S3ExportConfig config = new S3ExportConfig();
        config.setEndpoint("https://s3.us-east-005.backblazeb2.com");
        config.setBucket("my-bucket");
        config.setCredentialsId("my-creds");

        assertEquals("https://s3.us-east-005.backblazeb2.com", config.getEndpoint());
        assertEquals("my-bucket", config.getBucket());
        assertEquals("my-creds", config.getCredentialsId());
        assertEquals("S3", config.getStorageType());
    }

    @Test
    void httpConfigFieldsWork() {
        HttpExportConfig config = new HttpExportConfig();
        config.setUrl("https://example.com/webhook");
        config.setCredentialsId("http-creds");

        assertEquals("https://example.com/webhook", config.getUrl());
        assertEquals("http-creds", config.getCredentialsId());
        assertEquals("HTTP", config.getStorageType());
    }

    @Test
    void s3ExtractsRegionFromEndpoint() {
        assertEquals("us-east-005", S3ExportConfig.extractRegion("https://s3.us-east-005.backblazeb2.com"));
        assertEquals("us-west-2", S3ExportConfig.extractRegion("https://s3.us-west-2.amazonaws.com"));
        assertEquals("us-east-1", S3ExportConfig.extractRegion("https://minio.local:9000"));
        assertEquals("us-east-1", S3ExportConfig.extractRegion(null));
        assertEquals("us-east-1", S3ExportConfig.extractRegion(""));
    }

    @Test
    void configClassesImplementUpload() {
        // Verify the abstract method is implemented (would fail to compile if not)
        S3ExportConfig s3 = new S3ExportConfig();
        HttpExportConfig http = new HttpExportConfig();
        assertNotNull(s3, "S3 config should be an ExportStorageConfig");
        assertNotNull(http, "HTTP config should be an ExportStorageConfig");
    }
}
