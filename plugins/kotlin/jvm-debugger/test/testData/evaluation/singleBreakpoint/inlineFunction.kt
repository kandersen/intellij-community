
// FILE: inlineFunction.kt
package inlineFunction

import inlineFunctionOtherPackage.*

fun main(args: Array<String>) {
    //Breakpoint!
    val a = 1
}

inline fun foo() = 1

// EXPRESSION: myFun { 1 }
// RESULT: 3: I

// EXPRESSION: foo()
// RESULT: 1: I

// FILE: lib.kt
package inlineFunctionOtherPackage

inline fun myFun(f: () -> Int): Int = f() + secondUnrelatedLib.x.length

val String.prop: String
    get() {
        return secondUnrelatedLib.x
    }


// FILE: secondUnrelatedLib.kt
package secondUnrelatedLib

var x: String = "a"
    set(value) {
        field += value
    }
    get() {
        return field + "!"
    }

