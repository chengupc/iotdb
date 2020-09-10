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
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iotdb.db.query.dataset;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.iotdb.db.exception.StorageEngineException;
import org.apache.iotdb.db.exception.metadata.MetadataException;
import org.apache.iotdb.db.exception.query.QueryProcessException;
import org.apache.iotdb.db.qp.Planner;
import org.apache.iotdb.db.qp.executor.IPlanExecutor;
import org.apache.iotdb.db.qp.executor.PlanExecutor;
import org.apache.iotdb.db.qp.physical.crud.UDTFPlan;
import org.apache.iotdb.db.service.IoTDB;
import org.apache.iotdb.db.utils.EnvironmentUtils;
import org.apache.iotdb.tsfile.exception.filter.QueryFilterOptimizationException;
import org.apache.iotdb.tsfile.file.metadata.enums.CompressionType;
import org.apache.iotdb.tsfile.file.metadata.enums.TSDataType;
import org.apache.iotdb.tsfile.file.metadata.enums.TSEncoding;
import org.apache.iotdb.tsfile.read.common.Field;
import org.apache.iotdb.tsfile.read.common.Path;
import org.apache.iotdb.tsfile.read.common.RowRecord;
import org.apache.iotdb.tsfile.read.query.dataset.QueryDataSet;
import org.apache.thrift.TException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class UDTFAlignByTimeDataSetTest {

  protected final static int ITERATION_TIMES = 10_000;

  protected final static int ADDEND = 500_000;

  private final IPlanExecutor queryExecutor = new PlanExecutor();
  private final Planner processor = new Planner();

  public UDTFAlignByTimeDataSetTest() throws QueryProcessException {
  }

  @Before
  public void setUp() throws Exception {
    EnvironmentUtils.envSetUp();
    try {
      IoTDB.metaManager.setStorageGroup("root.vehicle");
      IoTDB.metaManager
          .createTimeseries("root.vehicle.d1.s1", TSDataType.FLOAT, TSEncoding.PLAIN,
              CompressionType.UNCOMPRESSED, null);
      IoTDB.metaManager
          .createTimeseries("root.vehicle.d1.s2", TSDataType.FLOAT, TSEncoding.PLAIN,
              CompressionType.UNCOMPRESSED, null);
    } catch (Exception e) {
      e.printStackTrace();
    }
    generateData();
    queryExecutor.processNonQuery(processor.parseSQLToPhysicalPlan(String
        .format("create function udf as \"%s\"", "org.apache.iotdb.db.query.udf.example.Adder")));
  }

  private void generateData() throws Exception {
    for (int i = 0; i < ITERATION_TIMES; ++i) {
      queryExecutor.processNonQuery(processor.parseSQLToPhysicalPlan(i % 3 != 0
          ? String.format("insert into root.vehicle.d1(timestamp,s1,s2) values(%d,%d,%d)", i, i, i)
          : i % 2 == 0
              ? String.format("insert into root.vehicle.d1(timestamp,s1) values(%d,%d)", i, i)
              : String.format("insert into root.vehicle.d1(timestamp,s2) values(%d,%d)", i, i)));
    }
  }

  @After
  public void tearDown() throws Exception {
    queryExecutor.processNonQuery(processor.parseSQLToPhysicalPlan("drop function udf"));
    EnvironmentUtils.cleanEnv();
  }

  @Test
  public void testHasNextAndNextWithoutValueFilter1() {
    try {
      String sqlStr = "select udf(d1.s2, d1.s1), udf(d1.s1, d1.s2), d1.s1, d1.s2, udf(d1.s1, d1.s2), udf(d1.s2, d1.s1), d1.s1, d1.s2 from root.vehicle";
      UDTFPlan queryPlan = (UDTFPlan) processor.parseSQLToPhysicalPlan(sqlStr);
      QueryDataSet dataSet = queryExecutor
          .processQuery(queryPlan, EnvironmentUtils.TEST_QUERY_CONTEXT);
      assertTrue(dataSet instanceof UDTFAlignByTimeDataSet);
      UDTFAlignByTimeDataSet udtfAlignByTimeDataSet = (UDTFAlignByTimeDataSet) dataSet;

      Set<Integer> s1s2 = new HashSet<>(Arrays.asList(0, 1, 4, 5));
      Set<Integer> s1 = new HashSet<>(Arrays.asList(2, 6));
      Set<Integer> s2 = new HashSet<>(Arrays.asList(3, 7));

      Map<String, Integer> path2Index = queryPlan.getPathToIndex();
      List<Integer> originalIndex2FieldIndex = new ArrayList<>();
      for (int i = 0; i < 8; ++i) {
        Path path = queryPlan.getPaths().get(i);
        String columnName = path == null ? queryPlan.getColumn(i) : path.getFullPath();
        originalIndex2FieldIndex.add(path2Index.get(columnName));
      }

      int count = 0;
      while (udtfAlignByTimeDataSet.hasNext()) {
        RowRecord rowRecord = udtfAlignByTimeDataSet.next();
        List<Field> fields = rowRecord.getFields();
        for (int i = 0; i < 8; ++i) {
          if (s1s2.contains(i)) {
            if (count % 3 != 0) {
              assertEquals(count * 2, fields.get(originalIndex2FieldIndex.get(i)).getFloatV(), 0);
            } else {
              assertTrue(fields.get(originalIndex2FieldIndex.get(i)).isNull());
            }
          } else if (s1.contains(i)) {
            if (count % 3 != 0 || count % 2 == 0) {
              assertEquals(count, fields.get(originalIndex2FieldIndex.get(i)).getFloatV(), 0);
            } else {
              assertTrue(fields.get(originalIndex2FieldIndex.get(i)).isNull());
            }
          } else if (s2.contains(i)) {
            if (count % 3 != 0 || count % 2 != 0) {
              assertEquals(count, fields.get(originalIndex2FieldIndex.get(i)).getFloatV(), 0);
            } else {
              assertTrue(fields.get(originalIndex2FieldIndex.get(i)).isNull());
            }
          }
        }
        ++count;
      }
      assertEquals(ITERATION_TIMES, count);
    } catch (StorageEngineException | QueryFilterOptimizationException | TException | MetadataException | QueryProcessException | SQLException | IOException | InterruptedException e) {
      e.printStackTrace();
      fail(e.toString());
    }
  }

  @Test
  public void testHasNextAndNextWithoutValueFilter2() {
    try {
      String sqlStr = "select udf(*, *) from root.vehicle";
      UDTFPlan queryPlan = (UDTFPlan) processor.parseSQLToPhysicalPlan(sqlStr);
      QueryDataSet dataSet = queryExecutor
          .processQuery(queryPlan, EnvironmentUtils.TEST_QUERY_CONTEXT);
      assertTrue(dataSet instanceof UDTFAlignByTimeDataSet);
      UDTFAlignByTimeDataSet udtfAlignByTimeDataSet = (UDTFAlignByTimeDataSet) dataSet;

      Map<String, Integer> path2Index = queryPlan.getPathToIndex();
      List<Integer> originalIndex2FieldIndex = new ArrayList<>();
      for (int i = 0; i < 4; ++i) {
        Path path = queryPlan.getPaths().get(i);
        String columnName = path == null ? queryPlan.getColumn(i) : path.getFullPath();
        originalIndex2FieldIndex.add(path2Index.get(columnName));
      }

      int count = 0;
      while (udtfAlignByTimeDataSet.hasNext()) {
        RowRecord rowRecord = udtfAlignByTimeDataSet.next();
        List<Field> fields = rowRecord.getFields();
        for (int i = 0; i < 4; ++i) {
          Field field = fields.get(originalIndex2FieldIndex.get(i));
          if (!field.isNull()) {
            assertEquals(count * 2, fields.get(originalIndex2FieldIndex.get(i)).getFloatV(), 0);
          }
        }
        ++count;
      }
      assertEquals(ITERATION_TIMES, count);
    } catch (StorageEngineException | QueryFilterOptimizationException | TException | MetadataException | QueryProcessException | SQLException | IOException | InterruptedException e) {
      e.printStackTrace();
      fail(e.toString());
    }
  }

  @Test
  public void testHasNextAndNextWithoutValueFilter3() {
    try {
      String sqlStr = "select *, udf(*, *), *, udf(*, *), * from root.vehicle";
      UDTFPlan queryPlan = (UDTFPlan) processor.parseSQLToPhysicalPlan(sqlStr);
      QueryDataSet dataSet = queryExecutor
          .processQuery(queryPlan, EnvironmentUtils.TEST_QUERY_CONTEXT);
      assertTrue(dataSet instanceof UDTFAlignByTimeDataSet);
      UDTFAlignByTimeDataSet udtfAlignByTimeDataSet = (UDTFAlignByTimeDataSet) dataSet;

      Map<String, Integer> path2Index = queryPlan.getPathToIndex();
      List<Integer> originalIndex2FieldIndex = new ArrayList<>();
      for (int i = 0; i < 14; ++i) {
        Path path = queryPlan.getPaths().get(i);
        String columnName = path == null ? queryPlan.getColumn(i) : path.getFullPath();
        originalIndex2FieldIndex.add(path2Index.get(columnName));
      }

      Set<Integer> s1AndS2 = new HashSet<>(Arrays.asList(2, 3, 4, 5, 8, 9, 10, 11));
      Set<Integer> s1OrS2 = new HashSet<>(Arrays.asList(0, 1, 6, 7, 12, 13));

      int count = 0;
      while (udtfAlignByTimeDataSet.hasNext()) {
        RowRecord rowRecord = udtfAlignByTimeDataSet.next();
        List<Field> fields = rowRecord.getFields();
        for (int i = 0; i < 14; ++i) {
          if (s1AndS2.contains(i)) {
            Field field = fields.get(originalIndex2FieldIndex.get(i));
            if (!field.isNull()) {
              assertEquals(count * 2, fields.get(originalIndex2FieldIndex.get(i)).getFloatV(), 0);
            }
          }
          if (s1OrS2.contains(i)) {
            Field field = fields.get(originalIndex2FieldIndex.get(i));
            if (!field.isNull()) {
              assertEquals(count, fields.get(originalIndex2FieldIndex.get(i)).getFloatV(), 0);
            }
          }
        }
        ++count;
      }
      assertEquals(ITERATION_TIMES, count);
    } catch (StorageEngineException | QueryFilterOptimizationException | TException | MetadataException | QueryProcessException | SQLException | IOException | InterruptedException e) {
      e.printStackTrace();
      fail(e.toString());
    }
  }

  @Test
  public void testHasNextAndNextWithoutValueFilter4() {
    try {
      String sqlStr =
          "select udf(*, *, \"addend\"=\"" + ADDEND + "\"), *, udf(*, *) from root.vehicle";
      UDTFPlan queryPlan = (UDTFPlan) processor.parseSQLToPhysicalPlan(sqlStr);
      QueryDataSet dataSet = queryExecutor
          .processQuery(queryPlan, EnvironmentUtils.TEST_QUERY_CONTEXT);
      assertTrue(dataSet instanceof UDTFAlignByTimeDataSet);
      UDTFAlignByTimeDataSet udtfAlignByTimeDataSet = (UDTFAlignByTimeDataSet) dataSet;

      Map<String, Integer> path2Index = queryPlan.getPathToIndex();
      List<Integer> originalIndex2FieldIndex = new ArrayList<>();
      for (int i = 0; i < 10; ++i) {
        Path path = queryPlan.getPaths().get(i);
        String columnName = path == null ? queryPlan.getColumn(i) : path.getFullPath();
        originalIndex2FieldIndex.add(path2Index.get(columnName));
      }

      Set<Integer> s1AndS2WithAddend = new HashSet<>(Arrays.asList(0, 1, 2, 3));
      Set<Integer> s1AndS2 = new HashSet<>(Arrays.asList(6, 7, 8, 9));
      Set<Integer> s1OrS2 = new HashSet<>(Arrays.asList(4, 5));

      int count = 0;
      while (udtfAlignByTimeDataSet.hasNext()) {
        RowRecord rowRecord = udtfAlignByTimeDataSet.next();
        List<Field> fields = rowRecord.getFields();
        for (int i = 0; i < 10; ++i) {
          if (s1AndS2WithAddend.contains(i)) {
            Field field = fields.get(originalIndex2FieldIndex.get(i));
            if (!field.isNull()) {
              assertEquals(count * 2 + ADDEND,
                  fields.get(originalIndex2FieldIndex.get(i)).getFloatV(), 0);
            }
          }
          if (s1AndS2.contains(i)) {
            Field field = fields.get(originalIndex2FieldIndex.get(i));
            if (!field.isNull()) {
              assertEquals(count * 2, fields.get(originalIndex2FieldIndex.get(i)).getFloatV(), 0);
            }
          }
          if (s1OrS2.contains(i)) {
            Field field = fields.get(originalIndex2FieldIndex.get(i));
            if (!field.isNull()) {
              assertEquals(count, fields.get(originalIndex2FieldIndex.get(i)).getFloatV(), 0);
            }
          }
        }
        ++count;
      }
      assertEquals(ITERATION_TIMES, count);
    } catch (StorageEngineException | QueryFilterOptimizationException | TException | MetadataException | QueryProcessException | SQLException | IOException | InterruptedException e) {
      e.printStackTrace();
      fail(e.toString());
    }
  }
}
