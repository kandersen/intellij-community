// FILE: common.kt
package ktij21854

fun test() {
    val x = 1
    fun bar(): Int {
        val y = x
        return y
    }
    val c = C()

    val a = 0
}

class C {
    val local: String = ""
}

// FILE: test.kt
package ktij21854
fun main() {
    //Breakpoint!
    test()
}

// STEP_INTO: 1
// STEP_OVER: 2

// EXPRESSION: c.local
// RESULT: "": Ljava/lang/String;
