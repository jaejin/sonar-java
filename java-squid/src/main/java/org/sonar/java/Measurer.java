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
package org.sonar.java;

import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Sets;
import com.google.common.io.Files;
import com.sonar.sslr.api.Token;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.api.batch.SensorContext;
import org.sonar.api.measures.*;
import org.sonar.api.resources.File;
import org.sonar.api.resources.Project;
import org.sonar.java.ast.visitors.*;
import org.sonar.java.model.InternalSyntaxToken;
import org.sonar.java.model.JavaTree;
import org.sonar.plugins.java.api.JavaFileScannerContext;
import org.sonar.plugins.java.api.tree.*;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

public class Measurer extends SubscriptionVisitor implements CharsetAwareVisitor {

  private Logger logger = LoggerFactory.getLogger(this.getClass());
  private static final Number[] LIMITS_COMPLEXITY_METHODS = {1, 2, 4, 6, 8, 10, 12};
  private static final Number[] LIMITS_COMPLEXITY_FILES = {0, 5, 10, 20, 30, 60, 90};

  static final Number[] CC_DISTRIBUTION_BOTTOM_LIMITS = { 50, 20, 10, 0 };
  static final Number[] NCLOC_DISTRIBUTION_BOTTOM_LIMITS = { 100, 50, 10, 0 };

  private RangeDistributionBuilder NCLOC_BY_CC_DISTRIB;
  private RangeDistributionBuilder NCLOC_BY_NCLOC_DISTRIB;

  private final SensorContext sensorContext;
  private final Project project;
  private final boolean analyseAccessors;
  private File sonarFile;
  private int methods;
  private int accessors;
  private int complexityInMethods;
  private RangeDistributionBuilder methodComplexityDistribution;

  private final Deque<ClassTree> classTrees = new LinkedList<ClassTree>();
  private final AccessorVisitor accessorVisitor;
  private Charset charset;
  private double classes;

  public Measurer(Project project, SensorContext context, boolean analyseAccessors) {
    this.project = project;
    this.sensorContext = context;
    this.analyseAccessors = analyseAccessors;
    accessorVisitor = new AccessorVisitor();
  }

  @Override
  public List<Tree.Kind> nodesToVisit() {
    return ImmutableList.of(Tree.Kind.CLASS, Tree.Kind.INTERFACE, Tree.Kind.ENUM, Tree.Kind.ANNOTATION_TYPE,
        Tree.Kind.NEW_CLASS, Tree.Kind.ENUM_CONSTANT,
        Tree.Kind.METHOD, Tree.Kind.CONSTRUCTOR);
  }


