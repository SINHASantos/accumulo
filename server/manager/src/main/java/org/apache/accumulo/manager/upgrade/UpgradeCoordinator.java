/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.accumulo.manager.upgrade;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.apache.accumulo.core.util.threads.ThreadPoolNames.MANAGER_UPGRADE_COORDINATOR_METADATA_POOL;
import static org.apache.accumulo.server.AccumuloDataVersion.METADATA_FILE_JSON_ENCODING;
import static org.apache.accumulo.server.AccumuloDataVersion.REMOVE_DEPRECATIONS_FOR_VERSION_3;
import static org.apache.accumulo.server.AccumuloDataVersion.ROOT_TABLET_META_CHANGES;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;

import org.apache.accumulo.core.Constants;
import org.apache.accumulo.core.client.AccumuloException;
import org.apache.accumulo.core.client.AccumuloSecurityException;
import org.apache.accumulo.core.client.NamespaceNotFoundException;
import org.apache.accumulo.core.client.TableNotFoundException;
import org.apache.accumulo.core.conf.ConfigCheckUtil;
import org.apache.accumulo.core.metadata.schema.Ample;
import org.apache.accumulo.core.util.threads.ThreadPools;
import org.apache.accumulo.core.volume.Volume;
import org.apache.accumulo.manager.EventCoordinator;
import org.apache.accumulo.server.AccumuloDataVersion;
import org.apache.accumulo.server.ServerContext;
import org.apache.accumulo.server.ServerDirs;
import org.apache.accumulo.server.conf.CheckCompactionConfig;
import org.apache.accumulo.server.fs.VolumeManager;
import org.apache.accumulo.server.util.upgrade.UpgradeProgress;
import org.apache.accumulo.server.util.upgrade.UpgradeProgressTracker;
import org.apache.hadoop.fs.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

import com.google.common.base.Preconditions;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

public class UpgradeCoordinator {

  public enum UpgradeStatus {
    /**
     * This signifies the upgrade status is in the process of being determined. It is best to assume
     * nothing is upgraded when seeing this.
     */
    INITIAL {
      @Override
      public boolean isParentLevelUpgraded(Ample.DataLevel level) {
        return false;
      }
    },
    /**
     * This signifies that only zookeeper has been upgraded so far.
     */
    UPGRADED_ZOOKEEPER {
      @Override
      public boolean isParentLevelUpgraded(Ample.DataLevel level) {
        return level == Ample.DataLevel.ROOT;
      }
    },
    /**
     * This signifies that only zookeeper and the root table have been upgraded so far.
     */
    UPGRADED_ROOT {
      @Override
      public boolean isParentLevelUpgraded(Ample.DataLevel level) {
        return level == Ample.DataLevel.METADATA || level == Ample.DataLevel.ROOT;
      }
    },
    /**
     * This signifies that zookeeper and the root and metadata tables have been upgraded so far.
     */
    UPGRADED_METADATA {
      @Override
      public boolean isParentLevelUpgraded(Ample.DataLevel level) {
        return level == Ample.DataLevel.METADATA || level == Ample.DataLevel.ROOT;
      }
    },
    /**
     * This signifies that everything (zookeeper, root table, metadata table) is upgraded.
     */
    COMPLETE {
      @Override
      public boolean isParentLevelUpgraded(Ample.DataLevel level) {
        return true;
      }
    },
    /**
     * This signifies a failure occurred during upgrade.
     */
    FAILED {
      @Override
      public boolean isParentLevelUpgraded(Ample.DataLevel level) {
        return false;
      }
    };

    /**
     * Determines if the place where this extent stores its metadata was upgraded for a given
     * upgrade status.
     */
    public abstract boolean isParentLevelUpgraded(Ample.DataLevel level);
  }

  private static final Logger log = LoggerFactory.getLogger(UpgradeCoordinator.class);

