/*
 * Copyright Consensys Software Inc., 2022
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */

package tech.pegasys.teku.ethereum.executionlayer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static tech.pegasys.teku.ethereum.executionlayer.ExecutionBuilderModule.BUILDER_BOOST_FACTOR_MAX_PROFIT;
import static tech.pegasys.teku.ethereum.executionlayer.ExecutionBuilderModule.BUILDER_BOOST_FACTOR_PREFER_BUILDER;

import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tech.pegasys.teku.ethereum.executionclient.BuilderClient;
import tech.pegasys.teku.ethereum.executionclient.schema.Response;
import tech.pegasys.teku.ethereum.executionlayer.ExecutionLayerManagerImpl.Source;
import tech.pegasys.teku.ethereum.performance.trackers.BlockProductionPerformance;
import tech.pegasys.teku.infrastructure.async.SafeFuture;
import tech.pegasys.teku.infrastructure.logging.EventLogger;
import tech.pegasys.teku.infrastructure.metrics.StubMetricsSystem;
import tech.pegasys.teku.infrastructure.metrics.TekuMetricCategory;
import tech.pegasys.teku.infrastructure.ssz.SszList;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.TestSpecFactory;
import tech.pegasys.teku.spec.datastructures.blocks.SignedBeaconBlock;
import tech.pegasys.teku.spec.datastructures.builder.BuilderBid;
import tech.pegasys.teku.spec.datastructures.builder.BuilderPayload;
import tech.pegasys.teku.spec.datastructures.builder.SignedBuilderBid;
import tech.pegasys.teku.spec.datastructures.execution.BlobsBundle;
import tech.pegasys.teku.spec.datastructures.execution.ClientVersion;
import tech.pegasys.teku.spec.datastructures.execution.ExecutionPayload;
import tech.pegasys.teku.spec.datastructures.execution.ExecutionPayloadContext;
import tech.pegasys.teku.spec.datastructures.execution.ExecutionPayloadHeader;
import tech.pegasys.teku.spec.datastructures.execution.ExecutionPayloadResult;
import tech.pegasys.teku.spec.datastructures.execution.FallbackData;
import tech.pegasys.teku.spec.datastructures.execution.FallbackReason;
import tech.pegasys.teku.spec.datastructures.execution.GetPayloadResponse;
import tech.pegasys.teku.spec.datastructures.execution.HeaderWithFallbackData;
import tech.pegasys.teku.spec.datastructures.state.beaconstate.BeaconState;
import tech.pegasys.teku.spec.datastructures.type.SszKZGCommitment;
import tech.pegasys.teku.spec.schemas.SchemaDefinitionsDeneb;
import tech.pegasys.teku.spec.util.DataStructureUtil;

class ExecutionLayerManagerImplTest {

  private final ExecutionClientHandler executionClientHandler = mock(ExecutionClientHandler.class);

  private final BuilderClient builderClient = Mockito.mock(BuilderClient.class);

  private Spec spec = TestSpecFactory.createMinimalCapella();

  private DataStructureUtil dataStructureUtil = new DataStructureUtil(spec);

  private final StubMetricsSystem stubMetricsSystem = new StubMetricsSystem();

  private final EventLogger eventLogger = mock(EventLogger.class);

  private final BuilderCircuitBreaker builderCircuitBreaker = mock(BuilderCircuitBreaker.class);
  private ExecutionLayerManagerImpl executionLayerManager;

  private final UInt256 localExecutionPayloadValue = UInt256.valueOf(1234);
  private final UInt256 builderExecutionPayloadValue = UInt256.valueOf(2345);

  @BeforeEach
  public void setup() {
    executionLayerManager = createExecutionLayerChannelImpl(true, false);
  }

  @Test
  public void builderShouldNotBeAvailableWhenBuilderNotEnabled() {
    executionLayerManager = createExecutionLayerChannelImpl(false, false);

    // trigger update of builder status (should not do anything since builder is not enabled)
    executionLayerManager.onSlot(UInt64.ONE);

    assertThat(executionLayerManager.getExecutionBuilderModule().isBuilderAvailable()).isFalse();
    verifyNoInteractions(builderClient);
    verifyNoInteractions(eventLogger);
  }

  @Test
  public void builderShouldBeAvailableWhenBuilderIsOperatingNormally() {
    final SafeFuture<Response<Void>> builderClientResponse =
        SafeFuture.completedFuture(Response.withNullPayload());

    updateBuilderStatus(builderClientResponse);

    assertThat(executionLayerManager.getExecutionBuilderModule().isBuilderAvailable()).isTrue();
    verifyNoInteractions(eventLogger);
  }

  @Test
  public void builderShouldNotBeAvailableWhenBuilderIsNotOperatingNormally() {
    final SafeFuture<Response<Void>> builderClientResponse =
        SafeFuture.completedFuture(Response.withErrorMessage("oops"));

    updateBuilderStatus(builderClientResponse);

    assertThat(executionLayerManager.getExecutionBuilderModule().isBuilderAvailable()).isFalse();
    verify(eventLogger).builderIsNotAvailable("oops");
  }

  @Test
  public void builderShouldNotBeAvailableWhenBuilderStatusCallFails() {
    final SafeFuture<Response<Void>> builderClientResponse =
        SafeFuture.failedFuture(new Throwable("oops"));

    updateBuilderStatus(builderClientResponse);

    assertThat(executionLayerManager.getExecutionBuilderModule().isBuilderAvailable()).isFalse();
    verify(eventLogger).builderIsNotAvailable("oops");
  }

