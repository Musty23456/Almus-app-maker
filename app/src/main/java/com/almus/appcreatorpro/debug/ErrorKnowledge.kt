package com.almus.appcreatorpro.debug

/**
 * The rule base behind "suggested solution". These are fixed, human-written explanations for well-known compiler,
 * Gradle and Android Gradle plugin messages. They are NOT produced by an AI model: the AI integration (see the `ai`
 * part of this package) is a separate, optional layer.
 */
internal class MessageRule(
    val id: String,
    val pattern: Regex,
    /** Overrides the category implied by the file type, when the message says more. */
    val category: ErrorCategory? = null,
    val severity: Severity? = null,
    /** True for lines that only repeat what a compiler already reported ("Compilation error. See log..."). */
    val summary: Boolean = false,
    /** Group (1-based) of the regex that holds the name the message is about, or 0 for none. */
    val symbolGroup: Int = 0,
    val suggest: (MatchResult) -> String,
) {
    /** Same rule, matching upper/lower case alike (javac and kotlinc print messages in different cases). */
    fun ignoringCase(): MessageRule =
        MessageRule(id, Regex(pattern.pattern, RegexOption.IGNORE_CASE), category, severity, summary, symbolGroup, suggest)
}

internal object ErrorKnowledge {

    private fun rule(
        id: String,
        regex: String,
        category: ErrorCategory? = null,
        severity: Severity? = null,
        summary: Boolean = false,
        symbolGroup: Int = 0,
        ignoreCase: Boolean = false,
        suggest: (MatchResult) -> String,
    ) = MessageRule(
        id = id,
        pattern = if (ignoreCase) Regex(regex, RegexOption.IGNORE_CASE) else Regex(regex),
        category = category,
        severity = severity,
        summary = summary,
        symbolGroup = symbolGroup,
        suggest = suggest,
    )

    /** Messages printed by the Kotlin compiler (after the "file:line:column" part). */
    val kotlin: List<MessageRule> = kotlinRules().map { it.ignoringCase() }

