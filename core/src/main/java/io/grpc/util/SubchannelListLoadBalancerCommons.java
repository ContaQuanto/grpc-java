/*
 * Copyright 2023 The gRPC Authors
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

package io.grpc.util;

import static com.google.common.base.Preconditions.checkNotNull;
import static io.grpc.ConnectivityState.CONNECTING;
import static io.grpc.ConnectivityState.IDLE;
import static io.grpc.ConnectivityState.READY;
import static io.grpc.ConnectivityState.SHUTDOWN;
import static io.grpc.ConnectivityState.TRANSIENT_FAILURE;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import io.grpc.Attributes;
import io.grpc.ConnectivityState;
import io.grpc.ConnectivityStateInfo;
import io.grpc.EquivalentAddressGroup;
import io.grpc.Internal;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancer.CreateSubchannelArgs;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.PickResult;
import io.grpc.LoadBalancer.PickSubchannelArgs;
import io.grpc.LoadBalancer.ResolvedAddresses;
import io.grpc.LoadBalancer.Subchannel;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.LoadBalancer.SubchannelStateListener;
import io.grpc.NameResolver;
import io.grpc.Status;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;

/**
 * A utility function that provides common processing for accepting a list of {@link
 * EquivalentAddressGroup}s from the {@link NameResolver}. It provides default implementation of
 * accepting {@link io.grpc.LoadBalancer.ResolvedAddresses} updates, process
 * aggregated load balancing state. A {@link LoadBalancer} that has sunchannel list will provide
 * creating subchannel task and load-balancing strategy over the subchannel list.
 */
@Internal
public class SubchannelListLoadBalancerCommons {
  @VisibleForTesting
  static final Attributes.Key<Ref<ConnectivityStateInfo>> STATE_INFO =
      Attributes.Key.create("state-info");
  private final LoadBalancer.Helper helper;
  private final Map<EquivalentAddressGroup, Subchannel> subchannels =
      new HashMap<>();
  private ConnectivityState currentState;
  private RoundRobinPicker currentPicker = new EmptyPicker(EMPTY_OK);
  private final Runnable afterUpdateTask;
  private final Function<List<Subchannel>, RoundRobinPicker> createReadyPickerTask;

  /**
   * Common new addresses list update handling.
   *
   * @param afterUpdateTask An action point after accepting the most recent resolved addresses.
   * @param createReadyPickerTask Returns the picker that selects a subchannel in the list for each
   *                              incoming RPC.
   */
  SubchannelListLoadBalancerCommons(Helper helper,
                                    Runnable afterUpdateTask,
                                    Function<List<Subchannel>, RoundRobinPicker>
                                            createReadyPickerTask) {
    this.helper = checkNotNull(helper, "helper");
    this.afterUpdateTask = checkNotNull(afterUpdateTask, "afterUpdateTask");
    this.createReadyPickerTask = checkNotNull(createReadyPickerTask, "createReadyPickerTask");
  }

