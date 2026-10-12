# MapLibre Native ships consumer ProGuard/R8 rules inside its AAR, so nothing is
# required here for Phase 1. Add app-specific keep rules below if R8 strips
# something reflectively used in a later phase.
#
# Keep line numbers for readable crash reports:
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