    private fun kotlinRules(): List<MessageRule> = listOf(
        rule("kotlin.unresolved-reference", "Unresolved reference(?::\\s*|\\s+)'?([\\w.`]+)'?", symbolGroup = 1) { m ->
            val name = m.groupValues[1]
            when {
                name == "R" || name.startsWith("R.") ->
                    "The generated class R does not exist yet. This usually means a resource problem stopped it from being created: " +
                        "fix the resource (XML, manifest or resource linking) errors first, then build again. Also check that the file's package matches the app namespace or that R is imported."
                name == "BuildConfig" ->
                    "BuildConfig is only generated when it is switched on. Add `buildFeatures { buildConfig = true }` inside the `android { }` block of app/build.gradle(.kts)."
                name.endsWith("Binding") ->
                    "View binding classes only exist when it is switched on. Add `buildFeatures { viewBinding = true }` to app/build.gradle(.kts) and check the layout file name."
                else ->
                    "`$name` is not declared where the compiler looked. Check the spelling, add the missing import, " +
                        "or add the library that contains it to the dependencies. If it is your own code, make sure the file is in the project and the name matches."
            }
        },
        rule("kotlin.type-mismatch", "(?:Argument |Initializer |Return )?[Tt]ype mismatch|inferred type is .* but .* was expected") {
            "The value has a different type than the place that receives it. Convert the value (for example `.toString()` or `.toInt()`), " +
                "change the declared type, or handle null with `?:` or `?.`."
        },
        rule("kotlin.val-reassign", "'?val'? cannot be reassigned") {
            "A `val` can only be set once. Change its declaration from `val` to `var` if it really must change, or create a new variable."
        },
        rule("kotlin.nullable-receiver", "Only safe \\(\\?\\.\\) or non-null asserted \\(!!\\.\\) calls are allowed") {
            "The value may be null. Use `?.` to call safely, give a default with `?:`, or check `if (x != null)` first. " +
                "Avoid `!!` unless you are certain, because it crashes when the value is null."
        },
        rule("kotlin.smart-cast", "Smart cast to .* is impossible") {
            "The compiler cannot prove the value did not change between the check and its use (for example a `var` property). " +
                "Copy it into a local `val` first and use that copy."
        },
        rule("kotlin.missing-return", "A 'return' expression required|Missing return|missing return") {
            "A function that declares a return type must return a value on every path. Add the missing `return`, or change the return type to `Unit`."
        },
        rule("kotlin.missing-argument", "No value passed for parameter '?([\\w]+)'?", symbolGroup = 1) { m ->
            "The call leaves out the parameter `${m.groupValues[1]}`. Pass it, or give the parameter a default value where the function is declared."
        },
        rule("kotlin.not-initialized", "Variable '([\\w]+)' must be initialized|must be initialized", symbolGroup = 1) {
            "Give the variable a value before it is read (for example at its declaration), or make it `lateinit var` / nullable."
        },
        rule("kotlin.when-exhaustive", "'when' expression must be exhaustive") {
            "Add the missing branches or an `else ->` branch."
        },
        rule("kotlin.abstract-member", "is not abstract and does not implement abstract member") {
            "The class must implement every abstract member of its parent or interface (use `override fun ...`), or be declared `abstract` itself."
        },
        rule("kotlin.redeclaration", "Redeclaration:|Conflicting declarations|Conflicting overloads|Platform declaration clash") {
            "The same name is declared twice in the same place. Rename one, delete the duplicate, or check for a copied file."
        },
        rule("kotlin.private-access", "Cannot access '([\\w.<>]+)': it is (?:private|protected|internal)", symbolGroup = 1) {
            "The member is not visible from here. Make it public (or internal within the module) or expose it through a public function."
        },
        rule("kotlin.composable-context", "@Composable invocations can only happen from the context of a @Composable function") {
            "Composable functions can only be called from other `@Composable` functions (or `setContent { }`). Mark the calling function `@Composable`, or move the call."
        },
        rule("kotlin.overload", "Overload resolution ambiguity|None of the following candidates is applicable|Cannot infer type|Cannot infer a type") {
            "The compiler cannot choose between functions or work out a type. Write the type explicitly (for example `listOf<String>()`) or convert the arguments to the expected types."
        },
        rule("kotlin.binary-incompatible", "incompatible version of Kotlin|binary version of its metadata", category = ErrorCategory.MISSING_DEPENDENCY) {
            "A library was built with a newer Kotlin than this project uses. Raise the Kotlin plugin version, or use an older version of that library."
        },
        rule("kotlin.syntax", "^Expecting |Unexpected tokens|[Ss]yntax error|^Expected |Unclosed (?:comment|string)|Missing '[})\\]]'") {
            "The code is not valid Kotlin at this point. Look at this line and the one before it for a missing bracket, quote or comma."
        },
        rule("kotlin.unused", "(?:Variable|Parameter|Value) '([\\w]+)' is never used|is never used", severity = Severity.WARNING, symbolGroup = 1) {
            "It is declared but never used. Remove it, or if it is a parameter you must keep, rename it to `_`."
        },
        rule("kotlin.deprecated", "is deprecated|Deprecated", severity = Severity.WARNING) {
            "This API is deprecated. Check its documentation for the replacement; it still builds today but may be removed later."
        },
        rule("kotlin.unchecked-cast", "[Uu]nchecked cast", severity = Severity.WARNING) {
            "The cast cannot be checked at run time and may throw. Check the type with `is` first, or use `as?` and handle null."
        },
    )

    /** Messages printed by javac (after "error:" / "warning:"). */
    val java: List<MessageRule> = javaRules().map { it.ignoringCase() }

