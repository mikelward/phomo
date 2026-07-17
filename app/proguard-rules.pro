# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Shrink-only mode: tree-shake unreferenced classes and methods (so e.g. the
# unused 99% of material-icons-extended is dropped) but keep original names
# and skip optimization passes. The result is smaller APKs whose stack traces
# stay readable in Crashlytics without a mapping file, and reflection-shaped
# bugs stay rare because the optimizer isn't rewriting code.
-dontobfuscate
-dontoptimize
