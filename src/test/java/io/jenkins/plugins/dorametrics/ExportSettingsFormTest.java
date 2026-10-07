package io.jenkins.plugins.dorametrics;

import io.jenkins.plugins.dorametrics.export.HttpExportConfig;
import io.jenkins.plugins.dorametrics.export.S3ExportConfig;
import org.htmlunit.html.HtmlCheckBoxInput;
import org.htmlunit.html.HtmlForm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The export settings sit inside an optional block on Manage Jenkins > System, and saving
 * that page for any reason used to switch the export off and reset its interval.
 */
@WithJenkins
class ExportSettingsFormTest {

    private JenkinsRule j;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        j = rule;
    }

    private static HttpExportConfig http(String url) {
        HttpExportConfig http = new HttpExportConfig();
        http.setUrl(url);
        return http;
    }

    @Test
    void anUntouchedSaveKeepsTheExportOn() throws Exception {
        DoraGlobalConfiguration config = DoraGlobalConfiguration.get();
        config.setExportEnabled(true);
        config.setExportIntervalHours(6);
        config.setExportStorage(http("https://metrics.example.invalid/hook"));
        config.save();

        j.configRoundtrip();

        config = DoraGlobalConfiguration.get();
        assertTrue(config.isExportEnabled(), "export must still be on");
        assertEquals(6, config.getExportIntervalHours());
        assertInstanceOf(HttpExportConfig.class, config.getExportStorage());
        assertEquals("https://metrics.example.invalid/hook",
                ((HttpExportConfig) config.getExportStorage()).getUrl());
    }

    @Test
    void anUntouchedSaveKeepsAnS3Export() throws Exception {
        S3ExportConfig s3 = new S3ExportConfig();
        s3.setEndpoint("https://s3.eu-west-1.amazonaws.com");
        s3.setBucket("dora-snapshots");
        DoraGlobalConfiguration config = DoraGlobalConfiguration.get();
        config.setExportEnabled(true);
        config.setExportIntervalHours(3);
        config.setExportStorage(s3);
        config.save();

        j.configRoundtrip();

        config = DoraGlobalConfiguration.get();
        assertTrue(config.isExportEnabled());
        assertEquals(3, config.getExportIntervalHours());
        assertInstanceOf(S3ExportConfig.class, config.getExportStorage());
        S3ExportConfig saved = (S3ExportConfig) config.getExportStorage();
        assertEquals("https://s3.eu-west-1.amazonaws.com", saved.getEndpoint());
        assertEquals("dora-snapshots", saved.getBucket());
    }

    @Test
    void anUntouchedSaveKeepsTheExportOff() throws Exception {
        DoraGlobalConfiguration config = DoraGlobalConfiguration.get();
        config.setExportEnabled(false);
        config.save();

        j.configRoundtrip();

        assertFalse(DoraGlobalConfiguration.get().isExportEnabled());
    }

    @Test
    void theCheckboxTurnsTheExportOnAndOff() throws Exception {
        DoraGlobalConfiguration config = DoraGlobalConfiguration.get();
        config.setExportEnabled(false);
        config.setExportIntervalHours(12);
        config.setExportStorage(http("https://metrics.example.invalid/hook"));
        config.save();

        setExportCheckbox(true);
        config = DoraGlobalConfiguration.get();
        assertTrue(config.isExportEnabled(), "ticking the box must turn the export on");
        assertEquals(12, config.getExportIntervalHours());

        setExportCheckbox(false);
        assertFalse(DoraGlobalConfiguration.get().isExportEnabled(), "unticking it must turn the export off");
    }

    private void setExportCheckbox(boolean checked) throws Exception {
        HtmlForm form = j.createWebClient().goTo("configure").getFormByName("config");
        HtmlCheckBoxInput box = form.getInputByName("_.exportEnabled");
        box.setChecked(checked);
        j.submit(form);
    }
}
