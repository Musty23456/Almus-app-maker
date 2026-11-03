package com.almus.appcreatorpro.debug

/** Name tables used by the static checks. Deliberately small: a name that is not here is never reported as "missing". */
internal object KnownSymbols {

    /** Simple class name -> possible fully qualified names (needs an import). */
    val importable: Map<String, List<String>> = mapOf(
        // Android framework
        "Log" to listOf("android.util.Log"),
        "Toast" to listOf("android.widget.Toast"),
        "Context" to listOf("android.content.Context"),
        "Intent" to listOf("android.content.Intent"),
        "Bundle" to listOf("android.os.Bundle"),
        "Handler" to listOf("android.os.Handler"),
        "Looper" to listOf("android.os.Looper"),
        "SharedPreferences" to listOf("android.content.SharedPreferences"),
        "Uri" to listOf("android.net.Uri"),
        "View" to listOf("android.view.View"),
        "ViewGroup" to listOf("android.view.ViewGroup"),
        "LayoutInflater" to listOf("android.view.LayoutInflater"),
        "TextView" to listOf("android.widget.TextView"),
        "EditText" to listOf("android.widget.EditText"),
        "ImageView" to listOf("android.widget.ImageView"),
        "LinearLayout" to listOf("android.widget.LinearLayout"),
        "FrameLayout" to listOf("android.widget.FrameLayout"),
        "ProgressBar" to listOf("android.widget.ProgressBar"),
        "Button" to listOf("android.widget.Button", "androidx.compose.material3.Button"),
        "Activity" to listOf("android.app.Activity"),
        "Application" to listOf("android.app.Application"),
        "Manifest" to listOf("android.Manifest"),
        "Build" to listOf("android.os.Build"),
        "Color" to listOf("android.graphics.Color", "androidx.compose.ui.graphics.Color"),
        "Bitmap" to listOf("android.graphics.Bitmap"),
        // AndroidX
        "AppCompatActivity" to listOf("androidx.appcompat.app.AppCompatActivity"),
        "ComponentActivity" to listOf("androidx.activity.ComponentActivity"),
        "Fragment" to listOf("androidx.fragment.app.Fragment"),
        "RecyclerView" to listOf("androidx.recyclerview.widget.RecyclerView"),
        "ViewModel" to listOf("androidx.lifecycle.ViewModel"),
        "LiveData" to listOf("androidx.lifecycle.LiveData"),
        "MutableLiveData" to listOf("androidx.lifecycle.MutableLiveData"),
        "NonNull" to listOf("androidx.annotation.NonNull"),
        "Nullable" to listOf("androidx.annotation.Nullable"),
        // Compose
        "Composable" to listOf("androidx.compose.runtime.Composable"),
        "Modifier" to listOf("androidx.compose.ui.Modifier"),
        "Text" to listOf("androidx.compose.material3.Text"),
        "Scaffold" to listOf("androidx.compose.material3.Scaffold"),
        "MaterialTheme" to listOf("androidx.compose.material3.MaterialTheme"),
        "Column" to listOf("androidx.compose.foundation.layout.Column"),
        "Row" to listOf("androidx.compose.foundation.layout.Row"),
        "Box" to listOf("androidx.compose.foundation.layout.Box"),
        "Spacer" to listOf("androidx.compose.foundation.layout.Spacer"),
        "Arrangement" to listOf("androidx.compose.foundation.layout.Arrangement"),
        "Alignment" to listOf("androidx.compose.ui.Alignment"),
        "LazyColumn" to listOf("androidx.compose.foundation.lazy.LazyColumn"),
        // Coroutines
        "CoroutineScope" to listOf("kotlinx.coroutines.CoroutineScope"),
        "Dispatchers" to listOf("kotlinx.coroutines.Dispatchers"),
        "Job" to listOf("kotlinx.coroutines.Job"),
        "Deferred" to listOf("kotlinx.coroutines.Deferred"),
        "GlobalScope" to listOf("kotlinx.coroutines.GlobalScope"),
        "StateFlow" to listOf("kotlinx.coroutines.flow.StateFlow"),
        "MutableStateFlow" to listOf("kotlinx.coroutines.flow.MutableStateFlow"),
        "Flow" to listOf("kotlinx.coroutines.flow.Flow"),
        // JDK
        "Date" to listOf("java.util.Date"),
        "Locale" to listOf("java.util.Locale"),
        "Random" to listOf("java.util.Random"),
        "UUID" to listOf("java.util.UUID"),
        "Arrays" to listOf("java.util.Arrays"),
        "Collections" to listOf("java.util.Collections"),
        "Objects" to listOf("java.util.Objects"),
        "Optional" to listOf("java.util.Optional"),
        "Calendar" to listOf("java.util.Calendar"),
        "SimpleDateFormat" to listOf("java.text.SimpleDateFormat"),
        "File" to listOf("java.io.File"),
        "IOException" to listOf("java.io.IOException"),
        "InputStream" to listOf("java.io.InputStream"),
        "OutputStream" to listOf("java.io.OutputStream"),
        "BufferedReader" to listOf("java.io.BufferedReader"),
        "InputStreamReader" to listOf("java.io.InputStreamReader"),
        "URL" to listOf("java.net.URL"),
        "HttpURLConnection" to listOf("java.net.HttpURLConnection"),
        "Executors" to listOf("java.util.concurrent.Executors"),
        "ExecutorService" to listOf("java.util.concurrent.ExecutorService"),
        "TimeUnit" to listOf("java.util.concurrent.TimeUnit"),
        "AtomicInteger" to listOf("java.util.concurrent.atomic.AtomicInteger"),
        "ConcurrentHashMap" to listOf("java.util.concurrent.ConcurrentHashMap"),
    )