  /**
   * Common new addresses list update handling.
   */
  public boolean acceptResolvedAddresses(ResolvedAddresses resolvedAddresses) {
    if (resolvedAddresses.getAddresses().isEmpty()) {
      handleNameResolutionError(Status.UNAVAILABLE.withDescription(
          "NameResolver returned no usable address. addrs=" + resolvedAddresses.getAddresses()
              + ", attrs=" + resolvedAddresses.getAttributes()));
      return false;
    }

    List<EquivalentAddressGroup> servers = resolvedAddresses.getAddresses();
    Set<EquivalentAddressGroup> currentAddrs = subchannels.keySet();
    Map<EquivalentAddressGroup, EquivalentAddressGroup> latestAddrs = stripAttrs(servers);
    Set<EquivalentAddressGroup> removedAddrs = setsDifference(currentAddrs, latestAddrs.keySet());

    for (Map.Entry<EquivalentAddressGroup, EquivalentAddressGroup> latestEntry :
        latestAddrs.entrySet()) {
      EquivalentAddressGroup strippedAddressGroup = latestEntry.getKey();
      EquivalentAddressGroup originalAddressGroup = latestEntry.getValue();
      Subchannel existingSubchannel = subchannels.get(strippedAddressGroup);
      if (existingSubchannel != null) {
        // EAG's Attributes may have changed.
        existingSubchannel.updateAddresses(Collections.singletonList(originalAddressGroup));
        continue;
      }
      // Create new subchannels for new addresses.

      // NB(lukaszx0): we don't merge `attributes` with `subchannelAttr` because subchannel
      // doesn't need them. They're describing the resolved server list but we're not taking
      // any action based on this information.
      Attributes.Builder subchannelAttrs = Attributes.newBuilder()
          // NB(lukaszx0): because attributes are immutable we can't set new value for the key
          // after creation but since we can mutate the values we leverage that and set
          // AtomicReference which will allow mutating state info for given channel.
          .set(STATE_INFO,
              new Ref<>(ConnectivityStateInfo.forNonError(IDLE)));

      final Subchannel subchannel = checkNotNull(
              helper.createSubchannel(CreateSubchannelArgs.newBuilder()
              .setAddresses(originalAddressGroup)
              .setAttributes(subchannelAttrs.build())
              .build()),
          "subchannel");
      subchannel.start(new SubchannelStateListener() {
          @Override
          public void onSubchannelState(ConnectivityStateInfo state) {
            processSubchannelState(subchannel, state);
          }
        });
      subchannels.put(strippedAddressGroup, subchannel);
      subchannel.requestConnection();
    }

    ArrayList<Subchannel> removedSubchannels = new ArrayList<>();
    for (EquivalentAddressGroup addressGroup : removedAddrs) {
      removedSubchannels.add(subchannels.remove(addressGroup));
    }

    afterUpdateTask.run();

    // Update the picker before shutting down the subchannels, to reduce the chance of the race
    // between picking a subchannel and shutting it down.
    updateBalancingState(createReadyPickerTask);

    // Shutdown removed subchannels
    for (Subchannel removedSubchannel : removedSubchannels) {
      shutdownSubchannel(removedSubchannel);
    }

    return true;
  }

  /**
   * Common error handling from the name resolver.
   */
  public void handleNameResolutionError(Status error) {
    if (currentState != READY)  {
      updateBalancingState(TRANSIENT_FAILURE, new EmptyPicker(error));
    }
  }

  private void processSubchannelState(Subchannel subchannel, ConnectivityStateInfo stateInfo) {
    if (subchannels.get(stripAttrs(subchannel.getAddresses())) != subchannel) {
      return;
    }
    if (stateInfo.getState() == TRANSIENT_FAILURE || stateInfo.getState() == IDLE) {
      helper.refreshNameResolution();
    }
    if (stateInfo.getState() == IDLE) {
      subchannel.requestConnection();
    }
    Ref<ConnectivityStateInfo> subchannelStateRef = getSubchannelStateInfoRef(subchannel);
    if (subchannelStateRef.value.getState().equals(TRANSIENT_FAILURE)) {
      if (stateInfo.getState().equals(CONNECTING) || stateInfo.getState().equals(IDLE)) {
        return;
      }
    }
    subchannelStateRef.value = stateInfo;
    updateBalancingState(createReadyPickerTask);
  }

  private void shutdownSubchannel(Subchannel subchannel) {
    subchannel.shutdown();
    getSubchannelStateInfoRef(subchannel).value =
        ConnectivityStateInfo.forNonError(SHUTDOWN);
  }

  public void shutdown() {
    for (Subchannel subchannel : getSubchannels()) {
      shutdownSubchannel(subchannel);
    }
    subchannels.clear();
  }

  private static final Status EMPTY_OK = Status.OK.withDescription("no subchannels ready");