  @Test
  public void builderAvailabilityIsUpdatedOnSlotEventAndLoggedAdequately() {
    // Initially builder should be available
    assertThat(executionLayerManager.getExecutionBuilderModule().isBuilderAvailable()).isTrue();

    // Given builder status is ok
    updateBuilderStatus(SafeFuture.completedFuture(Response.withNullPayload()));

    // Then
    assertThat(executionLayerManager.getExecutionBuilderModule().isBuilderAvailable()).isTrue();
    verifyNoInteractions(eventLogger);

    // Given builder status is not ok
    updateBuilderStatus(SafeFuture.completedFuture(Response.withErrorMessage("oops")));

    // Then
    assertThat(executionLayerManager.getExecutionBuilderModule().isBuilderAvailable()).isFalse();
    verify(eventLogger).builderIsNotAvailable("oops");

    // Given builder status is back to being ok
    updateBuilderStatus(SafeFuture.completedFuture(Response.withNullPayload()));

    // Then
    assertThat(executionLayerManager.getExecutionBuilderModule().isBuilderAvailable()).isTrue();
    verify(eventLogger).builderIsAvailable();
  }

  @Test
  public void engineGetPayload_shouldReturnGetPayloadResponseViaEngine() {
    final ExecutionPayloadContext executionPayloadContext =
        dataStructureUtil.randomPayloadExecutionContext(false, true);
    final UInt64 slot = executionPayloadContext.getForkChoiceState().getHeadBlockSlot();
    final BeaconState state = dataStructureUtil.randomBeaconState(slot);

    final GetPayloadResponse getPayloadResponse =
        prepareEngineGetPayloadResponse(executionPayloadContext, localExecutionPayloadValue, slot);

    assertThat(executionLayerManager.engineGetPayload(executionPayloadContext, state))
        .isCompletedWithValue(getPayloadResponse);

    // we expect no calls to builder
    verifyNoInteractions(builderClient);

    verifySourceCounter(Source.LOCAL_EL, FallbackReason.NONE);
  }

  @Test
  public void engineGetPayloadV2_shouldReturnPayloadViaEngine() {
    spec = TestSpecFactory.createMinimalCapella();
    dataStructureUtil = new DataStructureUtil(spec);
    final ExecutionPayloadContext executionPayloadContext =
        dataStructureUtil.randomPayloadExecutionContext(false, true);
    executionLayerManager = createExecutionLayerChannelImpl(false, false);
    final UInt64 slot = executionPayloadContext.getForkChoiceState().getHeadBlockSlot();
    final BeaconState state = dataStructureUtil.randomBeaconState(slot);

    final GetPayloadResponse getPayloadResponse =
        prepareEngineGetPayloadResponse(executionPayloadContext, localExecutionPayloadValue, slot);

    assertThat(executionLayerManager.engineGetPayload(executionPayloadContext, state))
        .isCompletedWithValue(getPayloadResponse);

    // we expect no calls to builder
    verifyNoInteractions(builderClient);

    verifySourceCounter(Source.LOCAL_EL, FallbackReason.NONE);
  }

  @Test
  public void engineGetClientVersion_shouldReturnClientVersionViaEngine() {
    final ClientVersion consensusClientVersion = dataStructureUtil.randomClientVersion();
    final List<ClientVersion> engineResponse = List.of(dataStructureUtil.randomClientVersion());

    when(executionClientHandler.engineGetClientVersion(consensusClientVersion))
        .thenReturn(SafeFuture.completedFuture(engineResponse));

    assertThat(executionLayerManager.engineGetClientVersion(consensusClientVersion))
        .isCompletedWithValue(engineResponse);
  }

  @Test
  public void builderGetHeaderGetPayload_shouldReturnHeaderAndPayloadViaBuilder() {
    setBuilderOnline();

    final ExecutionPayloadContext executionPayloadContext =
        dataStructureUtil.randomPayloadExecutionContext(false, true);
    final UInt64 slot = executionPayloadContext.getForkChoiceState().getHeadBlockSlot();
    final BeaconState state = dataStructureUtil.randomBeaconState(slot);

    final ExecutionPayloadHeader header =
        prepareBuilderGetHeaderResponse(
                executionPayloadContext, false, builderExecutionPayloadValue)
            .getHeader();
    prepareEngineGetPayloadResponse(executionPayloadContext, localExecutionPayloadValue, slot);

    final SafeFuture<UInt256> blockValueResult = new SafeFuture<>();

    // we expect result from the builder
    assertThat(
            executionLayerManager.builderGetHeader(
                executionPayloadContext,
                state,
                blockValueResult,
                Optional.empty(),
                BlockProductionPerformance.NOOP))
        .isCompletedWithValue(HeaderWithFallbackData.create(header));
    assertThat(blockValueResult).isCompletedWithValue(builderExecutionPayloadValue);

    // we expect both builder and local engine have been called
    verifyBuilderCalled(slot, executionPayloadContext);
    verifyEngineCalled(executionPayloadContext, slot);

    final SignedBeaconBlock signedBlindedBeaconBlock =
        dataStructureUtil.randomSignedBlindedBeaconBlock(slot);

    final ExecutionPayload payload = prepareBuilderGetPayloadResponse(signedBlindedBeaconBlock);

    // we expect result from the builder
    assertThat(
            executionLayerManager.builderGetPayload(
                signedBlindedBeaconBlock, (aSlot) -> Optional.empty()))
        .isCompletedWithValue(payload);

    // we expect both builder and local engine have been called
    verify(builderClient).getPayload(signedBlindedBeaconBlock);
    verifyNoMoreInteractions(executionClientHandler);

    verifySourceCounter(Source.BUILDER, FallbackReason.NONE);
  }

