package com.github.forax.macro;

import com.github.forax.macro.MacroParameter.ConstantParameter.CacheKind;
import com.github.forax.macro.MacroParameter.ConstantParameter.DeriveFunction;

public sealed interface MacroParameter {
  record ConstantParameter(DeriveFunction function, boolean dropValue, CacheKind cacheKind) implements MacroParameter {
    public interface DeriveFunction {
      Object derive(Class<?> declaredType, Object value);

      DeriveFunction VALUE = (declaredType, value) -> value;
      DeriveFunction GET_CLASS = (declaredType, value) -> declaredType.isPrimitive()? declaredType: value.getClass();
    }

    enum CacheKind { CHECKED, MONOMORPHIC, POLYMORPHIC }

    public ConstantParameter checked() {
      return new ConstantParameter(function, dropValue, CacheKind.CHECKED);
    }
    public ConstantParameter monomorphic() {
      return new ConstantParameter(function, dropValue, CacheKind.MONOMORPHIC);
    }
    public ConstantParameter polymorphic() {
      return new ConstantParameter(function, dropValue, CacheKind.POLYMORPHIC);
    }
  }

  enum IgnoreParameter implements MacroParameter { INSTANCE }
  enum ValueParameter implements MacroParameter { INSTANCE }

  ConstantParameter CONSTANT_VALUE = new ConstantParameter(DeriveFunction.VALUE, true, CacheKind.CHECKED);
  ConstantParameter CONSTANT_CLASS = new ConstantParameter(DeriveFunction.GET_CLASS, false, CacheKind.CHECKED);
  ValueParameter VALUE = ValueParameter.INSTANCE;
  IgnoreParameter IGNORE = IgnoreParameter.INSTANCE;
}
