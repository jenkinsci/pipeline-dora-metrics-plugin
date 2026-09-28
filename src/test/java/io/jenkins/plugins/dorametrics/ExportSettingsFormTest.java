package io.jenkins.plugins.dorametrics;

import io.jenkins.plugins.dorametrics.export.HttpExportConfig;
import io.jenkins.plugins.dorametrics.export.S3ExportConfig;
import org.htmlunit.html.HtmlCheckBoxInput;
import org.htmlunit.html.HtmlForm;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The export settings sit inside an optional block on Manage Jenkins > System, and saving
 * that page for any reason used to switch the export off and reset its interval.
 */
public class ExportSettingsFormTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    private static HttpExportConfig http(String url) {
        HttpExportConfig http = new HttpExportConfig();
        http.setUrl(url);
        return http;
    }

    @Test
    public void anUntouchedSaveKeepsTheExportOn() throws Exception {
        DoraGlobalConfiguration config = DoraGlobalConfiguration.get();
        config.setExportEnabled(true);
        config.setExportIntervalHours(6);
        config.setExportStorage(http("https://metrics.example.invalid/hook"));
        config.save();

        j.configRoundtrip();

        config = DoraGlobalConfiguration.get();
        assertTrue("export must still be on", config.isExportEnabled());
        assertEquals(6, config.getExportIntervalHours());
        assertTrue(config.getExportStorage() instanceof HttpExportConfig);
        assertEquals("https://metrics.example.invalid/hook",
                ((HttpExportConfig) config.getExportStorage()).getUrl());
    }

    @Test
    public void anUntouchedSaveKeepsAnS3Export() throws Exception {
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
        assertTrue(config.getExportStorage() instanceof S3ExportConfig);
        S3ExportConfig saved = (S3ExportConfig) config.getExportStorage();
        assertEquals("https://s3.eu-west-1.amazonaws.com", saved.getEndpoint());
        assertEquals("dora-snapshots", saved.getBucket());
    }

    @Test
    public void anUntouchedSaveKeepsTheExportOff() throws Exception {
        DoraGlobalConfiguration config = DoraGlobalConfiguration.get();
        config.setExportEnabled(false);
        config.save();

        j.configRoundtrip();

        assertFalse(DoraGlobalConfiguration.get().isExportEnabled());
    }

    @Test
    public void theCheckboxTurnsTheExportOnAndOff() throws Exception {
        DoraGlobalConfiguration config = DoraGlobalConfiguration.get();
        config.setExportEnabled(false);
        config.setExportIntervalHours(12);
        config.setExportStorage(http("https://metrics.example.invalid/hook"));
        config.save();

        setExportCheckbox(true);
        config = DoraGlobalConfiguration.get();
        assertTrue("ticking the box must turn the export on", config.isExportEnabled());
        assertEquals(12, config.getExportIntervalHours());

        setExportCheckbox(false);
        assertFalse("unticking it must turn the export off", DoraGlobalConfiguration.get().isExportEnabled());
    }

    private void setExportCheckbox(boolean checked) throws Exception {
        HtmlForm form = j.createWebClient().goTo("configure").getFormByName("config");
        HtmlCheckBoxInput box = form.getInputByName("_.exportEnabled");
        box.setChecked(checked);
        j.submit(form);
    }
}
