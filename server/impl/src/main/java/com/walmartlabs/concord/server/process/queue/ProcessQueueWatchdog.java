package com.walmartlabs.concord.server.process.queue;

/*-
 * *****
 * Concord
 * -----
 * Copyright (C) 2017 - 2018 Walmart Inc.
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

import com.walmartlabs.concord.db.AbstractDao;
import com.walmartlabs.concord.db.PgUtils;
import com.walmartlabs.concord.project.InternalConstants;
import com.walmartlabs.concord.sdk.Constants;
import com.walmartlabs.concord.server.Utils;
import com.walmartlabs.concord.server.agent.AgentCommandsDao;
import com.walmartlabs.concord.server.agent.Commands;
import com.walmartlabs.concord.server.cfg.ProcessWatchdogConfiguration;
import com.walmartlabs.concord.server.jooq.tables.ProcessQueue;
import com.walmartlabs.concord.server.jooq.tables.ProcessStatusHistory;
import com.walmartlabs.concord.server.process.*;
import com.walmartlabs.concord.server.process.logs.LogManager;
import com.walmartlabs.concord.server.task.ScheduledTask;
import com.walmartlabs.concord.server.user.UserDao;
import org.jooq.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import java.io.Serializable;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.walmartlabs.concord.db.PgUtils.interval;
import static com.walmartlabs.concord.server.jooq.tables.ProcessQueue.PROCESS_QUEUE;
import static com.walmartlabs.concord.server.jooq.tables.ProcessStatusHistory.PROCESS_STATUS_HISTORY;
import static org.jooq.impl.DSL.*;

@Named("process-queue-watchdog")
@Singleton
public class ProcessQueueWatchdog implements ScheduledTask {

    private static final Logger log = LoggerFactory.getLogger(ProcessQueueWatchdog.class);

    private static final PollEntry POLL_ENTRIES[] = {
            new PollEntry(ProcessStatus.FAILED,
                    Constants.Flows.ON_FAILURE_FLOW,
                    ProcessKind.FAILURE_HANDLER, 3),

            new PollEntry(ProcessStatus.CANCELLED,
                    Constants.Flows.ON_CANCEL_FLOW,
                    ProcessKind.CANCEL_HANDLER, 3),

            new PollEntry(ProcessStatus.TIMED_OUT,
                    Constants.Flows.ON_TIMEOUT_FLOW,
                    ProcessKind.TIMEOUT_HANDLER, 3)
    };

    private static final ProcessKind[] HANDLED_PROCESS_KINDS = {
            ProcessKind.DEFAULT
    };

    private static final ProcessKind[] SPECIAL_HANDLERS = {
            ProcessKind.FAILURE_HANDLER,
            ProcessKind.CANCEL_HANDLER,
            ProcessKind.TIMEOUT_HANDLER
    };

    private static final ProcessStatus[] ACTIVE_PROCESS_STATUSES = {
            ProcessStatus.SUSPENDED,
            ProcessStatus.ENQUEUED,
            ProcessStatus.RUNNING,
            ProcessStatus.PREPARING,
            ProcessStatus.RESUMING
    };

    private static final ProcessStatus[] POTENTIAL_STALLED_STATUSES = {
            ProcessStatus.RUNNING
    };

    private static final ProcessStatus[] FAILED_TO_START_STATUSES = {
            ProcessStatus.STARTING,
            ProcessStatus.RESUMING
    };

    private final ProcessWatchdogConfiguration cfg;
    private final ProcessQueueDao queueDao;
    private final AgentCommandsDao agentCommandsDao;
    private final LogManager logManager;
    private final WatchdogDao watchdogDao;
    private final UserDao userDao;
    private final PayloadManager payloadManager;
    private final ProcessManager processManager;

    @Inject
    public ProcessQueueWatchdog(ProcessWatchdogConfiguration cfg,
                                ProcessQueueDao queueDao,
                                AgentCommandsDao agentCommandsDao,
                                LogManager logManager,
                                WatchdogDao watchdogDao,
                                UserDao userDao,
                                PayloadManager payloadManager,
                                ProcessManager processManager) {
        this.cfg = cfg;

        this.queueDao = queueDao;
        this.agentCommandsDao = agentCommandsDao;
        this.logManager = logManager;
        this.watchdogDao = watchdogDao;
        this.userDao = userDao;
        this.payloadManager = payloadManager;
        this.processManager = processManager;
    }

    @Override
    public long getIntervalInSec() {
        return TimeUnit.SECONDS.toSeconds(2);
    }

    @Override
    public void performTask() {
        new ProcessHandlersWorker().run();
        new ProcessStalledWorker().run();
        new ProcessStartFailuresWorker().run();
        new ProcessTimedOutWorker().run();
    }

    private final class ProcessHandlersWorker implements Runnable {

        @Override
        public void run() {
            watchdogDao.transaction(tx -> {
                Field<Timestamp> maxAge = currentTimestamp().minus(interval(cfg.getMaxFailureHandlingAge()));

                for (PollEntry e : POLL_ENTRIES) {
                    List<ProcessEntry> parents = watchdogDao.poll(tx, e, maxAge, 1);

                    for (ProcessEntry parent : parents) {
                        UUID childId = UUID.randomUUID();

                        Map<String, Object> req = new HashMap<>();
                        req.put(InternalConstants.Request.ENTRY_POINT_KEY, e.flow);
                        req.put(InternalConstants.Request.TAGS_KEY, null); // clear tags

                        Payload payload = payloadManager.createFork(childId, parent.instanceId, e.handlerKind,
                                parent.initiatorId, userDao.get(parent.initiatorId).getName(), parent.projectId, req, null);

                        processManager.startFork(payload, false);

                        log.info("processHandlers -> created a new child process '{}' (parent '{}', entryPoint: '{}')",
                                childId, parent.instanceId, e.flow);
                    }
                }
            });
        }
    }

    private final class ProcessStalledWorker implements Runnable {
        @Override
        public void run() {
            watchdogDao.transaction(tx -> {
                Field<Timestamp> cutOff = currentTimestamp().minus(interval(cfg.getMaxStalledAge()));

                List<UUID> ids = watchdogDao.pollStalled(tx, POTENTIAL_STALLED_STATUSES, cutOff, 1);
                for (UUID id : ids) {
                    queueDao.updateAgentId(tx, id, null, ProcessStatus.FAILED);
                    logManager.warn(id, "Process stalled, no heartbeat for more than a minute");
                    log.info("processStalled -> marked as failed: {}", id);
                }
            });
        }
    }

    private final class ProcessStartFailuresWorker implements Runnable {
        @Override
        public void run() {
            watchdogDao.transaction(tx -> {
                Field<Timestamp> cutOff = currentTimestamp().minus(interval(cfg.getMaxStartFailureAge()));

                List<UUID> ids = watchdogDao.pollStalled(tx, FAILED_TO_START_STATUSES, cutOff, 1);
                for (UUID id : ids) {
                    queueDao.updateAgentId(tx, id, null, ProcessStatus.FAILED);
                    logManager.warn(id, "Process failed to start");
                    log.info("processStartFailures -> marked as failed: {}", id);
                }
            });
        }
    }

    private final class ProcessTimedOutWorker implements Runnable {
        @Override
        public void run() {
            watchdogDao.transaction(tx -> {
                List<TimedOutEntry> items = watchdogDao.pollExpired(tx, 1);
                for (TimedOutEntry i : items) {
                    queueDao.updateAgentId(tx, i.instanceId, null, ProcessStatus.TIMED_OUT);

                    // TODO should AgentManager be used instead?
                    agentCommandsDao.insert(UUID.randomUUID(), i.agentId, Commands.cancel(i.instanceId.toString()));

                    logManager.warn(i.instanceId, "Process timed out ({}s limit)", i.timeout);
                    log.info("processTimedOut -> marked as timed out: {}", i.instanceId);
                }
            });
        }
    }

    private static final class PollEntry {

        private final ProcessStatus status;
        private final String flow;
        private final ProcessKind handlerKind;
        private final int maxTries;

        private PollEntry(ProcessStatus status, String flow, ProcessKind handlerKind, int maxTries) {
            this.status = status;
            this.flow = flow;
            this.handlerKind = handlerKind;
            this.maxTries = maxTries;
        }
    }

    @Named
    private static final class WatchdogDao extends AbstractDao {

        @Inject
        public WatchdogDao(@Named("app") Configuration cfg) {
            super(cfg);
        }

        private void transaction(Tx t) {
            tx(t);
        }

        public List<ProcessEntry> poll(DSLContext tx, PollEntry entry, Field<Timestamp> maxAge, int maxEntries) {
            ProcessQueue q = PROCESS_QUEUE.as("q");

            return tx.select(q.INSTANCE_ID, q.PROJECT_ID, q.INITIATOR_ID)
                    .from(q)
                    .where(q.PROCESS_KIND.in(Utils.toString(HANDLED_PROCESS_KINDS))
                            .and(q.CURRENT_STATUS.eq(entry.status.toString()))
                            .and(q.CREATED_AT.greaterOrEqual(maxAge))
                            .and(PgUtils.contains(q.HANDLERS, new String[]{entry.flow}))
                            .and(noSuccessfulHandlers(q.INSTANCE_ID, entry.handlerKind))
                            .and(count(tx, q.INSTANCE_ID, entry.handlerKind).lessThan(entry.maxTries))
                            .and(noRunningHandlers(q.INSTANCE_ID)))
                    .limit(maxEntries)
                    .forUpdate()
                    .skipLocked()
                    .fetch(WatchdogDao::toEntry);
        }

        public List<UUID> pollStalled(DSLContext tx, ProcessStatus[] statuses, Field<Timestamp> cutOff, int maxEntries) {
            ProcessQueue q = PROCESS_QUEUE.as("q");
            return tx.select(q.INSTANCE_ID)
                    .from(q)
                    .where(q.CURRENT_STATUS.in(Utils.toString(statuses))
                            .and(q.LAST_UPDATED_AT.lessThan(cutOff)))
                    .orderBy(q.CREATED_AT)
                    .limit(maxEntries)
                    .forUpdate()
                    .skipLocked()
                    .fetch(q.INSTANCE_ID);
        }

        public List<TimedOutEntry> pollExpired(DSLContext tx, int maxEntries) {
            ProcessQueue q = PROCESS_QUEUE.as("q");
            ProcessStatusHistory s = PROCESS_STATUS_HISTORY.as("psh");

            Field<Object> runningAt = tx.select(max(s.CHANGE_DATE))
                    .from(s)
                    .where(s.INSTANCE_ID.eq(q.INSTANCE_ID)
                            .and(s.STATUS.eq(ProcessStatus.RUNNING.toString())))
                    .asField();

            @SuppressWarnings("unchecked")
            Field<? extends Number> i = (Field<? extends Number>) interval("1 second");

            return tx.select(q.INSTANCE_ID, q.LAST_AGENT_ID, q.TIMEOUT)
                    .from(q)
                    .where(q.CURRENT_STATUS.eq(ProcessStatus.RUNNING.toString())
                            .and(runningAt.plus(q.TIMEOUT.mul(i)).lessOrEqual(currentTimestamp())))
                    .orderBy(q.CREATED_AT)
                    .limit(maxEntries)
                    .forUpdate()
                    .skipLocked()
                    .fetch(WatchdogDao::toExpiredEntry);
        }

        private Field<Number> count(DSLContext tx, Field<UUID> parentInstanceId, ProcessKind kind) {
            return tx.selectCount()
                    .from(PROCESS_QUEUE)
                    .where(PROCESS_QUEUE.PARENT_INSTANCE_ID.eq(parentInstanceId)
                            .and(PROCESS_QUEUE.PROCESS_KIND.eq(kind.toString())))
                    .asField();
        }

        private Condition noSuccessfulHandlers(Field<UUID> parentInstanceId, ProcessKind kind) {
            return notExists(selectOne().from(PROCESS_QUEUE)
                    .where(PROCESS_QUEUE.PARENT_INSTANCE_ID.eq(parentInstanceId)
                            .and(PROCESS_QUEUE.PROCESS_KIND.eq(kind.toString()))
                            .and(PROCESS_QUEUE.CURRENT_STATUS.eq(ProcessStatus.FINISHED.toString()))));
        }

        private Condition noRunningHandlers(Field<UUID> parentInstanceId) {
            return notExists(selectOne().from(PROCESS_QUEUE)
                    .where(PROCESS_QUEUE.PARENT_INSTANCE_ID.eq(parentInstanceId)
                            .and(PROCESS_QUEUE.CURRENT_STATUS.in(Utils.toString(ACTIVE_PROCESS_STATUSES)))
                            .and(PROCESS_QUEUE.PROCESS_KIND.in(Utils.toString(SPECIAL_HANDLERS)))));
        }

        private static ProcessEntry toEntry(Record3<UUID, UUID, UUID> r) {
            return new ProcessEntry(r.get(PROCESS_QUEUE.INSTANCE_ID),
                    r.get(PROCESS_QUEUE.PROJECT_ID),
                    r.get(PROCESS_QUEUE.INITIATOR_ID));
        }

        private static TimedOutEntry toExpiredEntry(Record3<UUID, String, Long> r) {
            return new TimedOutEntry(r.value1(), r.value2(), r.value3());
        }
    }

    private static final class ProcessEntry implements Serializable {

        private final UUID instanceId;
        private final UUID projectId;
        private final UUID initiatorId;

        private ProcessEntry(UUID instanceId, UUID projectId, UUID initiatorId) {
            this.instanceId = instanceId;
            this.projectId = projectId;
            this.initiatorId = initiatorId;
        }
    }

    private static class TimedOutEntry {

        private final UUID instanceId;
        private final String agentId;
        private final Long timeout;

        private TimedOutEntry(UUID instanceId, String agentId, Long timeout) {
            this.instanceId = instanceId;
            this.agentId = agentId;
            this.timeout = timeout;
        }
    }
}
