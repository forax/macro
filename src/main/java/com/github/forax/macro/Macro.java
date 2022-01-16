package com.github.forax.macro;

import com.github.forax.macro.MacroParameter.ConstantParameter;
import com.github.forax.macro.MacroParameter.ConstantParameter.CacheKind;
import com.github.forax.macro.MacroParameter.ConstantParameter.DeriveFunction;
import com.github.forax.macro.MacroParameter.IgnoreParameter;
import com.github.forax.macro.MacroParameter.ValueParameter;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.MutableCallSite;
import java.lang.invoke.WrongMethodTypeException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static java.lang.invoke.MethodType.methodType;

public class Macro {
  interface Linker {
    MethodHandle link(List<Object> constants, MethodType methodType) throws ReflectiveOperationException;
  }

  public static MethodHandle createMH(MethodType methodType,
                                      List<? extends MacroParameter> parameters,
                                      Linker linker) {
    Objects.requireNonNull(methodType, "targetType is null");
    Objects.requireNonNull(parameters, "parameters is null");
    Objects.requireNonNull(linker, "linker is null");
    return new RootCallSite(methodType, List.copyOf(parameters), linker).dynamicInvoker();
  }

  private sealed interface Argument { }

  private enum ValueArgument implements Argument { INSTANCE }
  private record IgnoreArgument(Class<?> type, int position) implements Argument { }
  private record GuardedArgument(int position, boolean dropValue, Class<?> type, int constantIndex, int valueIndex, DeriveFunction function, Kind kind) implements Argument {
    enum Kind { MONOMORPHIC, POLYMORPHIC }
  }
  private record RequireCheckArgument(int position, boolean dropValue, Class<?> type, DeriveFunction function, Object constant) implements Argument { }

  private record AnalysisResult(List<Argument> arguments, List<Object> constants, List<Object> values, MethodType targetType) {
    private AnalysisResult {
      arguments = List.copyOf(arguments);
      constants = List.copyOf(constants);
      values = List.copyOf(values);
    }
  }

  private static AnalysisResult argumentAnalysis(Object[] args, List<MacroParameter> parameters, MethodType methodType) {
    var arguments = new ArrayList<Argument>();
    var constants = new ArrayList<>();
    var values = new ArrayList<>();
    var valueTypes = new ArrayList<Class<?>>();
    for(var i = 0; i < args.length; i++) {
      var parameter = parameters.get(i);
      var arg = args[i];
      var type = methodType.parameterType(i);
      var argument = switch (parameter) {
        case IgnoreParameter ignoreParameter -> new IgnoreArgument(type, i);
        case ValueParameter valueParameter -> {
          values.add(arg);
          valueTypes.add(type);
          yield ValueArgument.INSTANCE;
        }
        case ConstantParameter constantParameter -> {
          var constant = constantParameter.function().derive(type, arg);
          if (constantParameter.cacheKind() == CacheKind.CHECKED) {
            constants.add(constant);
            yield new RequireCheckArgument(i, constantParameter.dropValue(), type,
                constantParameter.function(), constant);
          }
          var constantIndex = constants.size();
          var valueIndex = values.size();

          constants.add(constant);
          values.add(arg);
          valueTypes.add(type);

          yield new GuardedArgument(i, constantParameter.dropValue(), type,
              constantIndex, valueIndex, constantParameter.function(),
              constantParameter.cacheKind() == CacheKind.MONOMORPHIC?
                  GuardedArgument.Kind.MONOMORPHIC:
                  GuardedArgument.Kind.POLYMORPHIC);
        }
      };
      arguments.add(argument);
    }
    return new AnalysisResult(arguments, constants, values, methodType(methodType.returnType(), valueTypes));
  }

  private static final class RootCallSite extends MutableCallSite {
    private static final MethodHandle FALLBACK, DERIVE_CHECK, REQUIRE_CONSTANT;
    static {
      var lookup = MethodHandles.lookup();
      try {
        FALLBACK = lookup.findVirtual(RootCallSite.class, "fallback", methodType(Object.class, Object[].class));
        DERIVE_CHECK = lookup.findStatic(RootCallSite.class, "derivedCheck", methodType(boolean.class, Object.class, Class.class, DeriveFunction.class, Object.class));
        REQUIRE_CONSTANT = lookup.findStatic(RootCallSite.class, "requireConstant", methodType(void.class, Object.class, Class.class, DeriveFunction.class, Object.class));
      } catch (NoSuchMethodException | IllegalAccessException e) {
       throw new AssertionError(e);
      }
    }

    private final List<MacroParameter> parameters;
    private final Linker linker;
    private final MethodHandle fallback;

    public RootCallSite(MethodType type, List<MacroParameter> parameters, Linker linker) {
      super(type);
      this.parameters = parameters;
      this.linker = linker;
      var fallback = FALLBACK.bindTo(this).asCollector(Object[].class, type.parameterCount()).asType(type);
      this.fallback = fallback;
      setTarget(fallback);
    }