    private fun javaRules(): List<MessageRule> = listOf(
        rule("java.cannot-find-symbol", "cannot find symbol") {
            "Java cannot find the name that is used here. Check the spelling and capitalisation, add the missing import, or declare the variable/method. " +
                "The `symbol:` and `location:` lines in the log say what is missing and where."
        },
        rule("java.package-missing", "package ([\\w.]+) does not exist", symbolGroup = 1) { m ->
            val pkg = m.groupValues[1]
            if (pkg.startsWith("androidx.") || pkg.startsWith("com.google.") || pkg.startsWith("com.squareup.") || pkg.startsWith("kotlinx.")) {
                "The package `$pkg` belongs to a library. Add that library to `dependencies { }` in app/build.gradle(.kts), then sync/build again."
            } else {
                "The import points to `$pkg`, which is not in the project or its libraries. Fix the package name in the import, or add the library or file that provides it."
            }
        },
        rule("java.incompatible-types", "incompatible types|possible lossy conversion") {
            "The value does not fit the declared type. Cast it explicitly where that is safe (for example `(int) value`), convert it (`String.valueOf`, `Integer.parseInt`), or change the variable's type."
        },
        rule("java.syntax", "'.' expected|<identifier> expected|reached end of file while parsing|illegal start of|not a statement|class, interface, enum, or record expected|unclosed string literal|';' expected") {
            "The code is not valid Java at this point. A missing `;`, bracket or quote on this line or the line above is the usual cause."
        },
        rule("java.unreported-exception", "unreported exception") {
            "This call can throw a checked exception. Wrap it in `try { ... } catch (...) { ... }` or add `throws` to the method."
        },
        rule("java.not-initialized", "might not have been initialized") {
            "Give the variable a value on every path before it is read (for example `int x = 0;`)."
        },
        rule("java.missing-return", "missing return statement") {
            "Every path of a method with a return type must end with `return`. Add the missing `return` at the end or in the branch that lacks it."
        },
        rule("java.unreachable", "unreachable statement") {
            "Code after `return`, `break`, `continue` or `throw` can never run. Remove it or move it before the jump."
        },
        rule("java.final-assign", "cannot assign a value to final variable") {
            "A `final` variable can only be assigned once. Remove `final` if it must change."
        },
        rule("java.already-defined", "is already defined in") {
            "The same name is declared twice in the same scope. Rename one or reuse the existing variable."
        },
        rule("java.public-class-file", "is public, should be declared in a file named") {
            "A public class must live in a file with the same name. Rename the file or the class."
        },
        rule("java.override", "method does not override or implement a method from a supertype|is not abstract and does not override abstract method") {
            "Check the method name and parameter types against the parent class or interface, or remove `@Override`. A class that does not implement every abstract method must be `abstract`."
        },
        rule("java.static-context", "non-static .* cannot be referenced from a static context") {
            "A static method (for example `main`) cannot use instance members directly. Create an object first, or make the member `static`."
        },
        rule("java.method-args", "cannot be applied to given types|no suitable (?:method|constructor) found") {
            "The call does not match any declared signature. Compare the arguments (count, order, types) with the method or constructor."
        },
        rule("java.invalid-release", "invalid (?:source|target) release|release version \\d+ not supported", category = ErrorCategory.SDK_CONFIGURATION) {
            "The Java version in the build settings is not supported by the JDK that runs the build. Align `compileOptions` (and Kotlin `jvmTarget`) with the JDK, for example 17."
        },
        rule("java.deprecated", "\\[deprecation\\]|\\[removal\\]|uses or overrides a deprecated API", severity = Severity.WARNING) {
            "A deprecated API is used. It still builds; check the documentation for the replacement."
        },
        rule("java.unchecked", "\\[unchecked\\]|unchecked", severity = Severity.WARNING) {
            "A generic type is used without checking. Add type arguments (for example `List<String>`) to remove the warning."
        },
    )

    /** Android resource (AAPT2) messages. */
    val resource: List<MessageRule> = listOf(
        rule("resource.not-found", "resource ([\\w.:]+/[\\w.$]+)(?: \\(aka [^)]*\\))? not found", symbolGroup = 1) { m ->
            "The resource `${m.groupValues[1]}` is referenced but not defined. Create it under app/src/main/res (values/*.xml or the matching folder), " +
                "or correct the name. For styles from a library (for example Theme.Material3...), add the library that provides them."
        },
        rule("resource.attribute-not-found", "(?:style )?attribute '?([\\w.:/]+)(?: \\(aka [^)]*\\))?'? not found", symbolGroup = 1) { m ->
            "The attribute `${m.groupValues[1]}` is unknown. Check the spelling, add the xmlns declaration for its prefix, or add the library that defines it."
        },
        rule("resource.duplicate", "[Dd]uplicate (?:value for )?resources?", symbolGroup = 0) {
            "The same resource name is defined twice. Keep only one definition, or move one into a different resource qualifier folder."
        },
        rule("resource.xml-parse", "not well-formed|ParseError|XML (?:parse|parsing) error|The element type .* must be terminated|Unexpected end tag|Open quote is expected|Element type .* must be followed") {
            "The XML file is not well formed. Check for a missing `>` or `/>`, an unclosed tag, an unquoted attribute value, or a stray `&` (write `&amp;`)."
        },
        rule("resource.incompatible-value", "is incompatible with attribute|has incompatible type|invalid color|not a valid") {
            "The value is not valid for this attribute. Use a value of the right kind (for example a colour like #RRGGBB, a dimension like 16dp)."
        },
        rule("resource.apostrophe", "Apostrophe not preceded by") {
            "In strings.xml escape apostrophes as `\\'` or wrap the whole text in double quotes."
        },
        rule("resource.generic", "") {
            "Open the file at the shown line. AAPT2 stops at the first broken resource, so fixing this one may reveal the next."
        },
    )

