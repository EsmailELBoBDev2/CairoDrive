-dontobfuscate

# ---------------------------------------------------------------------------------------------
# WHAT REPLACED THE BLANKET KEEP, AND HOW TO GET IT BACK
#
# This file used to carry `-keep class net.osmand.** { *; }` on line 5, which meant R8 ran but
# shrank none of this app's own code - only the third-party libraries. Everything below the
# "narrowed keeps" banner is the replacement: the smallest set of roots that still covers every
# way a net.osmand class is reached WITHOUT R8 being able to see it.
#
# The blanket keep is not deleted. `CAIRODRIVE_R8_SHRINK` (cairodrive.gradle) appends
# proguard-rules-keep-all.pro to this configuration and it is FALSE by default, so an ordinary
# build is still the old configuration. Every rule below is a subset of that blanket keep -
# every one of them names only classes under net.osmand - so with the flag off the union of the
# two files is exactly what shipped before and the dex is unchanged. That property is the point:
# a wrong keep rule here crashes the app mid-drive on a Cairo road with nothing in the log to
# explain it, so the shrinking configuration has to be opt-in until a drive has proved it.
#
# The evidence for each rule is named as file:line. A rule with no evidence does not belong here;
# a rule kept only because the answer was not provable says so in its own comment.
#
# NOT covered here, deliberately, because AGP already covers it: every class named in
# AndroidManifest.xml or in res/ (activities, services, receivers, providers, custom View
# subclasses in layouts, custom Preference tags in res/xml) gets a generated keep rule from
# AAPT2 in build/intermediates/aapt_proguard_file/. That is why none of them are named below.
#
# -printusage, -printseeds and -printconfiguration are NOT in this file either: they need
# absolute paths and a directory that exists before R8 opens them, so cairodrive.gradle writes
# them into build/outputs/r8/print-rules.pro and appends that. configuration.txt is where the
# AAPT2 rules above can be read rather than assumed.
# ---------------------------------------------------------------------------------------------

# Keep repo-source/vendor packages outside net.osmand as project code too.
-keep class btools.routingapp.** { *; }
-keep class com.example.android.common.view.** { *; }
-keep class com.github.ksoichiro.android.observablescrollview.** { *; }
-keep class com.google.protobuf.** { *; }
-keep class com.jwetherell.openmap.common.** { *; }
-keep class com.wdtinc.mapbox_vector_tile.** { *; }
-keep class io.github.cosinekitty.astronomy.** { *; }
-keep class net.sf.marineapi.** { *; }
-keep class org.openplacereviews.** { *; }

# Gson relies on generic signatures for fields like List<AssetEntry>.
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# Rhino loads several runtime classes by class name while initializing voice scripts.
-keep class org.mozilla.javascript.VMBridge { *; }
-keep class org.mozilla.javascript.Interpreter { *; }
-keep class org.mozilla.javascript.Interpreter$* { *; }
-keep class org.mozilla.javascript.NativeContinuation { *; }
-keep class org.mozilla.javascript.JavaAdapter { *; }
-keep class org.mozilla.javascript.jdk15.** { *; }
-keep class org.mozilla.javascript.jdk18.** { *; }
-keep class org.mozilla.javascript.regexp.** { *; }
-keep class org.mozilla.javascript.typedarrays.** { *; }
-keep class org.mozilla.javascript.xmlimpl.** { *; }

# Java classes called from Qt/OsmAndCore native code must keep their JNI-visible names and members.
-keep class org.qtproject.qt5.android.** { *; }

# Optional dependency surfaces referenced by bundled libraries but not available on Android.
-dontwarn java.beans.**
-dontwarn javax.ws.rs.**
-dontwarn org.immutables.value.**
-dontwarn org.kxml2.io.**


# =============================================================================================
# NARROWED KEEPS FOR net.osmand.** - inert while CAIRODRIVE_R8_SHRINK is false
# =============================================================================================

