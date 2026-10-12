# Neko language

Neko is a JVM language that a toy language with experimental.

## Syntax

### Class

```neko
class Neko() {
}
```

### Function (Method)

Member function:
```neko
class Neko() {
  fun test() {
  }
}
```

Static function:
```neko
class Neko() {
  static {
    fun testStatic() {
    }
  }
}
```

### Error control

Emit and raise:
```neko
class Neko() {
  fun invoker() {
    // Must use try to call a emitted function.
    try test() { emit1, emit2 ->
      println(emit1 * emit2)
    }
  }
  
  fun inheritEmit() {
    // When function signature has correct 
    // emitted like calling function,
    // Then don't need to try.
    test()
  } emit [int, int]
  
  fun test() {
    raise 123, 456
  } emit [int, int]
}
```

Emit and raise with returning value:
```neko
class Neko() {
  fun invoker() {
    // Use a variable to receive it return value.
    result: int = try test(true) { emit1, emit2 ->
      println(emit1 * emit2)
      // If raised, return this.
      yield emit1 * emit2
    }
  }
  
  fun test(flag: boolean): int {
    if (flag) {
      raise 123, 456
    } else {
      return 0
    }
  } emit [int, int]
}
```