package com.github.forax.macro.example;

import com.github.forax.macro.Macro;
import com.github.forax.macro.Macro.Linker;
import com.github.forax.macro.Macro.Parameter;

import java.io.Serializable;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.MethodType;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.github.forax.macro.Macro.CONSTANT_VALUE;
import static com.github.forax.macro.Macro.VALUE;
import static java.lang.invoke.MethodType.methodType;

public interface builder2 {
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

    private static List<Parameter> parameters(int fieldCount) {
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

  // ---

  private static void assertEquals(Object exptected, Object result) {
    if (!(Objects.equals(exptected, result))) {
      throw new AssertionError("not equals " + exptected + " != " + result);
    }
  }


  record Bar(int value, String text, double weight) {}

  Builder<Bar> BAR_BUILDER = Builder.of(MethodHandles.lookup(), Bar.class);

  static void main(String[] args){
    var bar1 = BAR_BUILDER.build(Bar::text, "hello", Bar::weight, 2.0, Bar::value, 42);
    var bar2 = BAR_BUILDER.build(Bar::weight, 2.0, Bar::value, 42, Bar::text, "hello");
    assertEquals(new Bar(42, "hello", 2.0), bar1);
    assertEquals(new Bar(42, "hello", 2.0), bar2);
  }
}