    /** Names that need an import in Java only (Kotlin has them in its default imports). */
    val javaOnlyImportable: Map<String, String> = mapOf(
        "List" to "java.util.List",
        "ArrayList" to "java.util.ArrayList",
        "Map" to "java.util.Map",
        "HashMap" to "java.util.HashMap",
        "Set" to "java.util.Set",
        "HashSet" to "java.util.HashSet",
        "LinkedList" to "java.util.LinkedList",
        "Iterator" to "java.util.Iterator",
        "Collection" to "java.util.Collection",
    )

    /** Available without an import in Kotlin. */
    val kotlinDefault: Set<String> = setOf(
        "String", "Int", "Long", "Double", "Float", "Boolean", "Char", "Byte", "Short", "Any", "Unit", "Nothing", "Array",
        "List", "MutableList", "Map", "MutableMap", "Set", "MutableSet", "Collection", "MutableCollection", "Iterable",
        "MutableIterable", "Iterator", "MutableIterator", "ListIterator", "Pair", "Triple", "Result", "Regex", "StringBuilder",
        "Exception", "RuntimeException", "IllegalArgumentException", "IllegalStateException", "NullPointerException",
        "UnsupportedOperationException", "IndexOutOfBoundsException", "ArithmeticException", "NumberFormatException",
        "ClassCastException", "ConcurrentModificationException", "NoSuchElementException", "AssertionError", "Throwable", "Error",
        "Thread", "Runnable", "Math", "System", "Number", "Comparable", "Comparator", "Sequence", "Lazy", "Deprecated", "Suppress",
        "JvmStatic", "JvmField", "JvmOverloads", "JvmName", "Volatile", "Synchronized", "Transient", "Throws", "ArrayList", "HashMap",
        "HashSet", "LinkedHashMap", "LinkedHashSet", "Class", "Object", "Enum", "IntArray", "LongArray", "DoubleArray",
        "FloatArray", "BooleanArray", "CharArray", "ByteArray", "ShortArray", "Annotation", "CharSequence", "Appendable",
        "AutoCloseable", "Closeable", "Cloneable", "Void", "Process", "StackTraceElement", "ClosedRange", "IntRange", "LongRange",
        "CharRange", "IntProgression", "Function", "Override", "SuppressWarnings", "Short", "UInt", "ULong", "UByte", "UShort",
        "Char", "OptIn", "RequiresOptIn", "ExperimentalStdlibApi", "Repeatable", "MustBeDocumented", "Target", "Retention",
        "StringBuffer", "Character", "Integer", "Iterator", "Boolean", "Exception", "Appendable", "Readable", "InterruptedException",
        "OutOfMemoryError", "StackOverflowError", "SecurityException", "ReflectiveOperationException", "ClassNotFoundException",
        "ThreadLocal", "InheritableThreadLocal", "NoSuchFieldException", "NoSuchMethodException", "CloneNotSupportedException",
        "Charsets", "RegexOption", "MatchResult", "MatchGroup", "Typography", "CharCategory", "DurationUnit", "Duration", "Random", "Volatile",
        "ArrayDeque", "ExperimentalContracts", "NoSuchElementException", "UninitializedPropertyAccessException", "TypeCastException", "KotlinNullPointerException",
    )

