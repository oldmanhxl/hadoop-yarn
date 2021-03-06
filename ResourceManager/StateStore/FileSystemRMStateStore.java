/**
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

package org.apache.hadoop.yarn.server.resourcemanager.recovery;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.classification.InterfaceAudience.Private;
import org.apache.hadoop.classification.InterfaceStability.Unstable;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.DataInputByteBuffer;
import org.apache.hadoop.security.Credentials;
import org.apache.hadoop.security.token.delegation.DelegationKey;
import org.apache.hadoop.yarn.api.records.ApplicationAttemptId;
import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.proto.YarnServerResourceManagerServiceProtos.ApplicationAttemptStateDataProto;
import org.apache.hadoop.yarn.proto.YarnServerResourceManagerServiceProtos.ApplicationStateDataProto;
import org.apache.hadoop.yarn.security.client.RMDelegationTokenIdentifier;
import org.apache.hadoop.yarn.server.resourcemanager.recovery.records.impl.pb.ApplicationAttemptStateDataPBImpl;
import org.apache.hadoop.yarn.server.resourcemanager.recovery.records.impl.pb.ApplicationStateDataPBImpl;
import org.apache.hadoop.yarn.util.ConverterUtils;

import com.google.common.annotations.VisibleForTesting;

@Private
@Unstable
/**
 * A simple class for storing RM state in any storage that implements a basic
 * FileSystem interface. Does not use directories so that simple key-value
 * stores can be used. The retry policy for the real filesystem client must be
 * configured separately to enable retry of filesystem operations when needed.
 * RM状态信息文件系统保存类
 */
public class FileSystemRMStateStore extends RMStateStore {

  public static final Log LOG = LogFactory.getLog(FileSystemRMStateStore.class);

  private static final String ROOT_DIR_NAME = "FSRMStateRoot";
  private static final String RM_DT_SECRET_MANAGER_ROOT = "RMDTSecretManagerRoot";
  private static final String RM_APP_ROOT = "RMAppRoot";
  private static final String DELEGATION_KEY_PREFIX = "DelegationKey_";
  private static final String DELEGATION_TOKEN_PREFIX = "RMDelegationToken_";
  private static final String DELEGATION_TOKEN_SEQUENCE_NUMBER_PREFIX =
      "RMDTSequenceNumber_";
  //文件系统对象
  protected FileSystem fs;
  
  //RM保存的文件路径
  private Path rootDirPath;
  private Path rmDTSecretManagerRoot;
  private Path rmAppRoot;
  private Path dtSequenceNumberPath = null;

  @VisibleForTesting
  Path fsWorkingPath;

  @Override
  public synchronized void initInternal(Configuration conf)
      throws Exception{
    //从配置中获取保存路径
    fsWorkingPath = new Path(conf.get(YarnConfiguration.FS_RM_STATE_STORE_URI));
    rootDirPath = new Path(fsWorkingPath, ROOT_DIR_NAME);
    rmDTSecretManagerRoot = new Path(rootDirPath, RM_DT_SECRET_MANAGER_ROOT);
    rmAppRoot = new Path(rootDirPath, RM_APP_ROOT);
  }

  @Override
  protected synchronized void startInternal() throws Exception {
    // create filesystem only now, as part of service-start. By this time, RM is
    // authenticated with kerberos so we are good to create a file-system
    // handle.
    //创建相应的保存目录
    fs = fsWorkingPath.getFileSystem(getConfig());
    fs.mkdirs(rmDTSecretManagerRoot);
    fs.mkdirs(rmAppRoot);
  }

  @Override
  protected synchronized void closeInternal() throws Exception {
    fs.close();
  }

  @Override
  public synchronized RMState loadState() throws Exception {
  	//新建RM状态对象
    RMState rmState = new RMState();
    //调用方法，从文件中进行恢复
    // recover DelegationTokenSecretManager
    loadRMDTSecretManagerState(rmState);
    // recover RM applications
    loadRMAppState(rmState);
    return rmState;
  }