# ---------------------------------------------------------------------------------------------
# 1. JNI - libosmand.so (OsmAnd-core-legacy)
#
# The C++ is not in this tree; CI checks it out at the SHA pinned in build-dev.yml:116
# (CORE_LEGACY_REF). Every class below is one the native side resolves by name through
# findGlobalClass/findClass in native/src/java_wrap.cpp and native/src/java_renderRules.h at
# that exact commit - it then reads FIELDS and calls CONSTRUCTORS and METHODS on them, none of
# which appears in any Java call graph. The list is the complete set at that SHA, extracted from
# those two files rather than guessed; it is identical on the repository's current head, so it
# is not drifting.
#
# `{ *; }` on each, rather than naming the individual fields the C++ touches, is deliberate: the
# member list is ~150 entries maintained in another repository on a pinned SHA that will be
# bumped, and a member that silently stops existing here is exactly the failure mode that costs
# a drive. Keeping the whole class also gives R8 the trace roots it needs to retain everything
# these classes reach, which is what makes enumerating only the ENTRY points sufficient.
-keep class net.osmand.NativeLibrary { *; }
-keep class net.osmand.NativeLibrary$* { *; }
-keep class net.osmand.RenderingContext { *; }
-keep class net.osmand.Reshaper { *; }
-keep class net.osmand.binary.RouteDataObject { *; }
-keep class net.osmand.binary.BinaryMapRouteReaderAdapter$RouteRegion { *; }
-keep class net.osmand.binary.BinaryMapRouteReaderAdapter$RouteSubregion { *; }
-keep class net.osmand.render.RenderingRule { *; }
-keep class net.osmand.render.RenderingRuleProperty { *; }
-keep class net.osmand.render.RenderingRuleSearchRequest { *; }
-keep class net.osmand.render.RenderingRuleStorageProperties { *; }
-keep class net.osmand.render.RenderingRulesStorage { *; }
-keep class net.osmand.router.GeneralRouter { *; }
-keep class net.osmand.router.GeneralRouter$* { *; }
-keep class net.osmand.router.HHRouteDataStructure$HHRoutingConfig { *; }
-keep class net.osmand.router.NativeTransportRoute { *; }
-keep class net.osmand.router.NativeTransportRouteResultSegment { *; }
-keep class net.osmand.router.NativeTransportRoutingResult { *; }
-keep class net.osmand.router.NativeTransportStop { *; }
-keep class net.osmand.router.PrecalculatedRouteDirection { *; }
-keep class net.osmand.router.RouteCalculationProgress { *; }
-keep class net.osmand.router.RoutePlannerFrontEnd$RouteCalculationMode { *; }
-keep class net.osmand.router.RouteSegmentResult { *; }
-keep class net.osmand.router.RoutingConfiguration { *; }
-keep class net.osmand.router.RoutingContext { *; }
-keep class net.osmand.router.TransportRoutingConfiguration { *; }
-keep class net.osmand.router.TurnType { *; }
-keep class net.osmand.util.TransliterationHelper { *; }

# The one JNI access that does NOT go through a named class. java_wrap.cpp:171-174 takes
# GetObjectClass of whatever object it was handed and reads a boolean field literally called
# "interrupted" off it; MapRenderRepositories.java:303 is the only caller and passes `this`,
# whose field is MapRenderRepositories.java:106 - private, and so exactly the kind R8 rewrites
# away. Losing it means the renderer can no longer be cancelled mid-tile.
-keepclassmembers class net.osmand.plus.render.MapRenderRepositories {
	boolean interrupted;
}

# A `native` declaration is bound lazily by name at first call, so R8 removing one produces an
# UnsatisfiedLinkError at the call site rather than at load. NativeLibrary.java:347-408 and
# NativeOsmandLibrary.java:90 are the declarations; this keeps them wherever they appear.
-keepclasseswithmembers class net.osmand.** {
	native <methods>;
}

# The OpenGL core. These classes are not in this tree at all - they come from the prebuilt
# OsmAndCore_android AAR (build.gradle:268-271) and are SWIG-generated JNI proxies plus SWIG
# "director" classes that libOsmAndCoreWithJNI.so constructs and calls back into;
# NativeCoreContext.java:9-14 is the app's entry to them. There is no source here to audit and
# no consumer proguard rules in the AAR, so this one is kept wholesale ON PURPOSE and is the
# largest single thing below that was not narrowed. Narrowing it needs the AAR unpacked and its
# director classes enumerated, which is a job of its own.
-keep class net.osmand.core.** { *; }

# ---------------------------------------------------------------------------------------------
# 2. Rhino / the *_tts.js voice scripts - NO rule needed, and this note is the finding
#
# CommandPlayer.java:112 builds the script scope with `initSafeStandardObjects()`, not
# `initStandardObjects()`. The safe scope is the one without LiveConnect: no `Packages`, no
# `java`, no `JavaAdapter`, no `getClass`. A voice script therefore CANNOT name a Java class at
# all, and the traffic runs the other way - JsCommandBuilder.java:40-45 calls INTO the script
# through Function.call. The Rhino keeps above this banner are about Rhino's own internals and
# are unrelated to net.osmand.
#
# If anyone ever switches that call to initStandardObjects(), this section stops being true and
# every class the scripts name has to be kept.

