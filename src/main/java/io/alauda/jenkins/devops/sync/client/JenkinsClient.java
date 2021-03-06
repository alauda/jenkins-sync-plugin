package io.alauda.jenkins.devops.sync.client;

import static io.alauda.jenkins.devops.sync.constants.Annotations.MULTI_BRANCH_NAME;
import static io.alauda.jenkins.devops.sync.constants.Constants.FOLDER_DESCRIPTION;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.BulkChange;
import hudson.model.*;
import hudson.model.Queue;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.triggers.SafeTimerTask;
import hudson.util.XStream2;
import io.alauda.devops.java.client.apis.DevopsAlaudaIoV1alpha1Api;
import io.alauda.devops.java.client.models.V1alpha1Jenkins;
import io.alauda.devops.java.client.models.V1alpha1Pipeline;
import io.alauda.devops.java.client.models.V1alpha1PipelineConfig;
import io.alauda.devops.java.client.utils.PatchGenerator;
import io.alauda.jenkins.devops.sync.*;
import io.alauda.jenkins.devops.sync.exception.PipelineConfigConvertException;
import io.alauda.jenkins.devops.sync.exception.PipelineException;
import io.alauda.jenkins.devops.sync.mapper.PipelineConfigMapper;
import io.alauda.jenkins.devops.sync.util.JenkinsUtils;
import io.alauda.jenkins.devops.sync.util.NamespaceName;
import io.alauda.jenkins.devops.sync.util.PipelineConfigUtils;
import io.alauda.jenkins.devops.sync.util.PipelineUtils;
import io.kubernetes.client.custom.V1Patch;
import io.kubernetes.client.openapi.ApiException;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamSource;
import jenkins.model.Jenkins;
import jenkins.util.Timer;
import org.apache.commons.lang3.StringUtils;
import org.apache.tools.ant.filters.StringInputStream;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// TODO move all jenkins related operation into this class
public class JenkinsClient {

  private Logger logger = LoggerFactory.getLogger(JenkinsClient.class.getName());

  private Map<NamespaceName, TopLevelItem> cachedJobMap;
  private Jenkins jenkins;
  private PipelineConfigMapper mapper;
  private Set<NamespaceName> deleteInProgress;

  private static JenkinsClient instance = new JenkinsClient();

  public static JenkinsClient getInstance() {
    return instance;
  }

  private JenkinsClient() {
    cachedJobMap = new ConcurrentHashMap<>();
    jenkins = Jenkins.get();

    mapper = new PipelineConfigMapper();
    deleteInProgress = new HashSet<>();
  }

  @CheckForNull
  public Item getItem(NamespaceName namespaceName) {
    TopLevelItem item = cachedJobMap.get(namespaceName);
    if (item != null) {
      return item;
    }

    try (ACLContext ignored = ACL.as(ACL.SYSTEM)) {
      String jobPath = mapper.jenkinsJobPath(namespaceName.getNamespace(), namespaceName.getName());
      return jenkins.getItemByFullName(jobPath);
    }
  }

  public boolean isCreatedByPipelineConfig(Item item) {
    if (item instanceof WorkflowJob) {
      return getWorkflowJobProperty((WorkflowJob) item) != null;
    }

    if (item instanceof WorkflowMultiBranchProject) {
      return getMultiBranchProperty((WorkflowMultiBranchProject) item) != null;
    }

    return false;
  }

  /**
   * Get workflow job by namespace and name
   *
   * @param namespaceName namespace and name
   * @return Workflow job that mapped by namespace and name, null if not correspondent job exists.
   */
  @CheckForNull
  public WorkflowJob getJob(NamespaceName namespaceName) {
    Item item = getItem(namespaceName);

    if (item == null) {
      return null;
    }

    if (item instanceof WorkflowJob) {
      return (WorkflowJob) item;
    }
    return null;
  }

  /**
   * Get WorkflowMultiBranchProject by namespace and name
   *
   * @param namespaceName namespace and name
   * @return WorkflowMultiBranchProject that mapped by namespace and name, null if not correspondent
   *     job exists.
   */
  @CheckForNull
  public WorkflowMultiBranchProject getMultiBranchProject(NamespaceName namespaceName) {
    Item item = getItem(namespaceName);

    if (item == null) {
      return null;
    }

    if (item instanceof WorkflowMultiBranchProject) {
      return (WorkflowMultiBranchProject) item;
    }
    return null;
  }

