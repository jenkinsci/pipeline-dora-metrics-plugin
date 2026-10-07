package io.jenkins.plugins.dorametrics.ui;

import io.jenkins.plugins.dorametrics.store.MetricsStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static org.junit.jupiter.api.Assertions.assertEquals;

@WithJenkins
class CsvExportTest {

    private JenkinsRule j;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        j = rule;
    }

    @Test
    void rowsEndWithAPlainNewline() throws Exception {
        MetricsStore.setInstance(null);
        long ts = System.currentTimeMillis() - 60_000L;
        MetricsStore.getInstance().insertBuild("app", 7, ts, 1500, "SUCCESS", "SCM", "main");

        String csv = j.createWebClient().goTo("dora-api/export?format=csv", "text/csv").getWebResponse().getContentAsString();

        assertEquals("job_name,build_number,timestamp,duration_ms,result,trigger_type,branch\n"
                + "app,7," + ts + ",1500,SUCCESS,SCM,main\n", csv);
    }
}
