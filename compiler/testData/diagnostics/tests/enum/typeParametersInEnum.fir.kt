// KT-5696 Prohibit type parameters for enum classes

package bug

public enum class Foo<T> {
    A()
}
