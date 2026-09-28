package io.jenkins.plugins.dorametrics.ui;

import hudson.Extension;
import hudson.model.RootAction;
import io.jenkins.plugins.dorametrics.JobVisibility;
import io.jenkins.plugins.dorametrics.DoraGlobalConfiguration;
import io.jenkins.plugins.dorametrics.collectors.BuildHistoryImporter;
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
import org.kohsuke.stapler.verb.POST;

import io.jenkins.plugins.dorametrics.util.Period;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    /**
     * Starts a build history import. Administer only, and POST only, because it writes.
     * Returns immediately: the import runs on Jenkins' own executor, and the caller polls
     * {@link #doImportStatus()} for the counters.
     */
    @POST
    public HttpResponse doImportHistory() {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);

        DoraGlobalConfiguration config = DoraGlobalConfiguration.get();
        int days = BuildHistoryImporter.resolveDays(config);

        // startAsync takes the slot before it returns, so the caller's first status poll
        // cannot see "not running" and mistake the previous run's counters for this one.
        if (!BuildHistoryImporter.startAsync(days)) {
            return new org.kohsuke.stapler.json.JsonHttpResponse(statusJson("An import is already running"), 200);
        }

        JSONObject json = new JSONObject();
        json.put("started", true);
        json.put("days", days);
        return new org.kohsuke.stapler.json.JsonHttpResponse(json, 200);
    }

    /**
     * Counters for the running or most recent import. Administer only, and deliberately
     * counters only: it exposes no job names or build data.
     */
    @GET
    public HttpResponse doImportStatus() {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        return new org.kohsuke.stapler.json.JsonHttpResponse(statusJson(null), 200);
    }

    private static JSONObject statusJson(String message) {
        JSONObject json = new JSONObject();
        json.put("running", BuildHistoryImporter.isRunning());
        BuildHistoryImporter.Result last = BuildHistoryImporter.getLastResult();
        if (last != null) {
            json.put("jobs", last.jobs);
            json.put("recorded", last.recorded);
            json.put("skipped", last.skipped);
            json.put("failed", last.failed);
            json.put("durationMs", last.durationMs);
            // Without these a run that stopped early, or threw, reads as an ordinary finish.
            json.put("completed", last.completed);
            if (last.error != null) {
                json.put("error", last.error);
            }
        }
        if (message != null) {
            json.put("message", message);
        }
        return json;
    }

    @GET
    public HttpResponse doOverview(@QueryParameter(value = "days") String daysParam,
                                  @QueryParameter(value = "from") String fromParam,
                                  @QueryParameter(value = "to") String toParam,
                                  @QueryParameter(value = "tz") String tzParam) {
        Jenkins.get().checkPermission(Jenkins.READ);
        Period period = Period.of(daysParam, fromParam, toParam, tzParam, 30, System.currentTimeMillis());
        int days = period.days;
        long toMs = period.toMs;
        long fromMs = period.fromMs;
        String pattern = getPattern();

        DoraCalculator calc = new DoraCalculator();
        JSONObject json = new JSONObject();
        json.put("period_days", days);
        json.put("deployment_frequency", metricToJson(calc.deploymentFrequency(fromMs, toMs, pattern)));
        json.put("lead_time", metricToJson(calc.leadTimeForChanges(fromMs, toMs, pattern)));
        json.put("mttr", metricToJson(calc.meanTimeToRestore(fromMs, toMs, pattern)));
        json.put("change_failure_rate", metricToJson(calc.changeFailureRate(fromMs, toMs, pattern)));
        DoraGlobalConfiguration config = DoraGlobalConfiguration.get();
        json.put("retention_days", config != null ? config.getRetentionDays() : 365);

        return new org.kohsuke.stapler.json.JsonHttpResponse(json, 200);
    }

    @GET
    public HttpResponse doPipelines(@QueryParameter(value = "days") String daysParam,
                                     @QueryParameter(value = "limit") String limitParam,
                                  @QueryParameter(value = "from") String fromParam,
                                  @QueryParameter(value = "to") String toParam,
                                  @QueryParameter(value = "tz") String tzParam) {
        Jenkins.get().checkPermission(Jenkins.READ);
        Period period = Period.of(daysParam, fromParam, toParam, tzParam, 30, System.currentTimeMillis());
        int limit = DurationFormatter.parseLimit(limitParam, 10);
        long toMs = period.toMs;
        long fromMs = period.fromMs;

        PipelineRanker ranker = new PipelineRanker();
        Jenkins jenkins = Jenkins.get();
        JSONObject json = new JSONObject();
        // Ranked in full and cut after filtering, so filtered rows do not take slots
        json.put("slowest", rankingsToJson(topVisible(ranker.slowestPipelines(fromMs, toMs, Integer.MAX_VALUE), jenkins, limit)));
        json.put("most_failing", rankingsToJson(topVisible(ranker.mostFailingPipelines(fromMs, toMs, Integer.MAX_VALUE), jenkins, limit)));
        json.put("flakiest", rankingsToJson(topVisible(ranker.flakiestPipelines(fromMs, toMs, Integer.MAX_VALUE), jenkins, limit)));
        // against the period of the same length just before this one
        long previousFrom = fromMs - (toMs - fromMs);
        json.put("most_improved", rankingsToJson(topVisible(
                ranker.mostImproved(fromMs, toMs, previousFrom, fromMs, Integer.MAX_VALUE), jenkins, limit)));

        return new org.kohsuke.stapler.json.JsonHttpResponse(json, 200);
    }

    /** Stage rankings for the period, as the dashboard lists them. */
    @GET
    public HttpResponse doStages(@QueryParameter(value = "days") String daysParam,
                                 @QueryParameter(value = "limit") String limitParam,
                                 @QueryParameter(value = "from") String fromParam,
                                 @QueryParameter(value = "to") String toParam,
                                 @QueryParameter(value = "tz") String tzParam) {
        Jenkins.get().checkPermission(Jenkins.READ);
        Period period = Period.of(daysParam, fromParam, toParam, tzParam, 30, System.currentTimeMillis());
        int limit = DurationFormatter.parseLimit(limitParam, 10);
        PipelineRanker ranker = new PipelineRanker();
        JSONObject json = new JSONObject();
        json.put("period_days", period.days);
        json.put("slowest", stagesToJson(ranker.slowestStages(period.fromMs, period.toMs, limit)));
        json.put("most_failing", stagesToJson(ranker.mostFailingStages(period.fromMs, period.toMs, limit)));
        return new org.kohsuke.stapler.json.JsonHttpResponse(json, 200);
    }

    static JSONArray stagesToJson(List<PipelineRanker.RankedStage> stages) {
        JSONArray arr = new JSONArray();
        for (PipelineRanker.RankedStage s : stages) {
            JSONObject j = new JSONObject();
            j.put("stage", s.stageName);
            j.put("value", s.displayValue);
            j.put("runs", s.occurrences);
            arr.add(j);
        }
        return arr;
    }

    /**
     * One point for every day of the period, in the viewer's time zone when {@code tz} is
     * given. Besides the build counts and durations, each day carries the four DORA metrics
     * for that day, filtered as the dashboard cards are, so a chart can show what they show.
     * A metric with nothing to measure that day is null.
     */
    @GET
    public HttpResponse doTrends(@QueryParameter(value = "days") String daysParam,
                                  @QueryParameter(value = "job") String jobName,
                                  @QueryParameter(value = "from") String fromParam,
                                  @QueryParameter(value = "to") String toParam,
                                  @QueryParameter(value = "tz") String tzParam) {
        Jenkins.get().checkPermission(Jenkins.READ);
        Period period = Period.of(daysParam, fromParam, toParam, tzParam, 90, System.currentTimeMillis());

        MetricsStore store = MetricsStore.getInstance();
        boolean singleJob = jobName != null && !jobName.isEmpty();
        if (singleJob && !JobVisibility.canRead(jobName)) {
            // Same answer for a job that does not exist and one the user may not read
            return org.kohsuke.stapler.HttpResponses.notFound();
        }
        List<BuildRecord> builds = singleJob
                ? store.getBuilds(jobName, period.fromMs, period.toMs)
                : store.getAllBuilds(period.fromMs, period.toMs,
                        JobVisibility.excludedForCurrentUser(DoraGlobalConfiguration.get(), store));
        Map<LocalDate, List<BuildRecord>> byDate = builds.stream()
                .collect(Collectors.groupingBy(b -> Instant.ofEpochMilli(b.timestamp).atZone(period.zone).toLocalDate()));

        DoraCalculator calc = singleJob
                ? new DoraCalculator(store, DoraGlobalConfiguration.get(), java.util.Collections.emptySet())
                : new DoraCalculator();
        String pattern = singleJob ? "^" + java.util.regex.Pattern.quote(jobName) + "$" : getPattern();
        Map<LocalDate, DoraCalculator.Day> dora = calc.dailyMetrics(period.fromMs, period.toMs, pattern, period.zone);

        JSONArray trendData = new JSONArray();
        dora.forEach((date, day) -> {
            List<BuildRecord> dateBuilds = byDate.getOrDefault(date, java.util.Collections.emptyList());
            JSONObject point = new JSONObject();
            point.put("date", date.toString());
            point.put("total_builds", dateBuilds.size());
            point.put("successful", dateBuilds.stream().filter(BuildRecord::isSuccess).count());
            point.put("failed", dateBuilds.stream().filter(BuildRecord::isFailure).count());
            point.put("avg_duration_ms", dateBuilds.stream().mapToLong(b -> b.durationMs).average().orElse(0));
            point.put("deployments", day.deployments);
            point.put("change_failure_rate", orNull(day.changeFailureRate()));
            point.put("lead_time_ms", orNull(day.leadTimeMs()));
            point.put("restore_time_ms", orNull(day.restoreTimeMs()));
            trendData.add(point);
        });

        JSONObject json = new JSONObject();
        json.put("period_days", period.days);
        json.put("job", jobName != null ? jobName : "all");
        json.put("trends", trendData);

        return new org.kohsuke.stapler.json.JsonHttpResponse(json, 200);
    }

    @GET
    public HttpResponse doExport(@QueryParameter(value = "days") String daysParam,
                                  @QueryParameter(value = "format") String format,
                                  @QueryParameter(value = "from") String fromParam,
                                  @QueryParameter(value = "to") String toParam,
                                  @QueryParameter(value = "tz") String tzParam) {
        Jenkins.get().checkPermission(Jenkins.READ);
        Period period = Period.of(daysParam, fromParam, toParam, tzParam, 90, System.currentTimeMillis());
        int days = period.days;
        long toMs = period.toMs;
        long fromMs = period.fromMs;

        MetricsStore store = MetricsStore.getInstance();
        List<BuildRecord> builds = store.getAllBuilds(fromMs, toMs,
                JobVisibility.excludedForCurrentUser(DoraGlobalConfiguration.get(), store));

        if ("csv".equalsIgnoreCase(format)) {
            StringBuilder csv = new StringBuilder();
            csv.append("job_name,build_number,timestamp,duration_ms,result,trigger_type,branch\n");
            for (BuildRecord b : builds) {
                // a plain \n on every platform, so the export does not depend on the controller's OS
                csv.append(String.format(Locale.ROOT, "%s,%d,%d,%d,%s,%s,%s",
                        escapeCsv(b.jobName), b.buildNumber, b.timestamp, b.durationMs,
                        escapeCsv(b.result), escapeCsv(b.triggerType),
                        escapeCsv(b.branch != null ? b.branch : ""))).append('\n');
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

    /** A JSON null for a missing value; putting a Java null would drop the key instead. */
    private static Object orNull(Object value) {
        return value == null ? net.sf.json.JSONNull.getInstance() : value;
    }

    static String escapeCsv(String value) {
        if (value == null) return "";
        // Prevent CSV injection
        if (value.length() > 0 && "=+-@\t\r".indexOf(value.charAt(0)) >= 0) {
            value = "'" + value;
        }
        // A semicolon is a separator too for spreadsheets in many locales
        if (value.contains(",") || value.contains(";") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private static List<RankedPipeline> topVisible(List<RankedPipeline> pipelines, Jenkins jenkins, int limit) {
        return filterVisible(pipelines, jenkins).stream().limit(limit).collect(Collectors.toList());
    }

    private static List<RankedPipeline> filterVisible(List<RankedPipeline> pipelines, Jenkins jenkins) {
        return pipelines.stream()
                .filter(p -> isVisibleItem(jenkins, p.jobName))
                .collect(java.util.stream.Collectors.toList());
    }

    /** A ranking row links to the job, so the job has to exist and be readable. */
    static boolean isVisibleItem(Jenkins jenkins, String jobName) {
        try {
            return JobVisibility.isRecordedJob(jenkins.getItemByFullName(jobName), jobName);
        } catch (org.springframework.security.access.AccessDeniedException e) {
            return false;
        }
    }

    /**
     * Every tracked job. The job settings, folders included, are already applied through
     * the excluded set every calculator is built with, so filtering again by the production
     * pattern alone would drop the jobs that only a production folder brings in.
     */
    private String getPattern() {
        return ".*";
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
