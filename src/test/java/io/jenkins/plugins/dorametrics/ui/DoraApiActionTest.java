package io.jenkins.plugins.dorametrics.ui;

import hudson.model.RootAction;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static org.junit.jupiter.api.Assertions.*;

@WithJenkins
class DoraApiActionTest {

    private JenkinsRule j;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        j = rule;
    }

    @Test
    void extensionRegistered() {
        DoraApiAction action = Jenkins.get().getExtensionList(RootAction.class)
                .get(DoraApiAction.class);
        assertNotNull(action, "DoraApiAction should be registered");
        assertEquals("dora-api", action.getUrlName());
        assertNull(action.getIconFileName(), "API action should have no icon");
    }

    @Test
    void escapeCsvNull() {
        assertEquals("", DoraApiAction.escapeCsv(null));
    }

    @Test
    void escapeCsvSimple() {
        assertEquals("simple", DoraApiAction.escapeCsv("simple"));
    }

    @Test
    void escapeCsvComma() {
        assertEquals("\"has,comma\"", DoraApiAction.escapeCsv("has,comma"));
    }

    @Test
    void escapeCsvQuotes() {
        assertEquals("\"has\"\"quote\"", DoraApiAction.escapeCsv("has\"quote"));
    }

    @Test
    void escapeCsvFormulaInjection() {
        assertEquals("'=formula", DoraApiAction.escapeCsv("=formula"));
        assertEquals("'+dangerous", DoraApiAction.escapeCsv("+dangerous"));
        assertEquals("'-negative", DoraApiAction.escapeCsv("-negative"));
        assertEquals("'@mention", DoraApiAction.escapeCsv("@mention"));
    }

    @Test
    void escapeCsvNewlines() {
        assertEquals("\"has\nnewline\"", DoraApiAction.escapeCsv("has\nnewline"));
    }

    @Test
    void metricToJsonStructure() {
        var metric = new io.jenkins.plugins.dorametrics.dora.DoraCalculator.DoraMetric(
                "Test", "42", io.jenkins.plugins.dorametrics.dora.DoraCalculator.DoraBand.ELITE, 42.0);
        JSONObject json = DoraApiAction.metricToJson(metric);
        assertEquals("Test", json.getString("name"));
        assertEquals("42", json.getString("value"));
        assertEquals("Elite", json.getString("band"));
        assertEquals("#116329", json.getString("color"));
        assertEquals(42.0, json.getDouble("raw_value"), 0.01);
    }

    @Test
    void rankingsToJsonEmpty() {
        var arr = DoraApiAction.rankingsToJson(java.util.Collections.emptyList());
        assertEquals(0, arr.size());
    }

    @Test
    void rankingsToJsonWithData() {
        var list = java.util.List.of(
                new io.jenkins.plugins.dorametrics.rankings.PipelineRanker.RankedPipeline("job-a", 10.0, "10s", 5));
        var arr = DoraApiAction.rankingsToJson(list);
        assertEquals(1, arr.size());
        assertEquals("job-a", arr.getJSONObject(0).getString("job"));
    }
}
