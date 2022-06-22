

// MODULE: common
// PLATFORM: common
// FILE: common.kt

fun unusedFunction() = 5

fun test() {
    val x = 1
    fun bar(): Int {
        val y = x
        return y
    }
    val c = C()

    //Breakpoint1
    val a = 0
}

class C {
    val local: String = ""
}

// ADDITIONAL_BREAKPOINT: common.kt / Breakpoint1 / line / 1

// EXPRESSION: c.local
// RESULT: "": Ljava/lang/String;

// FILE: jvm.kt


// MODULE: jvm
// PLATFORM: jvm
// DEPENDS_ON: common

// FILE: test.kt

fun unusedFunctionInJVMModuke() = 54


fun main() {
    test()
}