  @Test
  public void builderGetHeaderGetPayload_shouldReturnHeaderAndPayloadViaBuilderWhenLocalIsFailed() {
    setBuilderOnline();

    final ExecutionPayloadContext executionPayloadContext =
        dataStructureUtil.randomPayloadExecutionContext(false, true);
    final UInt64 slot = executionPayloadContext.getForkChoiceState().getHeadBlockSlot();
    final BeaconState state = dataStructureUtil.randomBeaconState(slot);

    final ExecutionPayloadHeader header =
        prepareBuilderGetHeaderResponse(
                executionPayloadContext, false, builderExecutionPayloadValue)
            .getHeader();
    prepareEngineFailedPayloadResponse(executionPayloadContext, slot);

    final SafeFuture<UInt256> blockValueResult = new SafeFuture<>();

    // we expect result from the builder
    assertThat(
            executionLayerManager.builderGetHeader(
                executionPayloadContext,
                state,
                blockValueResult,
                Optional.empty(),
                BlockProductionPerformance.NOOP))
        .isCompletedWithValue(HeaderWithFallbackData.create(header));
    assertThat(blockValueResult).isCompletedWithValue(builderExecutionPayloadValue);

    // we expect both builder and local engine have been called
    verifyBuilderCalled(slot, executionPayloadContext);
    verifyEngineCalled(executionPayloadContext, slot);

    final SignedBeaconBlock signedBlindedBeaconBlock =
        dataStructureUtil.randomSignedBlindedBeaconBlock(slot);

    final ExecutionPayload payload = prepareBuilderGetPayloadResponse(signedBlindedBeaconBlock);

    // we expect result from the builder
    assertThat(
            executionLayerManager.builderGetPayload(
                signedBlindedBeaconBlock, (aSlot) -> Optional.empty()))
        .isCompletedWithValue(payload);

    // we expect both builder and local engine have been called
    verify(builderClient).getPayload(signedBlindedBeaconBlock);
    verifyNoMoreInteractions(executionClientHandler);

    verifySourceCounter(Source.BUILDER, FallbackReason.NONE);
  }

  @Test
  public void builderGetHeaderGetPayload_shouldReturnHeaderAndPayloadViaEngineWhenValueHigher() {
    setBuilderOnline();

    final ExecutionPayloadContext executionPayloadContext =
        dataStructureUtil.randomPayloadExecutionContext(false, true);
    final UInt64 slot = executionPayloadContext.getForkChoiceState().getHeadBlockSlot();
    final BeaconState state = dataStructureUtil.randomBeaconState(slot);

    final UInt256 builderValue =
        prepareBuilderGetHeaderResponse(
                executionPayloadContext, false, builderExecutionPayloadValue)
            .getValue();
    final UInt256 localValueOverride = builderValue.multiply(2);
    final ExecutionPayload localExecutionPayload =
        prepareEngineGetPayloadResponse(executionPayloadContext, localValueOverride, slot)
            .getExecutionPayload();

    // we expect result from the local engine
    final ExecutionPayloadHeader expectedHeader =
        spec.getGenesisSpec()
            .getSchemaDefinitions()
            .toVersionBellatrix()
            .orElseThrow()
            .getExecutionPayloadHeaderSchema()
            .createFromExecutionPayload(localExecutionPayload);

    // we expect local engine header as result
    final HeaderWithFallbackData expectedResult =
        HeaderWithFallbackData.create(
            expectedHeader,
            new FallbackData(localExecutionPayload, FallbackReason.LOCAL_BLOCK_VALUE_WON));
    final SafeFuture<UInt256> blockValueResult = new SafeFuture<>();
    assertThat(
            executionLayerManager.builderGetHeader(
                executionPayloadContext,
                state,
                blockValueResult,
                Optional.empty(),
                BlockProductionPerformance.NOOP))
        .isCompletedWithValue(expectedResult);
    assertThat(blockValueResult).isCompletedWithValue(localValueOverride);
    verifyFallbackToLocalEL(slot, executionPayloadContext, expectedResult);
  }

  @Test
  public void
      builderGetHeaderGetPayload_shouldReturnLocalPayloadWhenBuilderFactorIsAlwaysBuilderAndBidValidationFails() {
    // Setup will always ignore local payload in favor of Builder bid
    executionLayerManager =
        createExecutionLayerChannelImpl(true, true, BUILDER_BOOST_FACTOR_PREFER_BUILDER);
    setBuilderOnline();

    final ExecutionPayloadContext executionPayloadContext =
        dataStructureUtil.randomPayloadExecutionContext(false, true);
    final UInt64 slot = executionPayloadContext.getForkChoiceState().getHeadBlockSlot();
    final BeaconState state = dataStructureUtil.randomBeaconState(slot);

    prepareBuilderGetHeaderResponse(executionPayloadContext, false, builderExecutionPayloadValue);
    final ExecutionPayload payload =
        prepareEngineGetPayloadResponse(executionPayloadContext, localExecutionPayloadValue, slot)
            .getExecutionPayload();

    final ExecutionPayloadHeader header =
        spec.getGenesisSpec()
            .getSchemaDefinitions()
            .toVersionBellatrix()
            .orElseThrow()
            .getExecutionPayloadHeaderSchema()
            .createFromExecutionPayload(payload);

    // we expect local engine header as result
    final HeaderWithFallbackData expectedResult =
        HeaderWithFallbackData.create(
            header, new FallbackData(payload, FallbackReason.BUILDER_ERROR));
    final SafeFuture<UInt256> blockValueResult = new SafeFuture<>();
    assertThat(
            executionLayerManager.builderGetHeader(
                executionPayloadContext,
                state,
                blockValueResult,
                Optional.empty(),
                BlockProductionPerformance.NOOP))
        .isCompletedWithValue(expectedResult);
    assertThat(blockValueResult).isCompletedWithValue(localExecutionPayloadValue);

    verifyFallbackToLocalEL(slot, executionPayloadContext, expectedResult);
  }

