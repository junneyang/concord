package com.walmartlabs.concord.runtime.v2.runner.remote;

/*-
 * *****
 * Concord
 * -----
 * Copyright (C) 2017 - 2019 Walmart Inc.
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

import com.fasterxml.jackson.core.JsonLocation;
import com.walmartlabs.concord.ApiClient;
import com.walmartlabs.concord.ApiException;
import com.walmartlabs.concord.client.ProcessEventRequest;
import com.walmartlabs.concord.client.ProcessEventsApi;
import com.walmartlabs.concord.runtime.v2.model.Expression;
import com.walmartlabs.concord.runtime.v2.model.FlowCall;
import com.walmartlabs.concord.runtime.v2.model.Step;
import com.walmartlabs.concord.runtime.v2.model.TaskCall;
import com.walmartlabs.concord.runtime.v2.runner.InstanceId;
import com.walmartlabs.concord.runtime.v2.runner.vm.StepCommand;
import com.walmartlabs.concord.svm.Runtime;
import com.walmartlabs.concord.svm.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Named;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

@Named
public class EventRecordingExecutionListener implements ExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(EventRecordingExecutionListener.class);

    private final ProcessEventsApi eventsApi;
    private final InstanceId processInstanceId;

    @Inject
    public EventRecordingExecutionListener(ApiClient apiClient, InstanceId processInstanceId) {
        this.eventsApi = new ProcessEventsApi(apiClient);
        this.processInstanceId = processInstanceId;
    }

    @Override
    public void afterCommand(Runtime runtime, VM vm, State state, ThreadId threadId, Command cmd) {
        // TODO consider using marker interfaces to determine which step/command should produce ELEMENT events

        if (!(cmd instanceof StepCommand)) {
            return;
        }

        StepCommand<?> s = (StepCommand<?>) cmd;
        if ((s.getStep() instanceof TaskCall) || (s.getStep() instanceof Expression)) {
            return;
        }

        JsonLocation loc = s.getStep().getLocation();

        Map<String, Object> m = new HashMap<>();
        m.put("processDefinitionId", "default"); // TODO
        m.put("line", loc.getLineNr());
        m.put("column", loc.getColumnNr());
        m.put("description", getDescription(s.getStep())); // TODO

        ProcessEventRequest req = new ProcessEventRequest();
        req.setEventType("ELEMENT"); // TODO constants
        req.setData(m);
        req.setEventDate(Instant.now().atOffset(ZoneOffset.UTC));

        try {
            eventsApi.event(processInstanceId.getValue(), req);
        } catch (ApiException e) {
            log.warn("afterCommand [{}] -> error while sending an event to the server: {}", cmd, e.getMessage());
        }
    }

    private static String getDescription(Step step) {
        if (step instanceof FlowCall) {
            return "Flow call: " + ((FlowCall) step).getFlowName();
        }
        return step.getClass().getName();
    }
}
