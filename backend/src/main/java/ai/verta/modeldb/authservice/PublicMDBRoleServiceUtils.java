package ai.verta.modeldb.authservice;

import ai.verta.common.ModelDBResourceEnum.ModelDBServiceResourceTypes;
import ai.verta.common.WorkspaceTypeEnum.WorkspaceType;
import ai.verta.modeldb.App;
import ai.verta.modeldb.ModelDBMessages;
import ai.verta.modeldb.common.authservice.AuthService;
import ai.verta.modeldb.common.collaborator.CollaboratorBase;
import ai.verta.modeldb.common.exceptions.NotFoundException;
import ai.verta.modeldb.dataset.DatasetDAO;
import ai.verta.modeldb.dataset.DatasetDAORdbImpl;
import ai.verta.modeldb.dto.WorkspaceDTO;
import ai.verta.modeldb.experiment.ExperimentDAO;
import ai.verta.modeldb.experiment.ExperimentDAORdbImpl;
import ai.verta.modeldb.experimentRun.ExperimentRunDAO;
import ai.verta.modeldb.experimentRun.ExperimentRunDAORdbImpl;
import ai.verta.modeldb.metadata.MetadataDAO;
import ai.verta.modeldb.metadata.MetadataDAORdbImpl;
import ai.verta.modeldb.project.ProjectDAO;
import ai.verta.modeldb.project.ProjectDAORdbImpl;
import ai.verta.modeldb.versioning.BlobDAORdbImpl;
import ai.verta.modeldb.versioning.CommitDAO;
import ai.verta.modeldb.versioning.CommitDAORdbImpl;
import ai.verta.modeldb.versioning.RepositoryDAORdbImpl;
import ai.verta.uac.*;
import ai.verta.uac.ModelDBActionEnum.ModelDBServiceActions;
import com.google.protobuf.GeneratedMessageV3;
import io.grpc.Metadata;
import java.util.*;

public class PublicMDBRoleServiceUtils implements MDBRoleService {

  private ProjectDAO projectDAO;
  private DatasetDAO datasetDAO;

  public PublicMDBRoleServiceUtils(AuthService authService) {
    MetadataDAO metadataDAO = new MetadataDAORdbImpl();
    CommitDAO commitDAO = new CommitDAORdbImpl(authService, this);
    ExperimentDAO experimentDAO = new ExperimentDAORdbImpl(authService, this);
    ExperimentRunDAO experimentRunDAO =
        new ExperimentRunDAORdbImpl(
            App.getInstance().mdbConfig,
            authService,
            this,
            new RepositoryDAORdbImpl(authService, this, commitDAO, metadataDAO),
            new CommitDAORdbImpl(authService, this),
            new BlobDAORdbImpl(authService, this),
            metadataDAO);
    this.projectDAO = new ProjectDAORdbImpl(authService, this, experimentDAO, experimentRunDAO);
    this.datasetDAO = new DatasetDAORdbImpl(authService, this);
  }

  @Override
  public boolean IsImplemented() {
    return false;
  }

  @Override
  public String buildRoleBindingName(
      String roleName, String resourceId, String userId, String resourceTypeName) {
    return null;
  }

  @Override
  public String buildRoleBindingName(
      String roleName, String resourceId, CollaboratorBase collaborator, String resourceTypeName) {
    return null;
  }

  @Override
  public void createRoleBinding(
      String roleName,
      CollaboratorBase collaborator,
      String resourceId,
      ModelDBServiceResourceTypes modelDBServiceResourceTypes) {
    // Do nothing
  }

  @Override
  public void isSelfAllowed(
      ModelDBServiceResourceTypes modelDBServiceResourceTypes,
      ModelDBServiceActions modelDBServiceActions,
      String resourceId) {
    // Do nothing
  }

  @Override
  public Map<String, Actions> getSelfAllowedActionsBatch(
      List<String> resourceIds, ModelDBServiceResourceTypes type) {
    return new HashMap<>();
  }

  @Override
  public boolean deleteRoleBindingsUsingServiceUser(List<String> roleBindingNames) {
    return true;
  }

  @Override
  public List<GetCollaboratorResponseItem> getResourceCollaborators(
      ModelDBServiceResourceTypes modelDBServiceResourceTypes,
      String resourceId,
      String resourceOwnerId,
      Metadata requestHeaders) {
    return Collections.emptyList();
  }

  /**
   * Checks permissions of the user wrt the Entity
   *
   * @param modelDBServiceResourceTypes : modelDBServiceResourceTypes
   * @param resourceId --> value of key like project.id, dataset.id etc.
   * @param modelDBServiceActions --> ModelDBServiceActions.UPDATE, ModelDBServiceActions.DELETE,
   */
  @Override
  public void validateEntityUserWithUserInfo(
      ModelDBServiceResourceTypes modelDBServiceResourceTypes,
      String resourceId,
      ModelDBServiceActions modelDBServiceActions) {
    if (resourceId != null && !resourceId.isEmpty()) {
      if (modelDBServiceResourceTypes.equals(ModelDBServiceResourceTypes.PROJECT)) {
        if (!projectDAO.projectExistsInDB(resourceId)) {
          String errorMessage = ModelDBMessages.PROJECT_NOT_FOUND_FOR_ID;
          throw new NotFoundException(errorMessage);
        }
      } else if (modelDBServiceResourceTypes.equals(ModelDBServiceResourceTypes.DATASET)) {
        datasetDAO.getDatasetById(resourceId);
      }
    }
  }

