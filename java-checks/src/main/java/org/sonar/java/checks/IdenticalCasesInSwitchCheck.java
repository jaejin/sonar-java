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
import com.google.common.collect.Sets;
import org.sonar.check.Priority;
import org.sonar.check.Rule;
import org.sonar.java.model.JavaTree;
import org.sonar.java.model.SyntacticEquivalence;
import org.sonar.plugins.java.api.tree.CaseGroupTree;
import org.sonar.plugins.java.api.tree.CaseLabelTree;
import org.sonar.plugins.java.api.tree.SwitchStatementTree;
import org.sonar.plugins.java.api.tree.Tree;

import java.util.List;
import java.util.Set;

@Rule(
    key = "S1871",
    priority = Priority.MAJOR,
    tags = {"bug"})
public class IdenticalCasesInSwitchCheck extends SubscriptionBaseVisitor {

  @Override
  public List<Tree.Kind> nodesToVisit() {
    return ImmutableList.of(Tree.Kind.SWITCH_STATEMENT);
  }

  @Override
  public void visitNode(Tree tree) {
    SwitchStatementTree switchStatementTree = (SwitchStatementTree) tree;
    int index = 0;
    List<CaseGroupTree> cases = switchStatementTree.cases();
    Set<CaseLabelTree> reportedLabels = Sets.newHashSet();
    for (CaseGroupTree caseGroupTree : cases) {
      index++;
      for (int i = index; i < cases.size(); i++) {
        if (SyntacticEquivalence.areEquivalent(caseGroupTree.body(), cases.get(i).body())) {
          CaseLabelTree labelToReport = getLastLabel(cases.get(i));
          if (!reportedLabels.contains(labelToReport)) {
            reportedLabels.add(labelToReport);
            addIssue(labelToReport,
                "Either merge this case with the identical one on line \"" + ((JavaTree) getLastLabel(caseGroupTree)).getLine() + "\" or change one of the implementations.");
          }
        }
      }
    }
  }

  private CaseLabelTree getLastLabel(CaseGroupTree cases) {
    return cases.labels().get(cases.labels().size() - 1);
  }

}
