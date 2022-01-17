package com.github.forax.macro;

import com.github.forax.macro.Macro.Linker;
import com.github.forax.macro.MacroParameter.ConstantParameter.ConstantPolicy;
import com.github.forax.macro.MacroParameter.ConstantParameter.ProjectionFunction;

import java.lang.invoke.MethodType;
import java.util.List;

import static java.util.Objects.requireNonNull;

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
public sealed interface MacroParameter {
  /**
   * A parameter indicating that the corresponding argument is a constant.
   */
  record ConstantParameter(ProjectionFunction function, boolean dropValue, ConstantPolicy policy) implements MacroParameter {
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
    enum ConstantPolicy {
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
   * A parameter indicating that the corresponding argument should be ignored.
   */
  enum IgnoreParameter implements MacroParameter {
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
  enum ValueParameter implements MacroParameter {
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
  ConstantParameter CONSTANT_VALUE = new ConstantParameter(ProjectionFunction.VALUE, true, ConstantPolicy.ERROR);

  /**
   * A constant parameter that defines the class of the argument as a constant,
   * the value is not dropped and throws an exception if the parameter see several classes
   */
  ConstantParameter CONSTANT_CLASS = new ConstantParameter(ProjectionFunction.GET_CLASS, false, ConstantPolicy.ERROR);

  /**
   * A value parameter
   */
  ValueParameter VALUE = ValueParameter.INSTANCE;

  /**
   * An ignore parameter
   */
  IgnoreParameter IGNORE = IgnoreParameter.INSTANCE;
}