    /** Available without an import in Java (java.lang). */
    val javaDefault: Set<String> = setOf(
        "String", "Integer", "Long", "Double", "Float", "Boolean", "Character", "Byte", "Short", "Object", "Math", "System",
        "Thread", "Runnable", "Exception", "RuntimeException", "Error", "Throwable", "StringBuilder", "StringBuffer", "Class",
        "Number", "Iterable", "Comparable", "CharSequence", "Override", "Deprecated", "SuppressWarnings", "FunctionalInterface",
        "SafeVarargs", "Void", "Enum", "Record", "AutoCloseable", "Cloneable", "Process", "ProcessBuilder", "Runtime",
        "IllegalArgumentException", "IllegalStateException", "NullPointerException", "UnsupportedOperationException",
        "IndexOutOfBoundsException", "ArrayIndexOutOfBoundsException", "ArithmeticException", "NumberFormatException",
        "ClassCastException", "InterruptedException", "AssertionError", "OutOfMemoryError", "StackOverflowError",
        "SecurityException", "ClassNotFoundException", "ThreadLocal", "StackTraceElement", "NoSuchFieldException",
        "NoSuchMethodException", "CloneNotSupportedException", "ReflectiveOperationException", "ArrayStoreException",
        "NegativeArraySizeException", "Package", "ClassLoader", "StrictMath", "InheritableThreadLocal", "Appendable", "Readable",
    )

    val kotlinKeywords: Set<String> = setOf(
        "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in", "interface", "is", "null", "object",
        "package", "return", "super", "this", "throw", "true", "try", "typealias", "val", "var", "when", "while", "by", "catch",
        "constructor", "delegate", "dynamic", "field", "file", "finally", "get", "import", "init", "param", "property", "receiver",
        "set", "setparam", "value", "where", "abstract", "actual", "annotation", "companion", "const", "crossinline", "data",
        "enum", "expect", "external", "final", "infix", "inline", "inner", "internal", "lateinit", "noinline", "open", "operator",
        "out", "override", "private", "protected", "public", "reified", "sealed", "suspend", "tailrec", "vararg", "it",
    )

    /** Top-level Kotlin functions and values that are available everywhere. */
    val kotlinFunctions: Set<String> = setOf(
        "println", "print", "readLine", "readln", "listOf", "mutableListOf", "arrayListOf", "emptyList", "listOfNotNull", "mapOf",
        "mutableMapOf", "hashMapOf", "linkedMapOf", "emptyMap", "setOf", "mutableSetOf", "hashSetOf", "linkedSetOf", "emptySet",
        "arrayOf", "byteArrayOf", "shortArrayOf", "floatArrayOf", "ubyteArrayOf", "intArrayOf", "longArrayOf", "doubleArrayOf", "booleanArrayOf", "charArrayOf", "arrayOfNulls", "emptyArray",
        "require", "requireNotNull", "check", "checkNotNull", "error", "assert", "TODO", "lazy", "repeat", "run", "with", "apply",
        "also", "let", "takeIf", "takeUnless", "maxOf", "minOf", "buildString", "buildList", "buildMap", "buildSet", "sequence",
        "sequenceOf", "generateSequence", "synchronized", "lazyOf", "to", "mutableListOf", "enumValues", "enumValueOf", "typeOf",
        "emptySequence", "pairOf", "runCatching", "use", "measureTimeMillis", "measureNanoTime", "main", "args", "toString",
        "hashCode", "equals", "javaClass", "until", "downTo", "step", "shl", "shr", "ushr", "and", "or", "xor", "inv", "zip", "then", "matches", "kotlin", "java", "javax", "android", "androidx", "com", "org", "kotlinx",
    )

    val operatorNames: Set<String> = setOf(
        "getValue", "setValue", "provideDelegate", "invoke", "plus", "minus", "times", "div", "rem", "mod", "compareTo", "contains",
        "iterator", "get", "set", "component1", "component2", "component3", "component4", "unaryMinus", "unaryPlus", "not", "inc",
        "dec", "rangeTo", "equals", "hasNext", "next", "plusAssign", "minusAssign", "timesAssign", "divAssign",
    )

    /** Library resource name prefixes: never reported as missing, because they come from libraries not visible here. */
    val libraryResourcePrefixes: List<String> = listOf(
        "abc_", "m3_", "material_", "mtrl_", "design_", "cardview_", "appcompat_", "androidx_", "browser_", "notification_",
        "ripple_", "tooltip_", "common_google", "Theme.", "ThemeOverlay.", "Widget.", "TextAppearance.", "Base.", "Platform.",
        "Animation.", "ShapeAppearance", "MaterialAlertDialog", "Preference", "Test.", "RtlOverlay.",
    )
}