  private void loadRMAppState(RMState rmState) throws Exception {
    try {
      List<ApplicationAttemptState> attempts =
          new ArrayList<ApplicationAttemptState>();

      for (FileStatus appDir : fs.listStatus(rmAppRoot)) {
        for (FileStatus childNodeStatus : fs.listStatus(appDir.getPath())) {
          assert childNodeStatus.isFile();
          String childNodeName = childNodeStatus.getPath().getName();
          byte[] childData =
              readFile(childNodeStatus.getPath(), childNodeStatus.getLen());
          if (childNodeName.startsWith(ApplicationId.appIdStrPrefix)) {
            // application
            LOG.info("Loading application from node: " + childNodeName);
            ApplicationId appId = ConverterUtils.toApplicationId(childNodeName);
            ApplicationStateDataPBImpl appStateData =
                new ApplicationStateDataPBImpl(
                  ApplicationStateDataProto.parseFrom(childData));
            ApplicationState appState =
                new ApplicationState(appStateData.getSubmitTime(),
                  appStateData.getApplicationSubmissionContext(),
                  appStateData.getUser());
            // assert child node name is same as actual applicationId
            assert appId.equals(appState.context.getApplicationId());
            rmState.appState.put(appId, appState);
          } else if (childNodeName
            .startsWith(ApplicationAttemptId.appAttemptIdStrPrefix)) {
            // attempt
            LOG.info("Loading application attempt from node: " + childNodeName);
            ApplicationAttemptId attemptId =
                ConverterUtils.toApplicationAttemptId(childNodeName);
            ApplicationAttemptStateDataPBImpl attemptStateData =
                new ApplicationAttemptStateDataPBImpl(
                  ApplicationAttemptStateDataProto.parseFrom(childData));
            Credentials credentials = null;
            if (attemptStateData.getAppAttemptTokens() != null) {
              credentials = new Credentials();
              DataInputByteBuffer dibb = new DataInputByteBuffer();
              dibb.reset(attemptStateData.getAppAttemptTokens());
              credentials.readTokenStorageStream(dibb);
            }
            ApplicationAttemptState attemptState =
                new ApplicationAttemptState(attemptId,
                  attemptStateData.getMasterContainer(), credentials);

            // assert child node name is same as application attempt id
            assert attemptId.equals(attemptState.getAttemptId());
            attempts.add(attemptState);
          } else {
            LOG.info("Unknown child node with name: " + childNodeName);
          }
        }
      }

      // go through all attempts and add them to their apps, Ideally, each
      // attempt node must have a corresponding app node, because remove
      // directory operation remove both at the same time
      for (ApplicationAttemptState attemptState : attempts) {
        ApplicationId appId = attemptState.getAttemptId().getApplicationId();
        ApplicationState appState = rmState.appState.get(appId);
        assert appState != null;
        appState.attempts.put(attemptState.getAttemptId(), attemptState);
      }
    } catch (Exception e) {
      LOG.error("Failed to load state.", e);
      throw e;
    }
  }
  
  //加载RM安全相关信息
  private void loadRMDTSecretManagerState(RMState rmState) throws Exception {
  	//找出对应的存储文件目录列表
    FileStatus[] childNodes = fs.listStatus(rmDTSecretManagerRoot);

    for(FileStatus childNodeStatus : childNodes) {
      assert childNodeStatus.isFile();
      String childNodeName = childNodeStatus.getPath().getName();
      if(childNodeName.startsWith(DELEGATION_TOKEN_SEQUENCE_NUMBER_PREFIX)) {
        rmState.rmSecretManagerState.dtSequenceNumber =
            Integer.parseInt(childNodeName.split("_")[1]);
        continue;
      }

      Path childNodePath = getNodePath(rmDTSecretManagerRoot, childNodeName);
      byte[] childData = readFile(childNodePath, childNodeStatus.getLen());
      ByteArrayInputStream is = new ByteArrayInputStream(childData);
      DataInputStream fsIn = new DataInputStream(is);
      if(childNodeName.startsWith(DELEGATION_KEY_PREFIX)){
        DelegationKey key = new DelegationKey();
        key.readFields(fsIn);
        rmState.rmSecretManagerState.masterKeyState.add(key);
      } else if (childNodeName.startsWith(DELEGATION_TOKEN_PREFIX)) {
        RMDelegationTokenIdentifier identifier = new RMDelegationTokenIdentifier();
        identifier.readFields(fsIn);
        long renewDate = fsIn.readLong();
        rmState.rmSecretManagerState.delegationTokenState.put(identifier,
          renewDate);
      } else {
        LOG.warn("Unknown file for recovering RMDelegationTokenSecretManager");
      }
      fsIn.close();
    }
  }

  @Override
  public synchronized void storeApplicationState(String appId,
      ApplicationStateDataPBImpl appStateDataPB) throws Exception {
    Path appDirPath = getAppDir(rmAppRoot, appId);
    fs.mkdirs(appDirPath);
    //获取待写入的目录路径
    Path nodeCreatePath = getNodePath(appDirPath, appId);

    LOG.info("Storing info for app: " + appId + " at: " + nodeCreatePath);
    //获取待写入的状态数据
    byte[] appStateData = appStateDataPB.getProto().toByteArray();
    try {
      // currently throw all exceptions. May need to respond differently for HA
      // based on whether we have lost the right to write to FS
      //进行状态信息的写入
      writeFile(nodeCreatePath, appStateData);
    } catch (Exception e) {
      LOG.info("Error storing info for app: " + appId, e);
      throw e;
    }
  }

  @Override
  public synchronized void storeApplicationAttemptState(String attemptId,
      ApplicationAttemptStateDataPBImpl attemptStateDataPB) throws Exception {
    ApplicationAttemptId appAttemptId =
        ConverterUtils.toApplicationAttemptId(attemptId);
    Path appDirPath =
        getAppDir(rmAppRoot, appAttemptId.getApplicationId().toString());
    Path nodeCreatePath = getNodePath(appDirPath, attemptId);
    LOG.info("Storing info for attempt: " + attemptId
             + " at: " + nodeCreatePath);
    byte[] attemptStateData = attemptStateDataPB.getProto().toByteArray();
    try {
      // currently throw all exceptions. May need to respond differently for HA
      // based on whether we have lost the right to write to FS
      writeFile(nodeCreatePath, attemptStateData);
    } catch (Exception e) {
      LOG.info("Error storing info for attempt: " + attemptId, e);
      throw e;
    }
  }

