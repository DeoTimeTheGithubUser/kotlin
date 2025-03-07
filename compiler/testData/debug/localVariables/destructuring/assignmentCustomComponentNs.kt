// IGNORE_BACKEND_K2_LIGHT_TREE: JVM_IR
//   Reason: KT-56755
// FILE: test.kt
class MyPair(val x: String, val y: String) {
    operator fun component1(): String {
        return "O"
    }

    operator fun component2(): String {
        return "K"
    }
}

fun box(): String {
    val p = MyPair("X", "Y")
    val (o, k) = p
    return o + k
}

// EXPECTATIONS JVM JVM_IR
// test.kt:15 box:
// test.kt:4 <init>: x:java.lang.String="X":java.lang.String, y:java.lang.String="Y":java.lang.String
// test.kt:15 box:
// test.kt:16 box: p:MyPair=MyPair
// test.kt:6 component1:
// test.kt:16 box: p:MyPair=MyPair
// test.kt:10 component2:
// EXPECTATIONS JVM
// test.kt:16 box: p:MyPair=MyPair
// EXPECTATIONS JVM_IR
// test.kt:16 box: p:MyPair=MyPair, o:java.lang.String="O":java.lang.String
// EXPECTATIONS JVM JVM_IR
// test.kt:17 box: p:MyPair=MyPair, o:java.lang.String="O":java.lang.String, k:java.lang.String="K":java.lang.String

// EXPECTATIONS JS_IR
// test.kt:15 box:
// test.kt:4 <init>: x="X":kotlin.String, y="Y":kotlin.String
// test.kt:4 <init>: x="X":kotlin.String, y="Y":kotlin.String
// test.kt:4 <init>: x="X":kotlin.String, y="Y":kotlin.String
// test.kt:16 box: p=MyPair
// test.kt:16 box: p=MyPair
// test.kt:6 component1:
// test.kt:16 box: p=MyPair, o="O":kotlin.String
// test.kt:10 component2:
// test.kt:17 box: p=MyPair, o="O":kotlin.String, k="K":kotlin.String
