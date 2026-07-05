# Room, DataStore, and Hilt publish their own consumer rules.

# Protobuf Lite rules
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }
