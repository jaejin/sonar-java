/*
 * SonarQube Java
 * Copyright (C) 2012 SonarSource
 * dev@sonar.codehaus.org
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02
 */
package org.sonar.java.resolve;

import com.google.common.collect.Lists;
import org.junit.Before;
import org.junit.Test;
import org.sonar.java.resolve.targets.Annotations;
import org.sonar.java.resolve.targets.AnonymousClass;
import org.sonar.java.resolve.targets.HasInnerClass;
import org.sonar.java.resolve.targets.InnerClassBeforeOuter;
import org.sonar.java.resolve.targets.NamedClassWithinMethod;

import java.io.File;
import java.util.List;

import static org.fest.assertions.Assertions.assertThat;

public class BytecodeCompleterTest {

  private BytecodeCompleter bytecodeCompleter;
  //used to load classes in same package
  public BytecodeCompleterPackageVisibility bytecodeCompleterPackageVisibility = new BytecodeCompleterPackageVisibility();

  private void accessPackageVisibility() {
    bytecodeCompleterPackageVisibility.add(1, 2);
  }

  @Before
  public void setUp() throws Exception {
    bytecodeCompleter = new BytecodeCompleter(Lists.newArrayList(new File("target/test-classes"), new File("target/classes")));
    new Symbols(bytecodeCompleter);

  }

  @Test
  public void class_names_ending_with_$() throws Exception {
    Symbol.TypeSymbol classSymbol = bytecodeCompleter.getClassSymbol("org/sonar/java/resolve/targets/OuterClassEndingWith$$InnerClassEndingWith$");
    assertThat(classSymbol.getName()).isEqualTo("InnerClassEndingWith$");
    assertThat(classSymbol.owner().getName()).isEqualTo("OuterClassEndingWith$");
  }

  @Test
  public void annotations() throws Exception {
    bytecodeCompleter.getClassSymbol(Annotations.class.getName().replace('.', '/')).complete();
  }

  @Test
  public void anonymous_class() {
    bytecodeCompleter.getClassSymbol(AnonymousClass.class.getName().replace('.', '/')).complete();
  }

  @Test
  public void named_class_within_method() {
    bytecodeCompleter.getClassSymbol(NamedClassWithinMethod.class.getName().replace('.', '/')).complete();
  }

  @Test
  public void inner_class_before_outer() {
    Symbol.TypeSymbol symbol = bytecodeCompleter.getClassSymbol(InnerClassBeforeOuter.class.getName());
    Symbol.TypeSymbol innerClass = symbol.getSuperclass().symbol;
    Symbol.TypeSymbol outerClass = (Symbol.TypeSymbol) innerClass.owner();
    assertThat(outerClass.members().lookup(HasInnerClass.InnerClass.class.getSimpleName())).containsExactly(innerClass);
  }

  @Test
  public void outer_class_before_inner() {
    Symbol.TypeSymbol outerClass = bytecodeCompleter.getClassSymbol(HasInnerClass.class.getName());
    assertThat(outerClass.members().lookup(HasInnerClass.InnerClass.class.getSimpleName())).hasSize(1);
  }

  @Test
  public void completing_symbol_ArrayList() throws Exception {
    Symbol.TypeSymbol arrayList = bytecodeCompleter.getClassSymbol("java/util/ArrayList");
    //Check supertype
    assertThat(arrayList.getSuperclass().symbol.name).isEqualTo("AbstractList");
    assertThat(arrayList.getSuperclass().symbol.owner().name).isEqualTo("java.util");

    //Check interfaces
    assertThat(arrayList.getInterfaces()).hasSize(4);
    List<String> interfacesName = Lists.newArrayList();
    for (Type interfaceType : arrayList.getInterfaces()) {
      interfacesName.add(interfaceType.symbol.name);
    }
    assertThat(interfacesName).hasSize(4);
    assertThat(interfacesName).contains("List", "RandomAccess", "Cloneable", "Serializable");
    assertThat(arrayList.isParametrized).isTrue();
  }

