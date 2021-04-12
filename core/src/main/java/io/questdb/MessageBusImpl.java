/*******************************************************************************
 *     ___                  _   ____  ____
 *    / _ \ _   _  ___  ___| |_|  _ \| __ )
 *   | | | | | | |/ _ \/ __| __| | | |  _ \
 *   | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *    \__\_\\__,_|\___||___/\__|____/|____/
 *
 *  Copyright (c) 2014-2019 Appsicle
 *  Copyright (c) 2019-2020 QuestDB
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

package io.questdb;

import io.questdb.cairo.CairoConfiguration;
import io.questdb.cairo.TableBlockWriter.TableBlockWriterTaskHolder;
import io.questdb.mp.*;
import io.questdb.tasks.*;
import org.jetbrains.annotations.NotNull;

public class MessageBusImpl implements MessageBus {
    private final RingQueue<ColumnIndexerTask> indexerQueue;
    private final MPSequence indexerPubSeq;
    private final MCSequence indexerSubSeq;

    private final RingQueue<VectorAggregateTask> vectorAggregateQueue;
    private final MPSequence vectorAggregatePubSeq;
    private final MCSequence vectorAggregateSubSeq;

    private final RingQueue<TableBlockWriterTaskHolder> tableBlockWriterQueue;
    private final MPSequence tableBlockWriterPubSeq;
    private final MCSequence tableBlockWriterSubSeq;

    private final RingQueue<O3CallbackTask> o3CallbackQueue;
    private final MPSequence o3CallbackPubSeq;
    private final MCSequence o3CallbackSubSeq;

    private final RingQueue<O3PurgeDiscoveryTask> o3PurgeDiscoveryQueue;
    private final MPSequence o3PurgeDiscoveryPubSeq;
    private final MCSequence o3PurgeDiscoverySubSeq;

    private final RingQueue<O3PurgeTask> o3PurgeQueue;
    private final MPSequence o3PurgePubSeq;
    private final MCSequence o3PurgeSubSeq;

    private final RingQueue<OutOfOrderPartitionTask> outOfOrderPartitionQueue;
    private final MPSequence outOfOrderPartitionPubSeq;
    private final MCSequence outOfOrderPartitionSubSeq;

    private final RingQueue<OutOfOrderOpenColumnTask> outOfOrderOpenColumnQueue;
    private final MPSequence outOfOrderOpenColumnPubSeq;
    private final MCSequence outOfOrderOpenColumnSubSeq;

    private final RingQueue<OutOfOrderCopyTask> outOfOrderCopyQueue;
    private final MPSequence outOfOrderCopyPubSeq;
    private final MCSequence outOfOrderCopySubSeq;

    private final RingQueue<OutOfOrderUpdPartitionSizeTask> outOfOrderUpdPartitionSizeQueue;
    private final MPSequence outOfOrderUpdPartitionSizePubSeq;
    private final SCSequence outOfOrderUpdPartitionSizeSubSeq;

    private final CairoConfiguration configuration;

    public MessageBusImpl(@NotNull CairoConfiguration configuration) {
        this.configuration = configuration;
        this.indexerQueue = new RingQueue<>(ColumnIndexerTask::new, configuration.getColumnIndexerQueueCapacity());
        this.indexerPubSeq = new MPSequence(indexerQueue.getCapacity());
        this.indexerSubSeq = new MCSequence(indexerQueue.getCapacity());
        indexerPubSeq.then(indexerSubSeq).then(indexerPubSeq);

        this.vectorAggregateQueue = new RingQueue<>(VectorAggregateTask::new, configuration.getVectorAggregateQueueCapacity());
        this.vectorAggregatePubSeq = new MPSequence(vectorAggregateQueue.getCapacity());
        this.vectorAggregateSubSeq = new MCSequence(vectorAggregateQueue.getCapacity());
        vectorAggregatePubSeq.then(vectorAggregateSubSeq).then(vectorAggregatePubSeq);

        this.tableBlockWriterQueue = new RingQueue<>(TableBlockWriterTaskHolder::new, configuration.getTableBlockWriterQueueCapacity());
        this.tableBlockWriterPubSeq = new MPSequence(tableBlockWriterQueue.getCapacity());
        this.tableBlockWriterSubSeq = new MCSequence(tableBlockWriterQueue.getCapacity());
        tableBlockWriterPubSeq.then(tableBlockWriterSubSeq).then(tableBlockWriterPubSeq);

        this.o3CallbackQueue = new RingQueue<>(O3CallbackTask::new, configuration.getO3CallbackQueueCapacity());
        this.o3CallbackPubSeq = new MPSequence(this.o3CallbackQueue.getCapacity());
        this.o3CallbackSubSeq = new MCSequence(this.o3CallbackQueue.getCapacity());
        o3CallbackPubSeq.then(o3CallbackSubSeq).then(o3CallbackPubSeq);

        this.outOfOrderPartitionQueue = new RingQueue<>(OutOfOrderPartitionTask::new, configuration.getO3PartitionQueueCapacity());
        this.outOfOrderPartitionPubSeq = new MPSequence(this.outOfOrderPartitionQueue.getCapacity());
        this.outOfOrderPartitionSubSeq = new MCSequence(this.outOfOrderPartitionQueue.getCapacity());
        outOfOrderPartitionPubSeq.then(outOfOrderPartitionSubSeq).then(outOfOrderPartitionPubSeq);

        this.outOfOrderOpenColumnQueue = new RingQueue<>(OutOfOrderOpenColumnTask::new, configuration.getO3OpenColumnQueueCapacity());
        this.outOfOrderOpenColumnPubSeq = new MPSequence(this.outOfOrderOpenColumnQueue.getCapacity());
        this.outOfOrderOpenColumnSubSeq = new MCSequence(this.outOfOrderOpenColumnQueue.getCapacity());
        outOfOrderOpenColumnPubSeq.then(outOfOrderOpenColumnSubSeq).then(outOfOrderOpenColumnPubSeq);

        this.outOfOrderCopyQueue = new RingQueue<>(OutOfOrderCopyTask::new, configuration.getO3CopyQueueCapacity());
        this.outOfOrderCopyPubSeq = new MPSequence(this.outOfOrderCopyQueue.getCapacity());
        this.outOfOrderCopySubSeq = new MCSequence(this.outOfOrderCopyQueue.getCapacity());
        outOfOrderCopyPubSeq.then(outOfOrderCopySubSeq).then(outOfOrderCopyPubSeq);

        this.outOfOrderUpdPartitionSizeQueue = new RingQueue<>(OutOfOrderUpdPartitionSizeTask::new, configuration.getO3UpdPartitionSizeQueueCapacity());
        this.outOfOrderUpdPartitionSizePubSeq = new MPSequence(this.outOfOrderUpdPartitionSizeQueue.getCapacity());
        this.outOfOrderUpdPartitionSizeSubSeq = new SCSequence();
        outOfOrderUpdPartitionSizePubSeq.then(outOfOrderUpdPartitionSizeSubSeq).then(outOfOrderUpdPartitionSizePubSeq);

        this.o3PurgeDiscoveryQueue = new RingQueue<>(O3PurgeDiscoveryTask::new, configuration.getO3PurgeDiscoveryQueueCapacity());
        this.o3PurgeDiscoveryPubSeq = new MPSequence(this.o3PurgeDiscoveryQueue.getCapacity());
        this.o3PurgeDiscoverySubSeq = new MCSequence(this.o3PurgeDiscoveryQueue.getCapacity());
        this.o3PurgeDiscoveryPubSeq.then(this.o3PurgeDiscoverySubSeq).then(o3PurgeDiscoveryPubSeq);

        this.o3PurgeQueue = new RingQueue<>(O3PurgeTask::new, configuration.getO3PurgeQueueCapacity());
        this.o3PurgePubSeq = new MPSequence(this.o3PurgeQueue.getCapacity());
        this.o3PurgeSubSeq = new MCSequence(this.o3PurgeQueue.getCapacity());
        this.o3PurgePubSeq.then(this.o3PurgeSubSeq).then(this.o3PurgePubSeq);
    }

    @Override
    public MPSequence getO3CallbackPubSeq() {
        return o3CallbackPubSeq;
    }

    @Override
    public RingQueue<O3CallbackTask> getO3CallbackQueue() {
        return o3CallbackQueue;
    }

    @Override
    public MCSequence getO3CallbackSubSeq() {
        return o3CallbackSubSeq;
    }

    @Override
    public CairoConfiguration getConfiguration() {
        return configuration;
    }

    @Override
    public Sequence getIndexerPubSequence() {
        return indexerPubSeq;
    }

    @Override
    public RingQueue<ColumnIndexerTask> getIndexerQueue() {
        return indexerQueue;
    }

    @Override
    public Sequence getIndexerSubSequence() {
        return indexerSubSeq;
    }

    @Override
    public RingQueue<VectorAggregateTask> getVectorAggregateQueue() {
        return vectorAggregateQueue;
    }

    @Override
    public Sequence getVectorAggregatePubSequence() {
        return vectorAggregatePubSeq;
    }

    @Override
    public Sequence getVectorAggregateSubSequence() {
        return vectorAggregateSubSeq;
    }

    @Override
    public RingQueue<TableBlockWriterTaskHolder> getTableBlockWriterQueue() {
        return tableBlockWriterQueue;
    }

    @Override
    public Sequence getTableBlockWriterPubSequence() {
        return tableBlockWriterPubSeq;
    }

    @Override
    public Sequence getTableBlockWriterSubSequence() {
        return tableBlockWriterSubSeq;
    }

    @Override
    public MPSequence getOutOfOrderPartitionPubSeq() {
        return outOfOrderPartitionPubSeq;
    }

    @Override
    public RingQueue<OutOfOrderPartitionTask> getOutOfOrderPartitionQueue() {
        return outOfOrderPartitionQueue;
    }

    @Override
    public MCSequence getOutOfOrderPartitionSubSeq() {
        return outOfOrderPartitionSubSeq;
    }

    @Override
    public MPSequence getOutOfOrderCopyPubSeq() {
        return outOfOrderCopyPubSeq;
    }

    @Override
    public RingQueue<OutOfOrderCopyTask> getOutOfOrderCopyQueue() {
        return outOfOrderCopyQueue;
    }

    @Override
    public MCSequence getOutOfOrderCopySubSequence() {
        return outOfOrderCopySubSeq;
    }

    @Override
    public MPSequence getOutOfOrderOpenColumnPubSequence() {
        return outOfOrderOpenColumnPubSeq;
    }

    @Override
    public RingQueue<OutOfOrderOpenColumnTask> getOutOfOrderOpenColumnQueue() {
        return outOfOrderOpenColumnQueue;
    }

    @Override
    public MCSequence getOutOfOrderOpenColumnSubSequence() {
        return outOfOrderOpenColumnSubSeq;
    }

    @Override
    public MPSequence getOutOfOrderUpdPartitionSizePubSequence() {
        return outOfOrderUpdPartitionSizePubSeq;
    }

    @Override
    public RingQueue<OutOfOrderUpdPartitionSizeTask> getOutOfOrderUpdPartitionSizeQueue() {
        return outOfOrderUpdPartitionSizeQueue;
    }

    @Override
    public SCSequence getOutOfOrderUpdPartitionSizeSubSequence() {
        return outOfOrderUpdPartitionSizeSubSeq;
    }

    @Override
    public RingQueue<O3PurgeDiscoveryTask> getO3PurgeDiscoveryQueue() {
        return o3PurgeDiscoveryQueue;
    }

    @Override
    public MPSequence getO3PurgeDiscoveryPubSeq() {
        return o3PurgeDiscoveryPubSeq;
    }

    @Override
    public MCSequence getO3PurgeDiscoverySubSeq() {
        return o3PurgeDiscoverySubSeq;
    }

    @Override
    public MPSequence getO3PurgePubSeq() {
        return o3PurgePubSeq;
    }

    @Override
    public RingQueue<O3PurgeTask> getO3PurgeQueue() {
        return o3PurgeQueue;
    }

    @Override
    public MCSequence getO3PurgeSubSeq() {
        return o3PurgeSubSeq;
    }
}