  @Test
  public void builderGetHeaderGetPayload_shouldReturnHeaderAndPayloadViaEngineOnBuilderFailure() {
    setBuilderOnline();

    final ExecutionPayloadContext executionPayloadContext =
        dataStructureUtil.randomPayloadExecutionContext(false, true);
    final UInt64 slot = executionPayloadContext.getForkChoiceState().getHeadBlockSlot();
    final BeaconState state = dataStructureUtil.randomBeaconState(slot);

    prepareBuilderGetHeaderFailure(executionPayloadContext);
    final ExecutionPayload payload =
        prepareEngineGetPayloadResponse(executionPayloadContext, localExecutionPayloadValue, slot)
            .getExecutionPayload();

    final ExecutionPayloadHeader header =
        spec.getGenesisSpec()
            .getSchemaDefinitions()
            .toVersionBellatrix()
            .orElseThrow()
            .getExecutionPayloadHeaderSchema()
            .createFromExecutionPayload(payload);

    // we expect local engine header as result
    HeaderWithFallbackData expectedResult =
        HeaderWithFallbackData.create(
            header, new FallbackData(payload, FallbackReason.BUILDER_ERROR));
    final SafeFuture<UInt256> blockValueResult = new SafeFuture<>();
    assertThat(
            executionLayerManager.builderGetHeader(
                executionPayloadContext,
                state,
                blockValueResult,
                Optional.empty(),
                BlockProductionPerformance.NOOP))
        .isCompletedWithValue(expectedResult);
    assertThat(blockValueResult).isCompletedWithValue(localExecutionPayloadValue);

    // we expect both builder and local engine have been called
    verifyBuilderCalled(slot, executionPayloadContext);
    verifyEngineCalled(executionPayloadContext, slot);

    final SignedBeaconBlock signedBlindedBeaconBlock =
        dataStructureUtil.randomSignedBlindedBeaconBlock(slot);

    // we expect result from the cached payload
    assertThat(
            executionLayerManager.builderGetPayload(
                signedBlindedBeaconBlock,
                (aSlot) ->
                    Optional.of(
                        new ExecutionPayloadResult(
                            executionPayloadContext,
                            Optional.empty(),
                            Optional.empty(),
                            Optional.of(SafeFuture.completedFuture(expectedResult)),
                            Optional.empty()))))
        .isCompletedWithValue(payload);

    // we expect no additional calls
    verifyNoMoreInteractions(builderClient);
    verifyNoMoreInteractions(executionClientHandler);

    verifySourceCounter(Source.BUILDER_LOCAL_EL_FALLBACK, FallbackReason.BUILDER_ERROR);
  }

  @Test
  public void
      builderGetHeaderGetPayload_shouldReturnHeaderAndPayloadViaEngineIfShouldOverrideBuilderIsSetToTrue() {
    // the shouldOverrideBuilder flag is available only from Deneb
    setupDeneb();
    setBuilderOnline();

    final ExecutionPayloadContext executionPayloadContext =
        dataStructureUtil.randomPayloadExecutionContext(false, true);
    final UInt64 slot = executionPayloadContext.getForkChoiceState().getHeadBlockSlot();
    final BeaconState state = dataStructureUtil.randomBeaconState(slot);

    prepareBuilderGetHeaderResponse(executionPayloadContext, false, builderExecutionPayloadValue);

    final GetPayloadResponse getPayloadResponse =
        prepareEngineGetPayloadResponse(
            executionPayloadContext, localExecutionPayloadValue, true, slot);

    // we expect result from the local engine
    final ExecutionPayloadHeader expectedHeader =
        spec.atSlot(slot)
            .getSchemaDefinitions()
            .toVersionBellatrix()
            .orElseThrow()
            .getExecutionPayloadHeaderSchema()
            .createFromExecutionPayload(getPayloadResponse.getExecutionPayload());
    final SszList<SszKZGCommitment> expectedBlobKzgCommitments =
        spec.atSlot(slot)
            .getSchemaDefinitions()
            .toVersionDeneb()
            .orElseThrow()
            .getBlobKzgCommitmentsSchema()
            .createFromBlobsBundle(getPayloadResponse.getBlobsBundle().orElseThrow());

    // we expect local engine header as result
    final HeaderWithFallbackData expectedResult =
        HeaderWithFallbackData.create(
            expectedHeader,
            Optional.of(expectedBlobKzgCommitments),
            new FallbackData(
                getPayloadResponse.getExecutionPayload(),
                getPayloadResponse.getBlobsBundle(),
                FallbackReason.SHOULD_OVERRIDE_BUILDER_FLAG_IS_TRUE));
    final SafeFuture<UInt256> blockValueResult = new SafeFuture<>();
    assertThat(
            executionLayerManager.builderGetHeader(
                executionPayloadContext,
                state,
                blockValueResult,
                Optional.empty(),
                BlockProductionPerformance.NOOP))
        .isCompletedWithValue(expectedResult);
    assertThat(blockValueResult).isCompletedWithValue(localExecutionPayloadValue);
    verifyFallbackToLocalEL(slot, executionPayloadContext, expectedResult);
  }

