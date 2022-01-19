package com.github.forax.macro;

import java.io.Serializable;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.MethodType;
import java.lang.invoke.MutableCallSite;
import java.lang.invoke.SerializedLambda;
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
 * The {@link Parameter macro parameters} describes how to extract a constant from an argument,
 * how to react if subsequent calls found new constants and if the argument are used or dropped
 * by the method handle retuend by the linker.
 *
 * @see #createMH(MethodType, List, Linker)
 */
public class Macro {
  /**
   * Describe the parameters of a function call of a macro.
   * Instead of a classical function call where all arguments are sent to the function, a macro separate the arguments
   * in two categories, the constants and the values, the constants are extracted and a linker is called
   * to provide a method handle that will be called with the values.
   *
   * The fact that the constant are computed earlier than the arguments allows data structures and
   * method linkage to be computed resulting in a code more efficient than using reflection.
   *
   * {@link Macro#createMH(MethodType, List, Linker)} create a macro, as a method handle,
   * from a method type, a list of parameters and a {@link Linker}.
   *
   * There are 3 kinds of parameters
   * <ul>
   *   <li>{@link ConstantParameter a constant parameter}, a parameter from which a
   *       {@link ConstantParameter#function() constant can be extracted},
   *       the argument itself will be @link {@link ConstantParameter#dropValue() kept or not} and
   *       {@link ConstantParameter#policy() one or more constants} can be extracted from a parameter.
   *   <li>{@link ValueParameter a value parameter}, a simple argument.
   *   <li>{@link IgnoreParameter an ignore parameter}, the corresponding argument is ignored.
   * </ul>
   *
   * The {@link Linker} defines the function called with the constants to provide the method handle
   * that will be called with the argument.
   *
   * @see Macro
   */
  public sealed interface Parameter {}

  /**
   * A parameter indicating that the corresponding argument is a constant.
   */
  public record ConstantParameter(ProjectionFunction function, boolean dropValue, ConstantPolicy policy) implements Parameter {
    /**
     * Creates a constant parameter with a projection function to compute the constant from a value,
     * a boolean indicating if the value should be dropped ot not and
     * an enum indicating the operation to do if there are different values for the constant
     * ({@link ConstantPolicy#ERROR}: emits an error, {@link ConstantPolicy#RELINK}: calls the linker again,
     * {@link ConstantPolicy#POLYMORPHIC}:install a polymorphic inlining cache).
     *
     * @param function the projection function, can be a lambda, {@link ProjectionFunction#VALUE} or
     *                 {@link ProjectionFunction#GET_CLASS}
     * @param dropValue a boolean indicating if the value should be dropped given it appears as a constant
     * @param policy policy when there are several constants for a parameter
     */
    public ConstantParameter {
      requireNonNull(function);
      requireNonNull(policy);
    }

    /**
     * Returns a new constant parameter using the {@link ConstantPolicy#ERROR}
     * @return a new constant parameter using the {@link ConstantPolicy#ERROR}
     */
    public ConstantParameter error() {
      return new ConstantParameter(function, dropValue, ConstantPolicy.ERROR);
    }

    /**
     * Returns a new constant parameter using the {@link ConstantPolicy#RELINK}
     * @return a new constant parameter using the {@link ConstantPolicy#RELINK}
     */
    public ConstantParameter relink() {
      return new ConstantParameter(function, dropValue, ConstantPolicy.RELINK);
    }

    /**
     * Returns a new constant parameter using the {@link ConstantPolicy#POLYMORPHIC}
     * @return a new constant parameter using the {@link ConstantPolicy#POLYMORPHIC}
     */
    public ConstantParameter polymorphic() {
      return new ConstantParameter(function, dropValue, ConstantPolicy.POLYMORPHIC);
    }

    /**
     * Returns a new constant parameter that drop/retain the value of the constant
     * If the value is dropped it will not be an argument of the method handle returned
     * by the {@link Linker}
     *
     * @param dropValue drop or retain the value of a constant
     * @return a new constant parameter that drop/retain the value of the constant
     */
    public ConstantParameter dropValue(boolean dropValue) {
      return new ConstantParameter(function, dropValue, policy);
    }
  }

  /**
   * The projection function used to extract the constant from a value.
   */
  @FunctionalInterface
  public interface ProjectionFunction {
    /**
     * Returns a constant from the value and its declared type.
     *
     * @param declaredType the declared type of the argument
     * @param value the value of the argument
     * @return a constant
     */
    Object computeConstant(Class<?> declaredType, Object value);

    /**
     * A projection function that returns the value as constant.
     */
    ProjectionFunction VALUE = (declaredType, value) -> value;

    /**
     * A projection function that return the class of the value as a constant.
     */
    ProjectionFunction GET_CLASS = (declaredType, value) -> declaredType.isPrimitive()? declaredType: value.getClass();
  }

  /**
   * The behavior when the macro system detects that a constant has several different values.
   */
  public enum ConstantPolicy {
    /**
     * Emits an exception
     */
    ERROR,

