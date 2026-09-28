package io.jenkins.plugins.dorametrics;

import io.jenkins.plugins.dorametrics.store.MetricsStore;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.Stapler;
import org.kohsuke.stapler.StaplerRequest2;
import org.springframework.security.access.AccessDeniedException;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Decides which recorded pipelines the current user may see. Build data is
 * stored by job name, outside the Jenkins item tree, so every read has to apply
 * the job's own Item/Read permission: Overall/Read alone says nothing about
 * what a user may see inside a folder or a restricted job.
 */
public final class JobVisibility {

    private static final String REQUEST_CACHE = JobVisibility.class.getName() + ".hidden";
    private static final String EXCLUDED_CACHE = JobVisibility.class.getName() + ".excluded";

    private JobVisibility() {
    }

    /**
     * Whether the current user may see data recorded for this job. Administrators
     * see everything, including jobs that no longer exist. Everyone else needs
     * Item/Read on a job that still exists, so a missing job and a job the user
     * may not read look the same. {@code getItemByFullName} already returns null
     * for a job the user may not read.
     */
    public static boolean canRead(String jobFullName) {
        if (jobFullName == null) {
            return false;
        }
        Jenkins jenkins = Jenkins.getInstanceOrNull();
        if (jenkins == null) {
            return true;
        }
        if (jenkins.hasPermission(Jenkins.ADMINISTER)) {
            return true;
        }
        try {
            return jenkins.getItemByFullName(jobFullName) != null;
        } catch (AccessDeniedException e) {
            // Item/Discover without Item/Read
            return false;
        }
    }

    /**
     * Full names of recorded jobs the current user may not see. Resolved once per
     * request, because one dashboard page asks for it many times.
     */
    public static Set<String> hiddenFromCurrentUser(MetricsStore store) {
        if (store == null) {
            return Collections.emptySet();
        }
        Jenkins jenkins = Jenkins.getInstanceOrNull();
        if (jenkins == null || jenkins.hasPermission(Jenkins.ADMINISTER)) {
            return Collections.emptySet();
        }
        StaplerRequest2 req = Stapler.getCurrentRequest2();
        if (req != null) {
            Object cached = req.getAttribute(REQUEST_CACHE);
            if (cached instanceof Set) {
                @SuppressWarnings("unchecked")
                Set<String> hidden = (Set<String>) cached;
                return hidden;
            }
        }
        Set<String> hidden = new HashSet<>();
        for (String name : store.getAllJobNames()) {
            if (name != null && !canRead(name)) {
                hidden.add(name);
            }
        }
        hidden = Collections.unmodifiableSet(hidden);
        if (req != null) {
            req.setAttribute(REQUEST_CACHE, hidden);
        }
        return hidden;
    }

    /**
     * Everything the job settings leave out for everyone: jobs the job filters do not
     * track, and disabled pipelines when that option is on.
     */
    public static Set<String> excludedForEveryone(DoraGlobalConfiguration config, MetricsStore store) {
        // One dashboard page builds a calculator or ranker for every card and list, so work
        // this out once per request, as with the jobs hidden from the user.
        StaplerRequest2 req = Stapler.getCurrentRequest2();
        if (req != null) {
            Object cached = req.getAttribute(EXCLUDED_CACHE);
            if (cached instanceof Set) {
                @SuppressWarnings("unchecked")
                Set<String> excluded = (Set<String>) cached;
                return excluded;
            }
        }
        Set<String> untracked = JobFilter.untracked(config, store);
        Set<String> disabled = DisabledPipelines.names(config, store);
        Set<String> all = new HashSet<>(untracked);
        all.addAll(disabled);
        Set<String> excluded = Collections.unmodifiableSet(all);
        if (req != null) {
            req.setAttribute(EXCLUDED_CACHE, excluded);
        }
        return excluded;
    }

    /**
     * Everything to leave out of what the current user is shown: what the job
     * settings leave out for everyone, plus jobs the user may not see.
     */
    public static Set<String> excludedForCurrentUser(DoraGlobalConfiguration config, MetricsStore store) {
        Set<String> excluded = excludedForEveryone(config, store);
        Set<String> hidden = hiddenFromCurrentUser(store);
        if (hidden.isEmpty()) {
            return excluded;
        }
        if (excluded.isEmpty()) {
            return hidden;
        }
        Set<String> all = new HashSet<>(excluded);
        all.addAll(hidden);
        return all;
    }
}
