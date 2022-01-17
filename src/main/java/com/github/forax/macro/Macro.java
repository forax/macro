package com.github.forax.macro;

import com.github.forax.macro.MacroParameter.ConstantParameter;
import com.github.forax.macro.MacroParameter.ConstantParameter.ConstantPolicy;
import com.github.forax.macro.MacroParameter.ConstantParameter.ProjectionFunction;
import com.github.forax.macro.MacroParameter.IgnoreParameter;
import com.github.forax.macro.MacroParameter.ValueParameter;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.MutableCallSite;
import java.lang.invoke.WrongMethodTypeException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static java.lang.invoke.MethodHandles.dropArguments;
import static java.lang.invoke.MethodHandles.insertArguments;
import static java.lang.invoke.MethodType.methodType;
import static java.util.Objects.requireNonNull;

/**
 * A way to defines macro at runtime in Java.
 *
 * A macro is a method that pre-compute a list of constants from some the arguments and ask a linker
 * to provide a method handle from the list of constants to be called with the resting arguments.
 *
 * <pre>
 * mh(arg1, arg2, arg3) + macro(param1, param2, param3) -> linker(const1, const2)(arg2, arg3)
 * </pre>
 *
 * The {@link MacroParameter macro parameters} describes how to extract a constant from an argument,
 * how to react if subsequent calls found new constants and if the argument are used or dropped
 * by the method handle retuend by the linker.
 *
 * @see #createMH(MethodType, List, Linker)
 */
public class Macro {
  /**
   * Called by the macro system with the constants and the method handle type
   * to get a method handle implementing the behavior.
   */
  public interface Linker {
    /**
     * This method is called by the macro system to find the method handle
     * to execute with the arguments.
     *
     * @param constants the constants gather from the arguments.
     * @param methodType the method type that must be the type of the returned method handle
     * @return a method handle
     *
     * @throws ClassNotFoundException if a class is not found
     * @throws IllegalAccessException if access is not possible
     * @throws InstantiationException if instantiation is not possible
     * @throws NoSuchFieldException if no field is found
     * @throws NoSuchMethodException if no method is found
     */
    MethodHandle apply(List<Object> constants, MethodType methodType)
        throws ClassNotFoundException, IllegalAccessException, InstantiationException,
               NoSuchFieldException, NoSuchMethodException;
  }

  /**
   * Creates a method handle conforming to the method type taken as parameter which
   * separate  the constant arguments from the other arguments and call the linker
   * one or more time with the constant arguments.
   *
   * @param methodType a method type
   * @param parameters the macro parameters
   * @param linker the linker that will be called with the constant to get the corresponding method handles
   * @return a method handle
   */
  public static MethodHandle createMH(MethodType methodType,
                                      List<? extends MacroParameter> parameters,
                                      Linker linker) {
    requireNonNull(methodType, "linkageType is null");
    requireNonNull(parameters, "parameters is null");
    requireNonNull(linker, "linker is null");
    return new RootCallSite(methodType, List.copyOf(parameters), linker).dynamicInvoker();
  }

  private sealed interface Argument { }

  private enum ValueArgument implements Argument { INSTANCE }
  private record IgnoredArgument(Class<?> type, int position) implements Argument { }
  private record GuardedArgument(Class<?> type, int position, boolean dropValue, ProjectionFunction function, Object constant, ConstantPolicy policy) implements Argument { }

