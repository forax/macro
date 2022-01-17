package com.github.forax.macro;

import com.github.forax.macro.MacroParameter.ConstantParameter;
import com.github.forax.macro.MacroParameter.ConstantParameter.ConstantPolicy;
import com.github.forax.macro.MacroParameter.ConstantParameter.ProjectionFunction;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class MacroParameterTest {
  private static Stream<Arguments> provideArguments() {
    return Stream.of(0)
        .mapMulti((__, consumer) -> {
            for(var projection: new ProjectionFunction[] { ProjectionFunction.VALUE, ProjectionFunction.GET_CLASS }) {
              for(var dropValue: new boolean[] { true, false}) {
                for(var policy: ConstantPolicy.values()) {
                  consumer.accept(Arguments.of(projection, dropValue, policy));
                }
              }
            }
        });
  }

  @ParameterizedTest
  @MethodSource("provideArguments")
  public void macroParameterWithObject(ProjectionFunction function, boolean dropValue, ConstantPolicy policy) throws Throwable {
    var parameter = new ConstantParameter(function, dropValue, policy);
    var mh =
        Macro.createMH(
            MethodType.methodType(Object.class, Object.class), List.of(parameter), (constants, methodType) -> {
              var expectedConstant = (function == ProjectionFunction.VALUE)? 42: Integer.class;
              assertEquals(List.of(expectedConstant), constants);

              var expectedMethodType = dropValue? MethodType.methodType(Object.class): MethodType.methodType(Object.class, Object.class);
              assertEquals(expectedMethodType, methodType);

              return MethodHandles.empty(methodType);
            });
    assertEquals(null, mh.invoke(42));
  }

  @ParameterizedTest
  @MethodSource("provideArguments")
  public void macroParameterWithAnInterface(ProjectionFunction function, boolean dropValue, ConstantPolicy policy) throws Throwable {
    interface I {}
    record R() implements I {}

    var r = new R();
    var parameter = new ConstantParameter(function, dropValue, policy);
    var mh =
        Macro.createMH(
            MethodType.methodType(int.class, I.class), List.of(parameter), (constants, methodType) -> {
              var expectedConstant = (function == ProjectionFunction.VALUE)? r: R.class;
              assertEquals(List.of(expectedConstant), constants);

              var expectedMethodType = dropValue? MethodType.methodType(int.class): MethodType.methodType(int.class, I.class);
              assertEquals(expectedMethodType, methodType);

              return MethodHandles.empty(methodType);
            });
    assertEquals(0, mh.invoke(r));
  }
}