  /**
   * Updates picker with the list of active subchannels (state == READY).
   */
  @SuppressWarnings("ReferenceEquality")
  private void updateBalancingState(Function<List<Subchannel>, RoundRobinPicker>
                                              createReadyPickerTask) {
    List<Subchannel> activeList = filterNonFailingSubchannels(getSubchannels());
    if (activeList.isEmpty()) {
      // No READY subchannels, determine aggregate state and error status
      boolean isConnecting = false;
      Status aggStatus = EMPTY_OK;
      for (Subchannel subchannel : getSubchannels()) {
        ConnectivityStateInfo stateInfo = getSubchannelStateInfoRef(subchannel).value;
        // This subchannel IDLE is not because of channel IDLE_TIMEOUT,
        // in which case LB is already shutdown.
        // RRLB will request connection immediately on subchannel IDLE.
        if (stateInfo.getState() == CONNECTING || stateInfo.getState() == IDLE) {
          isConnecting = true;
        }
        if (aggStatus == EMPTY_OK || !aggStatus.isOk()) {
          aggStatus = stateInfo.getStatus();
        }
      }
      updateBalancingState(isConnecting ? CONNECTING : TRANSIENT_FAILURE,
          // If all subchannels are TRANSIENT_FAILURE, return the Status associated with
          // an arbitrary subchannel, otherwise return OK.
          new EmptyPicker(aggStatus));
    } else {
      updateBalancingState(READY, createReadyPickerTask.apply(activeList));
    }
  }

  private void updateBalancingState(ConnectivityState state, RoundRobinPicker picker) {
    if (state != currentState || !picker.isEquivalentTo(currentPicker)) {
      helper.updateBalancingState(state, picker);
      currentState = state;
      currentPicker = picker;
    }
  }

  /**
   * Filters out non-ready subchannels.
   */
  private static List<Subchannel> filterNonFailingSubchannels(
      Collection<Subchannel> subchannels) {
    List<Subchannel> readySubchannels = new ArrayList<>(subchannels.size());
    for (Subchannel subchannel : subchannels) {
      if (isReady(subchannel)) {
        readySubchannels.add(subchannel);
      }
    }
    return readySubchannels;
  }

  /**
   * Converts list of {@link EquivalentAddressGroup} to {@link EquivalentAddressGroup} set and
   * remove all attributes. The values are the original EAGs.
   */
  private static Map<EquivalentAddressGroup, EquivalentAddressGroup> stripAttrs(
      List<EquivalentAddressGroup> groupList) {
    Map<EquivalentAddressGroup, EquivalentAddressGroup> addrs = new HashMap<>(groupList.size() * 2);
    for (EquivalentAddressGroup group : groupList) {
      addrs.put(stripAttrs(group), group);
    }
    return addrs;
  }

  private static EquivalentAddressGroup stripAttrs(EquivalentAddressGroup eag) {
    return new EquivalentAddressGroup(eag.getAddresses());
  }

  Collection<Subchannel> getSubchannels() {
    return subchannels.values();
  }

  private static Ref<ConnectivityStateInfo> getSubchannelStateInfoRef(
      Subchannel subchannel) {
    return checkNotNull(subchannel.getAttributes().get(STATE_INFO), "STATE_INFO");
  }
    
  // package-private to avoid synthetic access
  static boolean isReady(Subchannel subchannel) {
    return getSubchannelStateInfoRef(subchannel).value.getState() == READY;
  }

  private static <T> Set<T> setsDifference(Set<T> a, Set<T> b) {
    Set<T> aCopy = new HashSet<>(a);
    aCopy.removeAll(b);
    return aCopy;
  }

  // Only subclasses are ReadyPicker or EmptyPicker
  public abstract static class RoundRobinPicker extends SubchannelPicker {
    public abstract boolean isEquivalentTo(RoundRobinPicker picker);
  }

  @VisibleForTesting
  static final class EmptyPicker extends RoundRobinPicker {

    private final Status status;

    EmptyPicker(@Nonnull Status status) {
      this.status = Preconditions.checkNotNull(status, "status");
    }

    @Override
    public PickResult pickSubchannel(PickSubchannelArgs args) {
      return status.isOk() ? PickResult.withNoResult() : PickResult.withError(status);
    }

    @Override
    public boolean isEquivalentTo(RoundRobinPicker picker) {
      return picker instanceof EmptyPicker && (Objects.equal(status, ((EmptyPicker) picker).status)
          || (status.isOk() && ((EmptyPicker) picker).status.isOk()));
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(EmptyPicker.class).add("status", status).toString();
    }
  }

  /**
   * A lighter weight Reference than AtomicReference.
   */
  @VisibleForTesting
  static final class Ref<T> {
    T value;

    Ref(T value) {
      this.value = value;
    }
  }
}
