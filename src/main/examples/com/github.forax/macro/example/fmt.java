package com.github.forax.macro.example;

import com.github.forax.macro.Macro;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.StringConcatException;
import java.lang.invoke.StringConcatFactory;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

public interface fmt {
  interface Formatter {
    String format(String text, Object... args);

    static Formatter of() {
      record FMTImpl(MethodHandle mh) implements Formatter {
        public String format(String text, Object... args) {
          try {
            return (String) mh.invokeExact(text, args);
          } catch (Throwable t) {
            throw Macro.rethrow(t);
          }
        }
      }
      var mh = Macro.createMH(MethodType.methodType(String.class, String.class, Object[].class),
          List.of(Macro.CONSTANT_VALUE.polymorphic(), Macro.VALUE),
          (constants, methodType) -> {
            var text = (String) constants.get(0);
            var pattern = Pattern.compile("(%.)");
            var matcher = pattern.matcher(text);
            var box = new Object() { int counter; boolean unknown; };
            var recipe = matcher.replaceAll(matchResult -> {
              switch (matchResult.group(1)) {
                case "%s", "%d" -> box.counter++;
                default -> box.unknown = true;
              }
              return "\u0001";
            });
            var lookup = MethodHandles.lookup();
            if (box.unknown) {
              var target = lookup.findStatic(String.class, "format", MethodType.methodType(String.class, String.class, Object[].class));
              return target.bindTo(text);
            }
            var count = box.counter;
            var concatType = MethodType.methodType(String.class, Collections.nCopies(count, Object.class));
            MethodHandle target = null;
            try {
              target = StringConcatFactory.makeConcatWithConstants(lookup, "concat", concatType, recipe).dynamicInvoker();
            } catch (StringConcatException e) {
              throw new AssertionError(e);
            }
            target = target.asSpreader(Object[].class, count);
            return target.asType(methodType);
          });
      return new FMTImpl(mh);
    }
  }

  // ---


  private static void assertEquals(Object exptected, Object result) {
    if (!(Objects.equals(exptected, result))) {
      throw new AssertionError("not equals, " + exptected + " != " + result);
    }
  }

  Formatter FMT = Formatter.of();

  static void main(String[] args){
    assertEquals("hello FMT 42", FMT.format("hello %s %d", "FMT", 42));
    assertEquals("42.00", FMT.format("%.2f", 42.0));
  }
}
