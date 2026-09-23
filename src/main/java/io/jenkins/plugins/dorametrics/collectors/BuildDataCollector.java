package io.jenkins.plugins.dorametrics.collectors;

import hudson.Extension;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Listens to all build completions and hands each one to {@link BuildRecorder}.
 */
@Extension
public class BuildDataCollector extends RunListener<Run<?, ?>> {

    private static final Logger LOGGER = Logger.getLogger(BuildDataCollector.class.getName());

    @Override
    public void onCompleted(Run<?, ?> run, TaskListener listener) {
        try {
            BuildRecorder.record(run);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to collect metrics for " + run.getFullDisplayName(), e);
        }
    }
}