    /** Android manifest messages. */
    val manifest: List<MessageRule> = listOf(
        rule("manifest.exported", "android:exported needs to be explicitly specified") {
            "Apps targeting Android 12 (API 31) or higher must say `android:exported=\"true\"` or `\"false\"` on every activity, service and receiver that has an intent filter."
        },
        rule("manifest.merge-conflict", "Attribute (\\S+) value=\\(.*?\\) from ", symbolGroup = 1) { m ->
            "The app and a library set the same manifest attribute to different values. Add `tools:replace=\"${m.groupValues[1].substringAfter('@').let { "android:$it" }}\"` " +
                "(and the `xmlns:tools=\"http://schemas.android.com/tools\"` namespace) on that element, or align the values."
        },
        rule("manifest.package-attr", "Setting the namespace via the package attribute|package attribute") {
            "Newer Android Gradle plugins read the app id from `namespace` in app/build.gradle(.kts). Remove `package=\"...\"` from AndroidManifest.xml and set `android { namespace = \"your.package\" }`."
        },
        rule("manifest.unexpected-element", "unexpected element|unexpected end tag|attribute .* not found|Unexpected element") {
            "The manifest contains an element or attribute in the wrong place. Check that it is nested inside the right parent (`<application>` for activities, `<manifest>` for permissions)."
        },
        rule("manifest.activity-missing", "does not exist|ClassNotFoundException|not found.*Activity") {
            "A class named in the manifest does not exist. Check the `android:name` (a leading `.` means relative to the namespace) and the class's package."
        },
        rule("manifest.generic", "") {
            "Open AndroidManifest.xml at the shown line. The manifest merger combines your manifest with those of the libraries; the full message names the library."
        },
    )

