

// MODULE: common
// FILE: common.kt
// PLATFORM: common
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

// MODULE: jvm
// FILE: jvm.kt
// PLATFORM: jvm
fun main() {
    test()
}
