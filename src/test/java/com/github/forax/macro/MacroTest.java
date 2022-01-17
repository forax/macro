package com.github.forax.macro;

import com.github.forax.macro.Macro.Linker;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.MethodType;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.github.forax.macro.MacroParameter.CONSTANT_CLASS;
import static com.github.forax.macro.MacroParameter.CONSTANT_VALUE;
import static com.github.forax.macro.MacroParameter.VALUE;
import static java.lang.invoke.MethodType.methodType;
import static org.junit.jupiter.api.Assertions.*;

public class MacroTest {
  @Test
  public void simple() throws Throwable {
    class Foo {
      public double bar(int value) { return 1.0; }
      public double baz(int value) { return 2.0; }
    }

    class Example {
      private static final MethodHandle MH;
      static {
        Lookup lookup = MethodHandles.lookup();
        MH = Macro.createMH(MethodType.methodType(double.class, Foo.class, String.class, int.class),
            List.of(MacroParameter.VALUE, MacroParameter.CONSTANT_VALUE.polymorphic(), MacroParameter.VALUE),
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
    }

    Foo foo = new Foo();
    assertEquals(1.0, Example.call(foo, "bar", 42));
    assertEquals(1.0, Example.call(foo, "bar", 42));
    assertEquals(2.0, Example.call(foo, "baz", 42));
    assertEquals(2.0, Example.call(foo, "baz", 42));
  }

  @Test
  public void pattern() {
    interface PatternFactory {
      Pattern pattern(String pattern);

      static PatternFactory of() {
        record PatternFactoryImpl(MethodHandle mh) implements PatternFactory {
          @Override
          public Pattern pattern(String pattern) {
            try {
              return (Pattern) mh.invokeExact(pattern);
            } catch(Throwable t) {
              throw Macro.rethrow(t);
            }
          }
        }

        var mh = Macro.createMH(methodType(Pattern.class, String.class),
            List.of(CONSTANT_VALUE),
            (constants, type) -> MethodHandles.constant(Pattern.class, Pattern.compile((String) constants.get(0))));
        return new PatternFactoryImpl(mh);
      }
    }

    var factory = PatternFactory.of();
    var pattern1 = factory.pattern("foo");
    var pattern2 = factory.pattern("foo");
    assertAll(
        () -> assertSame(pattern1, pattern2),
        () -> assertThrows(IllegalStateException.class, () -> factory.pattern("bar"))
    );
  }

  @Test
  public void dynamicDispatch() {
    interface Dispatch {
      <T> T call(Object receiver, String name, MethodType methodType, Object... args);

      static Dispatch of(Lookup lookup) {
        record DispatchImpl(MethodHandle mh) implements Dispatch {
          @Override
          @SuppressWarnings("unchecked")
          public <T> T call(Object receiver, String name, MethodType methodType, Object... args) {
            try {
              return (T) mh.invokeExact(receiver, name, methodType, args);
            } catch(Throwable t) {
              throw Macro.rethrow(t);
            }
          }
        }

        var mh = Macro.createMH(methodType(Object.class, Object.class, String.class, MethodType.class, Object[].class),
            List.of(CONSTANT_CLASS.polymorphic(), CONSTANT_VALUE, CONSTANT_VALUE, VALUE),
            (constants, type) -> {
              var receiverClass = (Class<?>) constants.get(0);
              var name = (String) constants.get(1);
              var methodType = (MethodType) constants.get(2);
              return lookup.findVirtual(receiverClass, name, methodType)
                  .asSpreader(Object[].class, methodType.parameterCount())
                  .asType(type);
            });
        return new DispatchImpl(mh);
      }
    }

    record A() {
      String m(long value) { return "A"; }
    }
    record B() {
      String m(long value) { return "B"; }
    }

    var dispatch = Dispatch.of(MethodHandles.lookup());
    assertEquals("A", dispatch.call(new A(), "m", methodType(String.class, long.class), 3L));
    assertEquals("B", dispatch.call(new B(), "m", methodType(String.class, long.class), 4L));
    assertEquals("A", dispatch.call(new A(), "m", methodType(String.class, long.class), 5L));
    assertEquals("B", dispatch.call(new B(), "m", methodType(String.class, long.class), 6L));
  }

  @Test
  public void visitorDispatch() {
    interface VisitorCaller<R> {
      R call(Object visitor, Object object);

      static <R> VisitorCaller<R> of(Lookup lookup, Class<?> visitorClass, Class<R> returnType) {
        record VisitorCallerImpl<R>(MethodHandle mh) implements VisitorCaller<R> {
          @Override
          @SuppressWarnings("unchecked")
          public R call(Object visitor, Object element) {
            try {
              return (R) mh.invokeExact(visitor, element);
            } catch(Throwable t) {
              throw Macro.rethrow(t);
            }
          }
        }

        var mh = Macro.createMH(methodType(returnType, visitorClass, Object.class),
            List.of(CONSTANT_CLASS.relink(), CONSTANT_CLASS.polymorphic()),
            (constants, type) -> {
               var visitorType = (Class<?>) constants.get(0);
               var elementType = (Class<?>) constants.get(1);
               return lookup.findVirtual(visitorType, "visit", methodType(returnType, elementType)).asType(type);
            })
            .asType(methodType(Object.class, Object.class, Object.class));
        return new VisitorCallerImpl<>(mh);
      }
    }

    interface Vehicle {}
    record Car() implements Vehicle {}
    record Bus() implements Vehicle {}
    interface Visitor<R> {
      R visit(Car car);
      R visit(Bus bus);
    }
    var visitor = new Visitor<String>() {
      @Override
      public String visit(Car car) {
        return "Car";
      }
      @Override
      public String visit(Bus bus) {
        return "Bus";
      }
    };

    var caller = VisitorCaller.of(MethodHandles.lookup(), visitor.getClass(), String.class);
    assertEquals("Car", caller.call(visitor, new Car()));
    assertEquals("Bus", caller.call(visitor, new Bus()));
    assertEquals("Car", caller.call(visitor, new Car()));
    assertEquals("Bus", caller.call(visitor, new Bus()));
  }

  @Test
  public void builder() {
    interface Builder<T> {
      T build(String field1, Object val1);
      T build(String field1, Object val1, String field2, Object val2);
      T build(String field1, Object val1, String field2, Object val2, String field3, Object val3);
      T build(String field1, Object val1, String field2, Object val2, String field3, Object val3, String field4, Object val4);

      private static MethodType signature(int fieldCount) {
        var parameterTypes = Stream.concat(
              IntStream.range(0, fieldCount).mapToObj(__ -> String.class), IntStream.range(0, fieldCount).mapToObj(__ -> Object.class)
            ).toList();
        return MethodType.methodType(Object.class, parameterTypes);
      }

      private static List<MacroParameter> parameters(int fieldCount) {
        return Stream.concat(
              IntStream.range(0, fieldCount).mapToObj(__ -> CONSTANT_VALUE.polymorphic()), IntStream.range(0, fieldCount).mapToObj(__ -> VALUE)
            ).toList();
      }

      static <T extends Record> Builder<T> of(Lookup lookup, Class<T> recordType) {
        record BuilderImpl<T>(MethodHandle mh1, MethodHandle mh2, MethodHandle mh3, MethodHandle mh4) implements Builder<T> {
          @Override
          @SuppressWarnings("unchecked")
          public T build(String field1, Object val1) {
            try {
              return (T) mh1.invokeExact(field1, val1);
            } catch (Throwable t) {
              throw Macro.rethrow(t);
            }
          }

          @Override
          @SuppressWarnings("unchecked")
          public T build(String field1, Object val1, String field2, Object val2) {
            try {
              return (T) mh2.invokeExact(field1, field2, val1, val2);
            } catch (Throwable t) {
              throw Macro.rethrow(t);
            }
          }

          @Override
          @SuppressWarnings("unchecked")
          public T build(String field1, Object val1, String field2, Object val2, String field3, Object val3) {
            try {
              return (T) mh3.invokeExact(field1, field2, field3, val1, val2, val3);
            } catch (Throwable t) {
              throw Macro.rethrow(t);
            }
          }

          @Override
          @SuppressWarnings("unchecked")
          public T build(String field1, Object val1, String field2, Object val2, String field3, Object val3, String field4, Object val4) {
            try {
              return (T) mh4.invokeExact(field1, field2, field3, field4, val1, val2, val3, val4);
            } catch (Throwable t) {
              throw Macro.rethrow(t);
            }
          }
        }

        var components = recordType.getRecordComponents();
        var componentTypeMap = Arrays.stream(components)
            .collect(Collectors.toMap(RecordComponent::getName, RecordComponent::getType));
        MethodHandle constructor;
        try {
          constructor = lookup.findConstructor(recordType,
              methodType(void.class, Arrays.stream(components).map(RecordComponent::getType).toArray(Class[]::new)));
        } catch (NoSuchMethodException e) {
          throw (NoSuchMethodError) new NoSuchMethodError().initCause(e);
        } catch (IllegalAccessException e) {
          throw (IllegalAccessError) new IllegalAccessError().initCause(e);
        }
        var linker = (Linker) (constants, methodType) -> {
          if (!componentTypeMap.keySet().equals(new HashSet<>(constants))) {
            throw new IllegalStateException("wrong component names " + componentTypeMap.keySet() + " but was " + constants);
          }
          var orderMap = IntStream.range(0, constants.size()).boxed()
              .collect(Collectors.toMap(i -> (String) constants.get(i), i -> i));
          var reorder = Arrays.stream(components)
              .mapToInt(component -> orderMap.get(component.getName()))
              .toArray();
          var newMethodType = methodType(recordType, constants.stream()
              .map(constant -> componentTypeMap.get((String) constant))
              .toArray(Class[]::new));
          return MethodHandles.permuteArguments(constructor, newMethodType, reorder)
              .asType(methodType);
        };
        var mh1 = Macro.createMH(signature(1), parameters(1), linker);
        var mh2 = Macro.createMH(signature(2), parameters(2), linker);
        var mh3 = Macro.createMH(signature(3), parameters(3), linker);
        var mh4 = Macro.createMH(signature(4), parameters(4), linker);
        return new BuilderImpl<>(mh1, mh2, mh3, mh4);
      }
    }

    record Foo(int value, String text) {}

    var builder = Builder.of(MethodHandles.lookup(), Foo.class);
    var foo1 = builder.build("text", "hello", "value", 42);
    var foo2 = builder.build("value", 42, "text", "hello");
    assertEquals(new Foo(42, "hello"), foo1);
    assertEquals(new Foo(42, "hello"), foo2);
  }

  @Test
  public void builder2() {
    interface Builder<T> {
      @FunctionalInterface
      interface Accessor<T, V> extends Serializable {
        V apply(T record);
      }

      <V1> T build(Accessor<T, V1> field1, V1 val1);
      <V1, V2> T build(Accessor<T, V1> field1, V1 val1, Accessor<T, V2> field2, V2 val2);
      <V1, V2, V3> T build(Accessor<T, V1> field1, V1 val1, Accessor<T, V2> field2, V2 val2, Accessor<T, V3> field3, V3 val3);
      <V1, V2, V3, V4> T build(Accessor<T, V1> field1, V1 val1, Accessor<T, V2> field2, V2 val2, Accessor<T, V3> field3, V3 val3, Accessor<T, V4> field4, V4 val4);

      private static MethodType signature(int fieldCount) {
        var parameterTypes = Stream.concat(
            IntStream.range(0, fieldCount).mapToObj(__ -> Accessor.class), IntStream.range(0, fieldCount).mapToObj(__ -> Object.class)
        ).toList();
        return MethodType.methodType(Object.class, parameterTypes);
      }

      private static List<MacroParameter> parameters(int fieldCount) {
        return Stream.concat(
            IntStream.range(0, fieldCount).mapToObj(__ -> CONSTANT_VALUE.polymorphic()), IntStream.range(0, fieldCount).mapToObj(__ -> VALUE)
        ).toList();
      }

      static <T extends Record> Builder<T> of(Lookup lookup, Class<T> recordType) {
        record BuilderImpl<T>(MethodHandle mh1, MethodHandle mh2, MethodHandle mh3, MethodHandle mh4) implements Builder<T> {
          @Override
          @SuppressWarnings("unchecked")
          public <V1> T build(Accessor<T, V1> field1, V1 val1) {
            try {
              return (T) mh1.invokeExact(field1, val1);
            } catch (Throwable t) {
              throw Macro.rethrow(t);
            }
          }

          @Override
          @SuppressWarnings("unchecked")
          public <V1, V2> T build(Accessor<T, V1> field1, V1 val1, Accessor<T, V2> field2, V2 val2) {
            try {
              return (T) mh2.invokeExact(field1, field2, val1, val2);
            } catch (Throwable t) {
              throw Macro.rethrow(t);
            }
          }

          @Override
          @SuppressWarnings("unchecked")
          public <V1, V2, V3> T build(Accessor<T, V1> field1, V1 val1, Accessor<T, V2> field2, V2 val2, Accessor<T, V3> field3, V3 val3) {
            try {
              return (T) mh3.invokeExact(field1, field2, field3, val1, val2, val3);
            } catch (Throwable t) {
              throw Macro.rethrow(t);
            }
          }

          @Override
          @SuppressWarnings("unchecked")
          public <V1, V2, V3, V4> T build(Accessor<T, V1> field1, V1 val1, Accessor<T, V2> field2, V2 val2, Accessor<T, V3> field3, V3 val3, Accessor<T, V4> field4, V4 val4) {
            try {
              return (T) mh4.invokeExact(field1, field2, field3, field4, val1, val2, val3, val4);
            } catch (Throwable t) {
              throw Macro.rethrow(t);
            }
          }
        }

        var components = recordType.getRecordComponents();
        var componentTypeMap = Arrays.stream(components)
            .collect(Collectors.toMap(RecordComponent::getName, RecordComponent::getType));
        MethodHandle constructor;
        try {
          constructor = lookup.findConstructor(recordType,
              methodType(void.class, Arrays.stream(components).map(RecordComponent::getType).toArray(Class[]::new)));
        } catch (NoSuchMethodException e) {
          throw (NoSuchMethodError) new NoSuchMethodError().initCause(e);
        } catch (IllegalAccessException e) {
          throw (IllegalAccessError) new IllegalAccessError().initCause(e);
        }

        var linker = (Linker) (constants, methodType) -> {
          var methodNames = constants.stream()
              .map(lambda -> Macro.crack(lookup, lambda).getImplMethodName())
              .toList();
          if (!componentTypeMap.keySet().equals(new HashSet<>(methodNames))) {
            throw new IllegalStateException("wrong component names " + componentTypeMap.keySet() + " but was " + methodNames);
          }
          var orderMap = IntStream.range(0, methodNames.size()).boxed()
              .collect(Collectors.toMap(methodNames::get, i -> i));
          var reorder = Arrays.stream(components)
              .mapToInt(component -> orderMap.get(component.getName()))
              .toArray();
          var newMethodType = methodType(recordType, methodNames.stream()
              .map(componentTypeMap::get)
              .toArray(Class[]::new));
          return MethodHandles.permuteArguments(constructor, newMethodType, reorder)
              .asType(methodType);
        };
        var mh1 = Macro.createMH(signature(1), parameters(1), linker);
        var mh2 = Macro.createMH(signature(2), parameters(2), linker);
        var mh3 = Macro.createMH(signature(3), parameters(3), linker);
        var mh4 = Macro.createMH(signature(4), parameters(4), linker);
        return new BuilderImpl<>(mh1, mh2, mh3, mh4);
      }
    }

    record Bar(int value, String text, double weight) {}

    var builder = Builder.of(MethodHandles.lookup(), Bar.class);
    var bar1 = builder.build(Bar::text, "hello", Bar::weight, 2.0, Bar::value, 42);
    var bar2 = builder.build(Bar::weight, 2.0, Bar::value, 42, Bar::text, "hello");
    assertEquals(new Bar(42, "hello", 2.0), bar1);
    assertEquals(new Bar(42, "hello", 2.0), bar2);
  }

  @Test
  public void builder3() {
    interface Builder<T> {
      @FunctionalInterface
      interface Accessor<T, V> extends Serializable {
        V apply(T record);
      }

      <V1> T build(Accessor<T, V1> field1, V1 val1);
      <V1, V2> T build(Accessor<T, V1> field1, V1 val1, Accessor<T, V2> field2, V2 val2);
      <V1, V2, V3> T build(Accessor<T, V1> field1, V1 val1, Accessor<T, V2> field2, V2 val2, Accessor<T, V3> field3, V3 val3);
      <V1, V2, V3, V4> T build(Accessor<T, V1> field1, V1 val1, Accessor<T, V2> field2, V2 val2, Accessor<T, V3> field3, V3 val3, Accessor<T, V4> field4, V4 val4);

      private static MethodType signature(int fieldCount) {
        var parameterTypes = Stream.concat(
            IntStream.range(0, fieldCount).mapToObj(__ -> Accessor.class), IntStream.range(0, fieldCount).mapToObj(__ -> Object.class)
        ).toList();
        return MethodType.methodType(Object.class, parameterTypes);
      }

      private static List<MacroParameter> parameters(int fieldCount) {
        return Stream.concat(
            IntStream.range(0, fieldCount).mapToObj(__ -> CONSTANT_VALUE.polymorphic()), IntStream.range(0, fieldCount).mapToObj(__ -> VALUE)
        ).toList();
      }

      static <T extends Record> Builder<T> of(Lookup lookup, Class<T> recordType) {
        record BuilderImpl<T>(MethodHandle mh) implements Builder<T> {
          private static final Accessor<?,?> NO_ACCESSOR = null;
          private static final Object NO_VALUE = null;

          @Override
          @SuppressWarnings("unchecked")
          public <V1> T build(Accessor<T, V1> field1, V1 val1) {
            try {
              return (T) mh.invokeExact(1, field1, val1, NO_ACCESSOR, NO_VALUE, NO_ACCESSOR, NO_VALUE, NO_ACCESSOR, NO_VALUE);
            } catch (Throwable t) {
              throw Macro.rethrow(t);
            }
          }

          @Override
          @SuppressWarnings("unchecked")
          public <V1, V2> T build(Accessor<T, V1> field1, V1 val1, Accessor<T, V2> field2, V2 val2) {
            try {
              return (T) mh.invokeExact(2, field1, val1, field2, val2, NO_ACCESSOR, NO_VALUE, NO_ACCESSOR, NO_VALUE);
            } catch (Throwable t) {
              throw Macro.rethrow(t);
            }
          }

          @Override
          @SuppressWarnings("unchecked")
          public <V1, V2, V3> T build(Accessor<T, V1> field1, V1 val1, Accessor<T, V2> field2, V2 val2, Accessor<T, V3> field3, V3 val3) {
            try {
              return (T) mh.invokeExact(3, field1, val1, field2, val2, field3, val3, NO_ACCESSOR, NO_VALUE);
            } catch (Throwable t) {
              throw Macro.rethrow(t);
            }
          }

          @Override
          @SuppressWarnings("unchecked")
          public <V1, V2, V3, V4> T build(Accessor<T, V1> field1, V1 val1, Accessor<T, V2> field2, V2 val2, Accessor<T, V3> field3, V3 val3, Accessor<T, V4> field4, V4 val4) {
            try {
              return (T) mh.invokeExact(4, field1, val1, field2, val2, field3, val3, field4, val4);
            } catch (Throwable t) {
              throw Macro.rethrow(t);
            }
          }
        }

        var components = recordType.getRecordComponents();
        var componentTypeMap = Arrays.stream(components)
            .collect(Collectors.toMap(RecordComponent::getName, RecordComponent::getType));
        MethodHandle constructor;
        try {
          constructor = lookup.findConstructor(recordType,
              methodType(void.class, Arrays.stream(components).map(RecordComponent::getType).toArray(Class[]::new)));
        } catch (NoSuchMethodException e) {
          throw (NoSuchMethodError) new NoSuchMethodError().initCause(e);
        } catch (IllegalAccessException e) {
          throw (IllegalAccessError) new IllegalAccessError().initCause(e);
        }

        var mhType = methodType(Object.class, int.class, Accessor.class, Object.class, Accessor.class, Object.class, Accessor.class, Object.class, Accessor.class, Object.class);
        var parameters = List.of(CONSTANT_VALUE, CONSTANT_VALUE.polymorphic(), VALUE, CONSTANT_VALUE.polymorphic(), VALUE, CONSTANT_VALUE.polymorphic(), VALUE, CONSTANT_VALUE.polymorphic(), VALUE);
        var linker = (Linker) (constants, methodType) -> {
          var accessorCount = (int) constants.get(0);
          var methodNames = constants.stream()
              .skip(1)
              .limit(accessorCount)
              .map(lambda -> Macro.crack(lookup, lambda).getImplMethodName())
              .toList();
          if (!componentTypeMap.keySet().equals(new HashSet<>(methodNames))) {
            throw new IllegalStateException("wrong component names " + componentTypeMap.keySet() + " but was " + methodNames);
          }
          var orderMap = IntStream.range(0, methodNames.size()).boxed()
              .collect(Collectors.toMap(methodNames::get, i -> i));
          var reorder = Arrays.stream(components)
              .mapToInt(component -> orderMap.get(component.getName()))
              .toArray();
          var newMethodType = methodType(recordType, methodNames.stream()
              .map(componentTypeMap::get)
              .toArray(Class[]::new));
          var target = MethodHandles.permuteArguments(constructor, newMethodType, reorder);
          // drop NO_VALUE values
          if (accessorCount != 4) {
            target = MethodHandles.dropArguments(target, accessorCount, Collections.nCopies(4 - accessorCount, Object.class));
          }
          return target.asType(methodType);
        };
        var mh = Macro.createMH(mhType, parameters, linker);
        return new BuilderImpl<>(mh);
      }
    }

    record Bar(int value, String text, double weight) {}

    var barBuilder = Builder.of(MethodHandles.lookup(), Bar.class);
    var bar1 = barBuilder.build(Bar::text, "hello", Bar::weight, 2.0, Bar::value, 42);
    var bar2 = barBuilder.build(Bar::weight, 2.0, Bar::value, 42, Bar::text, "hello");
    assertEquals(new Bar(42, "hello", 2.0), bar1);
    assertEquals(new Bar(42, "hello", 2.0), bar2);
  }
}