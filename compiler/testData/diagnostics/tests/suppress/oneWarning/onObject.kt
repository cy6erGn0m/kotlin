suppress("REDUNDANT_NULLABLE")
object C {
    fun foo(): String?? = null <!USELESS_CAST!>as<!> Nothing??
}