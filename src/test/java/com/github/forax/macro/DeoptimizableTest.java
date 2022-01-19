package com.github.forax.macro;

import org.junit.jupiter.api.Test;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;

import static java.lang.invoke.MethodType.methodType;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class DeoptimizableTest {
  @Test
  public void constantAndDeoptimization() throws Throwable {
    var box = new Object() { private int value = 42; };
    var control = Macro.createMHControl(methodType(int.class), List.of(),
        (__, methodType) -> MethodHandles.constant(int.class, box.value));
    var mh = control.createMH();
    assertEquals(42, (int)mh.invokeExact());

    box.value = 747;
    assertEquals(42, (int) mh.invokeExact());

    control.deoptimize();
    assertEquals(747, (int) mh.invokeExact());
  }
}