  @Override
  public String buildReadOnlyRoleBindingName(
      String resourceId,
      CollaboratorBase collaborator,
      ModelDBServiceResourceTypes modelDBServiceResourceTypes) {
    return null;
  }

  @Override
  public List<String> getSelfAllowedResources(
      ModelDBServiceResourceTypes modelDBServiceResourceTypes,
      ModelDBServiceActions modelDBServiceActions) {
    return Collections.emptyList();
  }

  @Override
  public List<String> getSelfDirectlyAllowedResources(
      ModelDBServiceResourceTypes modelDBServiceResourceTypes,
      ModelDBServiceActions modelDBServiceActions) {
    return Collections.emptyList();
  }

  @Override
  public List<String> getAllowedResources(
      ModelDBServiceResourceTypes modelDBServiceResourceTypes,
      ModelDBServiceActions modelDBServiceActions,
      CollaboratorBase collaboratorBase) {
    return Collections.emptyList();
  }

  @Override
  public GeneratedMessageV3 getTeamById(String teamId) {
    return null;
  }

  @Override
  public GeneratedMessageV3 getTeamByName(String orgId, String teamName) {
    return null;
  }

  @Override
  public GeneratedMessageV3 getOrgById(String orgId) {
    return null;
  }

  @Override
  public GeneratedMessageV3 getOrgByName(String name) {
    return null;
  }

  @Override
  public List<String> getAccessibleResourceIds(
      CollaboratorBase hostUserInfo,
      CollaboratorBase currentLoginUserInfo,
      ModelDBServiceResourceTypes modelDBServiceResourceTypes,
      List<String> requestedResourceIds) {
    return requestedResourceIds;
  }

  @Override
  public List<String> getAccessibleResourceIdsByActions(
      ModelDBServiceResourceTypes modelDBServiceResourceTypes,
      ModelDBServiceActions modelDBServiceActions,
      List<String> requestedIdList) {
    return requestedIdList;
  }

  @Override
  public WorkspaceDTO getWorkspaceDTOByWorkspaceName(
      UserInfo currentLoginUserInfo, String workspaceName) {
    var workspaceDTO = new WorkspaceDTO();
    workspaceDTO.setWorkspaceName(workspaceName);
    return workspaceDTO;
  }

  @Override
  public Workspace getWorkspaceByWorkspaceName(
      UserInfo currentLoginUserInfo, String workspaceName) {
    return Workspace.newBuilder().build();
  }

  @Override
  public WorkspaceDTO getWorkspaceDTOByWorkspaceIdForServiceUser(
      UserInfo currentLoginUserInfo, String workspaceId, Integer workspaceType) {
    var workspaceDTO = new WorkspaceDTO();
    workspaceDTO.setWorkspaceId(workspaceId);
    workspaceDTO.setWorkspaceType(WorkspaceType.forNumber(workspaceType));
    return workspaceDTO;
  }

  @Override
  public List<Organization> listMyOrganizations() {
    return Collections.emptyList();
  }

  @Override
  public GetResourcesResponseItem getEntityResource(
      Optional<String> entityId,
      Optional<String> workspaceName,
      ModelDBServiceResourceTypes modelDBServiceResourceTypes) {
    return GetResourcesResponseItem.newBuilder().setVisibility(ResourceVisibility.PRIVATE).build();
  }

  @Override
  public List<GetResourcesResponseItem> getEntityResourcesByName(
      Optional<String> entityName,
      Optional<String> workspaceName,
      ModelDBServiceResourceTypes modelDBServiceResourceTypes) {
    return Collections.emptyList();
  }

  @Override
  public boolean createWorkspacePermissions(
      Optional<Long> workspaceId,
      Optional<String> workspaceName,
      String resourceId,
      String resourceName,
      Optional<Long> ownerId,
      ModelDBServiceResourceTypes resourceType,
      CollaboratorPermissions permissions,
      ResourceVisibility resourceVisibility,
      boolean isServiceUser) {
    return false;
  }

  @Override
  public boolean deleteEntityResourcesWithServiceUser(
      List<String> entityIds, ModelDBServiceResourceTypes modelDBServiceResourceTypes) {
    return true;
  }

  @Override
  public List<GetResourcesResponseItem> getResourceItems(
      Workspace workspace,
      Set<String> resourceIds,
      ModelDBServiceResourceTypes modelDBServiceResourceTypes,
      boolean isServiceUser) {
    return Collections.emptyList();
  }

  @Override
  public List<GetResourcesResponseItem> getResourceItemsSpecialPersonalWorkspace(
      Workspace workspace,
      Set<String> resourceIds,
      ModelDBServiceResourceTypes modelDBServiceResourceTypes) {
    return Collections.emptyList();
  }

  @Override
  public List<String> getWorkspaceRoleBindings(
      String workspace_id,
      WorkspaceType forNumber,
      String valueOf,
      String roleRepositoryAdmin,
      ModelDBServiceResourceTypes repository,
      boolean orgScopedPublic,
      String globalSharing) {
    return Collections.emptyList();
  }

  @Override
  public boolean checkConnectionsBasedOnPrivileges(
      ModelDBServiceResourceTypes serviceResourceTypes,
      ModelDBServiceActions serviceActions,
      String resourceId) {
    return true;
  }
}