  /**
   * Get workflow job by Pipeline and PipelineConfig. if we want get job from multi-branch project,
   * we should call this method.
   *
   * @param pipeline Pipeline
   * @param pipelineConfig PipelineConfig
   * @return Workflow job found by Pipeline and PipelineConfig
   */
  @CheckForNull
  public WorkflowJob getJob(V1alpha1Pipeline pipeline, V1alpha1PipelineConfig pipelineConfig) {
    Item item =
        getItem(
            new NamespaceName(
                pipelineConfig.getMetadata().getNamespace(),
                pipelineConfig.getMetadata().getName()));

    if (item == null) {
      return null;
    }

    if (item instanceof WorkflowJob) {
      return (WorkflowJob) item;
    }

    if (item instanceof WorkflowMultiBranchProject) {
      WorkflowMultiBranchProject project = (WorkflowMultiBranchProject) item;
      String branchName =
          pipeline.getMetadata().getAnnotations().get(MULTI_BRANCH_NAME.get().toString());

      if (StringUtils.isEmpty(branchName)) {
        return null;
      }

      try (ACLContext ignore = ACL.as(ACL.SYSTEM)) {
        return project.getItemByBranchName(branchName);
      }
    }

    return null;
  }

  /**
   * whether has synced job in Jenkins
   *
   * @param pipelineConfig PipelineConfig
   * @return true if PipelineConfig has synced Jenkins job
   */
  public boolean hasSyncedJenkinsJob(V1alpha1PipelineConfig pipelineConfig) {
    String namespace = pipelineConfig.getMetadata().getNamespace();
    String name = pipelineConfig.getMetadata().getName();
    NamespaceName namespaceName = new NamespaceName(namespace, name);

    Item item = getItem(namespaceName);
    if (item == null) {
      return false;
    }

    if (item instanceof WorkflowJob) {
      WorkflowJob job = (WorkflowJob) item;
      WorkflowJobProperty property = getWorkflowJobProperty(job);
      if (property == null) {
        return false;
      }

      return pipelineConfig
          .getMetadata()
          .getResourceVersion()
          .equals(property.getResourceVersion());
    }

    if (item instanceof WorkflowMultiBranchProject) {
      WorkflowMultiBranchProject job = (WorkflowMultiBranchProject) item;
      MultiBranchProperty property = getMultiBranchProperty(job);
      if (property == null) {
        return false;
      }

      return pipelineConfig
          .getMetadata()
          .getResourceVersion()
          .equals(property.getResourceVersion());
    }

    return false;
  }

  @CheckForNull
  public WorkflowJobProperty getWorkflowJobProperty(@Nonnull WorkflowJob job) {
    WorkflowJobProperty wfJobProperty = job.getProperty(WorkflowJobProperty.class);
    if (wfJobProperty == null) {
      // if cannot found WorkflowJobProperty from job, try to find PipelineConfigProjectProperty
      return job.getProperty(PipelineConfigProjectProperty.class);
    }
    return wfJobProperty;
  }

  @CheckForNull
  public MultiBranchProperty getMultiBranchProperty(@Nonnull WorkflowMultiBranchProject job) {
    return job.getProperties().get(MultiBranchProperty.class);
  }

