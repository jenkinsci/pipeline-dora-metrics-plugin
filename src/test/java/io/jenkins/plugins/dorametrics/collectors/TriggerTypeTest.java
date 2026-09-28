package io.jenkins.plugins.dorametrics.collectors;

import hudson.model.Cause;
import hudson.triggers.SCMTrigger;
import hudson.triggers.TimerTrigger;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** Every build gets one of a few documented trigger labels, never a Java class name. */
public class TriggerTypeTest {

    static class BranchIndexingCause extends Cause {
        @Override public String getShortDescription() { return "Branch indexing"; }
    }

    static class BranchEventCause extends Cause {
        @Override public String getShortDescription() { return "Push event"; }
    }

    static class GitHubPushCause extends Cause {
        @Override public String getShortDescription() { return "GitHub push"; }
    }

    static class SomePluginCause extends Cause {
        @Override public String getShortDescription() { return "Something else"; }
    }

    @Test
    public void causesMapToTheDocumentedLabels() {
        assertEquals("USER", BuildRecorder.triggerType(new Cause.UserIdCause()));
        assertEquals("TIMER", BuildRecorder.triggerType(new TimerTrigger.TimerTriggerCause()));
        assertEquals("SCM", BuildRecorder.triggerType(new SCMTrigger.SCMTriggerCause("")));
        assertEquals("SCM", BuildRecorder.triggerType(new BranchIndexingCause()));
        assertEquals("SCM", BuildRecorder.triggerType(new BranchEventCause()));
        assertEquals("SCM", BuildRecorder.triggerType(new GitHubPushCause()));
        assertEquals("REMOTE", BuildRecorder.triggerType(new Cause.RemoteCause("10.0.0.1", "")));
        assertEquals("OTHER", BuildRecorder.triggerType(new SomePluginCause()));
    }
}
