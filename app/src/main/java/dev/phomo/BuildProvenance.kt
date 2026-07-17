package dev.phomo

/**
 * Turns the git-derived `BuildConfig` fields into a short human-readable label
 * for the About / home surface, so a tester can tell exactly which build is on
 * their device.
 *
 * CI builds blank out the local fields (see `app/build.gradle.kts`), so those
 * fall back to the monotonic `versionName`. Local builds show `branch · sha`,
 * with a `· dirty` suffix when the working tree had uncommitted changes.
 *
 * Kept as pure Kotlin (no Android imports) so it is covered by a plain JVM unit
 * test.
 */
object BuildProvenance {
    fun label(branch: String, sha: String, dirty: Boolean, versionName: String): String {
        if (branch.isEmpty() && sha.isEmpty()) {
            // CI build: the local provenance fields are intentionally blank.
            return versionName
        }
        val base = when {
            branch.isNotEmpty() && sha.isNotEmpty() -> "$branch · $sha"
            sha.isNotEmpty() -> sha
            else -> branch
        }
        return if (dirty) "$base · dirty" else base
    }
}