  @Test
  public void
      builderGetHeaderGetPayload_shouldReturnHeaderAndPayloadViaEngineOnBidValidationFailure() {
    executionLayerManager = createExecutionLayerChannelImpl(true, true);
    setBuilderOnline();

    final ExecutionPayloadContext executionPayloadContext =
        dataStructureUtil.randomPayloadExecutionContext(false, true);
    final UInt64 slot = executionPayloadContext.getForkChoiceState().getHeadBlockSlot();
    final BeaconState state = dataStructureUtil.randomBeaconState(slot);

    prepareBuilderGetHeaderResponse(executionPayloadContext, false, builderExecutionPayloadValue);
    final ExecutionPayload payload =
        prepareEngineGetPayloadResponse(executionPayloadContext, localExecutionPayloadValue, slot)
            .getExecutionPayload();

    final ExecutionPayloadHeader header =
        spec.getGenesisSpec()
            .getSchemaDefinitions()
            .toVersionBellatrix()
            .orElseThrow()
            .getExecutionPayloadHeaderSchema()
            .createFromExecutionPayload(payload);

    // we expect local engine header as result
    final HeaderWithFallbackData expectedResult =
        HeaderWithFallbackData.create(
            header, new FallbackData(payload, FallbackReason.BUILDER_ERROR));
    final SafeFuture<UInt256> blockValueResult = new SafeFuture<>();
    assertThat(
            executionLayerManager.builderGetHeader(
                executionPayloadContext,
                state,
                blockValueResult,
                Optional.empty(),
                BlockProductionPerformance.NOOP))
        .isCompletedWithValue(expectedResult);
    assertThat(blockValueResult).isCompletedWithValue(localExecutionPayloadValue);

    verifyFallbackToLocalEL(slot, executionPayloadContext, expectedResult);
  }

  @Test
  public void builderGetHeaderGetPayload_shouldReturnHeaderAndPayloadViaEngineIfBuilderNotActive() {
    setBuilderOffline();

    final ExecutionPayloadContext executionPayloadContext =
        dataStructureUtil.randomPayloadExecutionContext(false, true);
    final UInt64 slot = executionPayloadContext.getForkChoiceState().getHeadBlockSlot();
    final BeaconState state = dataStructureUtil.randomBeaconState(slot);

    final ExecutionPayload payload =
        prepareEngineGetPayloadResponse(executionPayloadContext, localExecutionPayloadValue, slot)
            .getExecutionPayload();

    final ExecutionPayloadHeader header =
        spec.getGenesisSpec()
            .getSchemaDefinitions()
            .toVersionBellatrix()
            .orElseThrow()
            .getExecutionPayloadHeaderSchema()
            .createFromExecutionPayload(payload);

    // we expect local engine header as result
    final HeaderWithFallbackData expectedResult =
        HeaderWithFallbackData.create(
            header, new FallbackData(payload, FallbackReason.BUILDER_NOT_AVAILABLE));
    final SafeFuture<UInt256> blockValueResult = new SafeFuture<>();
    assertThat(
            executionLayerManager.builderGetHeader(
                executionPayloadContext,
                state,
                blockValueResult,
                Optional.empty(),
                BlockProductionPerformance.NOOP))
        .isCompletedWithValue(expectedResult);
    assertThat(blockValueResult).isCompletedWithValue(localExecutionPayloadValue);

    verifyFallbackToLocalEL(slot, executionPayloadContext, expectedResult);
  }

  @Test
  public void
      builderGetHeaderGetPayload_shouldReturnHeaderAndPayloadViaEngineIfBuilderHeaderNotAvailable() {
    setBuilderOnline();

    final ExecutionPayloadContext executionPayloadContext =
        dataStructureUtil.randomPayloadExecutionContext(false, true);
    final UInt64 slot = executionPayloadContext.getForkChoiceState().getHeadBlockSlot();
    final BeaconState state = dataStructureUtil.randomBeaconState(slot);

    prepareBuilderGetHeaderResponse(executionPayloadContext, true, builderExecutionPayloadValue);

    final ExecutionPayload payload =
        prepareEngineGetPayloadResponse(executionPayloadContext, localExecutionPayloadValue, slot)
            .getExecutionPayload();

    final ExecutionPayloadHeader header =
        spec.getGenesisSpec()
            .getSchemaDefinitions()
            .toVersionBellatrix()
            .orElseThrow()
            .getExecutionPayloadHeaderSchema()
            .createFromExecutionPayload(payload);

    // we expect local engine header as result
    final HeaderWithFallbackData expectedResult =
        HeaderWithFallbackData.create(
            header, new FallbackData(payload, FallbackReason.BUILDER_HEADER_NOT_AVAILABLE));
    final SafeFuture<UInt256> blockValueResult = new SafeFuture<>();
    assertThat(
            executionLayerManager.builderGetHeader(
                executionPayloadContext,
                state,
                blockValueResult,
                Optional.empty(),
                BlockProductionPerformance.NOOP))
        .isCompletedWithValue(expectedResult);
    assertThat(blockValueResult).isCompletedWithValue(localExecutionPayloadValue);

    verifyFallbackToLocalEL(slot, executionPayloadContext, expectedResult);
  }

  @Test
  public void
      builderGetHeaderGetPayload_shouldReturnHeaderAndPayloadViaEngineIfTransitionNotFinalized() {
    setBuilderOnline();

    final ExecutionPayloadContext executionPayloadContext =
        dataStructureUtil.randomPayloadExecutionContext(Bytes32.ZERO, false, true);
    final UInt64 slot = executionPayloadContext.getForkChoiceState().getHeadBlockSlot();
    final BeaconState state = dataStructureUtil.randomBeaconStatePreMerge(slot);

    final ExecutionPayload payload =
        prepareEngineGetPayloadResponse(executionPayloadContext, localExecutionPayloadValue, slot)
            .getExecutionPayload();

    final ExecutionPayloadHeader header =
        spec.getGenesisSpec()
            .getSchemaDefinitions()
            .toVersionBellatrix()
            .orElseThrow()
            .getExecutionPayloadHeaderSchema()
            .createFromExecutionPayload(payload);

    // we expect local engine header as result
    final HeaderWithFallbackData expectedResult =
        HeaderWithFallbackData.create(
            header, new FallbackData(payload, FallbackReason.TRANSITION_NOT_FINALIZED));
    final SafeFuture<UInt256> blockValueResult = new SafeFuture<>();
    assertThat(
            executionLayerManager.builderGetHeader(
                executionPayloadContext,
                state,
                blockValueResult,
                Optional.empty(),
                BlockProductionPerformance.NOOP))
        .isCompletedWithValue(expectedResult);
    assertThat(blockValueResult).isCompletedWithValue(localExecutionPayloadValue);

    verifyFallbackToLocalEL(slot, executionPayloadContext, expectedResult);
  }

