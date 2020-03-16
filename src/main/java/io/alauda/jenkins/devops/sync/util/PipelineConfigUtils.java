package io.alauda.jenkins.devops.sync.util;

import static io.alauda.jenkins.devops.sync.constants.Constants.PIPELINECONFIG_KIND;
import static io.alauda.jenkins.devops.sync.constants.Constants.PIPELINECONFIG_KIND_MULTI_BRANCH;

import hudson.Plugin;
import hudson.util.VersionNumber;
import io.alauda.devops.java.client.models.*;
import io.alauda.jenkins.devops.sync.constants.Constants;
import io.alauda.jenkins.devops.sync.constants.ErrorMessages;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import javax.annotation.Nonnull;
import jenkins.model.Jenkins;
import org.apache.commons.collections4.CollectionUtils;

public abstract class PipelineConfigUtils {
  private static final Logger logger = Logger.getLogger(PipelineConfigUtils.class.getName());

  private PipelineConfigUtils() {}

  public static boolean isSerialPolicy(V1alpha1PipelineConfig pipelineConfig) {
    if (pipelineConfig == null) {
      throw new IllegalArgumentException("param can't be null");
    }

    return Constants.PIPELINE_RUN_POLICY_SERIAL.equals(pipelineConfig.getSpec().getRunPolicy());
  }

  public static boolean isParallel(V1alpha1PipelineConfig pipelineConfig) {
    if (pipelineConfig == null) {
      throw new IllegalArgumentException("param can't be null");
    }

    return Constants.PIPELINE_RUN_POLICY_PARALLEL.equals(pipelineConfig.getSpec().getRunPolicy());
  }

  /**
   * Check PipelineConfig dependency
   *
   * @param pipelineConfig PipelineConfig
   * @param conditions condition list
   */
  public static void dependencyCheck(
      @Nonnull V1alpha1PipelineConfig pipelineConfig, @Nonnull List<V1alpha1Condition> conditions) {
    boolean fromTpl = createFromTpl(pipelineConfig);
    if (!fromTpl) {
      // just care about template case
      return;
    }

    V1alpha1PipelineConfigTemplate template = pipelineConfig.getSpec().getStrategy().getTemplate();
    V1alpha1PipelineDependency dependencies = template.getSpec().getDependencies();
    if (dependencies == null || CollectionUtils.isEmpty(dependencies.getPlugins())) {
      logger.info(
          "PipelineConfig " + pipelineConfig.getMetadata().getName() + " no any dependencies.");
      return;
    }

    final Jenkins jenkins = Jenkins.getInstance();
    dependencies
        .getPlugins()
        .forEach(
            plugin -> {
              String name = plugin.getName();
              String version = plugin.getVersion();
              VersionNumber verNumber = new VersionNumber(version);
              VersionNumber currentNumber;

              V1alpha1Condition condition = new V1alpha1Condition();
              condition.setReason(ErrorMessages.PLUGIN_ERROR);

              Plugin existsPlugin = jenkins.getPlugin(name);
              if (existsPlugin == null) {

                condition.setMessage(String.format("Lack plugin: %s, version: %s", name, version));
              } else {
                currentNumber = existsPlugin.getWrapper().getVersionNumber();

                if (currentNumber.isOlderThan(verNumber)) {
                  condition.setMessage(
                      String.format(
                          "Require plugin: %s, version: %s, found %s",
                          name, version, currentNumber));
                }
              }

              if (condition.getMessage() != null) {
                conditions.add(condition);
              }
            });
  }

  /**
   * Whether PipelineConfig is create from a template
   *
   * @param pipelineConfig PipelineConfig
   * @return whether PipelineConfig is create from a template
   */
  public static boolean createFromTpl(@Nonnull V1alpha1PipelineConfig pipelineConfig) {
    V1alpha1PipelineConfigTemplate template = pipelineConfig.getSpec().getStrategy().getTemplate();

    return template != null && template.getSpec() != null;
  }

  public static boolean isMultiBranch(@Nonnull V1alpha1PipelineConfig pipelineConfig) {
    Map<String, String> labels = pipelineConfig.getMetadata().getLabels();
    return (labels != null
        && PIPELINECONFIG_KIND_MULTI_BRANCH.equals(labels.get(PIPELINECONFIG_KIND)));
  }

  public static void updateDisabledStatus(@Nonnull V1alpha1PipelineConfig pipelineConfig, boolean disabled) {
      PipelineConfigUtils.updateDisabledStatus(pipelineConfig, disabled, "Jenkins");
  }

  public static void updateDisabledStatus(@Nonnull V1alpha1PipelineConfig pipelineConfig, boolean disabled, String trigger) {
      if (!pipelineConfig.getSpec().isDisabled().equals(disabled)) {
          pipelineConfig.getSpec().setDisabled(disabled);
          String reason = disabled ? "Disabled" : "Enabled";
          PipelineConfigUtils.addCondition(pipelineConfig, "Specific Modified", "OK",
                  reason, String.format("PipelineConfig %s by %s", reason, trigger));
      }
  }

  public static void addCondition(@Nonnull V1alpha1PipelineConfig pipelineConfig, String type, String status, String reason, String message) {
      if (pipelineConfig.getStatus() == null) {
          pipelineConfig.setStatus(new V1alpha1PipelineConfigStatus());
      }

      if (pipelineConfig.getStatus().getConditions() == null) {
          pipelineConfig.getStatus().setConditions(new ArrayList<>());
      }

      V1alpha1Condition condition = new V1alpha1Condition();
      condition.setReason(reason);
      condition.setMessage(message);
      condition.setType(type);
      condition.setStatus(status);
      pipelineConfig.getStatus().getConditions().add(condition);
  }
}