    /** Single-line messages from Gradle, the Android Gradle plugin and dependency resolution. */
    val gradle: List<MessageRule> = listOf(
        // ---- Android SDK / JDK ------------------------------------------------------------------------------
        rule("sdk.location", "SDK location not found", category = ErrorCategory.SDK_CONFIGURATION) {
            "Gradle cannot find the Android SDK. Set the `ANDROID_HOME` environment variable or `sdk.dir` in local.properties (never commit local.properties). On GitHub Actions use the `android-actions/setup-android` step or a runner image that includes the SDK."
        },
        rule("sdk.platform-missing", "Failed to find target with hash string 'android-([\\w.\\-]+)'", category = ErrorCategory.SDK_CONFIGURATION, symbolGroup = 1) { m ->
            "The SDK platform `android-${m.groupValues[1]}` is not installed. Install it (`sdkmanager \"platforms;android-${m.groupValues[1]}\"`), or lower `compileSdk` to an installed version."
        },
        rule("sdk.build-tools", "Failed to find Build Tools revision ([\\d.]+(?:-rc\\d+)?)", category = ErrorCategory.SDK_CONFIGURATION, symbolGroup = 1) { m ->
            "Build Tools ${m.groupValues[1]} is not installed. Install it (`sdkmanager \"build-tools;${m.groupValues[1]}\"`) or change/remove `buildToolsVersion` so the plugin picks its default."
        },
        rule("sdk.licenses", "licen[cs]es? .*not (?:been )?accepted|License for package .* not accepted|not accepted", category = ErrorCategory.SDK_CONFIGURATION) {
            "SDK licences have not been accepted. Run `yes | sdkmanager --licenses` (on GitHub Actions: `yes | sdkmanager --licenses` before the build)."
        },
        rule("sdk.agp-java", "Android Gradle plugin requires Java (\\d+) to run", category = ErrorCategory.SDK_CONFIGURATION, symbolGroup = 1) { m ->
            "This Android Gradle plugin needs JDK ${m.groupValues[1]} or newer to run Gradle. Select that JDK (on GitHub Actions: `actions/setup-java` with `java-version: ${m.groupValues[1]}`)."
        },
        rule("sdk.class-version", "Unsupported class file major version (\\d+)", category = ErrorCategory.SDK_CONFIGURATION, symbolGroup = 1) { m ->
            "The JDK running Gradle is newer than this Gradle version understands (class file version ${m.groupValues[1]}). Use a JDK that matches the Gradle version (17 works with Gradle 7.3+ and 8.x), or upgrade Gradle."
        },
        rule("sdk.compile-sdk-low", "Dependency '(.+?)' requires libraries and applications that depend on it to compile against version (\\d+) or later", category = ErrorCategory.SDK_CONFIGURATION, symbolGroup = 1) { m ->
            "A library needs `compileSdk` ${m.groupValues[2]} or higher. Raise `compileSdk` in app/build.gradle(.kts) (and make sure that platform is installed), or use an older version of `${m.groupValues[1]}`."
        },
        rule("sdk.min-sdk-lib", "uses-sdk:minSdkVersion (\\d+) cannot be smaller than version (\\d+) declared in library \\[(.+?)\\]", category = ErrorCategory.SDK_CONFIGURATION, symbolGroup = 3) { m ->
            "The app's `minSdk` is ${m.groupValues[1]} but `${m.groupValues[3]}` needs ${m.groupValues[2]}. Raise `minSdk` to ${m.groupValues[2]}, or use an older version of that library."
        },
        rule("gradle.min-version", "Minimum supported Gradle version is ([\\d.]+)\\. Current version is ([\\d.]+)", category = ErrorCategory.GRADLE, symbolGroup = 1) { m ->
            "The Android Gradle plugin needs Gradle ${m.groupValues[1]} or newer, but ${m.groupValues[2]} is used. Update `gradle/wrapper/gradle-wrapper.properties` (distributionUrl) or the Gradle version installed in the workflow."
        },
        // ---- Dependencies -----------------------------------------------------------------------------------
        rule("dependency.not-found", "Could not (?:resolve|find) ([\\w.\\-]+:[\\w.\\-]+(?::[\\w.\\-+\\[\\],()]+)?)", category = ErrorCategory.MISSING_DEPENDENCY, symbolGroup = 1) { m ->
            val coord = m.groupValues[1].trimEnd('.')
            "Gradle could not download `$coord`. Check the group:name:version for typos and that the version exists. " +
                (if (coord.startsWith("androidx.") || coord.startsWith("com.google.android") || coord.startsWith("com.android.")) {
                    "This one comes from Google's repository: make sure `google()` is in the `repositories` of settings.gradle(.kts) (dependencyResolutionManagement)."
                } else {
                    "Make sure the repository that hosts it (`mavenCentral()`, or a custom `maven { url = ... }`) is listed in settings.gradle(.kts)."
                })
        },
        rule("dependency.failed-resolve", "Failed to resolve: ([\\w.\\-]+:[\\w.\\-]+(?::[\\w.\\-+]+)?)", category = ErrorCategory.MISSING_DEPENDENCY, symbolGroup = 1) { m ->
            "`${m.groupValues[1].trimEnd('.')}` could not be resolved. Check the coordinate and version, and that its repository is configured."
        },
        rule("dependency.config-summary", "Could not resolve all (?:files|dependencies) for configuration '(.+?)'", category = ErrorCategory.MISSING_DEPENDENCY, summary = true, symbolGroup = 1) {
            "One or more dependencies of this configuration could not be resolved. The lines below this message name them."
        },
        rule("dependency.network", "Could not (?:GET|HEAD) '(.+?)'|Received status code (\\d{3}) from server|UnknownHostException|SSLHandshakeException|Connect to .* failed|Read timed out|Connection (?:reset|refused|timed out)", category = ErrorCategory.MISSING_DEPENDENCY, symbolGroup = 1) {
            "A download failed (network, firewall or the server). If it was temporary, run the build again. If it keeps failing, check that the repository URL is correct and reachable from the build machine."
        },
        rule("dependency.plugin-missing", "Plugin \\[id: '(.+?)'.*?\\] was not found in any of the following sources|Plugin with id '(.+?)' not found|Could not resolve plugin artifact '(.+?)'", category = ErrorCategory.MISSING_DEPENDENCY, symbolGroup = 1) {
            "A Gradle plugin could not be found. Check its id and version, and add `gradlePluginPortal()`, `google()` and `mavenCentral()` to `pluginManagement { repositories { } }` in settings.gradle(.kts)."
        },
        rule("dependency.duplicate-class", "Duplicate class (\\S+) found in modules (.+)", category = ErrorCategory.MISSING_DEPENDENCY, symbolGroup = 1) { m ->
            "The class `${m.groupValues[1]}` is contained in two libraries (${m.groupValues[2].take(160)}). Remove one of them, exclude the duplicate (`exclude(group = ..., module = ...)`), or align versions."
        },
        rule("dependency.no-variant", "No matching variant of|Unable to find a matching variant|Cannot choose between the following variants", category = ErrorCategory.MISSING_DEPENDENCY) {
            "A library has no variant that fits this app (wrong platform, build type or Java version). Check that it supports Android and the app's `minSdk`, and that a compatible version is used."
        },
        // ---- Gradle itself ----------------------------------------------------------------------------------
        rule("gradle.method-missing", "Could not find method (\\w+)\\(", category = ErrorCategory.GRADLE, symbolGroup = 1) { m ->
            "Gradle does not know `${m.groupValues[1]}(...)` in this place. Check the spelling, that the plugin that defines it is applied, and that it is in the right block."
        },
        rule("gradle.property-missing", "Could not get unknown property '(.+?)'", category = ErrorCategory.GRADLE, symbolGroup = 1) { m ->
            "The property `${m.groupValues[1]}` does not exist here. Check the spelling and that the plugin providing it is applied."
        },
        rule("gradle.namespace", "Namespace not specified", category = ErrorCategory.GRADLE) {
            "Add `namespace = \"your.package.name\"` inside the `android { }` block of the module's build.gradle(.kts)."
        },
        rule("gradle.jvm-target", "Inconsistent JVM-target compatibility", category = ErrorCategory.GRADLE) {
            "Java and Kotlin compile for different JVM versions. Set the same version for both: `compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }` and `kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }`."
        },
        rule("gradle.memory", "java\\.lang\\.OutOfMemoryError|Java heap space|GC overhead limit exceeded|Metaspace", category = ErrorCategory.GRADLE) {
            "Gradle ran out of memory. Raise it in gradle.properties, for example `org.gradle.jvmargs=-Xmx4g -XX:MaxMetaspaceSize=1g`."
        },
        rule("gradle.dex-limit", "Cannot fit requested classes in a single dex file", category = ErrorCategory.GRADLE) {
            "The app has too many methods for one dex file. Set a `minSdk` of 21 or higher (multidex is then automatic), enable code shrinking (R8), or remove unused libraries."
        },
        rule("gradle.wrapper-permission", "gradlew: (?:Permission denied|command not found|No such file)|Permission denied.*gradlew", category = ErrorCategory.GRADLE) {
            "The Gradle wrapper script is not executable (or missing). Run `chmod +x gradlew` in the workflow before building, and make sure gradlew and gradle/wrapper/* are committed."
        },
        rule("gradle.r8-missing", "Missing classes? detected while running R8|ERROR: R8: (.*)|R8: Missing class", category = ErrorCategory.GRADLE) {
            "R8 (code shrinking) found references to classes it cannot see. Add the keep/dontwarn rules it prints to proguard-rules.pro, or add the missing library."
        },
        rule("gradle.lint", "Lint found (?:errors|fatal errors)|lintVital", category = ErrorCategory.GRADLE) {
            "Android lint stopped the release build. Open the lint report named in the log, fix the errors, or (not recommended) set `lint { abortOnError = false }`."
        },
        rule("gradle.task-dependencies", "Could not determine the dependencies of task", category = ErrorCategory.GRADLE, summary = true) {
            "Gradle could not set up a task, usually because of the problem named on the next line."
        },
        rule("gradle.script-evaluating", "A problem occurred evaluating (?:root )?(?:project|settings) '?(.+?)'?\\.?\$", category = ErrorCategory.GRADLE, summary = true) {
            "A build script has an error. The line below this message says which one; open that file at the line shown."
        },
        // ---- Summaries that only repeat earlier errors ------------------------------------------------------
        rule("summary.kotlin-compile", "Compilation error\\. See log for more details", category = ErrorCategory.KOTLIN_COMPILATION, summary = true) {
            "The Kotlin compiler reported errors earlier in the log. Fix those first."
        },
        rule("summary.task-failed", "Execution failed for task '(.+?)'", category = ErrorCategory.GRADLE, summary = true, symbolGroup = 1) { m ->
            "The task `${m.groupValues[1]}` failed. The specific reason is listed separately; if none is, open the full log around this line."
        },
        rule("summary.resource-linking", "Android resource linking failed", category = ErrorCategory.RESOURCE_LINKING, summary = true) {
            "AAPT2 could not link the resources. The resource errors above/below name the file and line."
        },
        rule("summary.failed-linking", "^error: failed (?:linking references|processing manifest)", category = ErrorCategory.RESOURCE_LINKING, summary = true) {
            "AAPT2 gave up after the resource errors listed separately."
        },
        rule("summary.resource-compile", "Android resource compilation failed", category = ErrorCategory.RESOURCE_LINKING, summary = true) {
            "AAPT2 could not compile a resource file. The error naming the file is listed separately."
        },
        rule("summary.manifest-merger", "Manifest merger failed with multiple errors", category = ErrorCategory.MANIFEST, summary = true) {
            "The manifest merger failed; the individual manifest errors are listed separately."
        },
        rule("summary.exit-code", "Process completed with exit code (\\d+)", category = ErrorCategory.GRADLE, severity = Severity.INFO, summary = true) {
            "The step failed; the reason is in the errors above."
        },
        rule("gradle.deprecated-features", "Deprecated Gradle features were used", category = ErrorCategory.GRADLE, severity = Severity.WARNING) {
            "This build uses Gradle features that will stop working in a future Gradle version. Run with `--warning-mode all` to see which. It does not break the build today."
        },
        rule("gradle.agp-warning", "^WARNING: (.*)", category = ErrorCategory.GRADLE, severity = Severity.WARNING) {
            "A warning from the Android Gradle plugin; the build continues. Read the message for the option or property it names."
        },
    )