  private record AnalysisResult(List<Argument> arguments, List<Object> constants, List<Object> values, MethodType linkageType) {
    private AnalysisResult {
      arguments = Collections.unmodifiableList(arguments);
      constants = Collections.unmodifiableList(constants);
      values = Collections.unmodifiableList(values);
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
        case IgnoreParameter __ -> new IgnoredArgument(type, i);
        case ValueParameter __ -> {
          values.add(arg);
          valueTypes.add(type);
          yield ValueArgument.INSTANCE;
        }
        case ConstantParameter constantParameter -> {
          var constant = constantParameter.function().computeConstant(type, arg);
          constants.add(constant);
          if (!constantParameter.dropValue()) {
            values.add(arg);
            valueTypes.add(type);
          }
          yield new GuardedArgument(type, i, constantParameter.dropValue(),
              constantParameter.function(), constant,
              constantParameter.policy());
        }
      };
      arguments.add(argument);
    }
    return new AnalysisResult(arguments, constants, values, methodType(methodType.returnType(), valueTypes));
  }

  private static MethodHandle link(Linker linker, List<Object> constants, MethodType linkageType) {
    MethodHandle target;
    try {
      target = linker.apply(constants, linkageType);
    } catch (ClassNotFoundException e) {
      throw (NoClassDefFoundError) new NoClassDefFoundError().initCause(e);
    } catch (IllegalAccessException e) {
      throw (IllegalAccessError) new IllegalAccessError().initCause(e);
    } catch (InstantiationException e) {
      throw (InstantiationError) new InstantiationError().initCause(e);
    } catch (NoSuchFieldException e) {
      throw (NoSuchFieldError) new NoSuchFieldError().initCause(e);
    } catch (NoSuchMethodException e) {
      throw (NoSuchMethodError) new NoSuchMethodError().initCause(e);
    }

    requireNonNull(target, "linker return value is null");
    if (!target.type().equals(linkageType)) {
      throw new WrongMethodTypeException("linker return value as the wrong type");
    }
    return target;
  }

  private static final class RootCallSite extends MutableCallSite {
    private static final MethodHandle FALLBACK, DERIVE_CHECK, REQUIRE_CONSTANT;
    static {
      var lookup = MethodHandles.lookup();
      try {
        FALLBACK = lookup.findVirtual(RootCallSite.class, "fallback", methodType(Object.class, Object[].class));
        DERIVE_CHECK = lookup.findStatic(RootCallSite.class, "derivedCheck", methodType(boolean.class, Object.class, Class.class, ProjectionFunction.class, Object.class));
        REQUIRE_CONSTANT = lookup.findStatic(RootCallSite.class, "requireConstant", methodType(Object.class, Object.class, Class.class, ProjectionFunction.class, Object.class));
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

    private static boolean derivedCheck(Object arg, Class<?> type, ProjectionFunction function, Object constant) {
      return function.computeConstant(type, arg) == constant;
    }

    private static Object requireConstant(Object arg, Class<?> type, ProjectionFunction function, Object constant) {
      if (function.computeConstant(type, arg) != constant) {
        throw new IllegalStateException("constant violation for argument " + arg + " != " + constant);
      }
      return arg;
    }

    private MethodHandle dropValuesAndInstallGuards(List<Argument> arguments,
                                                    MethodType methodType, MethodHandle target) {

      // take care of the dropped values
      for(var argument: arguments) {
        switch (argument) {
          case IgnoredArgument ignoredArgument -> {
            target = dropArguments(target, ignoredArgument.position, ignoredArgument.type);
          }
          case GuardedArgument guardedArgument -> {
            if (guardedArgument.dropValue) {
              target = dropArguments(target, guardedArgument.position, guardedArgument.type);
            }
          }
          case ValueArgument __ -> {}
        }
      }

      // install guards
      for(var argument: arguments) {
        switch (argument) {
          case IgnoredArgument __ -> {}
          case GuardedArgument guardedArgument -> {
            var type = guardedArgument.type;
            if (guardedArgument.policy == ConstantPolicy.ERROR) {
              var requireConstant = insertArguments(REQUIRE_CONSTANT, 1, type, guardedArgument.function, guardedArgument.constant)
                  .asType(methodType(type, type));
              target = MethodHandles.filterArguments(target, guardedArgument.position, requireConstant);
              continue;
            }
            var deriveCheck = insertArguments(DERIVE_CHECK, 1, type, guardedArgument.function, guardedArgument.constant)
                .asType(methodType(boolean.class, type));
            var test = dropArguments(deriveCheck, 0, target.type().parameterList().subList(0, guardedArgument.position));
            var fallback = guardedArgument.policy == ConstantPolicy.RELINK?
                this.fallback :
                new RootCallSite(methodType, parameters, linker).dynamicInvoker();
            target = MethodHandles.guardWithTest(test, target, fallback);
          }
          case ValueArgument __ -> {}
        }
      }
      return target;
    }

    private Object fallback(Object[] args) throws Throwable {
      var analysisResult = argumentAnalysis(args, parameters, type());
      var arguments = analysisResult.arguments;
      var constants = analysisResult.constants;
      var values = analysisResult.values;
      var linkageType = analysisResult.linkageType;

      var linkerTarget = link(linker, constants, linkageType);
      var target = dropValuesAndInstallGuards(arguments, type(), linkerTarget);
      setTarget(target);

      return linkerTarget.invokeWithArguments(values);
    }
  }

  /**
   * Rethrow any throwable without the compiler considering as a checked exception.
   *
   * @param cause a throwable to throw
   * @return nothing typed as an AssertionError so rethrow can be a parameter of @code throw}.
   */
  public static AssertionError rethrow(Throwable cause) {
    throw rethrow0(cause);
  }

  @SuppressWarnings("unchecked")   // allow to erase the exception type, see above
  private static <T extends Throwable> AssertionError rethrow0(Throwable cause) throws T {
    throw (T) cause;
  }
}
