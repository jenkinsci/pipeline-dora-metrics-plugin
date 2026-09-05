package io.jenkins.plugins.dorametrics.ui;

import hudson.Extension;
import hudson.model.RootAction;
import hudson.model.Item;
import io.jenkins.plugins.dorametrics.DisabledPipelines;
import io.jenkins.plugins.dorametrics.DoraGlobalConfiguration;
import io.jenkins.plugins.dorametrics.dora.DoraCalculator;
import io.jenkins.plugins.dorametrics.dora.DoraCalculator.DoraMetric;
import io.jenkins.plugins.dorametrics.rankings.PipelineRanker;
import io.jenkins.plugins.dorametrics.rankings.PipelineRanker.RankedPipeline;
import io.jenkins.plugins.dorametrics.store.MetricsStore;
import io.jenkins.plugins.dorametrics.store.MetricsStore.BuildRecord;
import io.jenkins.plugins.dorametrics.util.DurationFormatter;
import jenkins.model.Jenkins;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.GET;

import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * REST API at /dora-api/ for DORA metrics data.
 */
@Extension
public class DoraApiAction implements RootAction {

    @Override
    public String getIconFileName() { return null; }

    @Override
    public String getDisplayName() { return null; }

    @Override
    public String getUrlName() { return "dora-api"; }

    @GET
    public HttpResponse doOverview(@QueryParameter(value = "days") String daysParam) {
        Jenkins.get().checkPermission(Jenkins.READ);
        int days = DurationFormatter.parseDays(daysParam, 30);
        long toMs = System.currentTimeMillis();
        long fromMs = toMs - ((long) days * 86400_000);
        String pattern = getPattern();

        DoraCalculator calc = new DoraCalculator();
        JSONObject json = new JSONObject();
        json.put("period_days", days);
        json.put("deployment_frequency", metricToJson(calc.deploymentFrequency(fromMs, toMs, pattern)));
        json.put("lead_time", metricToJson(calc.leadTimeForChanges(fromMs, toMs, pattern)));
        json.put("mttr", metricToJson(calc.meanTimeToRestore(fromMs, toMs, pattern)));
        json.put("change_failure_rate", metricToJson(calc.changeFailureRate(fromMs, toMs, pattern)));

        return new org.kohsuke.stapler.json.JsonHttpResponse(json, 200);
    }

    @GET
    public HttpResponse doPipelines(@QueryParameter(value = "days") String daysParam,
                                     @QueryParameter(value = "limit") String limitParam) {
        Jenkins.get().checkPermission(Jenkins.READ);
        int days = DurationFormatter.parseDays(daysParam, 30);
        int limit = DurationFormatter.parseLimit(limitParam, 10);
        long toMs = System.currentTimeMillis();
        long fromMs = toMs - ((long) days * 86400_000);

        PipelineRanker ranker = new PipelineRanker();
        Jenkins jenkins = Jenkins.get();
        JSONObject json = new JSONObject();
        json.put("slowest", rankingsToJson(filterVisible(ranker.slowestPipelines(fromMs, toMs, limit), jenkins)));
        json.put("most_failing", rankingsToJson(filterVisible(ranker.mostFailingPipelines(fromMs, toMs, limit), jenkins)));
        json.put("flakiest", rankingsToJson(filterVisible(ranker.flakiestPipelines(fromMs, toMs, limit), jenkins)));

        return new org.kohsuke.stapler.json.JsonHttpResponse(json, 200);
    }

    @GET
    public HttpResponse doTrends(@QueryParameter(value = "days") String daysParam,
                                  @QueryParameter(value = "job") String jobName) {
        Jenkins.get().checkPermission(Jenkins.READ);
        int days = DurationFormatter.parseDays(daysParam, 90);
        long toMs = System.currentTimeMillis();
        long fromMs = toMs - ((long) days * 86400_000);

        MetricsStore store = MetricsStore.getInstance();
        List<BuildRecord> builds = (jobName != null && !jobName.isEmpty())
                ? store.getBuilds(jobName, fromMs, toMs)
                : store.getAllBuilds(fromMs, toMs, DisabledPipelines.names(DoraGlobalConfiguration.get(), store));

        Map<String, List<BuildRecord>> byDate = builds.stream()
                .collect(Collectors.groupingBy(b -> {
                    Calendar cal = Calendar.getInstance();
                    cal.setTimeInMillis(b.timestamp);
                    return String.format("%d-%02d-%02d",
                            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH));
                }));

