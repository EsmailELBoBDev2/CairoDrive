# Appended to proguard-rules.pro by cairodrive.gradle whenever CAIRODRIVE_R8_SHRINK is not
# "true" - which is the default, so this is what an ordinary build gets.
#
# This single line is what the fork shipped before the narrowing work, and it is deliberately
# still what it ships. Every net.osmand rule in proguard-rules.pro names only classes and
# members that live under net.osmand, so each of them is a strict subset of this rule: with this
# file appended the union of the two is exactly this line, R8 sees the same configuration it saw
# before, and the dex is unchanged. That is the whole reason the narrowing lives in rules that
# can be subsumed rather than in an edit to this one.
#
# Turning it off is `CAIRODRIVE_R8_SHRINK=true`, and it should stay off until a build with it on
# has been driven. A keep rule that is one class short does not fail the build, does not fail an
# install and does not fail a smoke test - it throws ClassNotFoundException or NoSuchMethodError
# at the moment the code path is first taken, which for most of this app is somewhere on a Cairo
# road with the phone on the windscreen.
-keep class net.osmand.** { *; }
