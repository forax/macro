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
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.github.forax.macro.Macro.CONSTANT_VALUE;
import static com.github.forax.macro.Macro.VALUE;
import static java.lang.invoke.MethodType.methodType;

public interface builder3 {
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