# ---------------------------------------------------------------------------------------------
# 3. AIDL - NO rule needed, and this note is the finding
#
# Both exported AIDL services are removed in this fork: AndroidManifest.xml:1164-1182 comments
# them out and AndroidManifest-cairodrive.xml applies `tools:node="remove"` to both. Nothing in
# the app binds them. So net.osmand.aidl.** and net.osmand.aidlapi.** are reachable only through
# whatever the app itself still calls, which R8 can see, and the Parcelable CREATOR fields that
# do survive are already covered by proguard-android.txt's Parcelable rule. Keeping the IPC
# contract here would hold ~50 packages of wire-format classes alive for an interface no process
# can reach.

# ---------------------------------------------------------------------------------------------
# 4. Reflection inside net.osmand
# ---------------------------------------------------------------------------------------------

# CairoDriveMapMatching.java:79-80 reads the flag by name off BuildConfig rather than referencing
# it, so that the fork still compiles when the field has not been generated yet. R8 constant-
# folds BuildConfig and would then delete the class, and the catch turns that into a silent
# `false` - map matching would just quietly never run.
-keep class net.osmand.plus.BuildConfig {
	public static <fields>;
}

# OsmandApplication.java:1316 - Class.forName("net.osmand.plus.base.EnableStrictMode")
# .newInstance(). Nothing else names the class, so both the class and its no-arg constructor are
# invisible to R8.
-keep class net.osmand.plus.base.EnableStrictMode {
	<init>();
}

# The R fields are read by NAME, not by reference. RenderingIcons.java:278-281 walks
# R.drawable.class.getDeclaredFields() and builds the entire map-icon table from the field names
# (h_/mm_/mx_ prefixes) - lose the fields and every POI and shield icon on the map disappears.
# R.string.class.getField(...) is the same trick for translated names at MapPoiTypesTranslator
# .java:58, AndroidUtils.java:1345, WikipediaPlugin.java:334, FileNameTranslationHelper.java:165
# and WhatsNewDialogFragment.java:39. Scoped to this app's R because nonTransitiveRClass=false
# (gradle.properties) puts every resource in net.osmand.plus.R.
#
# proguard-android.txt already carries the same rule for `**.R$*`, so this one is a duplicate -
# stated explicitly rather than relied on silently, because it is the difference between the map
# rendering with icons and without, and it would be inherited from a file this repo does not own.
-keepclassmembers class net.osmand.plus.R$* {
	public static <fields>;
}

# QuickActionType.java:82,95 instantiates action classes through `cl.newInstance()` and
# `cl.getConstructor(QuickAction.class)`. The `cl` values arrive as .class literals from
# MapButtonsHelper, which keeps the CLASSES reachable - but a .class literal does not keep a
# CONSTRUCTOR, so without this every quick action throws UnsupportedOperationException the
# moment it is created or restored from settings.
-keepclassmembers class net.osmand.plus.quickaction.QuickAction {
	<init>();
	<init>(net.osmand.plus.quickaction.QuickAction);
}
-keepclassmembers class net.osmand.** extends net.osmand.plus.quickaction.QuickAction {
	<init>();
	<init>(net.osmand.plus.quickaction.QuickAction);
}

# MapButtonsHelper.java:80-83 finds the action categories by scanning its own getDeclaredFields()
# for the @QuickActionCategoryType annotation. The fields are never read by name anywhere, so R8
# sees write-only statics. The annotation type itself needs no rule: MapButtonsHelper.java:83
# names it as a .class literal, and -keepattributes *Annotation* above retains the uses.
-keepclassmembers class net.osmand.plus.quickaction.MapButtonsHelper {
	public static <fields>;
}

# Settings screens are opened by class-name STRING, not by reference: res/xml/*.xml carries 20
# `app:fragment="net.osmand.plus...."` attributes (AAPT2 does not emit keep rules for that
# attribute - it is a plain string, unlike a <fragment android:name=>), and
# BaseSettingsFragment.java:426,887, MapFragmentsHelper.java:300, MapRoutesFragment.java:234,
# SearchFragmentPagerAdapter.java:44 and ProfileAppearanceFragment.java:394 all go through
# Fragment.instantiate / FragmentFactory.instantiate with a String. On top of that the framework
# re-creates every fragment by name after process death, which on a head unit happens routinely.
#
# KEPT WIDER THAN THE EVIDENCE STRICTLY REQUIRES, and this is the biggest single conservatism in
# this file: the reachable-by-string set could be enumerated today, but it is spread over XML,
# enum tables and constants, so an edit that adds one more would silently reintroduce a crash
# that only appears on a rotation or a process restart. Every fragment stays.
-keep class net.osmand.** extends androidx.fragment.app.Fragment {
	<init>();
}