  @Test
  public void
      builderGetHeaderGetPayload_shouldReturnHeaderAndPayloadViaEngineIfCircuitBreakerEngages() {
    setBuilderOnline();

    final ExecutionPayloadContext executionPayloadContext =
        dataStructureUtil.randomPayloadExecutionContext(false, true);
    final UInt64 slot = executionPayloadContext.getForkChoiceState().getHeadBlockSlot();
    final BeaconState state = dataStructureUtil.randomBeaconState(slot);

    final ExecutionPayload payload =
        prepareEngineGetPayloadResponse(executionPayloadContext, localExecutionPayloadValue, slot)
            .getExecutionPayload();

    final ExecutionPayloadHeader header =
        spec.getGenesisSpec()
            .getSchemaDefinitions()
            .toVersionBellatrix()
            .orElseThrow()
            .getExecutionPayloadHeaderSchema()
            .createFromExecutionPayload(payload);

    when(builderCircuitBreaker.isEngaged(any())).thenReturn(true);

    // we expect local engine header as result
    final HeaderWithFallbackData expectedResult =
        HeaderWithFallbackData.create(
            header, new FallbackData(payload, FallbackReason.CIRCUIT_BREAKER_ENGAGED));
    final SafeFuture<UInt256> blockValueResult = new SafeFuture<>();
    assertThat(
            executionLayerManager.builderGetHeader(
                executionPayloadContext,
                state,
                blockValueResult,
                Optional.empty(),
                BlockProductionPerformance.NOOP))
        .isCompletedWithValue(expectedResult);
    assertThat(blockValueResult).isCompletedWithValue(localExecutionPayloadValue);

    verifyFallbackToLocalEL(slot, executionPayloadContext, expectedResult);
  }

  @Test
  public void
      builderGetHeaderGetPayload_shouldReturnHeaderAndPayloadViaEngineIfCircuitBreakerThrows() {
    setBuilderOnline();

    final ExecutionPayloadContext executionPayloadContext =
        dataStructureUtil.randomPayloadExecutionContext(false, true);
    final UInt64 slot = executionPayloadContext.getForkChoiceState().getHeadBlockSlot();
    final BeaconState state = dataStructureUtil.randomBeaconState(slot);

    final ExecutionPayload payload =
        prepareEngineGetPayloadResponse(executionPayloadContext, localExecutionPayloadValue, slot)
            .getExecutionPayload();

    final ExecutionPayloadHeader header =
        spec.getGenesisSpec()
            .getSchemaDefinitions()
            .toVersionBellatrix()
            .orElseThrow()
            .getExecutionPayloadHeaderSchema()
            .createFromExecutionPayload(payload);

    when(builderCircuitBreaker.isEngaged(any())).thenThrow(new RuntimeException("error"));

    // we expect local engine header as result
    final HeaderWithFallbackData expectedResult =
        HeaderWithFallbackData.create(
            header, new FallbackData(payload, FallbackReason.CIRCUIT_BREAKER_ENGAGED));
    final SafeFuture<UInt256> blockValueResult = new SafeFuture<>();
    assertThat(
            executionLayerManager.builderGetHeader(
                executionPayloadContext,
                state,
                blockValueResult,
                Optional.empty(),
                BlockProductionPerformance.NOOP))
        .isCompletedWithValue(expectedResult);
    assertThat(blockValueResult).isCompletedWithValue(localExecutionPayloadValue);

    verifyFallbackToLocalEL(slot, executionPayloadContext, expectedResult);
  }

  @Test
  public void
      builderGetHeaderGetPayload_shouldReturnHeaderAndPayloadViaEngineIfValidatorIsNotRegistered() {
    setBuilderOnline();

    final ExecutionPayloadContext executionPayloadContext =
        dataStructureUtil.randomPayloadExecutionContext(false, false);
    final UInt64 slot = executionPayloadContext.getForkChoiceState().getHeadBlockSlot();
    final BeaconState state = dataStructureUtil.randomBeaconState(slot);

    final ExecutionPayload payload =
        prepareEngineGetPayloadResponse(executionPayloadContext, localExecutionPayloadValue, slot)
            .getExecutionPayload();

    final ExecutionPayloadHeader header =
        spec.getGenesisSpec()
            .getSchemaDefinitions()
            .toVersionBellatrix()
            .orElseThrow()
            .getExecutionPayloadHeaderSchema()
            .createFromExecutionPayload(payload);

    // we expect local engine header as result
    final HeaderWithFallbackData expectedResult =
        HeaderWithFallbackData.create(
            header, new FallbackData(payload, FallbackReason.VALIDATOR_NOT_REGISTERED));
    final SafeFuture<UInt256> blockValueResult = new SafeFuture<>();
    assertThat(
            executionLayerManager.builderGetHeader(
                executionPayloadContext,
                state,
                blockValueResult,
                Optional.empty(),
                BlockProductionPerformance.NOOP))
        .isCompletedWithValue(expectedResult);
    assertThat(blockValueResult).isCompletedWithValue(localExecutionPayloadValue);

    verifyFallbackToLocalEL(slot, executionPayloadContext, expectedResult);
  }

