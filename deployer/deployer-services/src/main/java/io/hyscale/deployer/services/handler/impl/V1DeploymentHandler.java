/**
 * Copyright 2019 Pramati Prism, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.hyscale.deployer.services.handler.impl;

import com.google.common.collect.Lists;
import com.google.gson.JsonSyntaxException;
import io.hyscale.commons.constants.K8SRuntimeConstants;
import io.hyscale.commons.exception.HyscaleException;
import io.hyscale.commons.logger.ActivityContext;
import io.hyscale.commons.logger.WorkflowLogger;
import io.hyscale.commons.models.AnnotationKey;
import io.hyscale.commons.models.Status;
import io.hyscale.commons.utils.ResourceLabelUtil;
import io.hyscale.commons.utils.ResourceSelectorUtil;
import io.hyscale.deployer.core.model.DeploymentStatus;
import io.hyscale.deployer.core.model.ResourceKind;
import io.hyscale.deployer.core.model.ResourceOperation;
import io.hyscale.deployer.services.exception.DeployerErrorCodes;
import io.hyscale.deployer.services.handler.PodParentHandler;
import io.hyscale.deployer.services.handler.ResourceHandlers;
import io.hyscale.deployer.services.handler.ResourceLifeCycleHandler;
import io.hyscale.deployer.services.model.DeployerActivity;
import io.hyscale.deployer.services.model.ResourceStatus;
import io.hyscale.deployer.services.util.ExceptionHelper;
import io.hyscale.deployer.services.util.K8sResourcePatchUtil;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.AppsV1Api;
import io.kubernetes.client.openapi.models.*;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.kubernetes.client.custom.V1Patch;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class V1DeploymentHandler extends PodParentHandler<V1Deployment> implements ResourceLifeCycleHandler<V1Deployment> {
    private static final Logger LOGGER = LoggerFactory.getLogger(V1DeploymentHandler.class);

    @Override
    public V1Deployment create(ApiClient apiClient, V1Deployment resource, String namespace) throws HyscaleException {
        if (resource == null) {
            LOGGER.debug("Cannot create null Deployment");
            return resource;
        }
        WorkflowLogger.startActivity(DeployerActivity.DEPLOYING_DEPLOYMENT);
        AppsV1Api appsV1Api = new AppsV1Api(apiClient);
        V1Deployment v1Deployment = null;
        try {
            resource.getMetadata().putAnnotationsItem(
                    AnnotationKey.K8S_HYSCALE_LAST_APPLIED_CONFIGURATION.getAnnotation(), gson.toJson(resource));

            v1Deployment = appsV1Api.createNamespacedDeployment(namespace, resource, TRUE, null, null);
        } catch (ApiException e) {
            HyscaleException ex = new HyscaleException(e, DeployerErrorCodes.FAILED_TO_CREATE_RESOURCE,
                    ExceptionHelper.getExceptionMessage(getKind(), e, ResourceOperation.CREATE));
            LOGGER.error("Error while creating Deployment {} in namespace {}, error {}", v1Deployment, namespace,
                    ex.toString());
            WorkflowLogger.endActivity(Status.FAILED);
            throw ex;
        }
        WorkflowLogger.endActivity(Status.DONE);
        return v1Deployment;
    }

    @Override
    public boolean update(ApiClient apiClient, V1Deployment resource, String namespace) throws HyscaleException {
        if (resource == null) {
            LOGGER.debug("Cannot update null Deployment");
            return false;
        }
        AppsV1Api appsV1Api = new AppsV1Api(apiClient);
        String name = resource.getMetadata().getName();
        V1Deployment existingDeployment = null;
        try {
            existingDeployment = get(apiClient, name, namespace);
        } catch (HyscaleException ex) {
            LOGGER.debug("Error while getting Deployment {} in namespace {} for Update, creating new", name, namespace);
            V1Deployment deployment = create(apiClient, resource, namespace);
            return deployment != null ? true : false;
        }
        WorkflowLogger.startActivity(DeployerActivity.DEPLOYING_DEPLOYMENT);
        try {
            String resourceVersion = existingDeployment.getMetadata().getResourceVersion();
            resource.getMetadata().setResourceVersion(resourceVersion);
            appsV1Api.replaceNamespacedDeployment(name, namespace, existingDeployment, TRUE, null, null);
        } catch (ApiException e) {
            HyscaleException ex = new HyscaleException(e, DeployerErrorCodes.FAILED_TO_UPDATE_RESOURCE,
                    ExceptionHelper.getExceptionMessage(getKind(), e, ResourceOperation.UPDATE));
            LOGGER.error("Error while updating Deployment {} in namespace {}, error {}", name, namespace,
                    ex.toString());
            WorkflowLogger.endActivity(Status.FAILED);
            throw ex;
        }
        WorkflowLogger.endActivity(Status.DONE);
        return true;
    }

    @Override
    public V1Deployment get(ApiClient apiClient, String name, String namespace) throws HyscaleException {
        AppsV1Api appsV1Api = new AppsV1Api(apiClient);
        V1Deployment v1Deployment = null;
        try {
            v1Deployment = appsV1Api.readNamespacedDeployment(name, namespace, TRUE, false, false);
        } catch (ApiException e) {
            HyscaleException ex = ExceptionHelper.buildGetException(getKind(), e, ResourceOperation.GET);
            LOGGER.error("Error while fetching Deployment {} in namespace {}, error {} ", name, namespace,
                    ex.toString());
            throw ex;
        }
        return v1Deployment;
    }

    @Override
    public List<V1Deployment> getBySelector(ApiClient apiClient, String selector, boolean label, String namespace) throws HyscaleException {
        AppsV1Api appsV1Api = new AppsV1Api(apiClient);
        List<V1Deployment> v1Deployments = null;
        try {
            String labelSelector = label ? selector : null;
            String fieldSelector = label ? null : selector;

            V1DeploymentList v1DeploymentList = appsV1Api.listNamespacedDeployment(namespace, TRUE, null,
                    null, fieldSelector, labelSelector, null, null, null, null);

            v1Deployments = v1DeploymentList != null ? v1DeploymentList.getItems() : null;
        } catch (ApiException e) {
            HyscaleException ex = ExceptionHelper.buildGetException(getKind(), e, ResourceOperation.GET_BY_SELECTOR);
            LOGGER.error("Error while listing Deployments in namespace {}, with selectors {}, error {} ", namespace,
                    selector, ex.toString());
            throw ex;
        }
        return v1Deployments;
    }

    @Override
    public boolean patch(ApiClient apiClient, String name, String namespace, V1Deployment target) throws HyscaleException {
        if (target == null) {
            LOGGER.debug("Cannot patch null Deployment");
            return false;
        }
        AppsV1Api appsV1Api = new AppsV1Api(apiClient);
        target.getMetadata().putAnnotationsItem(AnnotationKey.K8S_HYSCALE_LAST_APPLIED_CONFIGURATION.getAnnotation(),
                gson.toJson(target));
        V1Deployment sourceDeployment = null;
        try {
            sourceDeployment = get(apiClient, name, namespace);
        } catch (HyscaleException e) {
            LOGGER.debug("Error while getting Deployment {} in namespace {} for Patch, creating new", name, namespace);
            V1Deployment deployment = create(apiClient, target, namespace);
            return deployment != null ? true : false;
        }
        WorkflowLogger.startActivity(DeployerActivity.DEPLOYING_DEPLOYMENT);
        Object patchObject = null;
        String lastAppliedConfig = sourceDeployment.getMetadata().getAnnotations()
                .get(AnnotationKey.K8S_HYSCALE_LAST_APPLIED_CONFIGURATION.getAnnotation());
        try {
            patchObject = K8sResourcePatchUtil.getJsonPatch(gson.fromJson(lastAppliedConfig, V1Deployment.class),
                    target, V1Deployment.class);
            V1Patch v1Patch = new V1Patch(patchObject.toString());
            appsV1Api.patchNamespacedDeployment(name, namespace, v1Patch, TRUE, null, null, null);
        } catch (HyscaleException e) {
            LOGGER.error("Error while creating patch for Deployment {}, source {}, target {}", name, sourceDeployment,
                    target);
            WorkflowLogger.endActivity(Status.FAILED);
            throw e;
        } catch (ApiException e) {
            HyscaleException ex = new HyscaleException(e, DeployerErrorCodes.FAILED_TO_PATCH_RESOURCE,
                    ExceptionHelper.getExceptionMessage(getKind(), e, ResourceOperation.PATCH));
            LOGGER.error("Error while patching Deployment {} in namespace {} , error {}", name, namespace,
                    ex.toString());
            WorkflowLogger.endActivity(Status.FAILED);
            throw ex;
        }
        WorkflowLogger.endActivity(Status.DONE);
        return true;
    }

    @Override
    public boolean delete(ApiClient apiClient, String name, String namespace, boolean wait) throws HyscaleException {
        AppsV1Api appsV1Api = new AppsV1Api(apiClient);
        V1DeleteOptions deleteOptions = getDeleteOptions();
        deleteOptions.setApiVersion("apps/v1");
        ActivityContext activityContext = new ActivityContext(DeployerActivity.DELETING_DEPLOYMENT);
        WorkflowLogger.startActivity(activityContext);
        try {
            try {
                appsV1Api.deleteNamespacedDeployment(name, namespace, TRUE, null, null, null, null, deleteOptions);
            } catch (JsonSyntaxException e) {
                // K8s end exception ignore
            }
            List<String> pendingDeployments = Lists.newArrayList();
            pendingDeployments.add(name);
            if (wait) {
                waitForResourceDeletion(apiClient, pendingDeployments, namespace, activityContext);
            }
        } catch (ApiException e) {
            if (e.getCode() == 404) {
                WorkflowLogger.endActivity(activityContext, Status.NOT_FOUND);
                return false;
            }
            HyscaleException ex = new HyscaleException(e, DeployerErrorCodes.FAILED_TO_DELETE_RESOURCE,
                    ExceptionHelper.getExceptionMessage(getKind(), e, ResourceOperation.DELETE));
            LOGGER.error("Error while deleting Deployment {} in namespace {}, error {} ", name, namespace,
                    ex.toString());
            WorkflowLogger.endActivity(activityContext, Status.FAILED);
            throw ex;
        }
        WorkflowLogger.endActivity(activityContext, Status.DONE);
        return true;
    }

    @Override
    public boolean deleteBySelector(ApiClient apiClient, String selector, boolean label, String namespace, boolean wait) throws HyscaleException {
        V1DeleteOptions deleteOptions = getDeleteOptions();
        deleteOptions.setApiVersion("apps/v1");
        try {
            List<V1Deployment> deploymentList = getBySelector(apiClient, selector, label, namespace);
            if (deploymentList == null || deploymentList.isEmpty()) {
                return false;
            }
            for (V1Deployment deployment : deploymentList) {
                delete(apiClient, deployment.getMetadata().getName(), namespace, wait);
            }
        } catch (HyscaleException e) {
            if (DeployerErrorCodes.RESOURCE_NOT_FOUND.equals(e.getHyscaleErrorCode())) {
                LOGGER.error("Error while deleting deployment for selector {} in namespace {}, error {}", selector,
                        namespace, e.toString());
                return false;
            }
            throw e;
        }
        return true;
    }

    @Override
    public String getKind() {
        return ResourceKind.DEPLOYMENT.getKind();
    }

    @Override
    public int getWeight() {
        return ResourceKind.DEPLOYMENT.getWeight();
    }

    @Override
    public boolean cleanUp() {
        return true;
    }

    @Override
    public ResourceStatus status(V1Deployment deployment) {
        V1DeploymentStatus deploymentStatus = deployment.getStatus();
        if (deploymentStatus == null) {
            return ResourceStatus.FAILED;
        }
        Integer desiredReplicas = deployment.getSpec().getReplicas();
        Integer statusReplicas = deploymentStatus.getReplicas();
        Integer updatedReplicas = deploymentStatus.getUpdatedReplicas();
        Integer availableReplicas = deploymentStatus.getAvailableReplicas();
        Integer readyReplicas = deploymentStatus.getReadyReplicas();
        if ((desiredReplicas == null || desiredReplicas == 0) && (statusReplicas == null || statusReplicas == 0)) {
            return ResourceStatus.STABLE;

        }
        // pending case
        if (updatedReplicas == null || (desiredReplicas != null && desiredReplicas > updatedReplicas)
                || (statusReplicas != null && statusReplicas > updatedReplicas)
                || (availableReplicas == null || availableReplicas < updatedReplicas)
                || (readyReplicas == null || (desiredReplicas != null && desiredReplicas > readyReplicas))) {
            return ResourceStatus.PENDING;
        }
        return ResourceStatus.STABLE;
    }

    public static String getDeploymentRevision(V1Deployment deployment) {
        if (deployment == null) {
            return null;
        }
        Map<String, String> annotations = deployment.getMetadata().getAnnotations();

        if (annotations == null) {
            return null;
        }

        return annotations.get(AnnotationKey.K8S_DEPLOYMENT_REVISION.getAnnotation());
    }

    public List<String> getServiceNames(ApiClient apiClient, String selector, boolean label, String namespace)
            throws HyscaleException {
        return getServiceNames(getBySelector(apiClient, selector, label, namespace));
    }

    /**
     * @param deploymentList
     * @return list of service names from label of deployment
     */
    public List<String> getServiceNames(List<V1Deployment> deploymentList) {
        if (deploymentList == null) {
            return null;
        }
        return deploymentList.stream().filter(each -> {
            return each != null && each.getMetadata() != null;
        }).map(each -> ResourceLabelUtil.getServiceName(each.getMetadata().getLabels())).collect(Collectors.toList());
    }

    @Override
    public List<DeploymentStatus> getStatus(ApiClient apiClient, String selector, boolean label, String namespace) {
        try {
            return buildStatus(getBySelector(apiClient, selector, label, namespace));
        } catch (HyscaleException e) {
            logger.error("Error while fetching Deployment with selector {} in namespace {}, error {}", selector,
                    namespace, e.getMessage());
        }
        return null;
    }

    @Override
    public DeploymentStatus buildStatus(V1Deployment deployment) {
        if (deployment == null) {
            return null;
        }
        return buildStatusFromMetadata(deployment.getMetadata(), DeploymentStatus.ServiceStatus.NOT_RUNNING);
    }

    @Override
    public List<DeploymentStatus> buildStatus(List<V1Deployment> deploymentList) {
        if (deploymentList == null) {
            return null;
        }
        List<DeploymentStatus> statuses = new ArrayList<DeploymentStatus>();
        deploymentList.stream().forEach(each -> {
            DeploymentStatus deployStatus = buildStatus(each);
            if (deployStatus != null) {
                statuses.add(deployStatus);
            }
        });
        return statuses;
    }

    @Override
    public String getPodSelector(ApiClient apiClient, String selector, boolean label, String namespace) {
        List<V1Deployment> deploymentList = null;
        try {
            deploymentList = getBySelector(apiClient, selector, label, namespace);
        } catch (HyscaleException e) {
            logger.error("Error fetching deployment for pod selection, ignoring", e);
            return selector;
        }
        if (deploymentList == null || deploymentList.isEmpty()) {
            return selector;
        }

        String revision = getDeploymentRevision(deploymentList.get(0));
        if (StringUtils.isBlank(revision)) {
            return selector;
        }

        V1ReplicaSetHandler v1ReplicaSetHandler = (V1ReplicaSetHandler) ResourceHandlers
                .getHandlerOf(ResourceKind.REPLICA_SET.getKind());
        V1ReplicaSet replicaSet = null;
        try {
            replicaSet = v1ReplicaSetHandler.getReplicaSetByRevision(apiClient, namespace, selector, true, revision);
        } catch (HyscaleException e) {
            logger.error("Error fetching replica set with revision {} for pod filtering, ignoring", revision, e);
            return selector;
        }
        if (replicaSet == null) {
            logger.debug("No Replica set found with revision: {} for filtering pods, returning empty list", revision);
            return null;
        }
        String podTemplateHash = replicaSet.getMetadata().getLabels()
                .get(K8SRuntimeConstants.K8s_DEPLOYMENT_POD_TEMPLATE_HASH);

        logger.debug("pod-template-hash: {}", podTemplateHash);

        return selector
                .concat("," + K8SRuntimeConstants.K8s_DEPLOYMENT_POD_TEMPLATE_HASH + "=" + podTemplateHash);
    }
}