        JSONArray trendData = new JSONArray();
        new TreeMap<>(byDate).forEach((date, dateBuilds) -> {
            JSONObject point = new JSONObject();
            point.put("date", date);
            point.put("total_builds", dateBuilds.size());
            point.put("successful", dateBuilds.stream().filter(BuildRecord::isSuccess).count());
            point.put("failed", dateBuilds.stream().filter(BuildRecord::isFailure).count());
            point.put("avg_duration_ms", dateBuilds.stream().mapToLong(b -> b.durationMs).average().orElse(0));
            trendData.add(point);
        });

        JSONObject json = new JSONObject();
        json.put("period_days", days);
        json.put("job", jobName != null ? jobName : "all");
        json.put("trends", trendData);

        return new org.kohsuke.stapler.json.JsonHttpResponse(json, 200);
    }

    @GET
    public HttpResponse doExport(@QueryParameter(value = "days") String daysParam,
                                  @QueryParameter(value = "format") String format) {
        Jenkins.get().checkPermission(Jenkins.READ);
        int days = DurationFormatter.parseDays(daysParam, 90);
        long toMs = System.currentTimeMillis();
        long fromMs = toMs - ((long) days * 86400_000);

        MetricsStore store = MetricsStore.getInstance();
        List<BuildRecord> builds = store.getAllBuilds(fromMs, toMs,
                DisabledPipelines.names(DoraGlobalConfiguration.get(), store));

        if ("csv".equalsIgnoreCase(format)) {
            StringBuilder csv = new StringBuilder();
            csv.append("job_name,build_number,timestamp,duration_ms,result,trigger_type,branch\n");
            for (BuildRecord b : builds) {
                csv.append(String.format("%s,%d,%d,%d,%s,%s,%s\n",
                        escapeCsv(b.jobName), b.buildNumber, b.timestamp, b.durationMs,
                        escapeCsv(b.result), escapeCsv(b.triggerType),
                        escapeCsv(b.branch != null ? b.branch : "")));
            }
            final String csvStr = csv.toString();
            return new org.kohsuke.stapler.HttpResponse() {
                @Override
                public void generateResponse(org.kohsuke.stapler.StaplerRequest2 req,
                                              org.kohsuke.stapler.StaplerResponse2 rsp,
                                              Object node) throws java.io.IOException, jakarta.servlet.ServletException {
                    rsp.setContentType("text/csv;charset=UTF-8");
                    rsp.setHeader("Content-Disposition", "attachment; filename=dora-metrics-export.csv");
                    rsp.getWriter().write(csvStr);
                }
            };
        }

        JSONArray arr = new JSONArray();
        for (BuildRecord b : builds) {
            JSONObject j = new JSONObject();
            j.put("job", b.jobName);
            j.put("build", b.buildNumber);
            j.put("timestamp", b.timestamp);
            j.put("duration_ms", b.durationMs);
            j.put("result", b.result);
            j.put("trigger", b.triggerType);
            j.put("branch", b.branch);
            arr.add(j);
        }

        JSONObject json = new JSONObject();
        json.put("period_days", days);
        json.put("total_builds", builds.size());
        json.put("builds", arr);

        return new org.kohsuke.stapler.json.JsonHttpResponse(json, 200);
    }

    static String escapeCsv(String value) {
        if (value == null) return "";
        // Prevent CSV injection
        if (value.length() > 0 && "=+-@\t\r".indexOf(value.charAt(0)) >= 0) {
            value = "'" + value;
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private static List<RankedPipeline> filterVisible(List<RankedPipeline> pipelines, Jenkins jenkins) {
        return pipelines.stream()
                .filter(p -> {
                    Item item = jenkins.getItemByFullName(p.jobName);
                    return item != null && item.hasPermission(Item.READ);
                })
                .collect(java.util.stream.Collectors.toList());
    }

    private String getPattern() {
        DoraGlobalConfiguration config = DoraGlobalConfiguration.get();
        return config != null ? config.getProductionJobPattern() : ".*";
    }

    static JSONObject metricToJson(DoraMetric m) {
        JSONObject j = new JSONObject();
        j.put("name", m.name);
        j.put("value", m.displayValue);
        j.put("band", m.band.label);
        j.put("color", m.band.color);
        j.put("raw_value", m.rawValue);
        return j;
    }

    static JSONArray rankingsToJson(List<RankedPipeline> rankings) {
        JSONArray arr = new JSONArray();
        for (RankedPipeline r : rankings) {
            JSONObject j = new JSONObject();
            j.put("job", r.jobName);
            j.put("value", r.displayValue);
            j.put("build_count", r.buildCount);
            arr.add(j);
        }
        return arr;
    }
}