  private int currentVersion;
  // map of "current version" -> upgrader to next version.
  // Sorted so upgrades execute in order from the oldest supported data version to current
  private final Map<Integer,
      Upgrader> upgraders = Collections.unmodifiableMap(new TreeMap<>(
          Map.of(ROOT_TABLET_META_CHANGES, new Upgrader10to11(), REMOVE_DEPRECATIONS_FOR_VERSION_3,
              new Upgrader11to12(), METADATA_FILE_JSON_ENCODING, new Upgrader12to13())));

  private final ServerContext context;
  private final UpgradeProgressTracker progressTracker;

  private volatile UpgradeStatus status;

  public UpgradeCoordinator(ServerContext context) {
    this.context = context;
    progressTracker = new UpgradeProgressTracker(context);
    status = UpgradeStatus.INITIAL;
  }

  public void continueUpgrade() {
    // No need to continue an upgrade if we are at the correct
    // version
    if (AccumuloDataVersion.getCurrentVersion(context) == AccumuloDataVersion.get()) {
      status = UpgradeStatus.COMPLETE;
      return;
    }
    // The following check will fail if an upgrade is in progress
    // but the target version is not the current version of the
    // software.
    progressTracker.continueUpgrade();
    status = UpgradeStatus.INITIAL;
  }

  private void setStatus(UpgradeStatus status, EventCoordinator eventCoordinator) {
    UpgradeStatus oldStatus = this.status;
    this.status = status;
    // calling this will wake up threads that may assign tablets. After the upgrade status changes
    // those threads may make different assignment decisions.
    eventCoordinator.event("Upgrade status changed from %s to %s", oldStatus, status);
  }

  @SuppressFBWarnings(value = "DM_EXIT",
      justification = "Want to immediately stop all manager threads on upgrade error")
  private void handleFailure(Exception e) {
    log.error("FATAL: Error performing upgrade", e);
    // do not want to call setStatus and signal an event in this case
    status = UpgradeStatus.FAILED;
    System.exit(1);
  }

  public synchronized void upgradeZookeeper(EventCoordinator eventCoordinator) {

    try {
      int cv = AccumuloDataVersion.getCurrentVersion(context);
      this.currentVersion = cv;

      if (cv == AccumuloDataVersion.get()) {
        status = UpgradeStatus.COMPLETE;
        return;
      }

      Preconditions.checkState(status == UpgradeStatus.INITIAL,
          "Not currently in a suitable state to do zookeeper upgrade %s", status);

      if (currentVersion < AccumuloDataVersion.get()) {
        abortIfFateTransactions();

        final UpgradeProgress progress = progressTracker.getProgress();

        for (int upgradeVersion = currentVersion; upgradeVersion < AccumuloDataVersion.get();
            upgradeVersion++) {
          if (progress.getZooKeeperVersion() > upgradeVersion) {
            log.info(
                "ZooKeeper has already run upgrader for version {}, moving on to next upgrader",
                upgradeVersion);
            continue;
          }
          log.info("Upgrading Zookeeper - current version {} as step towards target version {}",
              upgradeVersion, AccumuloDataVersion.get());
          var upgrader = upgraders.get(upgradeVersion);
          Objects.requireNonNull(upgrader,
              "upgrade ZooKeeper: failed to find upgrader for version " + upgradeVersion);
          upgrader.upgradeZookeeper(context);
          // The current version and the versions in the upgraders map are equal. For example,
          // if the current version is 10, then the code above will get the Upgrader for version
          // 10 from the upgraders map. It will run the Upgrader10to11 code, which means that the
          // current version of ZooKeeper after running the upgrade is 11.
          final int nextVersion = upgradeVersion + 1;
          progressTracker.updateZooKeeperVersion(nextVersion);
          log.info("ZooKeeper upgrade from version {} to version {} complete.", upgradeVersion,
              nextVersion);
        }
      }
      setStatus(UpgradeStatus.UPGRADED_ZOOKEEPER, eventCoordinator);
    } catch (Exception e) {
      handleFailure(e);
    }

  }

