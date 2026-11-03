package com.example.broken

fun greet(name: String): String {
    val unused = 42
    return "Hello " + name + undefinedHelper(name)
}
