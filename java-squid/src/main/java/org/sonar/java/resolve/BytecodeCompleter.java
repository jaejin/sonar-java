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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.io.Closeables;
import org.apache.commons.lang.StringUtils;
import org.objectweb.asm.ClassReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.java.bytecode.ClassLoaderBuilder;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class BytecodeCompleter implements Symbol.Completer {

  private static final Logger LOG = LoggerFactory.getLogger(BytecodeCompleter.class);

  private static final int ACCEPTABLE_BYTECODE_FLAGS = Flags.ACCESS_FLAGS |
      Flags.INTERFACE | Flags.ANNOTATION | Flags.ENUM |
      Flags.STATIC | Flags.FINAL | Flags.SYNCHRONIZED | Flags.VOLATILE | Flags.TRANSIENT | Flags.VARARGS | Flags.NATIVE |
      Flags.ABSTRACT | Flags.STRICTFP | Flags.DEPRECATED;

  private Symbols symbols;
  private final List<File> projectClasspath;

  /**
   * Indexed by flat name.
   */
  private final Map<String, Symbol.TypeSymbol> classes = new HashMap<String, Symbol.TypeSymbol>();
  private final Map<String, Symbol.PackageSymbol> packages = new HashMap<String, Symbol.PackageSymbol>();

  private ClassLoader classLoader;

  public BytecodeCompleter(List<File> projectClasspath) {
    this.projectClasspath = projectClasspath;
  }

  public void init(Symbols symbols) {
    this.symbols = symbols;
  }

  public Symbol.TypeSymbol registerClass(Symbol.TypeSymbol classSymbol) {
    String flatName = formFullName(classSymbol);
    Preconditions.checkState(!classes.containsKey(flatName), "Registering class 2 times : " + flatName);
    classes.put(flatName, classSymbol);
    return classSymbol;
  }

  @Override
  public void complete(Symbol symbol) {
    LOG.debug("Completing symbol : " + symbol.name);
    //complete outer class to set flags for inner class properly.
    if (symbol.owner.isKind(Symbol.TYP)) {
      symbol.owner.complete();
    }
    String bytecodeName = formFullName(symbol);
    Symbol.TypeSymbol classSymbol = getClassSymbol(bytecodeName);
    Preconditions.checkState(classSymbol == symbol);

    InputStream inputStream = null;
    ClassReader classReader = null;
    try {
      inputStream = inputStreamFor(bytecodeName);
      classReader = new ClassReader(inputStream);
    } catch (IOException e) {
      throw Throwables.propagate(e);
    } finally {
      Closeables.closeQuietly(inputStream);
    }
    if (classReader != null) {
      classReader.accept(new BytecodeVisitor(this, symbols, (Symbol.TypeSymbol) symbol), ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
    }
  }

  private InputStream inputStreamFor(String fullname) {
    return getClassLoader().getResourceAsStream(Convert.bytecodeName(fullname) + ".class");
  }

  private ClassLoader getClassLoader() {
    if (classLoader == null) {
      classLoader = ClassLoaderBuilder.create(projectClasspath);
    }
    return classLoader;
  }

  public String formFullName(Symbol symbol) {
    return formFullName(symbol.name, symbol.owner);
  }

  public String formFullName(String name, Symbol site) {
    String result = name;
    Symbol owner = site;
    while (owner != symbols.defaultPackage) {
      //Handle inner classes, if owner is a type, separate by $
      String separator = ".";
      if (owner.kind == Symbol.TYP) {
        separator = "$";
      }
      result = owner.name + separator + result;
      owner = owner.owner();
    }
    return result;
  }

  @VisibleForTesting
  Symbol.TypeSymbol getClassSymbol(String bytecodeName) {
    return getClassSymbol(bytecodeName, 0);
  }

  // FIXME(Godin): or parameter must be renamed, or should not receive flat name, in a former case - first transformation in this method seems useless
  Symbol.TypeSymbol getClassSymbol(String bytecodeName, int flags) {
    String flatName = Convert.flatName(bytecodeName);
    Symbol.TypeSymbol symbol = classes.get(flatName);
    if (symbol == null) {
      String shortName = Convert.shortName(flatName);
      String packageName = Convert.packagePart(flatName);
      String enclosingClassName = Convert.enclosingClassName(shortName);
      if (StringUtils.isNotEmpty(enclosingClassName)) {
        //handle innerClasses
        symbol = new Symbol.TypeSymbol(filterBytecodeFlags(flags), Convert.innerClassName(shortName), getClassSymbol(Convert.fullName(packageName, enclosingClassName)));
      } else {
        symbol = new Symbol.TypeSymbol(filterBytecodeFlags(flags), shortName, enterPackage(packageName));
      }
      symbol.members = new Scope(symbol);

      // (Godin): IOException will happen without this condition in case of missing class:
      if (getClassLoader().getResource(Convert.bytecodeName(flatName) + ".class") != null) {
        symbol.completer = this;
      } else {
        LOG.error("Class not found: " + bytecodeName);
        // TODO(Godin): why only interfaces, but not supertype for example?
        ((Type.ClassType) symbol.type).interfaces = ImmutableList.of();
      }

      classes.put(flatName, symbol);
    }
    return symbol;
  }

  public int filterBytecodeFlags(int flags) {
    return flags & ACCEPTABLE_BYTECODE_FLAGS;
  }

  /**
   * <b>Note:</b> Attempt to find something like "java.class" on case-insensitive file system can result in unwanted loading of "JAVA.class".
   * This method performs check of class name within file in order to avoid such situation.
   * This is definitely not the best solution in terms of performance, but acceptable for now.
   *
   * @return symbol for requested class, if corresponding class file exists, and {@link Resolve.SymbolNotFound} otherwise
   */
  // TODO(Godin): Method name is misleading because of lazy loading.
  public Symbol loadClass(String fullname) {
    Symbol.TypeSymbol symbol = classes.get(fullname);
    if (symbol != null) {
      return symbol;
    }

    // TODO(Godin): pull out conversion of name from the next method to avoid unnecessary conversion afterwards:
    InputStream inputStream = inputStreamFor(fullname);
    String bytecodeName = Convert.bytecodeName(fullname);

    if (inputStream == null) {
      return new Resolve.SymbolNotFound();
    }

    try {
      ClassReader classReader = new ClassReader(inputStream);
      String className = classReader.getClassName();
      if (!className.equals(bytecodeName)) {
        return new Resolve.SymbolNotFound();
      }
    } catch (IOException e) {
      throw Throwables.propagate(e);
    } finally {
      Closeables.closeQuietly(inputStream);
    }

    return getClassSymbol(fullname);
  }

  public Symbol.PackageSymbol enterPackage(String fullname) {
    if (StringUtils.isBlank(fullname)) {
      return symbols.defaultPackage;
    }
    Symbol.PackageSymbol result = packages.get(fullname);
    if (result == null) {
      result = new Symbol.PackageSymbol(fullname, symbols.defaultPackage);
      packages.put(fullname, result);
    }
    return result;
  }

  public void done() {
    if (classLoader != null && classLoader instanceof Closeable) {
      Closeables.closeQuietly((Closeable) classLoader);
    }
  }

  /**
   * Compiler marks all artifacts not presented in the source code as {@link Flags#SYNTHETIC}.
   */
  static boolean isSynthetic(int flags) {
    return (flags & Flags.SYNTHETIC) != 0;
  }

}