  @Override
  public void scanFile(JavaFileScannerContext context) {
    sonarFile = File.fromIOFile(context.getFile(), project);
    classTrees.clear();
    methods = 0;
    complexityInMethods = 0;
    accessors = 0;
    classes = 0;
    PublicApiChecker publicApiChecker = new PublicApiChecker();
    publicApiChecker.scan(context.getTree());
    methodComplexityDistribution = new RangeDistributionBuilder(CoreMetrics.FUNCTION_COMPLEXITY_DISTRIBUTION, LIMITS_COMPLEXITY_METHODS);
    NCLOC_BY_CC_DISTRIB = new RangeDistributionBuilder( MMMetrics.NCLOC_BY_CC_DISTRIB, CC_DISTRIBUTION_BOTTOM_LIMITS);
    NCLOC_BY_NCLOC_DISTRIB = new RangeDistributionBuilder( MMMetrics.NCLOC_BY_NCLOC_DISTRIB, NCLOC_DISTRIBUTION_BOTTOM_LIMITS);

    super.scanFile(context);
    //leave file.
    int fileComplexity = context.getComplexity(context.getTree());
    saveMetricOnFile(CoreMetrics.CLASSES, classes);
    saveMetricOnFile(CoreMetrics.FUNCTIONS, methods);
    saveMetricOnFile(CoreMetrics.ACCESSORS, accessors);
    saveMetricOnFile(CoreMetrics.COMPLEXITY_IN_FUNCTIONS, complexityInMethods);
    saveMetricOnFile(CoreMetrics.COMPLEXITY, fileComplexity);
    saveMetricOnFile(CoreMetrics.PUBLIC_API, publicApiChecker.getPublicApi());
    saveMetricOnFile(CoreMetrics.PUBLIC_DOCUMENTED_API_DENSITY, publicApiChecker.getDocumentedPublicApiDensity());
    saveMetricOnFile(CoreMetrics.PUBLIC_UNDOCUMENTED_API, publicApiChecker.getUndocumentedPublicApi());
    saveMetricOnFile(CoreMetrics.COMMENT_LINES, new CommentLinesVisitor().commentLines(context.getTree()));
    saveMetricOnFile(CoreMetrics.STATEMENTS, new StatementVisitor().numberOfStatements(context.getTree()));
    saveMetricOnFile(CoreMetrics.NCLOC, new LinesOfCodeVisitor().linesOfCode(context.getTree()));

    sensorContext.saveMeasure(sonarFile,NCLOC_BY_CC_DISTRIB.build());
    sensorContext.saveMeasure(sonarFile,NCLOC_BY_NCLOC_DISTRIB.build());

    sensorContext.saveMeasure(sonarFile, methodComplexityDistribution.build(true).setPersistenceMode(PersistenceMode.MEMORY));

    RangeDistributionBuilder fileComplexityDistribution = new RangeDistributionBuilder(CoreMetrics.FILE_COMPLEXITY_DISTRIBUTION, LIMITS_COMPLEXITY_FILES);
    sensorContext.saveMeasure(sonarFile, fileComplexityDistribution.add(fileComplexity).build(true).setPersistenceMode(PersistenceMode.MEMORY));
    saveLinesMetric();


  }

  private void saveLinesMetric() {
    try {
      String content = Files.toString(context.getFile(), charset);
      saveMetricOnFile(CoreMetrics.LINES, content.split("(\r)?\n|\r", -1).length);
    } catch (IOException e) {
      Throwables.propagate(e);
    }
  }

  @Override
  public void visitNode(Tree tree) {
    if (isClassTree(tree)) {
      classes++;
      classTrees.push((ClassTree) tree);
    }
    if (tree.is(Tree.Kind.NEW_CLASS) && ((NewClassTree) tree).classBody() != null) {
      classes--;
    }
    if (tree.is(Tree.Kind.METHOD, Tree.Kind.CONSTRUCTOR) && classTrees.peek().simpleName() != null) {
      //don't count methods in anonymous classes.
      MethodTree methodTree = (MethodTree) tree;
      if (analyseAccessors && accessorVisitor.isAccessor(classTrees.peek(), methodTree)) {
        accessors++;
      } else {
        methods++;
        int methodComplexity = context.getMethodComplexity(classTrees.peek(), methodTree);
        methodComplexityDistribution.add(methodComplexity);
        complexityInMethods += methodComplexity;

          Set<Integer> lines = Sets.newHashSet();


          for (Token token : ((JavaTree) tree).getAstNode().getTokens()) {
              SyntaxToken syntaxToken = new InternalSyntaxToken(token);
              InternalSyntaxToken internalSyntaxToken = (InternalSyntaxToken) syntaxToken;
              if(!internalSyntaxToken.isEOF()) {
                  lines.add(internalSyntaxToken.getLine());
              }

          }

          int lineOfCode = lines.size();



           NCLOC_BY_CC_DISTRIB.add(methodComplexity,lineOfCode);// line of code
           NCLOC_BY_NCLOC_DISTRIB.add(lineOfCode,lineOfCode);// line of code

          }
    }

  }

  @Override
  public void leaveNode(Tree tree) {
    if (isClassTree(tree)) {
      classTrees.pop();
    }
  }

  private boolean isClassTree(Tree tree) {
    return tree.is(Tree.Kind.CLASS) || tree.is(Tree.Kind.INTERFACE) || tree.is(Tree.Kind.ENUM) || tree.is(Tree.Kind.ANNOTATION_TYPE);
  }

  private void saveMetricOnFile(Metric metric, double value) {
    sensorContext.saveMeasure(sonarFile, new Measure(metric, value));
  }

  @Override
  public void setCharset(Charset charset) {
    this.charset = charset;
  }
}
