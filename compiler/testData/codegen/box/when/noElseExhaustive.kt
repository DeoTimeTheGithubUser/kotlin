// WITH_STDLIB
enum class En {
    A,
    B
}

fun box(): String = when(En.A) {
    En.A -> "OK"
    En.B -> "Fail 1"
}