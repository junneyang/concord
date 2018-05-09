package com.walmartlabs.concord.server.rpc;

/*-
 * *****
 * Concord
 * -----
 * Copyright (C) 2017 Wal-Mart Store, Inc.
 * -----
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * =====
 */

import com.google.protobuf.ByteString;
import com.google.protobuf.Empty;
import com.walmartlabs.concord.common.IOUtils;
import com.walmartlabs.concord.project.InternalConstants;
import com.walmartlabs.concord.rpc.*;
import com.walmartlabs.concord.server.api.process.ProcessStatus;
import com.walmartlabs.concord.server.process.ProcessManager;
import com.walmartlabs.concord.server.process.ProcessManager.PayloadEntry;
import com.walmartlabs.concord.server.process.logs.LogManager;
import com.walmartlabs.concord.server.process.state.ProcessStateManager;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Named;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static com.walmartlabs.concord.server.process.state.ProcessStateManager.path;

@Named
public class JobQueueImpl extends TJobQueueGrpc.TJobQueueImplBase {

    private static final Logger log = LoggerFactory.getLogger(JobQueueImpl.class);
    private static final int PAYLOAD_CHUNK_SIZE = 512 * 1024; // 512kb

    private final ProcessStateManager stateManager;
    private final LogManager logManager;
    private final ProcessManager processManager;

    @Inject
    public JobQueueImpl(ProcessStateManager stateManager, LogManager logManager, ProcessManager processManager) {
        this.stateManager = stateManager;
        this.logManager = logManager;
        this.processManager = processManager;
    }

    @Override
    public void poll(TJobRequest request, StreamObserver<TJobResponse> responseObserver) {
        try {
            PayloadEntry p = processManager.nextPayload();
            if (p == null) {
                responseObserver.onCompleted();
                return;
            }

            UUID instanceId = p.getProcessEntry().getInstanceId();

            Path tmp = p.getPayloadArchive();
            try (InputStream in = Files.newInputStream(tmp)) {
                int read;
                byte[] ab = new byte[PAYLOAD_CHUNK_SIZE];

                while ((read = in.read(ab)) > 0) {
                    TJobResponse r = TJobResponse.newBuilder()
                            .setInstanceId(instanceId.toString())
                            .setType(TJobType.RUNNER)
                            .setChunk(ByteString.copyFrom(ab, 0, read))
                            .build();

                    responseObserver.onNext(r);
                }

                responseObserver.onCompleted();
            } finally {
                cleanup(p);
            }
        } catch (IOException e) {
            responseObserver.onError(e);
        }
    }

    private void cleanup(PayloadEntry entry) {
        Path p = entry.getPayloadArchive();
        if (p == null) {
            return;
        }

        try {
            Files.deleteIfExists(p);
        } catch (IOException e) {
            UUID instanceId = entry.getProcessEntry().getInstanceId();
            logManager.warn(instanceId, "Unable to delete the temporary payload file: {}", p, e);
        }
    }

    @Override
    public void updateStatus(TJobStatusUpdate request, StreamObserver<Empty> responseObserver) {
        String agentId = request.getAgentId();
        UUID instanceId = UUID.fromString(request.getInstanceId());
        ProcessStatus status = convert(request.getStatus());

        processManager.updateStatus(instanceId, agentId, status);

        responseObserver.onNext(Empty.getDefaultInstance());
        responseObserver.onCompleted();
    }

    @Override
    public void appendLog(TJobLogEntry request, StreamObserver<Empty> responseObserver) {
        // TODO validate the id
        String instanceId = request.getInstanceId();
        byte[] data = request.getData().toByteArray();

        logManager.log(UUID.fromString(instanceId), data);

        responseObserver.onNext(Empty.getDefaultInstance());
        responseObserver.onCompleted();
    }

    @Override
    public void uploadAttachments(TAttachments request, StreamObserver<Empty> responseObserver) {
        UUID instanceId = UUID.fromString(request.getInstanceId());
        Path tmpIn = null;
        Path tmpDir = null;
        try {
            // TODO cfg
            byte[] data = request.getData().toByteArray();

            tmpIn = IOUtils.createTempFile("attachments", ".zip");
            Files.write(tmpIn, data);

            tmpDir = IOUtils.createTempDir("attachments");
            IOUtils.unzip(tmpIn, tmpDir);

            Path finalTmpDir = tmpDir;
            stateManager.delete(instanceId, path(InternalConstants.Files.JOB_ATTACHMENTS_DIR_NAME, InternalConstants.Files.JOB_STATE_DIR_NAME));
            stateManager.importPath(instanceId, InternalConstants.Files.JOB_ATTACHMENTS_DIR_NAME, finalTmpDir);
        } catch (IOException e) {
            responseObserver.onError(e);
            return;
        } finally {
            if (tmpDir != null) {
                try {
                    IOUtils.deleteRecursively(tmpDir);
                } catch (IOException e) {
                    log.warn("uploadAttachments -> cleanup error: {}", e.getMessage());
                }
            }
            if (tmpIn != null) {
                try {
                    Files.delete(tmpIn);
                } catch (IOException e) {
                    log.warn("uploadAttachments -> cleanup error: {}", e.getMessage());
                }
            }
        }

        responseObserver.onNext(Empty.getDefaultInstance());
        responseObserver.onCompleted();

        log.info("uploadAttachments ['{}'] -> done", instanceId);
    }

    private static ProcessStatus convert(TJobStatus s) {
        switch (s) {
            case RUNNING:
                return ProcessStatus.RUNNING;
            case COMPLETED:
                return ProcessStatus.FINISHED;
            case FAILED:
                return ProcessStatus.FAILED;
            case CANCELLED:
                return ProcessStatus.CANCELLED;
            default:
                throw new IllegalArgumentException("Unsupported job status type: " + s);
        }
    }
}