# Highway-hierarchy points are created with getDeclaredConstructor().newInstance() from a
# Class<T> parameter at BinaryHHRouteReaderAdapter.java:243, HHRoutePlanner.java:1041 and
# HHRoutingDB.java:135. This is the routing fast path - CLAUDE.md's settled `fast=SUCCESS` result
# depends on it - and the constructors are package-private no-arg ones nothing else calls.
-keepclassmembers class net.osmand.router.HHRouteDataStructure$NetworkDBPoint {
	<init>();
}
-keepclassmembers class net.osmand.** extends net.osmand.router.HHRouteDataStructure$NetworkDBPoint {
	<init>();
}

# CairoDriveOsmFeedback.java:201 reads OsmBugResult.warning by name on purpose, so that an
# upstream rename degrades to "upload failed, keep retrying" instead of breaking the build.
# OsmBugsUtil.java:8 declares it. R8 removing the field turns that deliberate degradation into a
# permanently stuck note queue.
-keepclassmembers class net.osmand.plus.plugins.osmedit.helpers.OsmBugsUtil$OsmBugResult {
	public <fields>;
}

# Enum constants are recovered from stored strings all over this app - IntentHelper.java:697,779
# (SettingsScreenType, OsmAndFeature), ContextMenuCardDialog.java:85, AddPointBottomSheetDialog
# .java:112, GPXItemPagerAdapter.java:752, OnlineRoutingEngineFragment.java:639 among others -
# and valueOf()/values() are synthesized methods no Java code references. proguard-android.txt
# carries the same rule for `enum *`; duplicated here for the same reason as the R rule above.
-keepclassmembers enum net.osmand.** {
	public static **[] values();
	public static ** valueOf(java.lang.String);
}

# ---------------------------------------------------------------------------------------------
# 5. Reflectively populated model classes
# ---------------------------------------------------------------------------------------------

# Gson reads and writes FIELDS. The Gson surface inside net.osmand is small and was enumerated
# rather than assumed: every other fromJson() call in the app deserializes a Map or a List of
# String, or goes through a hand-written adapter. The two annotated model classes are
# ApplicationModeBean.java and HistoryEntry.java, both read through
# `new GsonBuilder().excludeFieldsWithoutExposeAnnotation()` (ApplicationMode.java:598,622).
-keepclassmembers class net.osmand.** {
	@com.google.gson.annotations.Expose <fields>;
}

# The one Gson model with no annotations on it: DevicesSettingsCollection.java:174 deserializes
# HashMap<String, DeviceSettings> off plain field names, so every field has to survive.
-keep class net.osmand.plus.plugins.externalsensors.DevicesSettingsCollection$DeviceSettings { *; }

# kotlinx.serialization in OsmAnd-shared (16 @Serializable files, including the sealed
# BaseTrackFilter hierarchy and WikiCoreHelper). The compiler plugin generates a `$$serializer`
# class and a `Companion.serializer()` per type and reaches them by name; these are the three
# rules kotlinx.serialization documents for exactly this, scoped to this app's package. The
# generated serializer being kept whole is what pulls in the synthetic constructors it writes
# fields through, so no separate field rule is needed.
-keep class net.osmand.shared.**$$serializer { *; }
-keepclassmembers class net.osmand.shared.** {
	*** Companion;
}
-keepclasseswithmembers class net.osmand.shared.** {
	kotlinx.serialization.KSerializer serializer(...);
}

# Java serialization is used for Intent extras and saved instance state - OsmPoint.java:9,
# AvoidRoadInfo.java:14, ContextMenuItemsSettings.java:19 and DownloadOsmandIndexesHelper.java:43
# among others.
# These are the members the runtime looks up by name and that no code calls; the fields
# themselves are deliberately NOT kept, because both the write and the read happen inside one
# install of one build, so a field R8 can prove is unused is absent from both sides.
-keepclassmembers class net.osmand.** implements java.io.Serializable {
	static final long serialVersionUID;
	private static final java.io.ObjectStreamField[] serialPersistentFields;
	private void writeObject(java.io.ObjectOutputStream);
	private void readObject(java.io.ObjectInputStream);
	java.lang.Object writeReplace();
	java.lang.Object readResolve();
}

# ---------------------------------------------------------------------------------------------
# 6. Plugins and registries - NO rule needed, and this note is the finding
#
# PluginsHelper.java:110-126 registers every built-in plugin with a plain `new XPlugin(app)`, so
# the whole set is an ordinary reference R8 follows. Nothing in this app looks a plugin up by
# class name, and CustomOsmandPlugin (PluginsHelper.java:163) is data-driven rather than
# class-driven. The same is true of the map layers, the widget registry and the settings-item
# factory: all switch or construct directly.
#
# The reason plugin ids and preference ids are safe to leave alone at all is `-dontobfuscate` at
# the top of this file. With names preserved, the only thing that can go wrong anywhere in this
# configuration is REMOVAL - never a failed name lookup - which is why every rule above is about
# reachability and none of them is about naming.
