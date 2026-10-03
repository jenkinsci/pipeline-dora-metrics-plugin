package io.jenkins.plugins.dorametrics.ui;

import hudson.model.RootAction;
import io.jenkins.plugins.dorametrics.store.MetricsStore;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import static org.junit.Assert.*;

public class DoraApiActionTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Test
    public void extensionRegistered() {
        DoraApiAction action = Jenkins.get().getExtensionList(RootAction.class)
                .get(DoraApiAction.class);
        assertNotNull("DoraApiAction should be registered", action);
        assertEquals("dora-api", action.getUrlName());
        assertNull("API action should have no icon", action.getIconFileName());
    }

    @Test
    public void escapeCsvNull() {
        assertEquals("", DoraApiAction.escapeCsv(null));
    }

    @Test
    public void escapeCsvSimple() {
        assertEquals("simple", DoraApiAction.escapeCsv("simple"));
    }

    @Test
    public void escapeCsvComma() {
        assertEquals("\"has,comma\"", DoraApiAction.escapeCsv("has,comma"));
    }

    @Test
    public void escapeCsvQuotes() {
        assertEquals("\"has\"\"quote\"", DoraApiAction.escapeCsv("has\"quote"));
    }

    @Test
    public void escapeCsvFormulaInjection() {
        assertEquals("'=formula", DoraApiAction.escapeCsv("=formula"));
        assertEquals("'+dangerous", DoraApiAction.escapeCsv("+dangerous"));
        assertEquals("'-negative", DoraApiAction.escapeCsv("-negative"));
        assertEquals("'@mention", DoraApiAction.escapeCsv("@mention"));
    }

    @Test
    public void escapeCsvNewlines() {
        assertEquals("\"has\nnewline\"", DoraApiAction.escapeCsv("has\nnewline"));
    }

    @Test
    public void metricToJsonStructure() {
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
    public void rankingsToJsonEmpty() {
        var arr = DoraApiAction.rankingsToJson(java.util.Collections.emptyList());
        assertEquals(0, arr.size());
    }

    @Test
    public void rankingsToJsonWithData() {
        var list = java.util.List.of(
                new io.jenkins.plugins.dorametrics.rankings.PipelineRanker.RankedPipeline("job-a", 10.0, "10s", 5));
        var arr = DoraApiAction.rankingsToJson(list);
        assertEquals(1, arr.size());
        assertEquals("job-a", arr.getJSONObject(0).getString("job"));
    }
}