  public synchronized Future<Void> upgradeMetadata(EventCoordinator eventCoordinator) {
    if (status == UpgradeStatus.COMPLETE) {
      return CompletableFuture.completedFuture(null);
    }

    Preconditions.checkState(status == UpgradeStatus.UPGRADED_ZOOKEEPER,
        "Not currently in a suitable state to do metadata upgrade %s", status);

    if (currentVersion < AccumuloDataVersion.get()) {
      return ThreadPools.getServerThreadPools()
          .getPoolBuilder(MANAGER_UPGRADE_COORDINATOR_METADATA_POOL).numCoreThreads(0)
          .numMaxThreads(Integer.MAX_VALUE).withTimeOut(60L, SECONDS)
          .withQueue(new SynchronousQueue<>()).build().submit(() -> {
            try {
              UpgradeProgress progress = progressTracker.getProgress();
              for (int upgradeVersion = currentVersion; upgradeVersion < AccumuloDataVersion.get();
                  upgradeVersion++) {
                if (progress.getRootVersion() > upgradeVersion) {
                  log.info(
                      "Root table has already run upgrader for version {}, moving on to next upgrader",
                      upgradeVersion);
                  continue;
                }
                log.info("Upgrading Root - current version {} as step towards target version {}",
                    upgradeVersion, AccumuloDataVersion.get());
                var upgrader = upgraders.get(upgradeVersion);
                Objects.requireNonNull(upgrader,
                    "upgrade root: failed to find root upgrader for version " + upgradeVersion);
                upgraders.get(upgradeVersion).upgradeRoot(context);
                // The current version and the versions in the upgraders map are equal. For example,
                // if the current version is 10, then the code above will get the Upgrader for
                // version 10 from the upgraders map. It will run the Upgrader10to11 code, which
                // means that the current version of ZooKeeper after running the upgrade is 11.
                final int nextVersion = upgradeVersion + 1;
                progressTracker.updateRootVersion(nextVersion);
                log.info("Root upgrade from version {} to version {} complete.", upgradeVersion,
                    nextVersion);
              }
              setStatus(UpgradeStatus.UPGRADED_ROOT, eventCoordinator);

              for (int upgradeVersion = currentVersion; upgradeVersion < AccumuloDataVersion.get();
                  upgradeVersion++) {
                if (progress.getMetadataVersion() > upgradeVersion) {
                  log.info(
                      "Metadata table has already run upgrader for version {}, moving on to next upgrader",
                      upgradeVersion);
                  continue;
                }
                log.info(
                    "Upgrading Metadata - current version {} as step towards target version {}",
                    upgradeVersion, AccumuloDataVersion.get());
                var upgrader = upgraders.get(upgradeVersion);
                Objects.requireNonNull(upgrader,
                    "upgrade metadata: failed to find upgrader for version " + upgradeVersion);
                upgraders.get(upgradeVersion).upgradeMetadata(context);
                // The current version and the versions in the upgraders map are equal. For example,
                // if the current version is 10, then the code above will get the Upgrader for
                // version 10 from the upgraders map. It will run the Upgrader10to11 code, which
                // means that the current version of ZooKeeper after running the upgrade is 11.
                final int nextVersion = upgradeVersion + 1;
                progressTracker.updateMetadataVersion(nextVersion);
                log.info("Metadata upgrade from version {} to version {} complete.", upgradeVersion,
                    nextVersion);
              }
              setStatus(UpgradeStatus.UPGRADED_METADATA, eventCoordinator);

              log.info("Validating configuration properties.");
              validateProperties();

              log.info("Updating persistent data version.");
              updateAccumuloVersion(context.getServerDirs(), context.getVolumeManager(),
                  currentVersion);
              log.info("Upgrade complete");
              setStatus(UpgradeStatus.COMPLETE, eventCoordinator);
              progressTracker.upgradeComplete();
            } catch (Exception e) {
              handleFailure(e);
            }
            return null;
          });
    } else {
      return CompletableFuture.completedFuture(null);
    }
  }

