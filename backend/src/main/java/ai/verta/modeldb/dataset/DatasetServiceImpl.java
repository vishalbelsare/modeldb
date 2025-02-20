package ai.verta.modeldb.dataset;

import static io.grpc.Status.Code.INVALID_ARGUMENT;

import ai.verta.common.KeyValue;
import ai.verta.common.KeyValueQuery;
import ai.verta.common.ModelDBResourceEnum;
import ai.verta.common.ModelDBResourceEnum.ModelDBServiceResourceTypes;
import ai.verta.common.OperatorEnum;
import ai.verta.common.ValueTypeEnum;
import ai.verta.modeldb.*;
import ai.verta.modeldb.Dataset;
import ai.verta.modeldb.DatasetServiceGrpc.DatasetServiceImplBase;
import ai.verta.modeldb.GetAllDatasets.Response;
import ai.verta.modeldb.authservice.MDBRoleService;
import ai.verta.modeldb.common.CommonUtils;
import ai.verta.modeldb.common.authservice.AuthService;
import ai.verta.modeldb.common.event.FutureEventDAO;
import ai.verta.modeldb.common.exceptions.InvalidArgumentException;
import ai.verta.modeldb.common.exceptions.ModelDBException;
import ai.verta.modeldb.common.exceptions.NotFoundException;
import ai.verta.modeldb.common.handlers.TagsHandlerBase;
import ai.verta.modeldb.entities.versioning.RepositoryEnums;
import ai.verta.modeldb.experiment.ExperimentDAO;
import ai.verta.modeldb.experimentRun.ExperimentRunDAO;
import ai.verta.modeldb.metadata.MetadataDAO;
import ai.verta.modeldb.metadata.MetadataServiceImpl;
import ai.verta.modeldb.project.ProjectDAO;
import ai.verta.modeldb.utils.ModelDBUtils;
import ai.verta.modeldb.versioning.Commit;
import ai.verta.modeldb.versioning.CommitDAO;
import ai.verta.modeldb.versioning.DeleteRepositoryRequest;
import ai.verta.modeldb.versioning.ListCommitsRequest;
import ai.verta.modeldb.versioning.RepositoryDAO;
import ai.verta.modeldb.versioning.RepositoryIdentification;
import ai.verta.uac.GetResourcesResponseItem;
import ai.verta.uac.ModelDBActionEnum.ModelDBServiceActions;
import ai.verta.uac.ResourceVisibility;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.google.protobuf.ListValue;
import com.google.protobuf.Value;
import com.google.rpc.Code;
import io.grpc.stub.StreamObserver;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class DatasetServiceImpl extends DatasetServiceImplBase {

  private static final Logger LOGGER = LogManager.getLogger(DatasetServiceImpl.class);
  private static final String UPDATE_DATASET_EVENT_TYPE =
      "update.resource.dataset.update_dataset_succeeded";
  private final RepositoryDAO repositoryDAO;
  private final CommitDAO commitDAO;
  private final MetadataDAO metadataDAO;
  private final AuthService authService;
  private final MDBRoleService mdbRoleService;
  private final ProjectDAO projectDAO;
  private final ExperimentDAO experimentDAO;
  private final ExperimentRunDAO experimentRunDAO;
  private final FutureEventDAO futureEventDAO;
  private final boolean isEventSystemEnabled;

  public DatasetServiceImpl(ServiceSet serviceSet, DAOSet daoSet) {
    this.authService = serviceSet.authService;
    this.mdbRoleService = serviceSet.mdbRoleService;
    this.projectDAO = daoSet.projectDAO;
    this.experimentDAO = daoSet.experimentDAO;
    this.experimentRunDAO = daoSet.experimentRunDAO;
    this.repositoryDAO = daoSet.repositoryDAO;
    this.commitDAO = daoSet.commitDAO;
    this.metadataDAO = daoSet.metadataDAO;
    this.futureEventDAO = daoSet.futureEventDAO;
    this.isEventSystemEnabled = serviceSet.app.mdbConfig.isEvent_system_enabled();
  }

  private void addEvent(
      String entityId,
      Optional<Long> workspaceId,
      String eventType,
      Optional<String> updatedField,
      Map<String, Object> extraFieldsMap,
      String eventMessage) {

    if (!isEventSystemEnabled) {
      return;
    }

    // Add succeeded event in local DB
    JsonObject eventMetadata = new JsonObject();
    eventMetadata.addProperty("entity_id", entityId);
    if (updatedField.isPresent() && !updatedField.get().isEmpty()) {
      eventMetadata.addProperty("updated_field", updatedField.get());
    }
    if (extraFieldsMap != null && !extraFieldsMap.isEmpty()) {
      JsonObject updatedFieldValue = new JsonObject();
      extraFieldsMap.forEach(
          (key, value) -> {
            if (value instanceof JsonElement) {
              updatedFieldValue.add(key, (JsonElement) value);
            } else {
              updatedFieldValue.addProperty(key, String.valueOf(value));
            }
          });
      eventMetadata.add("updated_field_value", updatedFieldValue);
    }
    eventMetadata.addProperty("message", eventMessage);

    if (!workspaceId.isPresent()) {
      GetResourcesResponseItem datasetResource =
          mdbRoleService.getEntityResource(
              Optional.of(entityId),
              Optional.empty(),
              ModelDBResourceEnum.ModelDBServiceResourceTypes.DATASET);
      workspaceId = Optional.of(datasetResource.getWorkspaceId());
    }
    futureEventDAO.addLocalEventWithBlocking(
        ModelDBServiceResourceTypes.DATASET.name(), eventType, workspaceId.get(), eventMetadata);
  }

  /**
   * Create a Dataset from the input parameters and logs it. Required input parameter : name
   * Generate a random UUID for id set Name,Description,Attributes, Tags and Visibility from the
   * request. set current user as an owner
   */
  @Override
  public void createDataset(
      CreateDataset request, StreamObserver<CreateDataset.Response> responseObserver) {
    try {
      mdbRoleService.validateEntityUserWithUserInfo(
          ModelDBServiceResourceTypes.DATASET, null, ModelDBServiceActions.CREATE);

      var dataset = getDatasetFromRequest(request);
      var userInfo = authService.getCurrentLoginUserInfo();
      var createdDataset =
          repositoryDAO.createOrUpdateDataset(dataset, request.getWorkspaceName(), true, userInfo);

      var response = CreateDataset.Response.newBuilder().setDataset(createdDataset).build();

      // Add succeeded event in local DB
      addEvent(
          response.getDataset().getId(),
          Optional.of(response.getDataset().getWorkspaceServiceId()),
          "add.resource.dataset.add_dataset_succeeded",
          Optional.empty(),
          Collections.emptyMap(),
          "dataset logged successfully");

      responseObserver.onNext(response);
      responseObserver.onCompleted();

    } catch (Exception e) {
      CommonUtils.observeError(responseObserver, e, CreateDataset.Response.getDefaultInstance());
    }
  }

  private Dataset getDatasetFromRequest(CreateDataset request) {
    /*
     * Generate a random UUID for id
     * set Name,Description,Attributes, Tags and Visibility from the request
     * set times to current time
     */
    if (request.getName().isEmpty()) {
      request = request.toBuilder().setName(MetadataServiceImpl.createRandomName()).build();
    }
    var datasetBuilder =
        Dataset.newBuilder()
            .setName(ModelDBUtils.checkEntityNameLength(request.getName()))
            .setDescription(request.getDescription())
            .addAllAttributes(request.getAttributesList())
            .addAllTags(TagsHandlerBase.checkEntityTagsLength(request.getTagsList()))
            .setDatasetVisibility(request.getDatasetVisibility())
            .setVisibility(request.getVisibility())
            .setDatasetType(request.getDatasetType())
            .setCustomPermission(request.getCustomPermission())
            .setVersionNumber(1L);

    if (request.getTimeCreated() != 0L) {
      datasetBuilder
          .setTimeCreated(request.getTimeCreated())
          .setTimeUpdated(request.getTimeCreated());
    } else {
      datasetBuilder
          .setTimeCreated(Calendar.getInstance().getTimeInMillis())
          .setTimeUpdated(Calendar.getInstance().getTimeInMillis());
    }
    return datasetBuilder.build();
  }

  /** Fetch all (owned and shared) dataset for the current user */
  @Override
  public void getAllDatasets(
      GetAllDatasets request, StreamObserver<GetAllDatasets.Response> responseObserver) {
    try {
      // Get the user info from the Context
      var userInfo = authService.getCurrentLoginUserInfo();

      FindDatasets.Builder findDatasets =
          FindDatasets.newBuilder()
              .setPageNumber(request.getPageNumber())
              .setPageLimit(request.getPageLimit())
              .setAscending(request.getAscending())
              .setSortKey(request.getSortKey())
              .setWorkspaceName(request.getWorkspaceName());

      if (findDatasets.getSortKey().equals(ModelDBConstants.TIME_UPDATED)) {
        findDatasets.setSortKey(ModelDBConstants.DATE_UPDATED);
      }
      if (findDatasets.getSortKey().equals(ModelDBConstants.TIME_CREATED)) {
        findDatasets.setSortKey(ModelDBConstants.DATE_CREATED);
      }

      var datasetPaginationDTO =
          repositoryDAO.findDatasets(
              metadataDAO, findDatasets.build(), userInfo, ResourceVisibility.PRIVATE);

      LOGGER.debug(
          ModelDBMessages.ACCESSIBLE_DATASET_IN_SERVICE, datasetPaginationDTO.getDatasets().size());
      final var response =
          Response.newBuilder()
              .addAllDatasets(datasetPaginationDTO.getDatasets())
              .setTotalRecords(datasetPaginationDTO.getTotalRecords())
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();

    } catch (Exception e) {
      CommonUtils.observeError(responseObserver, e, GetAllDatasets.Response.getDefaultInstance());
    }
  }

  /** Deletes dataset corresponding to the id. Required input parameter : id */
  @Override
  public void deleteDataset(
      DeleteDataset request, StreamObserver<DeleteDataset.Response> responseObserver) {
    try {
      // Request Parameter Validation
      if (request.getId().isEmpty()) {
        throw new InvalidArgumentException(ModelDBMessages.DATASET_ID_NOT_FOUND_IN_REQUEST);
      }

      GetResourcesResponseItem entityResource =
          mdbRoleService.getEntityResource(request.getId(), ModelDBServiceResourceTypes.DATASET);
      deleteRepositoriesByDatasetIds(Collections.singletonList(entityResource.getResourceId()));
      var response = DeleteDataset.Response.newBuilder().setStatus(true).build();

      responseObserver.onNext(response);
      responseObserver.onCompleted();

    } catch (Exception e) {
      CommonUtils.observeError(responseObserver, e, DeleteDataset.Response.getDefaultInstance());
    }
  }

  /**
   * Fetches dataset corresponding to the id. <br>
   * Required input parameter : id
   */
  @Override
  public void getDatasetById(
      GetDatasetById request, StreamObserver<GetDatasetById.Response> responseObserver) {
    try {
      // Request Parameter Validation
      if (request.getId().isEmpty()) {
        throw new InvalidArgumentException(ModelDBMessages.DATASET_ID_NOT_FOUND_IN_REQUEST);
      }

      // Validate if current user has access to the entity or not
      mdbRoleService.validateEntityUserWithUserInfo(
          ModelDBServiceResourceTypes.DATASET, request.getId(), ModelDBServiceActions.READ);

      final var response = repositoryDAO.getDatasetById(metadataDAO, request.getId());
      responseObserver.onNext(response);
      responseObserver.onCompleted();

    } catch (Exception e) {
      CommonUtils.observeError(responseObserver, e, GetDatasetById.Response.getDefaultInstance());
    }
  }

  @Override
  public void findDatasets(
      FindDatasets request, StreamObserver<FindDatasets.Response> responseObserver) {
    try {
      // Get the user info from the Context
      var userInfo = authService.getCurrentLoginUserInfo();
      var datasetPaginationDTO =
          repositoryDAO.findDatasets(metadataDAO, request, userInfo, ResourceVisibility.PRIVATE);
      final var response =
          FindDatasets.Response.newBuilder()
              .addAllDatasets(datasetPaginationDTO.getDatasets())
              .setTotalRecords(datasetPaginationDTO.getTotalRecords())
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();

    } catch (Exception e) {
      CommonUtils.observeError(responseObserver, e, FindDatasets.Response.getDefaultInstance());
    }
  }

  @Override
  public void getDatasetByName(
      GetDatasetByName request, StreamObserver<GetDatasetByName.Response> responseObserver) {
    try {
      // Request Parameter Validation
      if (request.getName().isEmpty()) {
        throw new InvalidArgumentException(ModelDBMessages.DATASET_NAME_NOT_FOUND_IN_REQUEST);
      }

      // Get the user info from the Context
      var userInfo = authService.getCurrentLoginUserInfo();

      FindDatasets.Builder findDatasets =
          FindDatasets.newBuilder()
              .addPredicates(
                  KeyValueQuery.newBuilder()
                      .setKey(ModelDBConstants.NAME)
                      .setValue(Value.newBuilder().setStringValue(request.getName()).build())
                      .setOperator(OperatorEnum.Operator.EQ)
                      .setValueType(ValueTypeEnum.ValueType.STRING)
                      .build())
              .setWorkspaceName(
                  request.getWorkspaceName().isEmpty()
                      ? authService.getUsernameFromUserInfo(userInfo)
                      : request.getWorkspaceName());

      var datasetPaginationDTO =
          repositoryDAO.findDatasets(
              metadataDAO, findDatasets.build(), userInfo, ResourceVisibility.PRIVATE);

      if (datasetPaginationDTO.getTotalRecords() == 0) {
        throw new NotFoundException("Dataset not found");
      }
      Dataset selfOwnerdataset = null;
      List<Dataset> sharedDatasets = new ArrayList<>();
      Set<String> datasetIdSet = new HashSet<>();

      for (Dataset dataset : datasetPaginationDTO.getDatasets()) {
        if (userInfo == null
            || dataset.getOwner().equals(authService.getVertaIdFromUserInfo(userInfo))) {
          selfOwnerdataset = dataset;
        } else {
          sharedDatasets.add(dataset);
        }
        datasetIdSet.add(dataset.getId());
      }

      var responseBuilder = GetDatasetByName.Response.newBuilder();
      if (selfOwnerdataset != null) {
        responseBuilder.setDatasetByUser(selfOwnerdataset);
      }
      responseBuilder.addAllSharedDatasets(sharedDatasets);
      responseObserver.onNext(responseBuilder.build());
      responseObserver.onCompleted();

    } catch (Exception e) {
      CommonUtils.observeError(responseObserver, e, GetDatasetByName.Response.getDefaultInstance());
    }
  }

  @Override
  public void updateDatasetName(
      UpdateDatasetName request, StreamObserver<UpdateDatasetName.Response> responseObserver) {
    try {
      // Request Parameter Validation
      String errorMessage = null;
      if (request.getId().isEmpty() && request.getName().isEmpty()) {
        errorMessage = "Dataset ID and Dataset name not found in UpdateDatasetName request";
      } else if (request.getId().isEmpty()) {
        errorMessage = ModelDBMessages.DATASET_ID_NOT_FOUND_IN_REQUEST;
      } else if (request.getName().isEmpty()) {
        request = request.toBuilder().setName(MetadataServiceImpl.createRandomName()).build();
      }

      if (errorMessage != null) {
        throw new InvalidArgumentException(errorMessage);
      }

      // Validate if current user has access to the entity or not
      mdbRoleService.validateEntityUserWithUserInfo(
          ModelDBServiceResourceTypes.DATASET, request.getId(), ModelDBServiceActions.UPDATE);

      var getDatasetResponse = repositoryDAO.getDatasetById(metadataDAO, request.getId());
      var updatedDataset =
          getDatasetResponse.getDataset().toBuilder().setName(request.getName()).build();
      var userInfo = authService.getCurrentLoginUserInfo();
      updatedDataset = repositoryDAO.createOrUpdateDataset(updatedDataset, null, false, userInfo);

      var response = UpdateDatasetName.Response.newBuilder().setDataset(updatedDataset).build();

      // Add succeeded event in local DB
      addEvent(
          response.getDataset().getId(),
          Optional.of(response.getDataset().getWorkspaceServiceId()),
          UPDATE_DATASET_EVENT_TYPE,
          Optional.of("name"),
          Collections.singletonMap("name", response.getDataset().getName()),
          "dataset name updated successfully");

      responseObserver.onNext(response);
      responseObserver.onCompleted();

    } catch (Exception e) {
      CommonUtils.observeError(
          responseObserver, e, UpdateDatasetName.Response.getDefaultInstance());
    }
  }

  @Override
  public void updateDatasetDescription(
      UpdateDatasetDescription request,
      StreamObserver<UpdateDatasetDescription.Response> responseObserver) {
    try {
      // Request Parameter Validation
      if (request.getId().isEmpty()) {
        throw new ModelDBException(
            ModelDBMessages.DATASET_ID_NOT_FOUND_IN_REQUEST, INVALID_ARGUMENT);
      }

      // Validate if current user has access to the entity or not
      mdbRoleService.validateEntityUserWithUserInfo(
          ModelDBServiceResourceTypes.DATASET, request.getId(), ModelDBServiceActions.UPDATE);

      var getDatasetResponse = repositoryDAO.getDatasetById(metadataDAO, request.getId());
      var updatedDataset =
          getDatasetResponse
              .getDataset()
              .toBuilder()
              .setDescription(request.getDescription())
              .build();
      var userInfo = authService.getCurrentLoginUserInfo();
      updatedDataset = repositoryDAO.createOrUpdateDataset(updatedDataset, null, false, userInfo);
      var response =
          UpdateDatasetDescription.Response.newBuilder().setDataset(updatedDataset).build();

      // Add succeeded event in local DB
      addEvent(
          response.getDataset().getId(),
          Optional.of(response.getDataset().getWorkspaceServiceId()),
          UPDATE_DATASET_EVENT_TYPE,
          Optional.of("description"),
          Collections.emptyMap(),
          "dataset description updated successfully");

      responseObserver.onNext(response);
      responseObserver.onCompleted();

    } catch (Exception e) {
      CommonUtils.observeError(
          responseObserver, e, UpdateDatasetDescription.Response.getDefaultInstance());
    }
  }

  @Override
  public void addDatasetTags(
      AddDatasetTags request, StreamObserver<AddDatasetTags.Response> responseObserver) {
    try {
      // Request Parameter Validation
      String errorMessage = null;
      if (request.getId().isEmpty() && request.getTagsList().isEmpty()) {
        errorMessage = "Dataset ID and Dataset tags not found in AddDatasetTags request";
      } else if (request.getId().isEmpty()) {
        errorMessage = ModelDBMessages.DATASET_ID_NOT_FOUND_IN_REQUEST;
      } else if (request.getTagsList().isEmpty()) {
        errorMessage = "Dataset tags not found in AddDatasetTags request";
      }

      if (errorMessage != null) {
        throw new InvalidArgumentException(errorMessage);
      }

      // Validate if current user has access to the entity or not
      mdbRoleService.validateEntityUserWithUserInfo(
          ModelDBServiceResourceTypes.DATASET, request.getId(), ModelDBServiceActions.UPDATE);

      var response =
          repositoryDAO.addDatasetTags(
              metadataDAO,
              request.getId(),
              TagsHandlerBase.checkEntityTagsLength(request.getTagsList()));

      // Add succeeded event in local DB
      addEvent(
          response.getDataset().getId(),
          Optional.of(response.getDataset().getWorkspaceServiceId()),
          UPDATE_DATASET_EVENT_TYPE,
          Optional.of("tags"),
          Collections.singletonMap(
              "tags",
              new Gson()
                  .toJsonTree(
                      request.getTagsList(), new TypeToken<ArrayList<String>>() {}.getType())),
          "dataset tags added successfully");

      responseObserver.onNext(response);
      responseObserver.onCompleted();

    } catch (Exception e) {
      CommonUtils.observeError(responseObserver, e, AddDatasetTags.Response.getDefaultInstance());
    }
  }

  @Override
  public void getDatasetTags(GetTags request, StreamObserver<GetTags.Response> responseObserver) {
    try {
      // Request Parameter Validation
      LOGGER.info("getDatasetTags not supported");
      throw new ModelDBException("Not supported", Code.UNIMPLEMENTED);

    } catch (Exception e) {
      CommonUtils.observeError(responseObserver, e, GetTags.Response.getDefaultInstance());
    }
  }

  @Override
  public void deleteDatasetTags(
      DeleteDatasetTags request, StreamObserver<DeleteDatasetTags.Response> responseObserver) {
    try {
      // Request Parameter Validation
      String errorMessage = null;
      if (request.getId().isEmpty() && request.getTagsList().isEmpty() && !request.getDeleteAll()) {
        errorMessage = "Dataset ID and Dataset tags not found in DeleteDatasetTags request";
      } else if (request.getId().isEmpty()) {
        errorMessage = ModelDBMessages.DATASET_ID_NOT_FOUND_IN_REQUEST;
      } else if (request.getTagsList().isEmpty() && !request.getDeleteAll()) {
        errorMessage = "Dataset tags not found in DeleteDatasetTags request";
      }

      if (errorMessage != null) {
        throw new InvalidArgumentException(errorMessage);
      }

      // Validate if current user has access to the entity or not
      mdbRoleService.validateEntityUserWithUserInfo(
          ModelDBServiceResourceTypes.DATASET, request.getId(), ModelDBServiceActions.UPDATE);

      var updatedDataset =
          repositoryDAO.deleteDatasetTags(
              metadataDAO, request.getId(), request.getTagsList(), request.getDeleteAll());
      var response = DeleteDatasetTags.Response.newBuilder().setDataset(updatedDataset).build();

      // Add succeeded event in local DB
      Map<String, Object> extraField = new HashMap<>();
      if (request.getDeleteAll()) {
        extraField.put("tags_delete_all", true);
      } else {
        extraField.put(
            "tags",
            new Gson()
                .toJsonTree(
                    request.getTagsList(), new TypeToken<ArrayList<String>>() {}.getType()));
      }
      addEvent(
          response.getDataset().getId(),
          Optional.of(response.getDataset().getWorkspaceServiceId()),
          UPDATE_DATASET_EVENT_TYPE,
          Optional.of("tags"),
          extraField,
          "dataset tags deleted successfully");

      responseObserver.onNext(response);
      responseObserver.onCompleted();

    } catch (Exception e) {
      CommonUtils.observeError(
          responseObserver, e, DeleteDatasetTags.Response.getDefaultInstance());
    }
  }

  @Override
  public void addDatasetAttributes(
      AddDatasetAttributes request,
      StreamObserver<AddDatasetAttributes.Response> responseObserver) {
    try {
      // Request Parameter Validation
      String errorMessage = null;
      if (request.getId().isEmpty() && request.getAttributesList().isEmpty()) {
        errorMessage = "Dataset ID and Attribute list not found in AddDatasetAttributes request";
      } else if (request.getId().isEmpty()) {
        errorMessage = ModelDBMessages.DATASET_ID_NOT_FOUND_IN_REQUEST;
      } else if (request.getAttributesList().isEmpty()) {
        errorMessage = "Attribute list not found in AddDatasetAttributes request";
      }

      if (errorMessage != null) {
        throw new InvalidArgumentException(errorMessage);
      }

      // Validate if current user has access to the entity or not
      mdbRoleService.validateEntityUserWithUserInfo(
          ModelDBServiceResourceTypes.DATASET, request.getId(), ModelDBServiceActions.UPDATE);

      var getDatasetResponse = repositoryDAO.getDatasetById(metadataDAO, request.getId());
      var updatedDataset =
          getDatasetResponse
              .getDataset()
              .toBuilder()
              .addAllAttributes(request.getAttributesList())
              .build();
      var userInfo = authService.getCurrentLoginUserInfo();
      updatedDataset = repositoryDAO.createOrUpdateDataset(updatedDataset, null, false, userInfo);
      var response = AddDatasetAttributes.Response.newBuilder().setDataset(updatedDataset).build();

      addEvent(
          updatedDataset.getId(),
          Optional.of(updatedDataset.getWorkspaceServiceId()),
          UPDATE_DATASET_EVENT_TYPE,
          Optional.of("attributes"),
          Collections.singletonMap(
              "attribute_keys",
              new Gson()
                  .toJsonTree(
                      request.getAttributesList().stream()
                          .map(KeyValue::getKey)
                          .collect(Collectors.toSet()),
                      new TypeToken<ArrayList<String>>() {}.getType())),
          "dataset attributes added successfully");

      responseObserver.onNext(response);
      responseObserver.onCompleted();

    } catch (Exception e) {
      CommonUtils.observeError(
          responseObserver, e, AddDatasetAttributes.Response.getDefaultInstance());
    }
  }

  @Override
  public void updateDatasetAttributes(
      UpdateDatasetAttributes request,
      StreamObserver<UpdateDatasetAttributes.Response> responseObserver) {
    try {
      // Request Parameter Validation
      String errorMessage = null;
      if (request.getId().isEmpty() && request.getAttribute().getKey().isEmpty()) {
        errorMessage = "Dataset ID and attribute key not found in UpdateDatasetAttributes request";
      } else if (request.getId().isEmpty()) {
        errorMessage = ModelDBMessages.DATASET_ID_NOT_FOUND_IN_REQUEST;
      } else if (request.getAttribute().getKey().isEmpty()) {
        errorMessage = "Attribute key not found in UpdateDatasetAttributes request";
      }

      if (errorMessage != null) {
        throw new InvalidArgumentException(errorMessage);
      }

      // Validate if current user has access to the entity or not
      mdbRoleService.validateEntityUserWithUserInfo(
          ModelDBServiceResourceTypes.DATASET, request.getId(), ModelDBServiceActions.UPDATE);

      var getDatasetResponse = repositoryDAO.getDatasetById(metadataDAO, request.getId());
      var updatedDataset =
          getDatasetResponse.getDataset().toBuilder().addAttributes(request.getAttribute()).build();
      var userInfo = authService.getCurrentLoginUserInfo();
      updatedDataset = repositoryDAO.createOrUpdateDataset(updatedDataset, null, false, userInfo);
      var response =
          UpdateDatasetAttributes.Response.newBuilder().setDataset(updatedDataset).build();

      addEvent(
          updatedDataset.getId(),
          Optional.of(updatedDataset.getWorkspaceServiceId()),
          UPDATE_DATASET_EVENT_TYPE,
          Optional.of("attributes"),
          Collections.singletonMap(
              "attribute_keys",
              new Gson()
                  .toJsonTree(
                      Stream.of(request.getAttribute())
                          .map(KeyValue::getKey)
                          .collect(Collectors.toSet()),
                      new TypeToken<ArrayList<String>>() {}.getType())),
          "dataset attributes updated successfully");

      responseObserver.onNext(response);
      responseObserver.onCompleted();

    } catch (Exception e) {
      CommonUtils.observeError(
          responseObserver, e, UpdateDatasetAttributes.Response.getDefaultInstance());
    }
  }

  @Override
  public void deleteDatasetAttributes(
      DeleteDatasetAttributes request,
      StreamObserver<DeleteDatasetAttributes.Response> responseObserver) {
    try {
      // Request Parameter Validation
      String errorMessage = null;
      if (request.getId().isEmpty()
          && request.getAttributeKeysList().isEmpty()
          && !request.getDeleteAll()) {
        errorMessage =
            "Dataset ID and Dataset attribute keys not found in DeleteDatasetAttributes request";
      } else if (request.getId().isEmpty()) {
        errorMessage = ModelDBMessages.DATASET_ID_NOT_FOUND_IN_REQUEST;
      } else if (request.getAttributeKeysList().isEmpty() && !request.getDeleteAll()) {
        errorMessage = "Dataset attribute keys not found in DeleteDatasetAttributes request";
      }

      if (errorMessage != null) {
        throw new InvalidArgumentException(errorMessage);
      }

      // Validate if current user has access to the entity or not
      mdbRoleService.validateEntityUserWithUserInfo(
          ModelDBServiceResourceTypes.DATASET, request.getId(), ModelDBServiceActions.UPDATE);

      repositoryDAO.deleteRepositoryAttributes(
          Long.parseLong(request.getId()),
          request.getAttributeKeysList(),
          request.getDeleteAll(),
          false,
          RepositoryEnums.RepositoryTypeEnum.DATASET);
      var getDatasetResponse = repositoryDAO.getDatasetById(metadataDAO, request.getId());
      var response =
          DeleteDatasetAttributes.Response.newBuilder()
              .setDataset(getDatasetResponse.getDataset())
              .build();

      // Add succeeded event in local DB
      Map<String, Object> extraField = new HashMap<>();
      if (request.getDeleteAll()) {
        extraField.put("attributes_delete_all", true);
      } else {
        extraField.put(
            "attribute_keys",
            new Gson()
                .toJsonTree(
                    request.getAttributeKeysList(),
                    new TypeToken<ArrayList<String>>() {}.getType()));
      }
      addEvent(
          response.getDataset().getId(),
          Optional.of(response.getDataset().getWorkspaceServiceId()),
          UPDATE_DATASET_EVENT_TYPE,
          Optional.of("attributes"),
          extraField,
          "dataset attributes deleted successfully");

      responseObserver.onNext(response);
      responseObserver.onCompleted();

    } catch (Exception e) {
      CommonUtils.observeError(
          responseObserver, e, DeleteDatasetAttributes.Response.getDefaultInstance());
    }
  }

  @Override
  public void deleteDatasets(
      DeleteDatasets request, StreamObserver<DeleteDatasets.Response> responseObserver) {
    try {
      // Request Parameter Validation
      if (request.getIdsList().isEmpty()) {
        throw new InvalidArgumentException(ModelDBMessages.DATASET_ID_NOT_FOUND_IN_REQUEST);
      }

      List<GetResourcesResponseItem> responseItems =
          mdbRoleService.getResourceItems(
              null,
              new HashSet<>(request.getIdsList()),
              ModelDBServiceResourceTypes.DATASET,
              false);
      deleteRepositoriesByDatasetIds(
          responseItems.stream()
              .map(GetResourcesResponseItem::getResourceId)
              .collect(Collectors.toList()));
      var response = DeleteDatasets.Response.newBuilder().setStatus(true).build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();

    } catch (Exception e) {
      CommonUtils.observeError(responseObserver, e, DeleteDatasets.Response.getDefaultInstance());
    }
  }

  private void deleteRepositoriesByDatasetIds(List<String> datasetIds) throws ModelDBException {
    for (String datasetId : datasetIds) {
      repositoryDAO.deleteRepository(
          DeleteRepositoryRequest.newBuilder()
              .setRepositoryId(
                  RepositoryIdentification.newBuilder().setRepoId(Long.parseLong(datasetId)))
              .build(),
          commitDAO,
          experimentRunDAO,
          false,
          RepositoryEnums.RepositoryTypeEnum.DATASET);

      // Add succeeded event in local DB
      addEvent(
          datasetId,
          Optional.empty(),
          "delete.resource.dataset.delete_dataset_succeeded",
          Optional.empty(),
          Collections.emptyMap(),
          "dataset delete successfully");
    }
  }

  @Override
  public void getLastExperimentByDatasetId(
      LastExperimentByDatasetId request,
      StreamObserver<LastExperimentByDatasetId.Response> responseObserver) {
    try {
      if (request.getDatasetId().isEmpty()) {
        throw new InvalidArgumentException(ModelDBMessages.DATASET_ID_NOT_FOUND_IN_REQUEST);
      }

      // Validate if current user has access to the entity or not
      mdbRoleService.validateEntityUserWithUserInfo(
          ModelDBServiceResourceTypes.DATASET, request.getDatasetId(), ModelDBServiceActions.READ);
      // Get the user info from the Context
      var userInfo = authService.getCurrentLoginUserInfo();

      var repositoryIdentification =
          RepositoryIdentification.newBuilder()
              .setRepoId(Long.parseLong(request.getDatasetId()))
              .build();
      ListCommitsRequest.Builder listCommitsRequest =
          ListCommitsRequest.newBuilder().setRepositoryId(repositoryIdentification);
      var listCommitsResponse =
          commitDAO.listCommits(
              listCommitsRequest.build(),
              (session ->
                  repositoryDAO.getRepositoryById(
                      session,
                      repositoryIdentification,
                      false,
                      false,
                      RepositoryEnums.RepositoryTypeEnum.DATASET)),
              false);
      List<String> datasetVersionIds = new ArrayList<>();
      var listValueBuilder = ListValue.newBuilder();
      List<Commit> commitList = listCommitsResponse.getCommitsList();
      if (!commitList.isEmpty()) {
        for (Commit commit : commitList) {
          datasetVersionIds.add(commit.getCommitSha());
          listValueBuilder.addValues(
              Value.newBuilder().setStringValue(commit.getCommitSha()).build());
        }
      }

      Experiment lastUpdatedExperiment = null;
      if (!datasetVersionIds.isEmpty()) {

        var keyValueQuery =
            KeyValueQuery.newBuilder()
                .setKey(ModelDBConstants.DATASETS + "." + ModelDBConstants.LINKED_ARTIFACT_ID)
                .setValue(Value.newBuilder().setListValue(listValueBuilder.build()).build())
                .setOperator(OperatorEnum.Operator.IN)
                .build();
        var findExperimentRuns =
            FindExperimentRuns.newBuilder().addPredicates(keyValueQuery).build();
        var experimentRunPaginationDTO =
            experimentRunDAO.findExperimentRuns(projectDAO, userInfo, findExperimentRuns);
        if (experimentRunPaginationDTO != null
            && experimentRunPaginationDTO.getExperimentRuns() != null
            && !experimentRunPaginationDTO.getExperimentRuns().isEmpty()) {
          List<ExperimentRun> experimentRuns = experimentRunPaginationDTO.getExperimentRuns();
          List<String> experimentIds = new ArrayList<>();
          for (ExperimentRun experimentRun : experimentRuns) {
            experimentIds.add(experimentRun.getExperimentId());
          }
          var findExperiments =
              FindExperiments.newBuilder()
                  .addAllExperimentIds(experimentIds)
                  .setPageLimit(1)
                  .setPageNumber(1)
                  .setSortKey(ModelDBConstants.DATE_UPDATED)
                  .setAscending(false)
                  .build();
          var experimentPaginationDTO =
              experimentDAO.findExperiments(projectDAO, userInfo, findExperiments);
          if (experimentPaginationDTO.getExperiments() != null
              && !experimentPaginationDTO.getExperiments().isEmpty()) {
            lastUpdatedExperiment = experimentPaginationDTO.getExperiments().get(0);
          }
        }
      }

      final LastExperimentByDatasetId.Response response;
      if (lastUpdatedExperiment != null) {
        response =
            LastExperimentByDatasetId.Response.newBuilder()
                .setExperiment(lastUpdatedExperiment)
                .build();
        responseObserver.onNext(response);
      } else {
        response = LastExperimentByDatasetId.Response.newBuilder().build();
        responseObserver.onNext(response);
      }
      responseObserver.onCompleted();

    } catch (Exception e) {
      CommonUtils.observeError(
          responseObserver, e, LastExperimentByDatasetId.Response.getDefaultInstance());
    }
  }

  @Override
  public void getExperimentRunByDataset(
      GetExperimentRunByDataset request,
      StreamObserver<GetExperimentRunByDataset.Response> responseObserver) {
    try {
      if (request.getDatasetId().isEmpty()) {
        throw new InvalidArgumentException(ModelDBMessages.DATASET_ID_NOT_FOUND_IN_REQUEST);
      }

      // Validate if current user has access to the entity or not
      mdbRoleService.validateEntityUserWithUserInfo(
          ModelDBServiceResourceTypes.DATASET, request.getDatasetId(), ModelDBServiceActions.READ);

      // Get the user info from the Context
      var userInfo = authService.getCurrentLoginUserInfo();
      var repositoryIdentification =
          RepositoryIdentification.newBuilder()
              .setRepoId(Long.parseLong(request.getDatasetId()))
              .build();
      ListCommitsRequest.Builder listCommitsRequest =
          ListCommitsRequest.newBuilder().setRepositoryId(repositoryIdentification);
      var listCommitsResponse =
          commitDAO.listCommits(
              listCommitsRequest.build(),
              (session ->
                  repositoryDAO.getRepositoryById(
                      session,
                      repositoryIdentification,
                      false,
                      false,
                      RepositoryEnums.RepositoryTypeEnum.DATASET)),
              false);
      List<String> datasetVersionIds = new ArrayList<>();
      var listValueBuilder = ListValue.newBuilder();
      List<Commit> commitList = listCommitsResponse.getCommitsList();
      if (!commitList.isEmpty()) {
        for (Commit commit : commitList) {
          datasetVersionIds.add(commit.getCommitSha());
          listValueBuilder.addValues(
              Value.newBuilder().setStringValue(commit.getCommitSha()).build());
        }
      }

      List<ExperimentRun> experimentRuns = new ArrayList<>();
      if (!datasetVersionIds.isEmpty()) {
        var keyValueQuery =
            KeyValueQuery.newBuilder()
                .setKey(ModelDBConstants.DATASETS + "." + ModelDBConstants.LINKED_ARTIFACT_ID)
                .setValue(Value.newBuilder().setListValue(listValueBuilder.build()).build())
                .setOperator(OperatorEnum.Operator.IN)
                .build();
        var findExperimentRuns =
            FindExperimentRuns.newBuilder().addPredicates(keyValueQuery).build();
        var experimentRunPaginationDTO =
            experimentRunDAO.findExperimentRuns(projectDAO, userInfo, findExperimentRuns);
        if (experimentRunPaginationDTO != null
            && experimentRunPaginationDTO.getExperimentRuns() != null
            && !experimentRunPaginationDTO.getExperimentRuns().isEmpty()) {
          experimentRuns.addAll(experimentRunPaginationDTO.getExperimentRuns());
        }
      }

      final var response =
          GetExperimentRunByDataset.Response.newBuilder()
              .addAllExperimentRuns(experimentRuns)
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();

    } catch (Exception e) {
      CommonUtils.observeError(
          responseObserver, e, GetExperimentRunByDataset.Response.getDefaultInstance());
    }
  }
}
