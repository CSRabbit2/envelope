package com.cloudera.labs.envelope.plan.bulk;

import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.apache.spark.sql.DataFrame;
import org.apache.spark.sql.functions;

import com.cloudera.labs.envelope.plan.MutationType;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.typesafe.config.Config;

import scala.Tuple2;

/**
 * A planner implementation for appending the stream to the storage table. Only plans insert mutations.
 */
public class AppendPlanner extends BulkPlanner {
    
    public final static String KEY_FIELD_NAMES_CONFIG_NAME = "fields.key";
    public final static String LAST_UPDATED_FIELD_NAME_CONFIG_NAME = "field.last.updated";
    public final static String UUID_KEY_CONFIG_NAME = "uuid.key.enabled";
    
    public AppendPlanner(Config config) {
        super(config);
    }
    
    @Override
    public List<Tuple2<MutationType, DataFrame>> planMutationsForSet(DataFrame arriving)
    {
        if (setsKeyToUUID()) {
            if (!hasKeyFields()) {
                throw new RuntimeException("Key columns must be specified to provide UUID keys");
            }
          
            arriving = arriving.withColumn(getKeyFieldNames().get(0), functions.lit(UUID.randomUUID().toString()));
        }
        
        if (hasLastUpdatedField()) {
            arriving = arriving.withColumn(getLastUpdatedFieldName(), functions.lit(currentTimestampString()));
        }
        
        List<Tuple2<MutationType, DataFrame>> planned = Lists.newArrayList();
        
        planned.add(new Tuple2<MutationType, DataFrame>(MutationType.INSERT, arriving));
        
        return planned;
    }

    @Override
    public Set<MutationType> getEmittedMutationTypes() {
        return Sets.newHashSet(MutationType.INSERT);
    }
    
    private String currentTimestampString() {
        return new Date(System.currentTimeMillis()).toString();
    }
    
    private boolean hasKeyFields() {
        return config.hasPath(KEY_FIELD_NAMES_CONFIG_NAME);
    }
    
    private List<String> getKeyFieldNames() {
        return config.getStringList(KEY_FIELD_NAMES_CONFIG_NAME);
    }
    
    private boolean hasLastUpdatedField() {
        return config.hasPath(LAST_UPDATED_FIELD_NAME_CONFIG_NAME);
    }
    
    private String getLastUpdatedFieldName() {
        return config.getString(LAST_UPDATED_FIELD_NAME_CONFIG_NAME);
    }
    
    private boolean setsKeyToUUID() {
        if (!config.hasPath(UUID_KEY_CONFIG_NAME)) return false;
        
        return Boolean.parseBoolean(config.getString(UUID_KEY_CONFIG_NAME));
    }
    
}
