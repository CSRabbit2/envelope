/**
 * Copyright © 2016-2017 Cloudera, Inc.
 *
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
package com.cloudera.labs.envelope.plan;

import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Set;

import org.apache.spark.sql.Row;
import org.apache.spark.sql.types.DataTypes;

import com.cloudera.labs.envelope.utils.RowUtils;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.typesafe.config.Config;

/**
 * A planner implementation for updating existing and inserting new (upsert). This maintains the
 * most recent version of the values of a key, which is equivalent to Type I SCD modeling.
 */
public class EventTimeUpsertPlanner implements RandomPlanner {

  public static final String KEY_FIELD_NAMES_CONFIG_NAME = "fields.key";
  public static final String LAST_UPDATED_FIELD_NAME_CONFIG_NAME = "field.last.updated";
  public static final String TIMESTAMP_FIELD_NAME_CONFIG_NAME = "field.timestamp";
  public static final String VALUE_FIELD_NAMES_CONFIG_NAME = "field.values";

  private Config config;

  @Override
  public void configure(Config config) {
    this.config = config;
  }

  @Override
  public List<PlannedRow> planMutationsForKey(Row key, List<Row> arrivingForKey, List<Row> existingForKey)
  {
    if (key.schema() == null) {
      throw new RuntimeException("Key sent to event time upsert planner does not contain a schema");
    }

    String timestampFieldName = getTimestampFieldName();
    List<String> valueFieldNames = getValueFieldNames();

    Comparator<Row> tc = new TimestampComparator(timestampFieldName);

    List<PlannedRow> planned = Lists.newArrayList();

    if (arrivingForKey.size() > 1) {
      Collections.sort(arrivingForKey, Collections.reverseOrder(tc));
    }
    Row arrived = arrivingForKey.get(0);

    if (arrived.schema() == null) {
      throw new RuntimeException("Arriving row sent to event time upsert planner does not contain a schema");
    }

    Row existing = null;
    if (existingForKey.size() > 0) {
      existing = existingForKey.get(0);

      if (arrived.schema() == null) {
        throw new RuntimeException("Existing row sent to event time upsert planner does not contain a schema");
      }
    }

    if (existing == null) {
      if (hasLastUpdatedField()) {
        arrived = RowUtils.append(arrived, getLastUpdatedFieldName(), DataTypes.StringType, currentTimestampString());
      }

      planned.add(new PlannedRow(arrived, MutationType.INSERT));
    }
    else if (RowUtils.before(arrived, existing, timestampFieldName)) {
      // We do nothing because the arriving record is older than the existing record
    }
    else if ((RowUtils.simultaneous(arrived, existing, timestampFieldName) ||
          RowUtils.after(arrived, existing, timestampFieldName)) &&
          RowUtils.different(arrived, existing, valueFieldNames))
    {
      if (hasLastUpdatedField()) {
        arrived = RowUtils.append(arrived, getLastUpdatedFieldName(), DataTypes.StringType, currentTimestampString());
      }
      planned.add(new PlannedRow(arrived, MutationType.UPDATE));
    }

    return planned;
  }

  @Override
  public Set<MutationType> getEmittedMutationTypes() {
    return Sets.newHashSet(MutationType.INSERT, MutationType.UPDATE);
  }

  @Override
  public List<String> getKeyFieldNames() {
    return config.getStringList(KEY_FIELD_NAMES_CONFIG_NAME);
  }

  private boolean hasLastUpdatedField() {
    return config.hasPath(LAST_UPDATED_FIELD_NAME_CONFIG_NAME);
  }

  private String getLastUpdatedFieldName() {
    return config.getString(LAST_UPDATED_FIELD_NAME_CONFIG_NAME);
  }

  private List<String> getValueFieldNames() {
    return config.getStringList(VALUE_FIELD_NAMES_CONFIG_NAME);
  }

  private String getTimestampFieldName() {
    return config.getString(TIMESTAMP_FIELD_NAME_CONFIG_NAME);
  }

  private String currentTimestampString() {
    return new Date(System.currentTimeMillis()).toString();
  }

  @Override
  public String getAlias() {
    return "eventtimeupsert";
  }

  private class TimestampComparator implements Comparator<Row> {
    private String timestampFieldName;

    public TimestampComparator(String timestampFieldName) {
      this.timestampFieldName = timestampFieldName;
    }

    @Override
    public int compare(Row r1, Row r2) {
      return RowUtils.compareTimestamp(r1, r2, timestampFieldName);
    }
  }

}