    /** Fallback text per category when no rule explains the message. */
    fun defaultSuggestion(category: ErrorCategory): String = when (category) {
        ErrorCategory.KOTLIN_COMPILATION -> "Open the file at the shown line. The compiler reports the first place where it could not continue; the cause is often on this line or just above."
        ErrorCategory.JAVA_COMPILATION -> "Open the file at the shown line. The caret (^) line in the log points at the exact spot."
        ErrorCategory.GRADLE -> "Read the message and the build file it names. Gradle messages usually state what is wrong and how to get more detail (`--stacktrace`, `--info`)."
        ErrorCategory.MISSING_DEPENDENCY -> "Check the dependency notation (group:name:version), the repositories in settings.gradle(.kts), and your network connection."
        ErrorCategory.MANIFEST -> "Open AndroidManifest.xml at the shown line and compare it with the message."
        ErrorCategory.RESOURCE_LINKING -> "Open the resource file at the shown line. Check names, references (@string/..., @drawable/...) and XML syntax."
        ErrorCategory.SDK_CONFIGURATION -> "Check compileSdk, minSdk, targetSdk, the installed SDK platforms/build tools and the JDK used by the build."
        ErrorCategory.CODE_QUALITY -> "Review the line; the check is a heuristic and can be wrong."
        ErrorCategory.RUNTIME_RISK -> "Review the line; this is a heuristic hint about a possible crash or freeze, not proof."
        ErrorCategory.OTHER -> "Read the message and open the full log around it."
    }

    fun firstMatch(rules: List<MessageRule>, message: String): Pair<MessageRule, MatchResult>? {
        for (r in rules) {
            val m = r.pattern.find(message) ?: continue
            return r to m
        }
        return null
    }
}