  public boolean upsertJob(V1alpha1PipelineConfig pipelineConfig)
      throws IOException, PipelineConfigConvertException {
    String namespace = pipelineConfig.getMetadata().getNamespace();
    String name = pipelineConfig.getMetadata().getName();
    NamespaceName namespaceName = new NamespaceName(namespace, name);

    logger.debug("Starting upsert Jenkins job");
    try (ACLContext ignored = ACL.as(ACL.SYSTEM)) {
      Item jobInJenkins = getItem(namespaceName);
      if (jobInJenkins != null) {
        logger.debug(
            "Found correspondent Jenkins job {} for PipelineConfig '{}/{}'",
            jobInJenkins.getDisplayName(),
            namespace,
            name);
      }

      TopLevelItem jobInMemory = mapper.mapTo(pipelineConfig);
      InputStream jobStream = new StringInputStream(new XStream2().toXML(jobInMemory));

      // TODO add a checker to check if this item is valid

      // we should create a new job
      if (jobInJenkins == null) {
        ItemGroup parent = jobInMemory.getParent();
        if (parent instanceof Folder) {
          ((Folder) parent)
              .createProjectFromXML(mapper.jenkinsJobName(namespace, name), jobStream)
              .save();
        } else {
          // TODO throw an exception here
        }
      } else {
        ((AbstractItem) jobInJenkins).updateByXml(((Source) new StreamSource(jobStream)));
      }

      Item item = getItem(namespaceName);
      if (item == null) {
        throw new PipelineConfigConvertException(
            String.format(
                "Failed to create Jenkins job for PipelineConfig '%s/%s'", namespace, name));
      }

      TopLevelItem job = (TopLevelItem) item;
      if (cachedJobMap.putIfAbsent(namespaceName, job) == null) {
        logger.debug(
            "Added PipelineConfig '{}/{}', phase {} to in-memory cache map",
            namespace,
            name,
            pipelineConfig.getStatus().getPhase());
      }
    }
    return true;
  }

  public boolean deleteJob(NamespaceName namespaceName) throws IOException, InterruptedException {
    TopLevelItem job = cachedJobMap.remove(namespaceName);

    // if we cannot find job in cache, try to find it from jenkins
    if (job == null) {
      Item item = getItem(namespaceName);

      if (item == null) {
        logger.warn(
            "Unable to delete correspondent Jenkins job for '{}/{}', not job found in jenkins",
            namespaceName.getNamespace(),
            namespaceName.getName());
        return false;
      }

      if (!(item instanceof WorkflowJob || item instanceof WorkflowMultiBranchProject)) {
        logger.warn(
            "Unable to delete correspondent Jenkins job for '{}/{}', except WorkflowJob or WorkflowMultiBranchProject but found {}",
            namespaceName.getNamespace(),
            namespaceName.getName(),
            item.getClass().getName());
        return false;
      }

      job = (TopLevelItem) item;
    }
    try (ACLContext ignored = ACL.as(ACL.SYSTEM)) {
      deleteInProgress.add(namespaceName);
      job.delete();
      return true;
    } finally {
      deleteInProgress.remove(namespaceName);
    }
  }

  private static final Pattern PIPELINE_CONFIG_EXACT_PATTERN =
      Pattern.compile("(.*)(-([\\w]{5}|\\d+))");

  public boolean deletePipeline(NamespaceName pipelineNamespaceName) {
    String namespace = pipelineNamespaceName.getNamespace();
    String name = pipelineNamespaceName.getName();

    logger.debug(
        "Starting to delete pipeline '{}/{}', try to cancel it first if it is build",
        namespace,
        name);
    try {
      cancelPipeline(pipelineNamespaceName);
    } catch (PipelineException e) {
      logger.debug(
          "Failed to canceled '{}/{}', build might be completed, reason {}",
          namespace,
          name,
          e.getMessage());
    }

    V1alpha1PipelineConfig pipelineConfig = getPipelineConfigFromPipeline(pipelineNamespaceName);
    if (pipelineConfig == null) {
      logger.error(
          "Unable to delete pipeline '{}/{}', reason: cannot find pipelineConfig", namespace, name);
      return false;
    }

    if (PipelineConfigUtils.isMultiBranch(pipelineConfig)) {
      WorkflowMultiBranchProject multiBranchProject =
          getMultiBranchProject(
              new NamespaceName(namespace, pipelineConfig.getMetadata().getName()));
      if (multiBranchProject == null) {
        logger.error(
            "Unable to delete pipeline, reason: cannot find correspondent multi-branch job");
        return false;
      }

      boolean deleted = false;
      try (ACLContext ignore = ACL.as(ACL.SYSTEM)) {
        for (WorkflowJob job : multiBranchProject.getItems()) {
          deleted = deletePipeline(pipelineNamespaceName, job);
        }
      }
      return deleted;
    } else {
      WorkflowJob job =
          getJob(new NamespaceName(namespace, pipelineConfig.getMetadata().getName()));
      if (job == null) {
        logger.error("Unable to delete pipeline, reason: cannot find correspondent workflow job");
        return false;
      }
      return deletePipeline(pipelineNamespaceName, job);
    }
  }

