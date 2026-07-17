package dev.phomo

import org.junit.Assert.assertEquals
import org.junit.Test

class BuildProvenanceTest {

    @Test
    fun ciBuild_blankLocalFields_fallsBackToVersionName() {
        // CI blanks the local branch/sha fields, so the label is the monotonic
        // versionName rather than empty.
        assertEquals(
            "1.0.42+a1b2c3d",
            BuildProvenance.label(branch = "", sha = "", dirty = false, versionName = "1.0.42+a1b2c3d"),
        )
    }

    @Test
    fun localCleanBuild_showsBranchAndSha() {
        assertEquals(
            "main · a1b2c3d",
            BuildProvenance.label(branch = "main", sha = "a1b2c3d", dirty = false, versionName = "1.0.42+a1b2c3d"),
        )
    }

    @Test
    fun localDirtyBuild_appendsDirtySuffix() {
        assertEquals(
            "feature · a1b2c3d · dirty",
            BuildProvenance.label(branch = "feature", sha = "a1b2c3d", dirty = true, versionName = "ignored"),
        )
    }

    @Test
    fun shaOnly_whenBranchMissing() {
        assertEquals(
            "a1b2c3d",
            BuildProvenance.label(branch = "", sha = "a1b2c3d", dirty = false, versionName = "1.0.1+a1b2c3d"),
        )
    }
}