  @Override
  public synchronized void removeApplicationState(ApplicationState appState)
      throws Exception {
    String appId = appState.getAppId().toString();
    Path nodeRemovePath = getAppDir(rmAppRoot, appId);
    LOG.info("Removing info for app: " + appId + " at: " + nodeRemovePath);
    deleteFile(nodeRemovePath);
  }
  
  //保存RM授权相关信息，传入新的id标识符和更新时间，最新序列号
  @Override
  public synchronized void storeRMDelegationTokenAndSequenceNumberState(
      RMDelegationTokenIdentifier identifier, Long renewDate,
      int latestSequenceNumber) throws Exception {
    //获取新的路径
    Path nodeCreatePath =
        getNodePath(rmDTSecretManagerRoot,
          DELEGATION_TOKEN_PREFIX + identifier.getSequenceNumber());
    ByteArrayOutputStream os = new ByteArrayOutputStream();
    DataOutputStream fsOut = new DataOutputStream(os);
    LOG.info("Storing RMDelegationToken_" + identifier.getSequenceNumber());
    identifier.write(fsOut);
    fsOut.writeLong(renewDate);
    writeFile(nodeCreatePath, os.toByteArray());
    fsOut.close();
    
    //再保存序列号
    // store sequence number
    Path latestSequenceNumberPath = getNodePath(rmDTSecretManagerRoot,
          DELEGATION_TOKEN_SEQUENCE_NUMBER_PREFIX + latestSequenceNumber);
    LOG.info("Storing " + DELEGATION_TOKEN_SEQUENCE_NUMBER_PREFIX
        + latestSequenceNumber);
    if (dtSequenceNumberPath == null) {
      if (!createFile(latestSequenceNumberPath)) {
        throw new Exception("Failed to create " + latestSequenceNumberPath);
      }
    } else {
      if (!renameFile(dtSequenceNumberPath, latestSequenceNumberPath)) {
        throw new Exception("Failed to rename " + dtSequenceNumberPath);
      }
    }
    dtSequenceNumberPath = latestSequenceNumberPath;
  }

  @Override
  public synchronized void removeRMDelegationTokenState(
      RMDelegationTokenIdentifier identifier) throws Exception {
    Path nodeCreatePath = getNodePath(rmDTSecretManagerRoot,
      DELEGATION_TOKEN_PREFIX + identifier.getSequenceNumber());
    LOG.info("Removing RMDelegationToken_" + identifier.getSequenceNumber());
    deleteFile(nodeCreatePath);
  }

  @Override
  public synchronized void storeRMDTMasterKeyState(DelegationKey masterKey)
      throws Exception {
    Path nodeCreatePath = getNodePath(rmDTSecretManagerRoot,
          DELEGATION_KEY_PREFIX + masterKey.getKeyId());
    ByteArrayOutputStream os = new ByteArrayOutputStream();
    DataOutputStream fsOut = new DataOutputStream(os);
    LOG.info("Storing RMDelegationKey_" + masterKey.getKeyId());
    masterKey.write(fsOut);
    writeFile(nodeCreatePath, os.toByteArray());
    fsOut.close();
  }

  @Override
  public synchronized void
      removeRMDTMasterKeyState(DelegationKey masterKey) throws Exception {
    Path nodeCreatePath = getNodePath(rmDTSecretManagerRoot,
          DELEGATION_KEY_PREFIX + masterKey.getKeyId());
    LOG.info("Removing RMDelegationKey_"+ masterKey.getKeyId());
    deleteFile(nodeCreatePath);
  }

  private Path getAppDir(Path root, String appId) {
    return getNodePath(root, appId);
  }

  // FileSystem related code

  private void deleteFile(Path deletePath) throws Exception {
    if(!fs.delete(deletePath, true)) {
      throw new Exception("Failed to delete " + deletePath);
    }
  }

  private byte[] readFile(Path inputPath, long len) throws Exception {
    FSDataInputStream fsIn = fs.open(inputPath);
    // state data will not be that "long"
    byte[] data = new byte[(int)len];
    fsIn.readFully(data);
    fsIn.close();
    return data;
  }

  private void writeFile(Path outputPath, byte[] data) throws Exception {
    FSDataOutputStream fsOut = fs.create(outputPath, false);
    fsOut.write(data);
    fsOut.close();
  }

  private boolean renameFile(Path src, Path dst) throws Exception {
    return fs.rename(src, dst);
  }

  private boolean createFile(Path newFile) throws Exception {
    return fs.createNewFile(newFile);
  }

  private Path getNodePath(Path root, String nodeName) {
    return new Path(root, nodeName);
  }
}
