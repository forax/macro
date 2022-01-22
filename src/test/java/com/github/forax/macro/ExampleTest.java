package com.github.forax.macro;

import com.github.forax.macro.example.almostconstant;
import com.github.forax.macro.example.builder1;
import com.github.forax.macro.example.builder2;
import com.github.forax.macro.example.builder3;
import com.github.forax.macro.example.fmt;
import com.github.forax.macro.example.multimethods;

import org.junit.jupiter.api.Test;

public class ExampleTest {
  @Test
  public void builder1() {
    builder1.main(new String[0]);
  }

  @Test
  public void builder2() {
    builder2.main(new String[0]);
  }

  @Test
  public void builder3() {
    builder3.main(new String[0]);
  }

  @Test
  public void fmt() {
    fmt.main(new String[0]);
  }

  @Test
  public void almostconstant() {
    almostconstant.main(new String[0]);
  }

  @Test
  public void multimethod() {
    multimethods.main(new String[0]);
  }
}
