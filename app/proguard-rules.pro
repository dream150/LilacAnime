# Add project-specific ProGuard/R8 keep rules here.
# Applied on top of the bundled defaults (proguard-android-optimize.txt) when minifyEnabled = true.
#
# Keep a class referenced only by reflection / from XML, e.g.:
# -keep class com.example.SomeClass { *; }
#
# Preserve line numbers for readable crash stack traces, then hide the original file name:
# -keepattributes SourceFile,LineNumberTable
# -renamesourcefileattribute SourceFile
