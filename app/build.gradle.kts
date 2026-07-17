plugins {
    alias(libs.plugins.android.application)
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20"
}

// Emit Compose Compiler stability and metrics reports under
// `app/build/compose_compiler/` on every Kotlin compilation. Inspect
// `app_release-classes.txt` to see which classes the compiler considers
// `stable`, `unstable`, or `runtime`, and `app_release-composables.txt`
// to see which composables are `skippable` / `restartable`. Generated
// build output, not checked in.
composeCompiler {
    reportsDestination = layout.buildDirectory.dir("compose_compiler")
    metricsDestination = layout.buildDirectory.dir("compose_compiler")
}

// Firebase Crashlytics + Performance Monitoring need google-services.json to be
// present at app/google-services.json. The file isn't checked in (it identifies
// the Firebase project; the SDK still relies on the APK signature for trust),
// so forks and the sandbox without one build cleanly without telemetry — the
// plugins are skipped and the telemetry wrapper no-ops at runtime when no
// FirebaseApp is initialized. See docs/firebase-telemetry.md.
val firebaseConfigFile = file("google-services.json")
val hasFirebaseConfig = firebaseConfigFile.exists()
if (hasFirebaseConfig) {
    apply(plugin = libs.plugins.google.services.get().pluginId)
    apply(plugin = libs.plugins.firebase.crashlytics.get().pluginId)
    // If google-services.json has no release client (dev.phomo), disable the
    // release processing task so bundleRelease doesn't fail. When a release client
    // is present the task runs normally and Firebase/Crashlytics are wired up for
    // production.
    val hasReleaseClient = firebaseConfigFile.readText().contains("\"dev.phomo\"")
    if (!hasReleaseClient) {
        afterEvaluate {
            tasks.matching {
                it.name in setOf(
                    "processReleaseGoogleServices",
                    "uploadCrashlyticsMappingFileRelease",
                )
            }.configureEach {
                enabled = false
            }
        }
    }
}

fun gitOutput(vararg args: String, fallback: String): String =
    try {
        val output = providers.exec {
            commandLine("git", *args)
            isIgnoreExitValue = true
        }.standardOutput.asText.get().trim()
        output.ifEmpty { fallback }
    } catch (_: Exception) {
        fallback
    }

val gitCommitCount: Int =
    gitOutput("rev-list", "--count", "HEAD", fallback = "1").toIntOrNull() ?: 1
val gitShortSha: String = gitOutput("rev-parse", "--short", "HEAD", fallback = "unknown")
val gitBranchName: String = providers.environmentVariable("GITHUB_REF_NAME")
    .orElse(gitOutput("rev-parse", "--abbrev-ref", "HEAD", fallback = "unknown"))
    .get()
// `git status --porcelain` is empty for a clean tree, so we can't route this
// through `gitOutput` — its empty-means-fallback semantics would map clean to
// "dirty". Inline the exec, and *don't* ignore the exit code: a non-zero exit
// (e.g. running from a tarball with no `.git`) should fall through to the
// conservative "assume dirty" branch rather than being misread as clean.
val isGitWorkingTreeDirty: Boolean =
    try {
        providers.exec {
            commandLine("git", "status", "--porcelain")
        }.standardOutput.asText.get().trim().isNotEmpty()
    } catch (_: Exception) {
        true
    }
val baseVersionName = "1.0"
val isCiBuild: Boolean = providers.environmentVariable("CI")
    .map { value -> value.equals("true", ignoreCase = true) }
    .getOrElse(false)
val localBuildBranch = if (isCiBuild) "" else gitBranchName
val localBuildSha = if (isCiBuild) "" else gitShortSha
val localBuildDirty = !isCiBuild && isGitWorkingTreeDirty

fun buildConfigString(value: String): String = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

android {
    namespace = "dev.phomo"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "dev.phomo"
        minSdk = 34
        targetSdk = 36
        versionCode = gitCommitCount
        versionName = "$baseVersionName.$gitCommitCount+$gitShortSha"
        buildConfigField("String", "LOCAL_BUILD_BRANCH", buildConfigString(localBuildBranch))
        buildConfigField("String", "LOCAL_BUILD_SHA", buildConfigString(localBuildSha))
        buildConfigField("boolean", "LOCAL_BUILD_DIRTY", localBuildDirty.toString())

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // CI materializes a stable debug keystore from a secret and points
        // DEBUG_KEYSTORE_FILE at it, so successive Firebase App Distribution
        // builds carry the same signature and tester devices can install
        // them as updates. Local builds without DEBUG_KEYSTORE_FILE set
        // fall through to AGP's auto-generated ~/.android/debug.keystore.
        // See docs/firebase-app-distribution.md.
        getByName("debug") {
            val keystorePath = providers.environmentVariable("DEBUG_KEYSTORE_FILE").orNull
            if (!keystorePath.isNullOrEmpty() && file(keystorePath).exists()) {
                storeFile = file(keystorePath)
                storePassword = providers.environmentVariable("DEBUG_KEYSTORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("DEBUG_KEY_ALIAS").getOrElse("androiddebugkey")
                keyPassword = providers.environmentVariable("DEBUG_KEY_PASSWORD").orNull
            }
        }
        // CI materializes a release keystore from a secret for the Play Store
        // internal-track upload (see docs/play-store-internal-track.md). The
        // keystore is the upload key; Play App Signing re-signs with its
        // managed app-signing key before delivery to devices. Local builds
        // without RELEASE_KEYSTORE_FILE set produce an unsigned release AAB,
        // which is fine for inspection and means forks build cleanly.
        create("release") {
            val keystorePath = providers.environmentVariable("RELEASE_KEYSTORE_FILE").orNull
            if (!keystorePath.isNullOrEmpty() && file(keystorePath).exists()) {
                storeFile = file(keystorePath)
                storePassword = providers.environmentVariable("RELEASE_KEYSTORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("RELEASE_KEY_ALIAS").orNull
                keyPassword = providers.environmentVariable("RELEASE_KEY_PASSWORD").orNull
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            // CI runs R8 in shrink-only mode (see proguard-rules.pro) so tester APKs
            // drop the bulk of unused code. Local debug builds skip R8 to keep the
            // edit-install loop fast.
            isMinifyEnabled = isCiBuild
            isShrinkResources = isCiBuild
            // AGP 9.x rejects the non-optimize baseline (proguard-android.txt) by
            // default, so we always pull in the optimize baseline and rely on
            // -dontoptimize in proguard-rules.pro to keep this a shrink-only run.
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        release {
            isMinifyEnabled = isCiBuild
            isShrinkResources = isCiBuild
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Only attach the release signingConfig when CI has populated it;
            // otherwise an unset storeFile makes bundleRelease fail locally
            // for anyone without the secrets.
            if (!providers.environmentVariable("RELEASE_KEYSTORE_FILE").orNull.isNullOrEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures {
        buildConfig = true
        compose = true
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

tasks.withType<Test>().configureEach {
    if (project.hasProperty("roborazzi.test.record")) {
        jvmArgs("-Droborazzi.test.record=true")
    }
    if (project.hasProperty("roborazzi.test.verify")) {
        jvmArgs("-Droborazzi.test.verify=true")
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(platform(libs.androidx.compose.bom))
    debugImplementation(platform(libs.androidx.compose.bom))
    testImplementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.material)
    implementation(libs.linphone)
    implementation(libs.guava)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.crashlytics)
    implementation(libs.firebase.perf)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.uiautomator)
}
