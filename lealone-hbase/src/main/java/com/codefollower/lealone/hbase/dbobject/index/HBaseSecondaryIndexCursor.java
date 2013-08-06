/*
 * Copyright 2011 The Apache Software Foundation
 *
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
package com.codefollower.lealone.hbase.dbobject.index;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;

import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.regionserver.HRegionServer;
import org.apache.hadoop.hbase.util.Bytes;

import com.codefollower.lealone.command.Prepared;
import com.codefollower.lealone.dbobject.index.Cursor;
import com.codefollower.lealone.dbobject.table.Column;
import com.codefollower.lealone.dbobject.table.TableFilter;
import com.codefollower.lealone.expression.Parameter;
import com.codefollower.lealone.hbase.command.dml.WithWhereClause;
import com.codefollower.lealone.hbase.dbobject.table.HBaseTable;
import com.codefollower.lealone.hbase.engine.HBaseSession;
import com.codefollower.lealone.hbase.result.HBaseRow;
import com.codefollower.lealone.hbase.transaction.ValidityChecker;
import com.codefollower.lealone.hbase.util.HBaseRegionInfo;
import com.codefollower.lealone.hbase.util.HBaseUtils;
import com.codefollower.lealone.message.DbException;
import com.codefollower.lealone.result.ResultInterface;
import com.codefollower.lealone.result.Row;
import com.codefollower.lealone.result.SearchRow;
import com.codefollower.lealone.util.New;
import com.codefollower.lealone.value.Value;
import com.codefollower.lealone.value.ValueString;

public class HBaseSecondaryIndexCursor implements Cursor {
    private final ByteBuffer readBuffer = ByteBuffer.allocate(256);
    private final HBaseSecondaryIndex secondaryIndex;
    private final HBaseSession session;
    private final String hostAndPort;
    private final int fetchSize;
    private final byte[] regionName;

    private final long scannerId;
    private final List<Column> columns;

    private Result[] result;
    private int index = -1;

    private SearchRow searchRow;
    private HBaseRow row;

    public HBaseSecondaryIndexCursor(HBaseSecondaryIndex index, TableFilter filter, byte[] startKey, byte[] endKey) {
        secondaryIndex = index;
        session = (HBaseSession) filter.getSession();
        HRegionServer rs = session.getRegionServer();
        //getHostAndPort()是一个字符串拼接操作，这里是一个小优化，避免在next()方法中重复调用
        hostAndPort = rs.getServerName().getHostAndPort();

        Prepared p = filter.getPrepared();
        if (!(p instanceof WithWhereClause))
            throw DbException.throwInternalError("not instanceof WithWhereClause: " + p);

        regionName = Bytes.toBytes(((WithWhereClause) p).getWhereClauseSupport().getRegionName());
        if (regionName == null)
            throw DbException.throwInternalError("regionName is null");

        fetchSize = p.getFetchSize();

        if (filter.getSelect() != null)
            columns = filter.getSelect().getColumns(filter);
        else
            columns = Arrays.asList(filter.getTable().getColumns());

        if (startKey == null)
            startKey = HConstants.EMPTY_BYTE_ARRAY;
        if (endKey == null)
            endKey = HConstants.EMPTY_BYTE_ARRAY;

        Scan scan = new Scan();
        scan.setMaxVersions(1);
        try {
            HRegionInfo info = rs.getRegionInfo(regionName);
            if (Bytes.compareTo(startKey, info.getStartKey()) >= 0)
                scan.setStartRow(startKey);
            else
                scan.setStartRow(info.getStartKey());

            if (Bytes.equals(endKey, HConstants.EMPTY_BYTE_ARRAY))
                scan.setStopRow(info.getEndKey());
            else if (Bytes.compareTo(endKey, info.getEndKey()) < 0)
                scan.setStopRow(endKey);
            else
                scan.setStopRow(info.getEndKey());

            scannerId = rs.openScanner(regionName, scan);
        } catch (Exception e) {
            throw DbException.convert(e);
        }
    }

    private byte[] dataTableName;
    private Prepared selectPrepared;
    private Parameter selectParameter;

    private void initSelectPrepared() {
        StringBuilder buff = new StringBuilder("SELECT ");

        if (columns != null) {
            int i = 0;
            for (Column c : columns) {
                if (i > 0)
                    buff.append(',');
                buff.append(c.getFullName());
                i++;
            }
        } else {
            buff.append("*");
        }
        buff.append(" FROM ");
        buff.append(secondaryIndex.getTable().getSQL());

        HBaseTable htable = (HBaseTable) secondaryIndex.getTable();
        buff.append(" WHERE ").append(htable.getPrimaryKeyName()).append("=?");
        selectPrepared = session.prepare(buff.toString(), true);
        selectParameter = selectPrepared.getParameters().get(0);
        dataTableName = htable.getTableNameAsBytes();
    }

    @Override
    public Row get() {
        if (row == null) {
            if (searchRow != null) {
                if (selectPrepared == null)
                    initSelectPrepared();

                byte[] rowKey = HBaseUtils.toBytes(searchRow.getRowKey());
                HBaseRegionInfo regionInfo = HBaseUtils.getHBaseRegionInfo(dataTableName, rowKey);
                selectParameter.setValue(ValueString.get(Bytes.toString(rowKey)));
                ResultInterface r = selectPrepared.query(1);
                if (r.next()) {
                    Value[] data = r.currentRow();
                    List<Column> cols = columns;
                    if (cols == null)
                        cols = Arrays.asList(secondaryIndex.getTable().getColumns());

                    List<KeyValue> kvs = New.arrayList(cols.size());
                    for (Column c : columns) {
                        kvs.add(new KeyValue(rowKey, c.getColumnFamilyNameAsBytes(), c.getNameAsBytes()));
                    }
                    row = new HBaseRow(regionInfo.getRegionNameAsBytes(), searchRow.getRowKey(), data, Row.MEMORY_CALCULATE,
                            new Result(kvs));
                } else {
                    throw new RuntimeException("row key " + searchRow.getRowKey() + " not found");
                }
            }
        }
        return row;
    }

    @Override
    public SearchRow getSearchRow() {
        return searchRow;
    }

    private void setSearchRow() {
        readBuffer.put(result[index].getRow());
        readBuffer.flip();
        searchRow = secondaryIndex.getRow(readBuffer);
    }

    @Override
    public boolean next() {
        readBuffer.clear();
        searchRow = null;
        row = null;
        index++;
        if (result != null && index < result.length) {
            setSearchRow();
            return true;
        }

        try {
            result = ValidityChecker.fetchResults(session, hostAndPort, regionName, scannerId, fetchSize);
        } catch (Exception e) {
            close();
            throw DbException.convert(e);
        }

        index = 0;

        if (result != null && result.length > 0) {
            setSearchRow();
            return true;
        }

        close();
        return false;
    }

    @Override
    public boolean previous() {
        return false;
    }

    private void close() {
        try {
            session.getRegionServer().close(scannerId);
        } catch (IOException e) {
            //ignore
        }
    }
}
