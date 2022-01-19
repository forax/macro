package com.github.forax.macro.example;

import com.github.forax.macro.Macro;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.List;
import java.util.Objects;

import static java.lang.invoke.MethodType.methodType;

public interface almostconstant {
  interface AlmostConstant<T> {
    T get();
    void set(T value);

    static <T> AlmostConstant<T> of(T value) {
      class Box<T> {
        private T value;

        private Box(T value) {
          this.value = value;
        }
      }
      record AlmostConstantImpl<T>(MethodHandle mh, Box<T> box, Runnable deoptimize) implements AlmostConstant<T> {
        @Override
        @SuppressWarnings("unchecked")
        public T get() {
          try {
            return (T) mh.invokeExact();
          } catch (Throwable t) {
            throw Macro.rethrow(t);
          }
        }

        @Override
        public void set(T value) {
          box.value = value;
          deoptimize.run();
        }
      }
      var box = new Box<T>(value);
      var control = Macro.createMHControl(methodType(Object.class), List.of(),
          (__, methodType) -> MethodHandles.constant(Object.class, box.value));
      return new AlmostConstantImpl<>(control.createMH(), box, control::deoptimize);
    }
  }

  // ---

  private static void assertEquals(Object exptected, Object result) {
    if (!(Objects.equals(exptected, result))) {
      throw new AssertionError("not equals, " + exptected + " != " + result);
    }
  }


  AlmostConstant<Integer> ALMOST_CONSTANT = AlmostConstant.of(42);

  static void main(String[] args){
    assertEquals(42, ALMOST_CONSTANT.get());
    ALMOST_CONSTANT.set(505);
    assertEquals(505, ALMOST_CONSTANT.get());
  }
}
