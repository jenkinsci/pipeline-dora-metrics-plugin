package io.jenkins.plugins.dorametrics.store;

import hudson.ExtensionList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Where the database lives, and the warning when that is network storage. */
@WithJenkins
class StorageLocationTest {

    private JenkinsRule j;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        j = rule;
        MetricsStore.setInstance(null);
    }

    @AfterEach
    void tearDown() {
        System.clearProperty(MetricsStore.DIRECTORY_PROPERTY);
        MetricsStore.setInstance(null);
    }

    @Test
    void theDirectoryPropertyMovesTheDatabase(@TempDir Path dir) {
        System.setProperty(MetricsStore.DIRECTORY_PROPERTY, dir.toString());
        MetricsStore store = MetricsStore.getInstance();
        assertTrue(store.insertBuild("app", 1, 1000, 10, "SUCCESS", "SCM", "main") > 0);

        assertEquals(dir.resolve("metrics.db").toFile().getAbsoluteFile(), store.getDatabaseFile());
        assertTrue(Files.exists(dir.resolve("metrics.db")));
        assertFalse(new File(j.jenkins.getRootDir(), "pipeline-dora-metrics/metrics.db").exists());
    }

    @Test
    void byDefaultTheDatabaseStaysInJenkinsHome() {
        assertEquals(new File(j.jenkins.getRootDir(), "pipeline-dora-metrics/metrics.db").getAbsoluteFile(),
                MetricsStore.getInstance().getDatabaseFile());
    }

    @Test
    void networkFileSystemsAreRecognised() {
        for (String type : new String[] {"nfs", "nfs4", "NFS4", "cifs", "smbfs", "afpfs", "fuse.sshfs", "glusterfs"}) {
            assertTrue(MetricsStore.isNetworkFileSystem(type), type);
        }
        for (String type : new String[] {"apfs", "ext4", "xfs", "btrfs", "overlay", "tmpfs", "zfs", "NTFS", null}) {
            assertFalse(MetricsStore.isNetworkFileSystem(type), String.valueOf(type));
        }
    }

    @Test
    void localStorageRaisesNoWarning() {
        MetricsStore.getInstance();
        assertFalse(ExtensionList.lookupSingleton(NetworkStorageMonitor.class).isActivated());
    }

    @Test
    void networkStorageWarnsAdministrators(@TempDir Path dir) throws Exception {
        MetricsStore.setInstance(new MetricsStore("jdbc:sqlite:" + dir.resolve("metrics.db"), "nfs4"));
        assertTrue(ExtensionList.lookupSingleton(NetworkStorageMonitor.class).isActivated());

        JenkinsRule.WebClient wc = j.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        String page = wc.goTo("manage/").getWebResponse().getContentAsString();
        assertTrue(page.contains("on a network filesystem"), "the warning shows on Manage Jenkins");
        assertTrue(page.contains(MetricsStore.DIRECTORY_PROPERTY));
        assertTrue(page.contains("Dismiss"), "administrators can dismiss it");
    }
}
