package com.github.forax.macro.example;

import com.github.forax.macro.Macro;
import com.github.forax.macro.Macro.ConstantParameter;
import com.github.forax.macro.Macro.ConstantPolicy;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.MethodType;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import static com.github.forax.macro.Macro.CONSTANT_CLASS;
import static com.github.forax.macro.Macro.CONSTANT_VALUE;

public interface multimethods {
  interface Multimethod {
    <T> T call(String name, Object o1);
    <T> T call(String name, Object o1, Object o2);
    <T> T call(String name, Object o1, Object o2, Object o3);
    <T> T call(String name, Object o1, Object o2, Object o3, Object o4);
    <T> T call(String name, Object o1, Object o2, Object o3, Object o4, Object o5);

    static Multimethod of(Lookup lookup) {
      record MultimethodImpl(MethodHandle mh) implements Multimethod {
        private static final Object NONE = new Object();

        @Override
        public <T> T call(String name, Object o1) {
          return dispatch(1, name, o1, NONE, NONE, NONE, NONE);
        }

        @Override
        public <T> T call(String name, Object o1, Object o2) {
          return dispatch(2, name, o1, o2, NONE, NONE, NONE);
        }

        @Override
        public <T> T call(String name, Object o1, Object o2, Object o3) {
          return dispatch(3, name, o1, o2, o3, NONE, NONE);
        }

        @Override
        public <T> T call(String name, Object o1, Object o2, Object o3, Object o4) {
          return dispatch(4, name, o1, o2, o3, o4, NONE);
        }

        @Override
        public <T> T call(String name, Object o1, Object o2, Object o3, Object o4, Object o5) {
          return dispatch(5, name, o1, o2, o3, o4, o5);
        }

        @SuppressWarnings("unchecked")
        private <T> T dispatch(int arity, String name, Object o1, Object o2, Object o3, Object o4, Object o5) {
          try {
            return (T) mh.invokeExact(arity, name, o1, o2, o3, o4, o5);
          } catch (Throwable t) {
            throw Macro.rethrow(t);
          }
        }
      }
      var dyn = new ConstantParameter((__, value) -> value.getClass(), false, ConstantPolicy.POLYMORPHIC);
      var mh = Macro.createMH(MethodType.methodType(Object.class, int.class, String.class, Object.class, Object.class, Object.class, Object.class, Object.class),
          List.of(CONSTANT_VALUE, CONSTANT_VALUE, dyn, dyn, dyn, dyn, dyn),
          (constants, methodType) -> {
            var arity = (int) constants.get(0);
            var name = (String) constants.get(1);
            var type1 = (Class<?>) constants.get(2);
            var type2 = (Class<?>) constants.get(3);
            var type3 = (Class<?>) constants.get(4);
            var type4 = (Class<?>) constants.get(5);
            var type5 = (Class<?>) constants.get(6);
            var parameterTypes = switch (arity) {
              case 1 -> List.<Class<?>>of();
              case 2 -> List.<Class<?>>of(type2);
              case 3 -> List.of(type2, type3);
              case 4 -> List.of(type2, type3, type4);
              case 5 -> List.of(type2, type3, type4, type5);
              default -> throw new AssertionError();
            };
            var mhs = applicableMethods(lookup, type1, name, parameterTypes);
            if (mhs.isEmpty()) {
              throw new IllegalStateException("no methods matching !");
            }
            var target = mostSpecific(mhs);
            if (target == null) {
              throw new IllegalStateException("no method is more specific than the others !");
            }
            if (arity != 5) {
              target = MethodHandles.dropArguments(target, target.type().parameterCount(), Collections.nCopies(5 - arity, Object.class));
            }
            return target.asType(methodType);
          });
      return new MultimethodImpl(mh);
    }

    private static List<MethodHandle> applicableMethods(Lookup lookup, Class<?> declaringClass, String name, List<Class<?>> parameterTypes) {
      return Arrays.stream(declaringClass.getDeclaredMethods())
          .filter(m -> !m.isBridge() &&
              m.getName().equals(name) &&
              ((m.isVarArgs() && m.getParameterCount() <= parameterTypes.size()) || m.getParameterCount() == parameterTypes.size()))
          .flatMap(m -> {
            MethodHandle mh;
            try {
              mh = lookup.unreflect(m);
            } catch (IllegalAccessException e) {
              return null;
            }
            if (m.isVarArgs()) {
              var lastParameter = mh.type().parameterType(mh.type().parameterCount() - 1);
              mh = mh.asSpreader(lastParameter, 1 + parameterTypes.size() - mh.type().parameterCount());
            }

            if (!moreSpecific(MethodType.methodType(Object.class, parameterTypes), mh.type().dropParameterTypes(0, 1))) {
              return null;
            }

            return Stream.of(mh);
          })
          .toList();
    }

    private static boolean moreSpecific(Class<?> parameter1, Class<?> parameter2) {
      return parameter2.isAssignableFrom(parameter1);
    }

    private static boolean moreSpecific(MethodType type1, MethodType type2) {
      for(var i = 0; i < type1.parameterCount(); i++) {
        var parameter1 = type1.parameterType(i);
        var parameter2 = type2.parameterType(i);
        if (type1 == type2) {
          continue;
        }
        if (!moreSpecific(parameter1, parameter2)) {
          return false;
        }
      }
      return true;
    }

    private static MethodHandle mostSpecific(List<MethodHandle> mhs) {
      loop: for(var mh1: mhs) {
        for(var mh2: mhs) {
          if (mh1 == mh2) {
            continue;
          }
          if (!moreSpecific(mh1.type(), mh2.type())) {
            continue loop;
          }
        }
        return mh1;
      }
      return null;
    }
  }


  // ---

  private static void assertEquals(Object exptected, Object result) {
    if (!(Objects.equals(exptected, result))) {
      throw new AssertionError("not equals, " + exptected + " != " + result);
    }
  }

  sealed interface Shape {}
  record Rectangle() implements Shape {}
  record Circle() implements Shape {}

  class Test {
    public int m1(Shape shape) { throw new AssertionError(); }
    public int m1(Rectangle rectangle) { return 1; }
    public int m1(Circle circle) { return 2; }

    public int m2(Rectangle r1, Rectangle r2) { return 10; }
    public int m2(Rectangle r1, Circle c2) { return 20; }
    public int m2(Circle c1, Rectangle r2) { return 30; }
    public int m2(Circle c1, Circle c2) { return 40; }
  }

  Multimethod MULTI_METHOD = Multimethod.of(MethodHandles.lookup());

  static void main(String[] args){
    var rectangle = new Rectangle();
    var circle = new Circle();

    assertEquals(1, MULTI_METHOD.call("m1", new Test(), rectangle));
    assertEquals(2, MULTI_METHOD.call("m1", new Test(), circle));

    assertEquals(10, MULTI_METHOD.call("m2", new Test(), rectangle, rectangle));
    assertEquals(20, MULTI_METHOD.call("m2", new Test(), rectangle, circle));
    assertEquals(30, MULTI_METHOD.call("m2", new Test(), circle, rectangle));
    assertEquals(40, MULTI_METHOD.call("m2", new Test(), circle, circle));
  }
}