  private void validateProperties() {
    ConfigCheckUtil.validate(context.getSiteConfiguration(), "site configuration");
    ConfigCheckUtil.validate(context.getConfiguration(), "system configuration");
    try {
      for (String ns : context.namespaceOperations().list()) {
        ConfigCheckUtil.validate(
            context.namespaceOperations().getNamespaceProperties(ns).entrySet(),
            ns + " namespace configuration");
      }
      for (String table : context.tableOperations().list()) {
        ConfigCheckUtil.validate(context.tableOperations().getTableProperties(table).entrySet(),
            table + " table configuration");
      }
    } catch (AccumuloException | AccumuloSecurityException | NamespaceNotFoundException
        | TableNotFoundException e) {
      throw new IllegalStateException("Error checking properties", e);
    }
    try {
      CheckCompactionConfig.validate(context.getConfiguration(), Level.INFO);
    } catch (RuntimeException | ReflectiveOperationException e) {
      throw new IllegalStateException("Error validating compaction configuration", e);
    }
  }

  // visible for testing
  synchronized void updateAccumuloVersion(ServerDirs serverDirs, VolumeManager fs, int oldVersion) {
    for (Volume volume : fs.getVolumes()) {
      try {
        if (serverDirs.getAccumuloPersistentVersion(volume) == oldVersion) {
          log.debug("Attempting to upgrade {}", volume);
          Path dataVersionLocation = serverDirs.getDataVersionLocation(volume);
          fs.create(new Path(dataVersionLocation, Integer.toString(AccumuloDataVersion.get())))
              .close();
          // TODO document failure mode & recovery if FS permissions cause above to work and below
          // to fail ACCUMULO-2596
          Path prevDataVersionLoc = new Path(dataVersionLocation, Integer.toString(oldVersion));
          if (!fs.delete(prevDataVersionLoc)) {
            throw new RuntimeException("Could not delete previous data version location ("
                + prevDataVersionLoc + ") for " + volume);
          }
        }
      } catch (IOException e) {
        throw new RuntimeException("Unable to set accumulo version: an error occurred.", e);
      }
    }
  }

  public UpgradeStatus getStatus() {
    return status;
  }

  /**
   * Exit loudly if there are outstanding Fate operations. Since Fate serializes class names, we
   * need to make sure there are no queued transactions from a previous version before continuing an
   * upgrade. The status of the operations is irrelevant; those in SUCCESSFUL status cause the same
   * problem as those just queued.
   * <p>
   * Note that the Manager should not allow write access to Fate until after all upgrade steps are
   * complete.
   * <p>
   * Should be called as a guard before performing any upgrade steps, after determining that an
   * upgrade is needed.
   * <p>
   * see ACCUMULO-2519
   */
  @SuppressFBWarnings(value = "DM_EXIT",
      justification = "Want to immediately stop all manager threads on upgrade error")
  private void abortIfFateTransactions() {
    try {
      // The current version of the code creates the new accumulo.fate table on upgrade, so no
      // attempt is made to read it here. Attempting to read it this point would likely cause a hang
      // as tablets are not assigned when this is called. The Fate code is not used to read from
      // zookeeper below because the serialization format changed in zookeeper, that is why a direct
      // read is performed.
      if (!context.getZooSession().asReader().getChildren(Constants.ZFATE).isEmpty()) {
        throw new AccumuloException("Aborting upgrade because there are"
            + " outstanding FATE transactions from a previous Accumulo version."
            + " You can start the tservers and then use the shell to delete completed "
            + " transactions. If there are incomplete transactions, you will need to roll"
            + " back and fix those issues. Please see the following page for more information: "
            + " https://accumulo.apache.org/docs/2.x/troubleshooting/advanced#upgrade-issues");
      }
    } catch (Exception exception) {
      log.error("Problem verifying Fate readiness", exception);
      System.exit(1);
    }
  }
}
