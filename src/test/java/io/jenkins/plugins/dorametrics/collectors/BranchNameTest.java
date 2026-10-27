package io.jenkins.plugins.dorametrics.collectors;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BranchNameTest {

    @Test
    void keepsThePathOfTheBranch() {
        assertEquals("release/1.2", BuildRecorder.branchName("BRANCH_NAME", "release/1.2"));
        assertEquals("feature/login", BuildRecorder.branchName("CHANGE_BRANCH", "feature/login"));
        assertEquals("PR-7", BuildRecorder.branchName("BRANCH_NAME", "PR-7"));
    }

    @Test
    void dropsTheRemoteFromGitBranch() {
        assertEquals("main", BuildRecorder.branchName("GIT_BRANCH", "origin/main"));
        assertEquals("release/1.2", BuildRecorder.branchName("GIT_BRANCH", "origin/release/1.2"));
        assertEquals("main", BuildRecorder.branchName("GIT_BRANCH", "main"));
    }

    @Test
    void dropsRefPrefixes() {
        assertEquals("release/1.2", BuildRecorder.branchName("GIT_BRANCH", "refs/heads/release/1.2"));
        assertEquals("main", BuildRecorder.branchName("GIT_BRANCH", "refs/remotes/origin/main"));
        assertEquals("hotfix/x", BuildRecorder.branchName("BRANCH", "refs/heads/hotfix/x"));
    }
}
