package io.jenkins.plugins.dorametrics;

import hudson.model.Job;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.dorametrics.store.MetricsStore;
import jenkins.model.Jenkins;
import jenkins.model.ParameterizedJobMixIn.ParameterizedJob;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Resolves which recorded pipelines are currently disabled in Jenkins, so that
 * metrics, rankings and exports can leave them out when the plugin is configured
 * to ignore disabled pipelines. Records are never removed: the exclusion is
 * applied when data is read, so it also covers builds recorded while a pipeline
 * was still active, and re-enabling the pipeline or unchecking the option brings
 * its numbers back.
 */
public final class DisabledPipelines {

    private DisabledPipelines() {
    }

    /**
     * Full names of recorded jobs that Jenkins currently reports as not buildable,
     * or an empty set when the option is off. That covers a job disabled through
     * its own Disable action and a multibranch branch or pull request job kept
     * after its branch went away, which Jenkins marks dead and stops building.
     * The check is limited to {@link ParameterizedJob}s (Pipeline and freestyle
     * jobs), the only job types with a disabled state, so a job type that can
     * never be built, such as an externally monitored job, stays included. So
     * does a job that no longer exists in Jenkins.
     */
    public static Set<String> names(DoraGlobalConfiguration config, MetricsStore store) {
        if (config == null || !config.isIgnoreDisabledPipelines() || store == null) {
            return Collections.emptySet();
        }
        Jenkins jenkins = Jenkins.getInstanceOrNull();
        if (jenkins == null) {
            return Collections.emptySet();
        }
        Set<String> disabled = new HashSet<>();
        // The result must not depend on who is looking at the dashboard, so the
        // lookup runs as SYSTEM. Only the job name and its disabled state are read.
        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
            for (String name : store.getAllJobNames()) {
                Job<?, ?> job = jenkins.getItemByFullName(name, Job.class);
                if (job instanceof ParameterizedJob && !job.isBuildable()) {
                    disabled.add(name);
                }
            }
        }
        return disabled;
    }
}