    private static boolean derivedCheck(Object arg, Class<?> type, DeriveFunction function, Object constant) {
      return function.derive(type, arg) == constant;
    }

    private static void requireConstant(Object arg, Class<?> type, DeriveFunction function, Object constant) {
      if (function.derive(type, arg) != constant) {
        throw new IllegalStateException("constant violation for argument " + arg);
      }
    }

    private Object fallback(Object[] args) throws ReflectiveOperationException /*FIXME*/, Throwable {
      var analysisResult = argumentAnalysis(args, parameters, type());
      var arguments = analysisResult.arguments;
      var constants = analysisResult.constants;
      var values = analysisResult.values;
      var targetType = analysisResult.targetType;

      var target = linker.link(constants, targetType);
      Objects.requireNonNull(target, "linker return value is null");
      if (!target.type().equals(targetType)) {
        throw new WrongMethodTypeException("linker return value as the wrong type");
      }

      var result = target.invokeWithArguments(values);

      // deal with cache arguments
      for(var argument: arguments) {
        switch(argument) {
          case GuardedArgument guardedArgument -> {
            var constant = constants.get(guardedArgument.constantIndex);
            var type = guardedArgument.type;
            var deriveCheck = MethodHandles.insertArguments(DERIVE_CHECK, 1, type, guardedArgument.function, constant)
                .asType(methodType(boolean.class, type));
            var test = MethodHandles.dropArguments(deriveCheck, 0, target.type().parameterList().subList(0, guardedArgument.valueIndex));
            var fallback = guardedArgument.kind == GuardedArgument.Kind.MONOMORPHIC?
                this.fallback:
                new InliningCacheCallSite(target.type(), guardedArgument, constants, linker).dynamicInvoker();
            target = MethodHandles.guardWithTest(test, target, fallback);
          }
          default -> {}
        }
      }

      // deal with constant arguments and ignore arguments
      for(var argument: arguments) {
        switch (argument) {
          case GuardedArgument guardedArgument -> {
            if (guardedArgument.dropValue) {
              target = MethodHandles.dropArguments(target, guardedArgument.position, guardedArgument.type);
            }
          }
          case RequireCheckArgument requireCheckArgument -> {
            var type = requireCheckArgument.type;
            if (requireCheckArgument.dropValue) {
              target = MethodHandles.dropArguments(target, requireCheckArgument.position, type);
            }
            var requireConstant = MethodHandles.insertArguments(REQUIRE_CONSTANT, 1, type, requireCheckArgument.function, requireCheckArgument.constant)
                .asType(methodType(type, type));
            target = MethodHandles.filterArguments(target, requireCheckArgument.position, requireConstant);
          }
          case IgnoreArgument ignoreArgument -> {
            target = MethodHandles.dropArguments(target, ignoreArgument.position, ignoreArgument.type);
          }
          default -> {}
        }
      }

      setTarget(target);
      return result;
    }
  }

  private static final class InliningCacheCallSite extends MutableCallSite {
    private static final MethodHandle FALLBACK;
    static {
      var lookup = MethodHandles.lookup();
      try {
        FALLBACK = lookup.findVirtual(InliningCacheCallSite.class, "fallback", methodType(MethodHandle.class, Object.class));
      } catch (NoSuchMethodException | IllegalAccessException e) {
        throw new AssertionError(e);
      }
    }

    private final GuardedArgument guardedArgument;
    private final List<Object> constants;
    private final Linker linker;

    public InliningCacheCallSite(MethodType type, GuardedArgument guardedArgument, List<Object> constants, Linker linker) {
      super(type);
      this.guardedArgument = guardedArgument;
      this.constants = constants;
      this.linker = linker;
      var fallback = FALLBACK.bindTo(this)
          .asType(methodType(MethodHandle.class, type.parameterType(guardedArgument.valueIndex)));
      fallback = MethodHandles.dropArguments(fallback, 0, type.parameterList().subList(0, guardedArgument.valueIndex));
      setTarget(MethodHandles.foldArguments(MethodHandles.exactInvoker(type), fallback));
    }

    private MethodHandle fallback(Object receiver) throws ReflectiveOperationException {
      var type = guardedArgument.type;
      var constant = guardedArgument.function.derive(type, receiver);
      var constants = new ArrayList<>(this.constants);
      constants.set(guardedArgument.constantIndex, constant);
      var target = linker.link(List.copyOf(constants), type());

      var deriveCheck = MethodHandles.insertArguments(RootCallSite.DERIVE_CHECK, 1, type, guardedArgument.function, constant)
          .asType(methodType(boolean.class, type));
      var test = MethodHandles.dropArguments(deriveCheck, 0, type().parameterList().subList(0, guardedArgument.valueIndex));
      var guard = MethodHandles.guardWithTest(test,
          target,
          new InliningCacheCallSite(type(), guardedArgument, constants, linker).dynamicInvoker());
      setTarget(guard);

      return target;
    }
  }



  @SuppressWarnings("unchecked")   // very wrong but works
  public static <T extends Throwable> AssertionError rethrow(Throwable cause) throws T {
    throw (T) cause;
  }
}
