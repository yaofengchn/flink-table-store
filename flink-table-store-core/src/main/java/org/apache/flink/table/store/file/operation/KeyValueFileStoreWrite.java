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

import org.apache.flink.table.data.RowData;
import org.apache.flink.table.data.binary.BinaryRowData;
import org.apache.flink.table.store.CoreOptions;
import org.apache.flink.table.store.file.KeyValue;
import org.apache.flink.table.store.file.compact.CompactManager;
import org.apache.flink.table.store.file.compact.CompactResult;
import org.apache.flink.table.store.file.compact.CompactUnit;
import org.apache.flink.table.store.file.compact.NoopCompactManager;
import org.apache.flink.table.store.file.io.DataFileMeta;
import org.apache.flink.table.store.file.io.KeyValueFileReaderFactory;
import org.apache.flink.table.store.file.io.KeyValueFileWriterFactory;
import org.apache.flink.table.store.file.io.RollingFileWriter;
import org.apache.flink.table.store.file.mergetree.Levels;
import org.apache.flink.table.store.file.mergetree.MergeTreeReader;
import org.apache.flink.table.store.file.mergetree.MergeTreeWriter;
import org.apache.flink.table.store.file.mergetree.compact.CompactRewriter;
import org.apache.flink.table.store.file.mergetree.compact.CompactStrategy;
import org.apache.flink.table.store.file.mergetree.compact.MergeFunction;
import org.apache.flink.table.store.file.mergetree.compact.MergeTreeCompactManager;
import org.apache.flink.table.store.file.mergetree.compact.MergeTreeCompactTask;
import org.apache.flink.table.store.file.mergetree.compact.UniversalCompaction;
import org.apache.flink.table.store.file.schema.SchemaManager;
import org.apache.flink.table.store.file.utils.FileStorePathFactory;
import org.apache.flink.table.store.file.utils.RecordReaderIterator;
import org.apache.flink.table.store.file.utils.RecordWriter;
import org.apache.flink.table.store.file.utils.SnapshotManager;
import org.apache.flink.table.types.logical.RowType;

import javax.annotation.Nullable;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;

import static org.apache.flink.table.store.file.io.DataFileMeta.getMaxSequenceNumber;

/** {@link FileStoreWrite} for {@link org.apache.flink.table.store.file.KeyValueFileStore}. */
public class KeyValueFileStoreWrite extends AbstractFileStoreWrite<KeyValue> {

    private final KeyValueFileReaderFactory.Builder readerFactoryBuilder;
    private final KeyValueFileWriterFactory.Builder writerFactoryBuilder;
    private final Supplier<Comparator<RowData>> keyComparatorSupplier;
    private final MergeFunction<KeyValue> mergeFunction;
    private final CoreOptions options;

    public KeyValueFileStoreWrite(
            SchemaManager schemaManager,
            long schemaId,
            RowType keyType,
            RowType valueType,
            Supplier<Comparator<RowData>> keyComparatorSupplier,
            MergeFunction<KeyValue> mergeFunction,
            FileStorePathFactory pathFactory,
            SnapshotManager snapshotManager,
            FileStoreScan scan,
            CoreOptions options) {
        super(snapshotManager, scan);
        this.readerFactoryBuilder =
                KeyValueFileReaderFactory.builder(
                        schemaManager,
                        schemaId,
                        keyType,
                        valueType,
                        options.fileFormat(),
                        pathFactory);
        this.writerFactoryBuilder =
                KeyValueFileWriterFactory.builder(
                        schemaId,
                        keyType,
                        valueType,
                        options.fileFormat(),
                        pathFactory,
                        options.targetFileSize());
        this.keyComparatorSupplier = keyComparatorSupplier;
        this.mergeFunction = mergeFunction;
        this.options = options;
    }

    @Override
    public RecordWriter<KeyValue> createWriter(
            BinaryRowData partition, int bucket, ExecutorService compactExecutor) {
        return createMergeTreeWriter(
                partition, bucket, scanExistingFileMetas(partition, bucket), compactExecutor);
    }

    @Override
    public RecordWriter<KeyValue> createEmptyWriter(
            BinaryRowData partition, int bucket, ExecutorService compactExecutor) {
        return createMergeTreeWriter(partition, bucket, Collections.emptyList(), compactExecutor);
    }

    @Override
    public Callable<CompactResult> createCompactWriter(
            BinaryRowData partition, int bucket, @Nullable List<DataFileMeta> compactFiles) {
        if (compactFiles == null) {
            compactFiles = scanExistingFileMetas(partition, bucket);
        }
        Comparator<RowData> keyComparator = keyComparatorSupplier.get();
        CompactRewriter rewriter = compactRewriter(partition, bucket, keyComparator);
        Levels levels = new Levels(keyComparator, compactFiles, options.numLevels());
        CompactUnit unit =
                CompactUnit.fromLevelRuns(levels.numberOfLevels() - 1, levels.levelSortedRuns());
        return new MergeTreeCompactTask(
                keyComparator, options.targetFileSize(), rewriter, unit, true);
    }

    private MergeTreeWriter createMergeTreeWriter(
            BinaryRowData partition,
            int bucket,
            List<DataFileMeta> restoreFiles,
            ExecutorService compactExecutor) {
        KeyValueFileWriterFactory writerFactory = writerFactoryBuilder.build(partition, bucket);
        Comparator<RowData> keyComparator = keyComparatorSupplier.get();
        CompactManager compactManager;
        if (options.writeCompactionSkip()) {
            compactManager = new NoopCompactManager(compactExecutor);
        } else {
            Levels levels = new Levels(keyComparator, restoreFiles, options.numLevels());
            compactManager =
                    createCompactManager(
                            partition,
                            bucket,
                            new UniversalCompaction(
                                    options.maxSizeAmplificationPercent(),
                                    options.sortedRunSizeRatio(),
                                    options.numSortedRunCompactionTrigger(),
                                    options.maxSortedRunNum()),
                            compactExecutor,
                            levels);
        }
        return new MergeTreeWriter(
                options.writeBufferSpillable(),
                options.localSortMaxNumFileHandles(),
                ioManager,
                compactManager,
                getMaxSequenceNumber(restoreFiles),
                keyComparator,
                mergeFunction.copy(),
                writerFactory,
                options.commitForceCompact(),
                options.changelogProducer());
    }

    private CompactManager createCompactManager(
            BinaryRowData partition,
            int bucket,
            CompactStrategy compactStrategy,
            ExecutorService compactExecutor,
            Levels levels) {
        Comparator<RowData> keyComparator = keyComparatorSupplier.get();
        CompactRewriter rewriter = compactRewriter(partition, bucket, keyComparator);
        return new MergeTreeCompactManager(
                compactExecutor,
                levels,
                compactStrategy,
                keyComparator,
                options.targetFileSize(),
                options.numSortedRunStopTrigger(),
                rewriter);
    }

    private CompactRewriter compactRewriter(
            BinaryRowData partition, int bucket, Comparator<RowData> keyComparator) {
        KeyValueFileWriterFactory writerFactory = writerFactoryBuilder.build(partition, bucket);
        return (outputLevel, dropDelete, sections) -> {
            RollingFileWriter<KeyValue, DataFileMeta> writer =
                    writerFactory.createRollingMergeTreeFileWriter(outputLevel);
            writer.write(
                    new RecordReaderIterator<>(
                            new MergeTreeReader(
                                    sections,
                                    dropDelete,
                                    readerFactoryBuilder.build(partition, bucket),
                                    keyComparator,
                                    mergeFunction.copy())));
            writer.close();
            return writer.result();
        };
    }
}