  public boolean deletePipeline(NamespaceName namespaceName, WorkflowJob job) {
    try (ACLContext ignore = ACL.as(ACL.SYSTEM)) {
      for (WorkflowRun run : job.getBuilds()) {
        JenkinsPipelineCause cause = PipelineUtils.findAlaudaCause(run);
        if (cause != null
            && cause.getName().equals(namespaceName.getName())
            && cause.getNamespace().equals(namespaceName.getNamespace())) {
          JenkinsUtils.deleteRun(run);
          return true;
        }
      }
    }
    return false;
  }

  public void cancelPipeline(NamespaceName pipelineNamespaceName) throws PipelineException {
    String namespace = pipelineNamespaceName.getNamespace();
    String name = pipelineNamespaceName.getName();

    V1alpha1PipelineConfig pipelineConfig = getPipelineConfigFromPipeline(pipelineNamespaceName);
    if (pipelineConfig == null) {
      throw new PipelineException("Unable to cancel build, reason: cannot find pipelineConfig");
    }

    // cancel if in the queue
    try (ACLContext ignore = ACL.as(ACL.SYSTEM)) {
      Queue pipelineQueue = jenkins.getQueue();
      Optional<Queue.Item> buildInQueue =
          Arrays.stream(pipelineQueue.getItems())
              .filter(
                  item ->
                      PipelineUtils.findAllAlaudaCauses(item)
                          .stream()
                          .anyMatch(
                              cause ->
                                  cause.getNamespace().equals(namespace)
                                      && cause.getName().equals(name)))
              .findFirst();

      // try to cancel the build if it is in the queue
      if (buildInQueue.isPresent()) {
        logger.debug(
            "canceling build: %s,  pipeline: %s/%s  in queue",
            buildInQueue.get().getDisplayName(),
            pipelineNamespaceName.getNamespace(),
            pipelineNamespaceName.getName());
        if (pipelineQueue.cancel(buildInQueue.get())) {
          return;
        } else {
          logger.debug("Unable to cancel build in queue, build might leave the queue");
        }
      }
    }

    boolean canceled = false;
    if (PipelineConfigUtils.isMultiBranch(pipelineConfig)) {
      WorkflowMultiBranchProject multiBranchProject =
          getMultiBranchProject(
              new NamespaceName(namespace, pipelineConfig.getMetadata().getName()));
      if (multiBranchProject == null) {
        throw new PipelineException(
            "Unable to cancel build, reason: cannot find correspondent multi-branch job");
      }

      try (ACLContext ignore = ACL.as(ACL.SYSTEM)) {
        for (WorkflowJob job : multiBranchProject.getItems()) {
          canceled = cancelPipeline(pipelineNamespaceName, job);
        }
      }
    } else {
      WorkflowJob job =
          getJob(new NamespaceName(namespace, pipelineConfig.getMetadata().getName()));
      if (job == null) {
        throw new PipelineException(
            "Unable to cancel build, reason: cannot find correspondent workflow job");
      }
      canceled = cancelPipeline(pipelineNamespaceName, job);
    }
    if (!canceled) {
      throw new PipelineException(
          "Unable to cancel build, reason: cannot find correspondent running build");
    }
  }

  public boolean cancelPipeline(NamespaceName namespaceName, WorkflowJob job) {
    try (ACLContext ignore = ACL.as(ACL.SYSTEM)) {
      for (WorkflowRun run : job.getBuilds()) {
        JenkinsPipelineCause cause = PipelineUtils.findAlaudaCause(run);
        if (cause != null
            && cause.getName().equals(namespaceName.getName())
            && cause.getNamespace().equals(namespaceName.getNamespace())) {
          if (run.hasntStartedYet() || run.isBuilding()) {
            terminateRun(run);
            return true;
          } else {
            logger.debug(
                String.format(
                    "run hasStartedYet:%s, isBuilding:%s, run: %s",
                    run.hasntStartedYet(),
                    run.isBuilding(), run.getFullDisplayName()));
          }
        }
      }
    }

    logger.debug(
        String.format(
            "has no run in current builds of this pipeline: %s/%s",
            namespaceName.getNamespace(),
            namespaceName.getName()));
    return false;
  }

