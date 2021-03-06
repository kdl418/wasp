/**
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.wasp.zookeeper;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hbase.Abortable;
import org.apache.wasp.ServerName;
import org.apache.wasp.master.FServerManager;
import org.apache.zookeeper.KeeperException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.NavigableSet;
import java.util.TreeSet;

/**
 * Tracks the online fservers via ZK.
 * 
 * <p>
 * Handling of new RSs checking in is done via RPC. This class is only
 * responsible for watching for expired nodes. It handles listening for changes
 * in the RS node list and watching each node.
 * 
 * <p>
 * If an FS node gets deleted, this automatically handles calling of
 * {@link FServerManager#expireServer(ServerName)}
 */
public class FServerTracker extends ZooKeeperListener {
  protected static final Log LOG = LogFactory.getLog(FServerTracker.class);
  private NavigableSet<ServerName> fservers = new TreeSet<ServerName>();
  private FServerManager serverManager;
  private Abortable abortable;

  public FServerTracker(ZooKeeperWatcher watcher, Abortable abortable,
      FServerManager serverManager) {
    super(watcher);
    this.abortable = abortable;
    this.serverManager = serverManager;
  }

  /**
   * Starts the tracking of online FServers.
   * 
   * <p>
   * All RSs will be tracked after this method is called.
   * 
   * @throws KeeperException
   * @throws IOException
   */
  public void start() throws KeeperException, IOException {
    watcher.registerListener(this);
    List<String> servers = ZKUtil.listChildrenAndWatchThem(watcher,
        watcher.fsZNode);
    add(servers);
  }

  protected void add(final List<String> servers) throws IOException {
    synchronized (this.fservers) {
      this.fservers.clear();
      for (String n : servers) {
        ServerName sn = ServerName.parseServerName(ZKUtil.getNodeName(n));
        this.fservers.add(sn);
      }
    }
  }

  protected void remove(final ServerName sn) {
    synchronized (this.fservers) {
      this.fservers.remove(sn);
    }
  }

  @Override
  public void nodeDeleted(String path) {
    if (path.startsWith(watcher.fsZNode)) {
      String serverName = ZKUtil.getNodeName(path);
      LOG.info("FServer ephemeral node deleted, processing expiration ["
          + serverName + "]");
      ServerName sn = ServerName.parseServerName(serverName);
      if (!serverManager.isServerOnline(sn)) {
        LOG.warn(serverName.toString()
            + " is not online or isn't known to the master."
            + "The latter could be caused by a DNS misconfiguration.");
        return;
      }
      remove(sn);
      this.serverManager.expireServer(sn);
    }
  }

  @Override
  public void nodeChildrenChanged(String path) {
    if (path.equals(watcher.fsZNode)) {
      try {
        List<String> servers = ZKUtil.listChildrenAndWatchThem(watcher,
            watcher.fsZNode);
        add(servers);
      } catch (IOException e) {
        abortable.abort("Unexpected zk exception getting RS nodes", e);
      } catch (KeeperException e) {
        abortable.abort("Unexpected zk exception getting RS nodes", e);
      }
    }
  }

  /**
   * Gets the online servers.
   * 
   * @return list of online servers
   */
  public List<ServerName> getOnlineServers() {
    synchronized (this.fservers) {
      return new ArrayList<ServerName>(this.fservers);
    }
  }
}
