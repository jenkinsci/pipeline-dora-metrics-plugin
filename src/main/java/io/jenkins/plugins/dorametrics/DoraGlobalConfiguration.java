package io.jenkins.plugins.dorametrics;

import hudson.Extension;
import hudson.util.FormValidation;
import jenkins.model.GlobalConfiguration;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.verb.POST;

import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Global configuration for the Pipeline DORA Metrics plugin.
 */
@Extension
public class DoraGlobalConfiguration extends GlobalConfiguration {

    private static final Logger LOGGER = Logger.getLogger(DoraGlobalConfiguration.class.getName());

    private String productionJobPattern = ".*";
    private String excludedJobPattern = "";
    private String productionBranchPattern = "main|master";
    private String productionFolders = "";
    private String productionJobLabel = "dora-production";
    private boolean trackAllBranches = true;
    private boolean ignoreDisabledPipelines = false;
    private int retentionDays = 365;
    private int historyImportDays = 30;
    /** Set once the first import has run, so a restart does not scan again. */
    private boolean historyImportDone = false;
    private int dashboardTopN = 10;

    // External storage export
    private boolean exportEnabled = false;
    private int exportIntervalHours = 24;
    private io.jenkins.plugins.dorametrics.export.ExportStorageConfig exportStorage;

    // DORA band thresholds
    private double dfEliteThreshold = 1.0;
    private double dfHighThreshold = 0.142;
    private double dfMediumThreshold = 0.033;
    private long ltEliteSeconds = 3600;      // 1 hour
    private long ltHighSeconds = 86400;      // 1 day
    private long ltMediumSeconds = 604800;   // 1 week
    private long mttrEliteSeconds = 3600;
    private long mttrHighSeconds = 86400;
    private long mttrMediumSeconds = 604800;
    private double cfrElitePercent = 5.0;
    private double cfrHighPercent = 10.0;
    private double cfrMediumPercent = 15.0;

    // Pre-compiled patterns for performance
    private transient Pattern compiledProductionPattern;
    private transient Pattern compiledExcludedPattern;

    public DoraGlobalConfiguration() {
        load();
        compilePatterns();
    }

    public static DoraGlobalConfiguration get() {
        return GlobalConfiguration.all().get(DoraGlobalConfiguration.class);
    }

    @SuppressWarnings("unused") // called from Jelly
    public java.util.List<hudson.model.Descriptor<io.jenkins.plugins.dorametrics.export.ExportStorageConfig>> getExportStorageDescriptors() {
        return jenkins.model.Jenkins.get().getDescriptorList(io.jenkins.plugins.dorametrics.export.ExportStorageConfig.class);
    }

    @Override
    public boolean configure(StaplerRequest2 req, JSONObject json) throws FormException {
        this.productionJobPattern = validateRegex(json.optString("productionJobPattern", ".*"), ".*");
        this.excludedJobPattern = validateRegex(json.optString("excludedJobPattern", ""), "");
        this.productionBranchPattern = validateRegex(json.optString("productionBranchPattern", "main|master"), "main|master");
        this.productionFolders = json.optString("productionFolders", "");
        this.productionJobLabel = json.optString("productionJobLabel", "dora-production");
        this.trackAllBranches = json.optBoolean("trackAllBranches", true);
        this.ignoreDisabledPipelines = json.optBoolean("ignoreDisabledPipelines", false);
        this.retentionDays = Math.max(1, json.optInt("retentionDays", 365));
        this.historyImportDays = Math.max(1, json.optInt("historyImportDays", 30));
        this.dashboardTopN = Math.max(1, json.optInt("dashboardTopN", 10));

        this.exportEnabled = json.optBoolean("exportEnabled", false);
        this.exportIntervalHours = Math.max(1, json.optInt("exportIntervalHours", 24));
        if (json.has("exportStorage")) {
            this.exportStorage = req.bindJSON(io.jenkins.plugins.dorametrics.export.ExportStorageConfig.class, json.getJSONObject("exportStorage"));
        }

        this.dfEliteThreshold = Math.max(0, json.optDouble("dfEliteThreshold", 1.0));
        this.dfHighThreshold = Math.max(0, json.optDouble("dfHighThreshold", 0.142));
        this.dfMediumThreshold = Math.max(0, json.optDouble("dfMediumThreshold", 0.033));
        this.ltEliteSeconds = Math.max(0, json.optLong("ltEliteSeconds", 3600));
        this.ltHighSeconds = Math.max(0, json.optLong("ltHighSeconds", 86400));
        this.ltMediumSeconds = Math.max(0, json.optLong("ltMediumSeconds", 604800));
        this.mttrEliteSeconds = Math.max(0, json.optLong("mttrEliteSeconds", 3600));
        this.mttrHighSeconds = Math.max(0, json.optLong("mttrHighSeconds", 86400));
        this.mttrMediumSeconds = Math.max(0, json.optLong("mttrMediumSeconds", 604800));
        this.cfrElitePercent = Math.max(0, Math.min(100, json.optDouble("cfrElitePercent", 5.0)));
        this.cfrHighPercent = Math.max(0, Math.min(100, json.optDouble("cfrHighPercent", 10.0)));
        this.cfrMediumPercent = Math.max(0, Math.min(100, json.optDouble("cfrMediumPercent", 15.0)));

        compilePatterns();
        save();
        return true;
    }