  public void terminateRun(final WorkflowRun run) {
    try (ACLContext ignore = ACL.as(ACL.SYSTEM)) {
      run.doTerm();
      Timer.get()
          .schedule(
              new SafeTimerTask() {
                @Override
                public void doRun() {
                  try (ACLContext ignore = ACL.as(ACL.SYSTEM)) {
                    logger.debug("terminate run: {}, doKill", run.getFullDisplayName());
                    run.doKill();
                  }
                }
              },
              5,
              TimeUnit.SECONDS);
    }
  }

  public V1alpha1PipelineConfig getPipelineConfigFromPipeline(NamespaceName namespaceName) {
    String namespace = namespaceName.getNamespace();
    String name = namespaceName.getName();

    Matcher pipelineConfigNameMatcher = PIPELINE_CONFIG_EXACT_PATTERN.matcher(name);
    if (!pipelineConfigNameMatcher.matches()) {
      logger.warn("Unable to exact pipelineConfig name from Pipeline '{}/{}'", namespace, name);
      return null;
    }

    String pipelineConfigName = pipelineConfigNameMatcher.group(1);

    return Clients.get(V1alpha1PipelineConfig.class)
        .lister()
        .namespace(namespace)
        .get(pipelineConfigName);
  }

  public Folder getFolder(String folderName) {
    try (ACLContext ignored = ACL.as(ACL.SYSTEM)) {
      Item folder = jenkins.getItemByFullName(folderName);
      if (folder == null) {
        return null;
      }

      if (!(folder instanceof Folder)) {
        return null;
      }

      return (Folder) folder;
    }
  }

  public Folder upsertFolder(String folderName) throws IOException {
    try (ACLContext ignored = ACL.as(ACL.SYSTEM)) {
      Folder folder = getFolder(folderName);
      if (folder != null) {
        AlaudaFolderProperty alaPro = folder.getProperties().get(AlaudaFolderProperty.class);
        if (alaPro == null) {
          folder.addProperty(new AlaudaFolderProperty());
        } else {
          alaPro.setDirty(false);
        }
        folder.save();
        return folder;
      } else {
        folder = new Folder(jenkins, folderName);
        folder.setDescription(FOLDER_DESCRIPTION + folderName);
        folder.addProperty(new AlaudaFolderProperty());
        BulkChange bk = new BulkChange(folder);
        InputStream jobStream = new StringInputStream(new XStream2().toXML(folder));

        jenkins.createProjectFromXML(folderName, jobStream).save();
        bk.commit();

        // lets look it up again to be sure
        folder = getFolder(folderName);
        if (folder != null) {
          return folder;
        }
      }
    }
    return null;
  }

  public Jenkins getJenkins() {
    return jenkins;
  }

  public boolean isDeleteInProgress(String namespace, String name) {
    return deleteInProgress.contains(new NamespaceName(namespace, name));
  }

  public boolean updateJenkins(V1alpha1Jenkins oldJenkins, V1alpha1Jenkins newJenkins) {
    String name = oldJenkins.getMetadata().getName();

    String patch;
    try {
      patch = new PatchGenerator().generatePatchBetween(oldJenkins, newJenkins);
    } catch (IOException e) {
      logger.warn(
          "Failed to update Jenkins '{}', unable to generate patch, reason: {}",
          name,
          e.getMessage());
      return false;
    }

    logger.debug("Patch: {}", patch);

    DevopsAlaudaIoV1alpha1Api api = new DevopsAlaudaIoV1alpha1Api();
    try {
      api.patchJenkins(name, new V1Patch(patch), null, null, null, null);
    } catch (ApiException e) {
      logger.warn("Failed to update Jenkins '{}', reason: {}", name, e.getMessage());
      return false;
    }
    return true;
  }
}
