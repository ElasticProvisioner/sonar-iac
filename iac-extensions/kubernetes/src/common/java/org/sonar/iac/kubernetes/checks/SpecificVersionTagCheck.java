/*
 * SonarQube IaC Plugin
 * Copyright (C) SonarSource Sàrl
 * mailto:info AT sonarsource DOT com
 *
 * You can redistribute and/or modify this program under the terms of
 * the Sonar Source-Available License Version 1, as published by SonarSource Sàrl.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the Sonar Source-Available License for more details.
 *
 * You should have received a copy of the Sonar Source-Available License
 * along with this program; if not, see https://sonarsource.com/license/ssal/
 */
package org.sonar.iac.kubernetes.checks;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.sonar.check.Rule;
import org.sonar.iac.common.checks.DockerImageReference;
import org.sonar.iac.common.checks.TextUtils;
import org.sonar.iac.common.yaml.object.BlockObject;
import org.sonar.iac.common.yaml.tree.TupleTree;
import org.sonar.iac.common.yaml.tree.YamlTree;
import org.sonar.iac.kubernetes.visitors.KubernetesCheckContext;

@Rule(key = "S6596")
public class SpecificVersionTagCheck extends AbstractKubernetesObjectCheck {
  public static final String MESSAGE = "Use a specific version tag for the image.";
  private static final String MESSAGE_SPECIFIC_FORMAT = "Use a specific version tag for the image instead of \"%s\".";
  protected static final String KIND_POD = "Pod";
  protected static final List<String> KIND_WITH_TEMPLATE = List.of("DaemonSet", "Deployment", "Job", "ReplicaSet", "ReplicationController", "StatefulSet", "CronJob");
  private static final Predicate<YamlTree> SENSITIVE_VERSION_TAG_PREDICATE = tree -> TextUtils.matchesValue(tree, SpecificVersionTagCheck::hasSensitiveVersionTag)
    .isTrue();

  @Override
  void initializeCheck(KubernetesCheckContext ctx) {
    ctx.setShouldReportSecondaryInValues(true);
  }

  @Override
  protected void registerObjectCheck() {
    register(KIND_POD, document -> checkDocument(document, false));
    register(KIND_WITH_TEMPLATE, document -> checkDocument(document, true));
  }

  private void checkDocument(BlockObject document, boolean isKindWithTemplate) {
    Stream<BlockObject> containers;

    if (isKindWithTemplate) {
      containers = document.block("template").block("spec").blocks("containers");
    } else {
      containers = document.blocks("containers");
    }
    containers
      .map(container -> container.attribute("image"))
      .filter(image -> image.isValue(sensitiveVersionTagPredicate()))
      .forEach(image -> image.reportOnValue(Optional.ofNullable(image.tree)
        .map(TupleTree::value)
        .map(SpecificVersionTagCheck::extractTag)
        .map(MESSAGE_SPECIFIC_FORMAT::formatted)
        .orElse(MESSAGE)));
  }

  public Predicate<YamlTree> sensitiveVersionTagPredicate() {
    return SENSITIVE_VERSION_TAG_PREDICATE;
  }

  public static boolean hasSensitiveVersionTag(String fullImageName) {
    // unresolved with Helm: do not raise an issue
    if (fullImageName.startsWith("$")) {
      return false;
    }
    return DockerImageReference.isLatest(withPlaceholderNameIfMissing(fullImageName));
  }

  public static boolean isSensitiveVersionTag(@Nullable String tag) {
    // Flag if explicit "latest" or no tag specified (implicit latest)
    return tag == null || "latest".equals(tag);
  }

  @Nullable
  private static String extractTag(YamlTree tree) {
    var imageValue = TextUtils.getValue(tree).orElse("");
    return DockerImageReference.parse(withPlaceholderNameIfMissing(imageValue)).map(DockerImageReference::tag).orElse(null);
  }

  // Helm substitutes an unresolved "{{ .Values.x }}" image name with an empty string, e.g. ":latest" - DockerImageReference
  // requires a non-blank name, but this check only cares about the tag/digest, so a placeholder name is good enough.
  private static String withPlaceholderNameIfMissing(String imageReference) {
    if (imageReference.startsWith(":") || imageReference.startsWith("@")) {
      return "unresolved" + imageReference;
    }
    return imageReference;
  }
}
