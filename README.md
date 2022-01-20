# Java Macro Library

Unlike a traditional macro system which transform the source code at compile time,
this library provide building bricks to create macros at runtime with no modification to the Java syntax.

A macro is a method call that is able to extract/separate the constants arguments from
the other live arguments allowing to transform/pre-compute data from the constants.
This is close to the LISP way of doing macro by [quoting](https://en.wikipedia.org/wiki/Lisp_(programming_language)#Self-evaluating_forms_and_quoting)
(here consider as constant) arguments of a method call.

For example, instead of using reflection to dynamically calls methods
```java
public class Foo {
  public double bar(int value) { ... }
  public double baz(int value) { ... }
}

public static String call(Foo foo, String name, int value) {
  Method method = Foo.class.getMethod(name, int.class);
  return (double) method.invoke(foo, 3);  
}
```

one can use `Macro.createMH` to implement the same idea
```java
private static final MethodHandle MH;
static {
  Lookup lookup = MethodHandles.lookup();
  MH = Macro.createMH(MethodType.methodType(double.class, Foo.class, String.class, int.class),
      List.of(Macro.VALUE, Macro.CONSTANT_VALUE.polymorphic(), Macro.VALUE),
      (constants, type) -> {
        String name  = (String) constants.get(0);
        return lookup.findVirtual(Foo.class, name, MethodType.methodType(double.class, int.class)).asType(type);
      });
}

public static double call(Foo foo, String name, int value) {
  try {
    return (double) MH.invokeExact(foo, name, value);
  } catch(Throwable t) {
    throw Macro.rethrow(t);
  }
}
```

It's a lot of more code, but it's way faster because the VM is able to fully inline all the calls thanks to
the use of an inlining cache.

`Macro.createMH` takes 3 parameters:
- a method type which are the declared parameter types and the return type of the resulting method handle
- a list of `Parameter` that indicates if a parameter is a constant and how it behaves
  In the example above, the class of the second parameter is constant (`CONSTANT_VALUE`) and if there are more
  than one constant, a __polymorphic inlining cache__ is used. The first and last parameter as just value (VALUE)
  so will not be treated specially.
- a `Linker`, a lambda that takes a list of constants and a method type and returns a method handle of that type
  In the example above, the linker will be called at most twice, once per declared method.

There are 3 kinds of `Parameter`
- a `ConstantParameter` to extract a constant from it.
  The general form takes a projection function (`ProjectionFunction`) that is used to extract the constant from a value,
  a boolean that indicates if the value should be dropped or not and a `ConstantPolicy` that indicates
  how to react if there are several values for the constant (emit an error, re-link or construct a polymorphic
  inlining cache). There are two default implementation `Macro.CONSTANT_VALUE` if the value is itself the constant
  and `Macro.CONSTANT_CLASS` if the class of the value is the constant.
- an `IgnoreParameter` to ignore an argument.
  `Macro.IGNORE` is the singleton instance of an `IgnoreParameter`.
- and a `ValueParameter` to do nothing special on an argument.
  `Macro.VALUE` is the singleton instance of a `ValueParameter`.


### More examples

Several examples are available,
- A builder of record [builder1](src/main/examples/com/github.forax/macro/example/builder1.java),
  [builder2](src/main/examples/com/github.forax/macro/example/builder2.java) and
  [builder3](src/main/examples/com/github.forax/macro/example/builder3.java).
- A string formatter [fmt](src/main/examples/com/github.forax/macro/example/fmt.java) (like `String.format()`).
- A constant that can be changed [almostconstant](src/main/examples/com/github.forax/macro/example/almostconstant.java).

