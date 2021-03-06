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
package org.sonar.java.checks;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import org.sonar.check.BelongsToProfile;
import org.sonar.check.Priority;
import org.sonar.check.Rule;
import org.sonar.java.checks.methods.AbstractMethodDetection;
import org.sonar.java.checks.methods.MethodInvocationMatcher;
import org.sonar.java.model.AbstractTypedTree;
import org.sonar.java.resolve.Type;
import org.sonar.plugins.java.api.tree.ExpressionTree;
import org.sonar.plugins.java.api.tree.LiteralTree;
import org.sonar.plugins.java.api.tree.MethodInvocationTree;
import org.sonar.plugins.java.api.tree.Tree;

import javax.annotation.Nullable;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Rule(
    key = "S2275",
    priority = Priority.CRITICAL,
    tags = {"bug", "pitfall"})
@BelongsToProfile(title = "Sonar way", priority = Priority.CRITICAL)
public class PrintfCheck extends AbstractMethodDetection {

  private static final Pattern PRINTF_PARAM_PATTERN = Pattern.compile("%(\\d+\\$)?([-#+ 0,(\\<]*)?(\\d+)?(\\.\\d+)?([tT])?([a-zA-Z%])");
  private static final Set<String> TIME_CONVERSIONS = Sets.newHashSet(
      "H", "I", "k", "l", "M", "S", "L", "N", "p", "z", "Z", "s", "Q",
      "B", "b", "h", "A", "a", "C", "Y", "y", "j", "m", "d", "e",
      "R", "T", "r", "D", "F", "c"
  );
  private static final String FORMAT_METHOD_NAME = "format";

  @Override
  protected List<MethodInvocationMatcher> getMethodInvocationMatchers() {
    return ImmutableList.of(
        MethodInvocationMatcher.create().typeDefinition("java.lang.String").name(FORMAT_METHOD_NAME).withNoParameterConstraint(),
        MethodInvocationMatcher.create().typeDefinition("java.util.Formatter").name(FORMAT_METHOD_NAME).withNoParameterConstraint(),
        MethodInvocationMatcher.create().typeDefinition("java.io.PrintStream").name(FORMAT_METHOD_NAME).withNoParameterConstraint(),
        MethodInvocationMatcher.create().typeDefinition("java.io.PrintStream").name("printf").withNoParameterConstraint(),
        MethodInvocationMatcher.create().typeDefinition("java.io.PrintWriter").name(FORMAT_METHOD_NAME).withNoParameterConstraint(),
        MethodInvocationMatcher.create().typeDefinition("java.io.PrintWriter").name("printf").withNoParameterConstraint()
    );
  }

  @Override
  protected void onMethodFound(MethodInvocationTree mit) {
    ExpressionTree formatStringTree;
    List<ExpressionTree> args;
    //Check type of first argument:
    if (((AbstractTypedTree) mit.arguments().get(0)).getSymbolType().is("java.lang.String")) {
      formatStringTree = mit.arguments().get(0);
      args = mit.arguments().subList(1, mit.arguments().size());
    } else {
      //format method with "Locale" first argument, skip that one.
      formatStringTree = mit.arguments().get(1);
      args = mit.arguments().subList(2, mit.arguments().size());
    }
    if (formatStringTree.is(Tree.Kind.STRING_LITERAL)) {
      String formatString = trimQuotes(((LiteralTree) formatStringTree).value());
      checkLineFeed(formatString, mit);

      List<String> params = getParameters(formatString, mit);
      if (usesMessageFormat(formatString, params)) {
        addIssue(mit, "Looks like there is a confusion with the use of java.text.MessageFormat, parameters will be simply ignored here");
        return;
      }
      cleanupLineSeparator(params);
      if (params.size() > args.size()) {
        addIssue(mit, "Not enough arguments.");
        return;
      }
      verifyParameters(mit, args, params);
    }
  }

  private void cleanupLineSeparator(List<String> params) {
    //Cleanup %n and %% values
    Iterator<String> iter = params.iterator();
    while (iter.hasNext()) {
      String param = iter.next();
      if ("n".equals(param) || "%".equals(param)) {
        iter.remove();
      }
    }
  }

  private void checkLineFeed(String formatString, MethodInvocationTree mit) {
    if (formatString.contains("\\n")) {
      addIssue(mit, "%n should be used in place of \\n to produce the platform-specific line separator.");
    }
  }

