/*
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

package com.facebook.presto.hudi;

import com.facebook.presto.hive.HdfsContext;
import com.facebook.presto.hive.HdfsEnvironment;
import com.facebook.presto.hive.filesystem.ExtendedFileSystem;
import com.facebook.presto.hive.metastore.Column;
import com.facebook.presto.hive.metastore.ExtendedHiveMetastore;
import com.facebook.presto.hive.metastore.MetastoreContext;
import com.facebook.presto.hive.metastore.Partition;
import com.facebook.presto.hive.metastore.Table;
import com.facebook.presto.spi.ConnectorSession;
import com.facebook.presto.spi.ConnectorSplitSource;
import com.facebook.presto.spi.ConnectorTableLayoutHandle;
import com.facebook.presto.spi.FixedSplitSource;
import com.facebook.presto.spi.PrestoException;
import com.facebook.presto.spi.connector.ConnectorSplitManager;
import com.facebook.presto.spi.connector.ConnectorTransactionHandle;
import com.facebook.presto.spi.schedule.NodeSelectionStrategy;
import com.google.common.base.Verify;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Streams;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hudi.common.config.HoodieMetadataConfig;
import org.apache.hudi.common.engine.HoodieLocalEngineContext;
import org.apache.hudi.common.fs.FSUtils;
import org.apache.hudi.common.model.FileSlice;
import org.apache.hudi.common.table.HoodieTableMetaClient;
import org.apache.hudi.common.table.timeline.HoodieInstant;
import org.apache.hudi.common.table.timeline.HoodieTimeline;
import org.apache.hudi.common.table.view.HoodieTableFileSystemView;
import org.apache.hudi.common.util.Option;

import javax.inject.Inject;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static com.facebook.presto.hive.metastore.MetastoreUtil.extractPartitionValues;
import static com.facebook.presto.hudi.HudiErrorCode.HUDI_FILESYSTEM_ERROR;
import static com.facebook.presto.hudi.HudiErrorCode.HUDI_INVALID_METADATA;
import static com.facebook.presto.hudi.HudiMetadata.fromDataColumns;
import static com.facebook.presto.hudi.HudiMetadata.toMetastoreContext;
import static com.facebook.presto.hudi.HudiSessionProperties.isHudiMetadataTableEnabled;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;
import static org.apache.hudi.common.table.view.FileSystemViewManager.createInMemoryFileSystemViewWithTimeline;

public class HudiSplitManager
        implements ConnectorSplitManager
{
    private final HdfsEnvironment hdfsEnvironment;
    private final HudiTransactionManager hudiTransactionManager;
    private final HudiPartitionManager hudiPartitionManager;

    @Inject
    public HudiSplitManager(
            HdfsEnvironment hdfsEnvironment,
            HudiTransactionManager hudiTransactionManager,
            HudiPartitionManager hudiPartitionManager)
    {
        this.hdfsEnvironment = requireNonNull(hdfsEnvironment, "hdfsEnvironment is null");
        this.hudiTransactionManager = requireNonNull(hudiTransactionManager, "hudiTransactionManager is null");
        this.hudiPartitionManager = requireNonNull(hudiPartitionManager, "hudiPartitionManager is null");
    }

    @Override
    public ConnectorSplitSource getSplits(
            ConnectorTransactionHandle transaction,
            ConnectorSession session,
            ConnectorTableLayoutHandle layoutHandle,
            SplitSchedulingContext splitSchedulingContext)
    {
        ExtendedHiveMetastore metastore = ((HudiMetadata) hudiTransactionManager.get(transaction)).getMetastore();
        HudiTableLayoutHandle layout = (HudiTableLayoutHandle) layoutHandle;
        HudiTableHandle table = layout.getTable();

        // Load Hudi metadata
        ExtendedFileSystem fs = getFileSystem(session, table);
        boolean hudiMetadataTableEnabled = isHudiMetadataTableEnabled(session);
        HoodieMetadataConfig metadataConfig = HoodieMetadataConfig.newBuilder().enable(hudiMetadataTableEnabled).build();
        Configuration conf = fs.getConf();
        HoodieTableMetaClient metaClient = HoodieTableMetaClient
                .builder()
                .setConf(conf)
                .setBasePath(table.getPath())
                .build();

        // Retrieve and prune partitions
        List<String> partitions = hudiPartitionManager.getEffectivePartitions(session, metastore, metaClient, table.getSchemaName(), table.getTableName(), layout.getTupleDomain());
        if (partitions.isEmpty()) {
            return new FixedSplitSource(ImmutableList.of());
        }

        HoodieTimeline timeline = metaClient.getActiveTimeline().getCommitsTimeline().filterCompletedInstants();
        String timestamp = timeline.lastInstant().map(HoodieInstant::getTimestamp).orElse(null);
        if (timestamp == null) {
            // no completed instant for current table
            return new FixedSplitSource(ImmutableList.of());
        }

        HoodieLocalEngineContext engineContext = new HoodieLocalEngineContext(conf);
        // if metadata table enabled, support data skipping.
        if (hudiMetadataTableEnabled) {
            MetastoreContext metastoreContext = toMetastoreContext(session);
            Optional<Table> hiveTableOpt = metastore.getTable(metastoreContext, table.getSchemaName(), table.getTableName());
            Verify.verify(hiveTableOpt.isPresent());
            HudiMetaTableBaseFilesManager hudiMetaTableBaseFilesManager = new HudiMetaTableBaseFilesManager(
                    partitions,
                    HudiSessionProperties.getHoodieFilesystemViewSpillableDir(session),
                    engineContext,
                    metaClient,
                    HudiMetaTableBaseFilesManager.getQueryType(session, hiveTableOpt.get().getStorage().getStorageFormat().getInputFormat()),
                    Option.empty());
            ImmutableList.Builder<HudiSplit> splitsBuilder = ImmutableList.builder();
            Map<String, HudiPartition> hudiPartitionMap = getHudiPartitions(hiveTableOpt.get(), layout, partitions);
            hudiMetaTableBaseFilesManager.listQueryFiles(layout.getTupleDomain())
                    .entrySet()
                    .stream()
                    .flatMap(entry -> entry.getValue().stream().map(fileSlice -> createHudiSplit(table, fileSlice, timestamp, hudiPartitionMap.get(entry.getKey()))))
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .forEach(splitsBuilder::add);
            List<HudiSplit> splitsList = splitsBuilder.build();
            return splitsList.isEmpty() ? new FixedSplitSource(ImmutableList.of()) : new FixedSplitSource(splitsList);
        }

        HoodieTableFileSystemView fsView = createInMemoryFileSystemViewWithTimeline(engineContext, metaClient, metadataConfig, timeline);

        // Construct Presto splits
        Path tablePath = new Path(table.getPath());
        MetastoreContext metastoreContext = toMetastoreContext(session);
        ImmutableList.Builder<HudiSplit> builder = ImmutableList.builder();
        for (String partitionName : partitions) {
            HudiPartition hudiPartition = getHudiPartition(metastore, metastoreContext, layout, partitionName);
            Path partitionPath = new Path(hudiPartition.getStorage().getLocation());
            String relativePartitionPath = FSUtils.getRelativePartitionPath(tablePath, partitionPath);
            fsView.getLatestFileSlicesBeforeOrOn(relativePartitionPath, timestamp, false)
                    .map(fileSlice -> createHudiSplit(table, fileSlice, timestamp, hudiPartition))
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .forEach(builder::add);
        }
        List<HudiSplit> splitList = builder.build();
        return splitList.isEmpty() ? new FixedSplitSource(ImmutableList.of()) : new FixedSplitSource(splitList);
    }

    private ExtendedFileSystem getFileSystem(ConnectorSession session, HudiTableHandle table)
    {
        HdfsContext hdfsContext = new HdfsContext(
                session,
                table.getSchemaName(),
                table.getTableName(),
                table.getPath(),
                false);
        try {
            return hdfsEnvironment.getFileSystem(hdfsContext, new Path(table.getPath()));
        }
        catch (IOException e) {
            throw new PrestoException(HUDI_FILESYSTEM_ERROR, "Could not open file system for " + table, e);
        }
    }

    private Optional<HudiSplit> createHudiSplit(HudiTableHandle table, FileSlice slice, String timestamp, HudiPartition partition)
    {
        HudiFile hudiFile = slice.getBaseFile().map(f -> new HudiFile(f.getPath(), 0, f.getFileLen())).orElse(null);
        if (null == hudiFile && table.getTableType() == HudiTableType.COW) {
            return Optional.empty();
        }
        List<HudiFile> logFiles = slice.getLogFiles()
                .map(logFile -> new HudiFile(logFile.getPath().toString(), 0, logFile.getFileSize()))
                .collect(toImmutableList());
        return Optional.of(new HudiSplit(
                table,
                timestamp,
                partition,
                Optional.ofNullable(hudiFile),
                logFiles,
                ImmutableList.of(),
                NodeSelectionStrategy.NO_PREFERENCE));
    }

    private Map<String, HudiPartition> getHudiPartitions(Table table, HudiTableLayoutHandle tableLayout, List<String> partitions)
    {
        List<Column> partitionColumns = table.getPartitionColumns();

        Map<String, Map<String, String>> partitionMap = HudiPartitionManager
                .getPartitions(partitionColumns.stream().map(f -> f.getName()).collect(Collectors.toList()), partitions);
        if (partitions.size() == 1 && partitions.get(0).isEmpty()) {
            // non-non-partitioned
            return ImmutableMap.of(partitions.get(0), new HudiPartition(partitions.get(0), ImmutableList.of(), ImmutableMap.of(), table.getStorage(), tableLayout.getDataColumns()));
        }
        ImmutableMap.Builder<String, HudiPartition> builder = ImmutableMap.builder();
        partitionMap.entrySet().stream().map(entry -> {
            List<String> partitionValues = extractPartitionValues(entry.getKey());
            return new HudiPartition(entry.getKey(), partitionValues, entry.getValue(), table.getStorage(), fromDataColumns(table.getDataColumns()));
        }).forEach(p -> builder.put(p.getName(), p));
        return builder.build();
    }

    public static HudiPartition getHudiPartition(ExtendedHiveMetastore metastore, MetastoreContext context, HudiTableLayoutHandle tableLayout, String partitionName)
    {
        String databaseName = tableLayout.getTable().getSchemaName();
        String tableName = tableLayout.getTable().getTableName();
        List<HudiColumnHandle> partitionColumns = tableLayout.getPartitionColumns();

        if (partitionColumns.isEmpty()) {
            // non-partitioned tableLayout
            Table table = metastore.getTable(context, databaseName, tableName)
                    .orElseThrow(() -> new PrestoException(HUDI_INVALID_METADATA, format("Table %s.%s expected but not found", databaseName, tableName)));
            return new HudiPartition(partitionName, ImmutableList.of(), ImmutableMap.of(), table.getStorage(), tableLayout.getDataColumns());
        }
        else {
            // partitioned tableLayout
            List<String> partitionValues = extractPartitionValues(partitionName);
            checkArgument(partitionColumns.size() == partitionValues.size(),
                    format("Invalid partition name %s for partition columns %s", partitionName, partitionColumns));
            Partition partition = metastore.getPartition(context, databaseName, tableName, partitionValues)
                    .orElseThrow(() -> new PrestoException(HUDI_INVALID_METADATA, format("Partition %s expected but not found", partitionName)));
            Map<String, String> keyValues = zipPartitionKeyValues(partitionColumns, partitionValues);
            return new HudiPartition(partitionName, partitionValues, keyValues, partition.getStorage(), fromDataColumns(partition.getColumns()));
        }
    }

    private static Map<String, String> zipPartitionKeyValues(List<HudiColumnHandle> partitionColumns, List<String> partitionValues)
    {
        ImmutableMap.Builder<String, String> builder = ImmutableMap.builder();
        Streams.forEachPair(partitionColumns.stream(), partitionValues.stream(),
                (column, value) -> builder.put(column.getName(), value));
        return builder.build();
    }
}