  @Test
  void onSlot_shouldCleanUpFallbackCache() {
    setBuilderOffline();

    IntStream.rangeClosed(1, 4)
        .forEach(
            value -> {
              final UInt64 slot = UInt64.valueOf(value);
              final ExecutionPayloadContext executionPayloadContext =
                  dataStructureUtil.randomPayloadExecutionContext(slot, false);
              final BeaconState state = dataStructureUtil.randomBeaconState(slot);
              prepareEngineGetPayloadResponse(
                  executionPayloadContext, localExecutionPayloadValue, slot);
              assertThat(
                      executionLayerManager.builderGetHeader(
                          executionPayloadContext,
                          state,
                          SafeFuture.completedFuture(localExecutionPayloadValue),
                          Optional.empty(),
                          BlockProductionPerformance.NOOP))
                  .isCompleted();
            });

    reset(executionClientHandler);

    // this trigger onSlot at slot 5, which cleans cache up to slot 3
    setBuilderOffline(UInt64.valueOf(5));

    // we expect a call to builder even if it is offline
    // since we have nothing better to do

    final UInt64 slot = UInt64.ONE;
    final SignedBeaconBlock signedBlindedBeaconBlock =
        dataStructureUtil.randomSignedBlindedBeaconBlock(slot);

    final ExecutionPayload payload = prepareBuilderGetPayloadResponse(signedBlindedBeaconBlock);

    // we expect result from the builder
    assertThat(
            executionLayerManager.builderGetPayload(
                signedBlindedBeaconBlock, (aSlot) -> Optional.empty()))
        .isCompletedWithValue(payload);

    // we expect both builder and local engine have been called
    verify(builderClient).getPayload(signedBlindedBeaconBlock);
    verifyNoMoreInteractions(executionClientHandler);
  }

  private void setupDeneb() {
    spec = TestSpecFactory.createMinimalDeneb();
    dataStructureUtil = new DataStructureUtil(spec);
    setup();
  }

  private BuilderBid prepareBuilderGetHeaderResponse(
      final ExecutionPayloadContext executionPayloadContext,
      final boolean prepareEmptyResponse,
      final UInt256 builderBlockValue) {
    final UInt64 slot = executionPayloadContext.getForkChoiceState().getHeadBlockSlot();

    final BuilderBid builderBid =
        dataStructureUtil.randomBuilderBid(dataStructureUtil.randomPublicKey(), builderBlockValue);
    final SignedBuilderBid signedBuilderBid = dataStructureUtil.randomSignedBuilderBid(builderBid);

    doAnswer(
            __ -> {
              if (prepareEmptyResponse) {
                return SafeFuture.completedFuture(new Response<>(Optional.empty()));
              }
              return SafeFuture.completedFuture(new Response<>(Optional.of(signedBuilderBid)));
            })
        .when(builderClient)
        .getHeader(
            slot,
            executionPayloadContext
                .getPayloadBuildingAttributes()
                .getValidatorRegistrationPublicKey()
                .orElseThrow(),
            executionPayloadContext.getParentHash());

    return signedBuilderBid.getMessage();
  }

  private void verifyFallbackToLocalEL(
      final UInt64 slot,
      final ExecutionPayloadContext executionPayloadContext,
      final HeaderWithFallbackData headerWithFallbackData) {
    final FallbackData fallbackData = headerWithFallbackData.getFallbackData().orElseThrow();
    final FallbackReason fallbackReason = fallbackData.getReason();
    if (fallbackReason == FallbackReason.BUILDER_HEADER_NOT_AVAILABLE
        || fallbackReason == FallbackReason.BUILDER_ERROR
        || fallbackReason == FallbackReason.SHOULD_OVERRIDE_BUILDER_FLAG_IS_TRUE
        || fallbackReason == FallbackReason.LOCAL_BLOCK_VALUE_WON) {
      // we expect both builder and local engine have been called
      verifyBuilderCalled(slot, executionPayloadContext);
    } else {
      // we expect only local engine have been called
      verifyNoInteractions(builderClient);
    }
    verifyEngineCalled(executionPayloadContext, slot);

    final BuilderPayload builderPayload =
        fallbackData
            .getBlobsBundle()
            .map(
                executionBlobsBundle -> {
                  final SchemaDefinitionsDeneb schemaDefinitions =
                      SchemaDefinitionsDeneb.required(spec.atSlot(slot).getSchemaDefinitions());
                  final tech.pegasys.teku.spec.datastructures.builder.BlobsBundle blobsBundle =
                      schemaDefinitions
                          .getBlobsBundleSchema()
                          .createFromExecutionBlobsBundle(executionBlobsBundle);
                  return (BuilderPayload)
                      schemaDefinitions
                          .getExecutionPayloadAndBlobsBundleSchema()
                          .create(fallbackData.getExecutionPayload(), blobsBundle);
                })
            .orElse(fallbackData.getExecutionPayload());

    final SignedBeaconBlock signedBlindedBeaconBlock =
        dataStructureUtil.randomSignedBlindedBeaconBlock(slot);

    // we expect result from the cached payload
    assertThat(
            executionLayerManager.builderGetPayload(
                signedBlindedBeaconBlock,
                (aSlot) ->
                    Optional.of(
                        new ExecutionPayloadResult(
                            executionPayloadContext,
                            Optional.empty(),
                            Optional.empty(),
                            Optional.of(SafeFuture.completedFuture(headerWithFallbackData)),
                            Optional.empty()))))
        .isCompletedWithValue(builderPayload);

    // we expect no additional calls
    verifyNoMoreInteractions(builderClient);
    verifyNoMoreInteractions(executionClientHandler);

    verifySourceCounter(Source.BUILDER_LOCAL_EL_FALLBACK, fallbackReason);
  }