  private void verifyParameters(MethodInvocationTree mit, List<ExpressionTree> args, List<String> params) {
    int index = 0;
    List<ExpressionTree> unusedArgs = Lists.newArrayList(args);
    for (String rawParam : params) {
      String param = rawParam;
      int argIndex = index;
      if (param.contains("$")) {
        argIndex = Integer.valueOf(param.substring(0, param.indexOf("$"))) - 1;
        param = param.substring(param.indexOf("$") + 1);
      } else {
        index++;
      }
      ExpressionTree argExpressionTree = args.get(argIndex);
      unusedArgs.remove(argExpressionTree);
      Type argType = ((AbstractTypedTree) argExpressionTree).getSymbolType();
      if (param.startsWith("d") && !isNumerical(argType)) {
        addIssue(mit, "An 'int' is expected rather than a " + argType + ".");
      }
      if (param.startsWith("b") && !(argType.is("boolean") || argType.is("java.lang.Boolean"))) {
        addIssue(mit, "Directly inject the boolean value.");
      }
      checkTimeConversion(mit, param, argType);

    }
    reportUnusedArgs(mit, args, unusedArgs);
  }

  private void checkTimeConversion(MethodInvocationTree mit, String param, Type argType) {
    if (param.startsWith("t") || param.startsWith("T")) {
      String timeConversion = param.substring(1);
      if (timeConversion.isEmpty()) {
        addIssue(mit, "Time conversion requires a second character.");
        checkTimeTypeArgument(mit, argType);
        return;
      }
      if (!TIME_CONVERSIONS.contains(timeConversion)) {
        addIssue(mit, timeConversion + " is not a supported time conversion character");
      }
      checkTimeTypeArgument(mit, argType);
    }
  }

  private void checkTimeTypeArgument(MethodInvocationTree mit, Type argType) {
    if (!(argType.isNumerical() || argType.is("java.lang.Long") || argType.isSubtypeOf("java.util.Date") || argType.isSubtypeOf("java.util.Calendar"))) {
      addIssue(mit, "Time argument is expected (long, Long, Date or Calendar).");
    }
  }


  private boolean isNumerical(Type argType) {
    return argType.isNumerical()
        || argType.is("java.math.BigInteger")
        || argType.is("java.math.BigDecimal")
        || argType.is("java.lang.Byte")
        || argType.is("java.lang.Short")
        || argType.is("java.lang.Integer")
        || argType.is("java.lang.Long")
        || argType.is("java.lang.Float")
        || argType.is("java.lang.Double")
        ;
  }

  private boolean usesMessageFormat(String formatString, List<String> params) {
    return params.isEmpty() && (formatString.contains("{0") || formatString.contains("{1"));
  }

  private void reportUnusedArgs(MethodInvocationTree mit, List<ExpressionTree> args, List<ExpressionTree> unusedArgs) {
    for (ExpressionTree unusedArg : unusedArgs) {
      int i = args.indexOf(unusedArg);
      String stringArgIndex = "first";
      if (i == 1) {
        stringArgIndex = "2nd";
      } else if (i == 2) {
        stringArgIndex = "3rd";
      } else if (i >= 3) {
        stringArgIndex = (i + 1) + "th";
      }
      addIssue(mit, stringArgIndex + " argument is not used.");
    }
  }

  private List<String> getParameters(String formatString, MethodInvocationTree mit) {
    List<String> params = Lists.newArrayList();
    Matcher matcher = PRINTF_PARAM_PATTERN.matcher(formatString);
    while (matcher.find()) {
      if (firstArgumentIsLT(params, matcher.group(2))) {
        addIssue(mit, "The argument index '<' refers to the previous format specifier but there isn't one.");
        continue;
      }
      StringBuilder param = new StringBuilder();
      for (int groupIndex : new int[]{1, 5, 6}) {
        if (matcher.group(groupIndex) != null) {
          param.append(matcher.group(groupIndex));
        }
      }
      params.add(param.toString());
    }
    return params;
  }

  private boolean firstArgumentIsLT(List<String> params, @Nullable String group) {
    return params.isEmpty() && group != null && group.startsWith("<");
  }

  private String trimQuotes(String value) {
    return value.substring(1, value.length() - 1);
  }
}
