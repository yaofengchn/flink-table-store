/*
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

package org.apache.flink.table.store.file.operation;

import org.apache.flink.runtime.io.disk.iomanager.IOManager;
import org.apache.flink.table.data.binary.BinaryRowData;
import org.apache.flink.table.store.file.io.DataFileMeta;
import org.apache.flink.table.store.file.manifest.ManifestEntry;
import org.apache.flink.table.store.file.utils.SnapshotManager;

import org.apache.flink.shaded.guava30.com.google.common.collect.Lists;

import javax.annotation.Nullable;

import java.util.Collections;
import java.util.List;

/**
 * Base {@link FileStoreWrite} implementation.
 *
 * @param <T> type of record to write.
 */
public abstract class AbstractFileStoreWrite<T> implements FileStoreWrite<T> {

    private final SnapshotManager snapshotManager;
    private final FileStoreScan scan;

    @Nullable protected IOManager ioManager;

    protected AbstractFileStoreWrite(SnapshotManager snapshotManager, FileStoreScan scan) {
        this.snapshotManager = snapshotManager;
        this.scan = scan;
    }

    @Override
    public FileStoreWrite<T> withIOManager(IOManager ioManager) {
        this.ioManager = ioManager;
        return this;
    }

    protected List<DataFileMeta> scanExistingFileMetas(BinaryRowData partition, int bucket) {
        Long latestSnapshotId = snapshotManager.latestSnapshotId();
        List<DataFileMeta> existingFileMetas = Lists.newArrayList();
        if (latestSnapshotId != null) {
            // Concat all the DataFileMeta of existing files into existingFileMetas.
            scan.withSnapshot(latestSnapshotId)
                    .withPartitionFilter(Collections.singletonList(partition)).withBucket(bucket)
                    .plan().files().stream()
                    .map(ManifestEntry::file)
                    .forEach(existingFileMetas::add);
        }
        return existingFileMetas;
    }
}