  private ExecutionPayload prepareBuilderGetPayloadResponse(
      final SignedBeaconBlock signedBlindedBeaconBlock) {

    final ExecutionPayload payload = dataStructureUtil.randomExecutionPayload();

    when(builderClient.getPayload(signedBlindedBeaconBlock))
        .thenReturn(SafeFuture.completedFuture(new Response<>(payload)));

    return payload;
  }

  private void prepareBuilderGetHeaderFailure(
      final ExecutionPayloadContext executionPayloadContext) {
    final UInt64 slot = executionPayloadContext.getForkChoiceState().getHeadBlockSlot();

    when(builderClient.getHeader(
            slot,
            executionPayloadContext
                .getPayloadBuildingAttributes()
                .getValidatorRegistrationPublicKey()
                .orElseThrow(),
            executionPayloadContext.getParentHash()))
        .thenReturn(SafeFuture.failedFuture(new Throwable("error")));
  }

  private GetPayloadResponse prepareEngineGetPayloadResponse(
      final ExecutionPayloadContext executionPayloadContext,
      final UInt256 localBlockValue,
      final UInt64 slot) {
    final ExecutionPayload payload = dataStructureUtil.randomExecutionPayload();
    final GetPayloadResponse getPayloadResponse = new GetPayloadResponse(payload, localBlockValue);
    when(executionClientHandler.engineGetPayload(executionPayloadContext, slot))
        .thenReturn(SafeFuture.completedFuture(getPayloadResponse));
    return getPayloadResponse;
  }

  private void prepareEngineFailedPayloadResponse(
      final ExecutionPayloadContext executionPayloadContext, final UInt64 slot) {
    when(executionClientHandler.engineGetPayload(executionPayloadContext, slot))
        .thenReturn(SafeFuture.failedFuture(new RuntimeException("")));
  }

  private GetPayloadResponse prepareEngineGetPayloadResponse(
      final ExecutionPayloadContext executionPayloadContext,
      final UInt256 blockValue,
      final boolean shouldOverrideBuilder,
      final UInt64 slot) {
    final ExecutionPayload payload = dataStructureUtil.randomExecutionPayload();
    final BlobsBundle blobsBundle = dataStructureUtil.randomBlobsBundle(3);
    final GetPayloadResponse getPayloadResponse =
        new GetPayloadResponse(payload, blockValue, blobsBundle, shouldOverrideBuilder);
    when(executionClientHandler.engineGetPayload(executionPayloadContext, slot))
        .thenReturn(SafeFuture.completedFuture(getPayloadResponse));
    return getPayloadResponse;
  }

  private ExecutionLayerManagerImpl createExecutionLayerChannelImpl(
      final boolean builderEnabled, final boolean builderValidatorEnabled) {
    return createExecutionLayerChannelImpl(
        builderEnabled, builderValidatorEnabled, BUILDER_BOOST_FACTOR_MAX_PROFIT);
  }

  private ExecutionLayerManagerImpl createExecutionLayerChannelImpl(
      final boolean builderEnabled,
      final boolean builderValidatorEnabled,
      final UInt64 builderBidCompareFactor) {
    when(builderCircuitBreaker.isEngaged(any())).thenReturn(false);
    return ExecutionLayerManagerImpl.create(
        eventLogger,
        executionClientHandler,
        builderEnabled ? Optional.of(builderClient) : Optional.empty(),
        spec,
        stubMetricsSystem,
        builderValidatorEnabled
            ? new BuilderBidValidatorImpl(spec, eventLogger)
            : BuilderBidValidator.NOOP,
        builderCircuitBreaker,
        builderBidCompareFactor,
        true);
  }

  private void updateBuilderStatus(final SafeFuture<Response<Void>> builderClientResponse) {
    updateBuilderStatus(builderClientResponse, UInt64.ONE);
  }

  private void updateBuilderStatus(SafeFuture<Response<Void>> builderClientResponse, UInt64 slot) {
    when(builderClient.status()).thenReturn(builderClientResponse);
    // trigger update of the builder status
    executionLayerManager.onSlot(slot);
  }

  private void setBuilderOffline() {
    setBuilderOffline(UInt64.ONE);
  }

  private void setBuilderOffline(final UInt64 slot) {
    updateBuilderStatus(SafeFuture.completedFuture(Response.withErrorMessage("oops")), slot);
    reset(builderClient);
    assertThat(executionLayerManager.getExecutionBuilderModule().isBuilderAvailable()).isFalse();
  }

  private void setBuilderOnline() {
    updateBuilderStatus(SafeFuture.completedFuture(Response.withNullPayload()), UInt64.ONE);
    reset(builderClient);
    assertThat(executionLayerManager.getExecutionBuilderModule().isBuilderAvailable()).isTrue();
  }

  private void verifyBuilderCalled(
      final UInt64 slot, final ExecutionPayloadContext executionPayloadContext) {
    verify(builderClient)
        .getHeader(
            slot,
            executionPayloadContext
                .getPayloadBuildingAttributes()
                .getValidatorRegistrationPublicKey()
                .orElseThrow(),
            executionPayloadContext.getParentHash());
  }

  private void verifyEngineCalled(
      final ExecutionPayloadContext executionPayloadContext, final UInt64 slot) {
    verify(executionClientHandler).engineGetPayload(executionPayloadContext, slot);
  }

  private void verifySourceCounter(final Source source, final FallbackReason reason) {
    final long actualCount =
        stubMetricsSystem
            .getCounter(TekuMetricCategory.BEACON, "execution_payload_source")
            .getValue(source.toString(), reason.toString());
    assertThat(actualCount).isOne();
  }
}
