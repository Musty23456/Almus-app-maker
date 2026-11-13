package com.almus.appcreatorpro.git

/** Reads Git conflict markers (`<<<<<<<`, `=======`, `>>>>>>>`) in a text file. */
object GitConflicts {
    private val start = Regex("""^<{7}( .*)?$""")
    private val end = Regex("""^>{7}( .*)?$""")

    /** Number of conflict regions (a region starts with <<<<<<< and ends with >>>>>>>). */
    fun countRegions(text: String): Int {
        var open = false
        var regions = 0
        for (line in text.lineSequence()) {
            val l = line.trimEnd('\r')
            if (!open && start.matches(l)) open = true
            else if (open && end.matches(l)) { open = false; regions++ }
        }
        return regions + if (open) 1 else 0
    }

    fun hasMarkers(text: String): Boolean = countRegions(text) > 0
}