    /**
     * Calls the linker again, trashing all previous computation
     */
    RELINK,

    /**
     * Use a polymorphic inlining cache to remember all the linkages of the constants
     */
    POLYMORPHIC
  }

  /**
   * A parameter indicating that the corresponding argument should be ignored.
   */
  public enum IgnoreParameter implements Parameter {
    /**
     * The singleton instance.
     *
     * @see #IGNORE
     */
    INSTANCE
  }

  /**
   * A parameter indicating that the corresponding argument is just a value (not a constant).
   */
  public enum ValueParameter implements Parameter {
    /**
     * The singleton instance.
     *
     * @see #VALUE
     */
    INSTANCE
  }

  /**
   * A constant parameter that defines the value of the argument as a constant,
   * drop the value and throws an exception if the parameter see several values
   */
  public static final ConstantParameter CONSTANT_VALUE = new ConstantParameter(ProjectionFunction.VALUE, true, ConstantPolicy.ERROR);

  /**
   * A constant parameter that defines the class of the argument as a constant,
   * the value is not dropped and throws an exception if the parameter see several classes
   */
  public static final ConstantParameter CONSTANT_CLASS = new ConstantParameter(ProjectionFunction.GET_CLASS, false, ConstantPolicy.ERROR);

  /**
   * A value parameter
   */
  public static final ValueParameter VALUE = ValueParameter.INSTANCE;

  /**
   * An ignored parameter
   */
  public static final IgnoreParameter IGNORE = IgnoreParameter.INSTANCE;

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
                                      List<? extends Parameter> parameters,
                                      Linker linker) {
    return createMHControl(methodType, parameters, linker).createMH();
  }

  /**
   * An interface that can create a method handle from the parameters of
   * {@link #createMHControl(MethodType, List, Linker)} and reset all created method handles to their initial state.
   */
  public interface MethodHandleControl {
    /**
     * Creates a method handle from the parameter of {@link #createMHControl(MethodType, List, Linker)}.
     * @return a new method handle
     */
    MethodHandle createMH();

    /**
     * Deoptimize all method handles created with {@link #createMH()}.
     */
    void deoptimize();
  }

  /**
   * Return a method handle control which provides
   * <ol>
   *  <li>a method {@link MethodHandleControl#createMH()} that create a method handle from a recipe.
   *  <li>a method {@link MethodHandleControl#deoptimize()} that can reset the method handle to its initial state
   * </ol>
   *
   * @param methodType a method type
   * @param parameters the macro parameters
   * @param linker the linker that will be called with the constant to get the corresponding method handles
   * @return a method handle
   */
  public static MethodHandleControl createMHControl(MethodType methodType,
                                                    List<? extends Parameter> parameters,
                                                    Linker linker) {
    requireNonNull(methodType, "linkageType is null");
    requireNonNull(parameters, "parameters is null");
    requireNonNull(linker, "linker is null");
    if (methodType.parameterCount() != parameters.size()) {
      throw new IllegalArgumentException("methodType.parameterCount() != parameters.size()");
    }
    return new RootCallSite(methodType, List.copyOf(parameters), linker);
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

  private static AnalysisResult argumentAnalysis(Object[] args, List<Parameter> parameters, MethodType methodType) {
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

  private static final class RootCallSite extends MutableCallSite implements MethodHandleControl {
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

    private final List<Parameter> parameters;
    private final Linker linker;
    private final MethodHandle fallback;

    public RootCallSite(MethodType type, List<Parameter> parameters, Linker linker) {
      super(type);
      this.parameters = parameters;
      this.linker = linker;
      var fallback = FALLBACK.bindTo(this).asCollector(Object[].class, type.parameterCount()).asType(type);
      this.fallback = fallback;
      setTarget(fallback);
    }

    @Override
    public MethodHandle createMH() {
      return dynamicInvoker();
    }

    @Override
    public void deoptimize() {
      setTarget(fallback);
      MutableCallSite.syncAll(new MutableCallSite[] { this });
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

  /**
   * Returns a {@code java.lang.invoke.SerializedLambda} from a serializable lambda.
   * This operation quite slow, so it should not be done in a fast path.
   *
   * @param lookup a lookup that can see the lambda
   * @param lambda a serializable lambda
   * @return a SerializedLambda object containing all the info about a lambda
   */
  public static SerializedLambda crack(Lookup lookup, Object lambda) {
    if (!(lambda instanceof Serializable)) {
      throw new IllegalArgumentException("the lambda is not serializable");
    }
    MethodHandle writeReplace;
    try {
      writeReplace = lookup.findVirtual(lambda.getClass(), "writeReplace", methodType(Object.class));
    } catch (IllegalAccessException e) {
      throw (IllegalAccessError) new IllegalAccessError().initCause(e);
    } catch (NoSuchMethodException e) {
      throw (NoSuchMethodError) new NoSuchMethodError().initCause(e);
    }
    try {
      return (SerializedLambda) writeReplace.invoke(lambda);
    } catch (Throwable t) {
      throw rethrow(t);
    }
  }
}