  @Test
  public void symbol_type_in_same_package_should_be_resolved() throws Exception {
    Symbol.TypeSymbol thisTest = bytecodeCompleter.getClassSymbol(Convert.bytecodeName(getClass().getName()));
    List<Symbol> symbols = thisTest.members().lookup("bytecodeCompleterPackageVisibility");
    assertThat(symbols).hasSize(1);
    Symbol.VariableSymbol symbol = (Symbol.VariableSymbol) symbols.get(0);
    assertThat(symbol.type.symbol.name).isEqualTo("BytecodeCompleterPackageVisibility");
    assertThat(symbol.type.symbol.owner().name).isEqualTo(thisTest.owner().name);
  }

  @Test
  public void void_method_type_should_be_resolved() {
    Symbol.TypeSymbol thisTest = bytecodeCompleter.getClassSymbol(Convert.bytecodeName(getClass().getName()));
    List<Symbol> symbols = thisTest.members().lookup("bytecodeCompleterPackageVisibility");
    assertThat(symbols).hasSize(1);
    Symbol.VariableSymbol symbol = (Symbol.VariableSymbol) symbols.get(0);
    symbols = symbol.getType().symbol.members().lookup("voidMethod");
    assertThat(symbols).hasSize(1);
    Symbol method = symbols.get(0);
    assertThat(method.type).isInstanceOf(Type.MethodType.class);
    assertThat(((Type.MethodType) method.type).resultType.symbol.name).isEqualTo("void");
  }

  @Test
  public void inner_class_should_be_correctly_flagged() {
    Symbol.TypeSymbol interfaceWithInnerEnum = bytecodeCompleter.getClassSymbol("org.sonar.java.resolve.targets.subpackage.FlagCompletion");
    List<Symbol> members = interfaceWithInnerEnum.members().lookup("bar");
    Symbol.TypeSymbol innerEnum = ((Symbol.MethodSymbol) members.get(0)).getReturnType();
    //complete outer class
    innerEnum.owner().complete();
    //verify flag are set for inner class.
    assertThat(innerEnum.isEnum()).isTrue();
    assertThat(innerEnum.isPublic()).isTrue();
    assertThat(innerEnum.isStatic()).isTrue();
    assertThat(innerEnum.isFinal()).isTrue();
    assertThat(innerEnum.isParametrized).isFalse();
  }

  @Test
  public void deprecated_classes_should_be_flagged() throws Exception {
    Symbol.TypeSymbol deprecatedClass = bytecodeCompleter.getClassSymbol("org.sonar.java.resolve.targets.DeprecatedClass");
    assertThat(deprecatedClass.isDeprecated()).isTrue();
    Symbol.TypeSymbol staticInnerClass = (Symbol.TypeSymbol) deprecatedClass.members().lookup("StaticInnerClass").get(0);
    assertThat(staticInnerClass.isDeprecated()).isTrue();
    Symbol.TypeSymbol innerClass = (Symbol.TypeSymbol) deprecatedClass.members().lookup("InnerClass").get(0);
    assertThat(innerClass.isDeprecated()).isTrue();
  }

  @Test
  public void complete_flags_for_inner_class() throws Exception {
    Symbol.TypeSymbol classSymbol = bytecodeCompleter.getClassSymbol("org.sonar.java.resolve.targets.ProtectedInnerClassChild");
    Symbol.MethodSymbol foo = (Symbol.MethodSymbol) classSymbol.members().lookup("foo").get(0);
    Symbol.TypeSymbol innerClassRef = foo.getReturnType();
    assertThat(innerClassRef.isPrivate()).isFalse();
    assertThat(innerClassRef.isPublic()).isFalse();
    assertThat(innerClassRef.isPackageVisibility()).isFalse();
    assertThat(innerClassRef.isDeprecated());
  }

  @Test
  public void complete_flags_for_varargs_methods() throws Exception {
    Symbol.TypeSymbol classSymbol = bytecodeCompleter.getClassSymbol("org.sonar.java.resolve.targets.ProtectedInnerClassChild");
    Symbol.MethodSymbol foo = (Symbol.MethodSymbol) classSymbol.members().lookup("foo").get(0);
    assertThat((foo.flags & Flags.VARARGS) != 0);
  }

}
