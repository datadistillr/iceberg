/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iceberg;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import org.apache.iceberg.exceptions.RuntimeIOException;
import org.apache.iceberg.expressions.Expression;
import org.apache.iceberg.expressions.Expressions;
import org.apache.iceberg.expressions.ResidualEvaluator;
import org.apache.iceberg.io.CloseableIterable;
import org.apache.iceberg.relocated.com.google.common.collect.Iterables;
import org.apache.iceberg.relocated.com.google.common.collect.Sets;
import org.apache.iceberg.types.TypeUtil;
import org.apache.iceberg.types.Types.StructType;
import org.apache.iceberg.util.ParallelIterable;

/**
 * A {@link Table} implementation that exposes a table's manifest entries as rows, for both delete and data files.
 * <p>
 * WARNING: this table exposes internal details, like files that have been deleted. For a table of the live data files,
 * use {@link DataFilesTable}.
 */
public class AllEntriesTable extends BaseMetadataTable {

  AllEntriesTable(TableOperations ops, Table table) {
    this(ops, table, table.name() + ".all_entries");
  }

  AllEntriesTable(TableOperations ops, Table table, String name) {
    super(ops, table, name);
  }

  @Override
  public TableScan newScan() {
    return new Scan(operations(), table(), schema());
  }

  @Override
  public Schema schema() {
    StructType partitionType = Partitioning.partitionType(table());
    Schema schema = ManifestEntry.getSchema(partitionType);
    if (partitionType.fields().size() < 1) {
      // avoid returning an empty struct, which is not always supported. instead, drop the partition field (id 102)
      return TypeUtil.selectNot(schema, Sets.newHashSet(102));
    } else {
      return schema;
    }
  }

  @Override
  MetadataTableType metadataTableType() {
    return MetadataTableType.ALL_ENTRIES;
  }

  private static class Scan extends BaseAllMetadataTableScan {

    Scan(TableOperations ops, Table table, Schema schema) {
      super(ops, table, schema);
    }

    private Scan(TableOperations ops, Table table, Schema schema, TableScanContext context) {
      super(ops, table, schema, context);
    }

    @Override
    protected TableScan newRefinedScan(TableOperations ops, Table table, Schema schema,
                                       TableScanContext context) {
      return new Scan(ops, table, schema, context);
    }

    @Override
    protected String tableType() {
      return MetadataTableType.ALL_ENTRIES.name();
    }

    @Override
    protected CloseableIterable<FileScanTask> planFiles(
        TableOperations ops, Snapshot snapshot, Expression rowFilter,
        boolean ignoreResiduals, boolean caseSensitive, boolean colStats) {
      CloseableIterable<ManifestFile> manifests = allManifestFiles(
          ops.current().snapshots(), context().planExecutor());
      String schemaString = SchemaParser.toJson(schema());
      String specString = PartitionSpecParser.toJson(PartitionSpec.unpartitioned());
      Expression filter = ignoreResiduals ? Expressions.alwaysTrue() : rowFilter;
      ResidualEvaluator residuals = ResidualEvaluator.unpartitioned(filter);

      return CloseableIterable.transform(manifests, manifest -> new ManifestEntriesTable.ManifestReadTask(
          ops.io(), manifest, schema(), schemaString, specString, residuals, ops.current().specsById()));
    }
  }

  private static CloseableIterable<ManifestFile> allManifestFiles(
      List<Snapshot> snapshots, ExecutorService workerPool) {
    try (CloseableIterable<ManifestFile> iterable = new ParallelIterable<>(
        Iterables.transform(snapshots, snapshot -> (Iterable<ManifestFile>) () -> snapshot.allManifests().iterator()),
        workerPool)) {
      return CloseableIterable.withNoopClose(Sets.newHashSet(iterable));
    } catch (IOException e) {
      throw new RuntimeIOException(e, "Failed to close parallel iterable");
    }
  }
}