    private String validateRegex(String pattern, String fallback) {
        if (pattern == null || pattern.isEmpty()) return fallback;
        try {
            Pattern.compile(pattern);
            return pattern;
        } catch (PatternSyntaxException e) {
            LOGGER.log(Level.WARNING, "Invalid regex pattern: " + pattern, e);
            return fallback;
        }
    }

    private void compilePatterns() {
        try {
            compiledProductionPattern = (productionJobPattern != null && !productionJobPattern.isEmpty())
                    ? Pattern.compile(productionJobPattern) : null;
        } catch (PatternSyntaxException e) {
            compiledProductionPattern = null;
        }
        try {
            compiledExcludedPattern = (excludedJobPattern != null && !excludedJobPattern.isEmpty())
                    ? Pattern.compile(excludedJobPattern) : null;
        } catch (PatternSyntaxException e) {
            compiledExcludedPattern = null;
        }
    }

    // --- Form validation (simple input checks, no data access) ---

    @POST
    @SuppressWarnings("lgtm[jenkins/no-permission-check]")
    public FormValidation doCheckRetentionDays(@QueryParameter String value) {
        return validatePositiveInt(value, "Retention days");
    }

    @POST
    @SuppressWarnings("lgtm[jenkins/no-permission-check]")
    public FormValidation doCheckDashboardTopN(@QueryParameter String value) {
        return validatePositiveInt(value, "Dashboard Top N");
    }

    @POST
    @SuppressWarnings("lgtm[jenkins/no-permission-check]")
    public FormValidation doCheckExportIntervalHours(@QueryParameter String value) {
        return validatePositiveInt(value, "Export interval");
    }

    @POST
    @SuppressWarnings("lgtm[jenkins/no-permission-check]")
    public FormValidation doCheckCfrElitePercent(@QueryParameter String value) {
        return validatePercent(value);
    }

    @POST
    @SuppressWarnings("lgtm[jenkins/no-permission-check]")
    public FormValidation doCheckCfrHighPercent(@QueryParameter String value) {
        return validatePercent(value);
    }

    @POST
    @SuppressWarnings("lgtm[jenkins/no-permission-check]")
    public FormValidation doCheckCfrMediumPercent(@QueryParameter String value) {
        return validatePercent(value);
    }

    private static FormValidation validatePositiveInt(String value, String fieldName) {
        if (value == null || value.isEmpty()) return FormValidation.ok();
        try {
            int v = Integer.parseInt(value);
            if (v < 1) return FormValidation.error(fieldName + " must be at least 1");
            return FormValidation.ok();
        } catch (NumberFormatException e) {
            return FormValidation.error(fieldName + " must be a whole number");
        }
    }

    private static FormValidation validatePercent(String value) {
        if (value == null || value.isEmpty()) return FormValidation.ok();
        try {
            double v = Double.parseDouble(value);
            if (v < 0 || v > 100) return FormValidation.error("Must be between 0 and 100");
            return FormValidation.ok();
        } catch (NumberFormatException e) {
            return FormValidation.error("Must be a number");
        }
    }

    /**
     * Check if a job should be tracked based on all filter criteria.
     * Uses pre-compiled patterns for performance.
     */
    public boolean shouldTrackJob(String fullJobName) {
        if (fullJobName == null) return false;

        if (compiledExcludedPattern == null && excludedJobPattern != null && !excludedJobPattern.isEmpty()) {
            compilePatterns();
        }

        if (compiledExcludedPattern != null && compiledExcludedPattern.matcher(fullJobName).matches()) {
            return false;
        }

        if (productionFolders != null && !productionFolders.isEmpty()) {
            String[] folders = productionFolders.split(",");
            for (String folder : folders) {
                if (fullJobName.startsWith(folder.trim() + "/")) {
                    return true;
                }
            }
        }

        if (compiledProductionPattern == null && productionJobPattern != null && !productionJobPattern.isEmpty()) {
            compilePatterns();
        }

        if (compiledProductionPattern != null) {
            return compiledProductionPattern.matcher(fullJobName).matches();
        }

        return true;
    }

