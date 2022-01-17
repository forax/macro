package com.github.forax.macro;

import org.junit.jupiter.api.Test;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.MethodType;
import java.util.List;
import java.util.regex.Pattern;

import static java.lang.invoke.MethodType.methodType;
import static org.junit.jupiter.api.Assertions.*;

public class MacroTest {
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
            List.of(MacroParameter.CONSTANT_VALUE),
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
            List.of(MacroParameter.CONSTANT_CLASS.polymorphic(), MacroParameter.CONSTANT_VALUE, MacroParameter.CONSTANT_VALUE, MacroParameter.VALUE),
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
    assertEquals("B", dispatch.call(new B(), "m", methodType(String.class, long.class), 3L));
    assertEquals("A", dispatch.call(new A(), "m", methodType(String.class, long.class), 3L));
    assertEquals("B", dispatch.call(new B(), "m", methodType(String.class, long.class), 3L));
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
            List.of(MacroParameter.CONSTANT_CLASS.relink(), MacroParameter.CONSTANT_CLASS.polymorphic()),
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
}