    // --- Getters/Setters ---

    public String getProductionJobPattern() { return productionJobPattern; }
    public void setProductionJobPattern(String v) { this.productionJobPattern = v; compilePatterns(); }

    public String getExcludedJobPattern() { return excludedJobPattern; }
    public void setExcludedJobPattern(String v) { this.excludedJobPattern = v; compilePatterns(); }

    public String getProductionBranchPattern() { return productionBranchPattern; }
    public void setProductionBranchPattern(String v) { this.productionBranchPattern = v; }

    public String getProductionFolders() { return productionFolders; }
    public void setProductionFolders(String v) { this.productionFolders = v; }

    public String getProductionJobLabel() { return productionJobLabel; }
    public void setProductionJobLabel(String v) { this.productionJobLabel = v; }

    public boolean isTrackAllBranches() { return trackAllBranches; }
    public void setTrackAllBranches(boolean v) { this.trackAllBranches = v; }

    public boolean isIgnoreDisabledPipelines() { return ignoreDisabledPipelines; }
    public void setIgnoreDisabledPipelines(boolean v) { this.ignoreDisabledPipelines = v; }

    public int getRetentionDays() { return retentionDays; }
    public void setRetentionDays(int v) { this.retentionDays = v; }

    public int getHistoryImportDays() { return historyImportDays; }
    public void setHistoryImportDays(int v) { this.historyImportDays = Math.max(1, v); }

    public boolean isHistoryImportDone() { return historyImportDone; }
    public void setHistoryImportDone(boolean v) { this.historyImportDone = v; save(); }

    public int getDashboardTopN() { return dashboardTopN; }
    public void setDashboardTopN(int v) { this.dashboardTopN = v; }

    public boolean isExportEnabled() { return exportEnabled; }
    public void setExportEnabled(boolean v) { this.exportEnabled = v; }

    public int getExportIntervalHours() { return exportIntervalHours; }
    public void setExportIntervalHours(int v) { this.exportIntervalHours = v; }

    public io.jenkins.plugins.dorametrics.export.ExportStorageConfig getExportStorage() { return exportStorage; }
    public void setExportStorage(io.jenkins.plugins.dorametrics.export.ExportStorageConfig v) { this.exportStorage = v; }

    public double getDfEliteThreshold() { return dfEliteThreshold; }
    public void setDfEliteThreshold(double v) { this.dfEliteThreshold = v; }
    public double getDfHighThreshold() { return dfHighThreshold; }
    public void setDfHighThreshold(double v) { this.dfHighThreshold = v; }
    public double getDfMediumThreshold() { return dfMediumThreshold; }
    public void setDfMediumThreshold(double v) { this.dfMediumThreshold = v; }
    public long getLtEliteSeconds() { return ltEliteSeconds; }
    public void setLtEliteSeconds(long v) { this.ltEliteSeconds = v; }
    public long getLtHighSeconds() { return ltHighSeconds; }
    public void setLtHighSeconds(long v) { this.ltHighSeconds = v; }
    public long getLtMediumSeconds() { return ltMediumSeconds; }
    public void setLtMediumSeconds(long v) { this.ltMediumSeconds = v; }
    public long getMttrEliteSeconds() { return mttrEliteSeconds; }
    public void setMttrEliteSeconds(long v) { this.mttrEliteSeconds = v; }
    public long getMttrHighSeconds() { return mttrHighSeconds; }
    public void setMttrHighSeconds(long v) { this.mttrHighSeconds = v; }
    public long getMttrMediumSeconds() { return mttrMediumSeconds; }
    public void setMttrMediumSeconds(long v) { this.mttrMediumSeconds = v; }
    public double getCfrElitePercent() { return cfrElitePercent; }
    public void setCfrElitePercent(double v) { this.cfrElitePercent = v; }
    public double getCfrHighPercent() { return cfrHighPercent; }
    public void setCfrHighPercent(double v) { this.cfrHighPercent = v; }
    public double getCfrMediumPercent() { return cfrMediumPercent; }
    public void setCfrMediumPercent(double v) { this.cfrMediumPercent = v; }
}
