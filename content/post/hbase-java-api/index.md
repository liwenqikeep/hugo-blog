---
title: "HBase（一）：Java API 实战手册"
date: 2022-01-07
draft: false
categories: ["大数据"]
tags: ["HBase", "Java", "NoSQL", "大数据", "Hadoop"]
toc: true
---

## 前言

HBase 是 Apache 基金会下的分布式 NoSQL 数据库，建立在 HDFS 之上，提供实时的随机读写能力。它源自 Google 的 BigTable 论文，适合存储海量的稀疏数据——例如网页索引、监控指标、用户行为日志等。

如果你已经了解 HDFS（建议先阅读 [Hadoop 核心概念与 HDFS 架构详解]({{< relref "post/hadoop-core-concepts" >}})），那么 HBase 可以理解为**在 HDFS 之上构建了一个支持随机读写、行级一致性的分布式多维表**。

本文先用最短篇幅讲清核心概念，然后**重点实战 Java API**——涵盖 CRUD、过滤器、批量操作等生产环境最常用的场景。

<!--more-->

## 一、HBase 核心概念精炼

### 1.1 数据模型

HBase 本质上是一个四维表：**行键 + 列族 + 列限定符 + 时间戳 → 值**

```
RowKey  ColumnFamily:Qualifier  Timestamp  Value
──────  ──────────────────────  ─────────  ─────
rk001   info:name              1641600000  Alice
rk001   info:age               1641600000  28
rk001   info:email             1641600001  alice@example.com
rk002   info:name              1641600500  Bob
rk002   order:last             1641600500  ORD-2022-001
```

关键概念：

| 概念 | 说明 |
|------|------|
| **RowKey** | 行唯一标识，按字典序排序，是查询的唯一主键 |
| **ColumnFamily** | 列族，必须在建表时预定义，同一列族的数据在物理上一起存储 |
| **Qualifier** | 列限定符，列族内可动态增加 |
| **Timestamp** | 时间戳，同一单元格的不同版本数据 |
| **Cell** | 由 `(RowKey, CF, Qualifier, Timestamp)` 唯一确定的存储单元 |

### 1.2 架构简图

```
Client
  │
  ├──▶ Zookeeper（找到 Meta 表位置）
  │
  └──▶ HMaster（管理 Region 分配、负载均衡）
          │
          ▼
    ┌─────────────┐    ┌─────────────┐
    │ RegionServer 1 │    │ RegionServer 2 │
    │  ┌─────────┐  │    │  ┌─────────┐  │
    │  │ Region  │  │    │  │ Region  │  │
    │  │  ┌────┐ │  │    │  │  ┌────┐ │  │
    │  │  │HStore│ │  │    │  │  │HStore│ │  │
    │  │  └────┘ │  │    │  │  └────┘ │  │
    │  │  ┌────┐ │  │    │  │  ┌────┐ │  │
    │  │  │HStore│ │  │    │  │  │HStore│ │  │
    │  │  └────┘ │  │    │  │  └────┘ │  │
    │  └─────────┘  │    │  └─────────┘  │
    └─────────────┘    └─────────────┘
           │                    │
           └────────┬───────────┘
                    │
               ┌────▼────┐
               │  HDFS   │
               └─────────┘
```

核心要点：
- **HMaster** 负责元数据操作和 Region 分配，不参与数据读写
- **RegionServer** 负责实际数据读写，一个 RegionServer 管理多个 Region
- 每个 Region 管理一段 RowKey 区间，**通过 HDFS 实现数据持久化**
- 查询时先通过 Zookeeper 获取 `hbase:meta` 表位置，再找到目标 Region

---

## 二、Java API 实战

### 2.1 环境准备

#### Maven 依赖

```xml
<dependency>
    <groupId>org.apache.hbase</groupId>
    <artifactId>hbase-client</artifactId>
    <version>2.4.11</version>
</dependency>
```

#### 获取连接

```java
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.*;
import org.apache.hadoop.hbase.client.*;
import org.apache.hadoop.hbase.filter.*;
import org.apache.hadoop.hbase.util.Bytes;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class HBaseClientDemo {

    private static Connection connection;

    private static Connection getConnection() throws IOException {
        if (connection == null || connection.isClosed()) {
            Configuration conf = HBaseConfiguration.create();
            conf.set("hbase.zookeeper.quorum", "localhost:2181");
            connection = ConnectionFactory.createConnection(conf);
        }
        return connection;
    }

    private static Table getTable(String tableName) throws IOException {
        return getConnection().getTable(TableName.valueOf(tableName));
    }

    private static Admin getAdmin() throws IOException {
        return getConnection().getAdmin();
    }
```

> **注意**：`Connection` 是重量级对象，一个进程只需创建一个，线程安全。`Table` 是轻量级对象，建议每次使用后关闭。

### 2.2 表操作

```java
    // ========== 表操作 ==========

    /** 创建表 */
    public static void createTable(String tableName, String... columnFamilies) throws IOException {
        try (Admin admin = getAdmin()) {
            TableName tn = TableName.valueOf(tableName);
            if (admin.tableExists(tn)) {
                System.out.println("表已存在: " + tableName);
                return;
            }
            TableDescriptorBuilder builder = TableDescriptorBuilder.newBuilder(tn);
            for (String cf : columnFamilies) {
                builder.setColumnFamily(ColumnFamilyDescriptorBuilder.newBuilder(Bytes.toBytes(cf)).build());
            }
            admin.createTable(builder.build());
            System.out.println("表创建成功: " + tableName);
        }
    }

    /** 删除表 */
    public static void dropTable(String tableName) throws IOException {
        try (Admin admin = getAdmin()) {
            TableName tn = TableName.valueOf(tableName);
            if (!admin.tableExists(tn)) {
                System.out.println("表不存在: " + tableName);
                return;
            }
            admin.disableTable(tn);
            admin.deleteTable(tn);
            System.out.println("表删除成功: " + tableName);
        }
    }
```

### 2.3 增删改查（CRUD）

```java
    // ========== CRUD ==========

    /** 插入/更新一行 */
    public static void putRow(String tableName, String rowKey,
                              String cf, String qualifier, String value) throws IOException {
        try (Table table = getTable(tableName)) {
            Put put = new Put(Bytes.toBytes(rowKey));
            put.addColumn(Bytes.toBytes(cf), Bytes.toBytes(qualifier), Bytes.toBytes(value));
            table.put(put);
            System.out.println("写入成功: " + rowKey + " → " + cf + ":" + qualifier + " = " + value);
        }
    }

    /** 批量插入 */
    public static void putBatch(String tableName, String rowKey,
                                String cf, String... kvPairs) throws IOException {
        try (Table table = getTable(tableName)) {
            Put put = new Put(Bytes.toBytes(rowKey));
            for (int i = 0; i < kvPairs.length; i += 2) {
                put.addColumn(Bytes.toBytes(cf), Bytes.toBytes(kvPairs[i]), Bytes.toBytes(kvPairs[i + 1]));
            }
            table.put(put);
            System.out.println("批量写入成功: " + rowKey);
        }
    }

    /** 读取一行 */
    public static void getRow(String tableName, String rowKey) throws IOException {
        try (Table table = getTable(tableName)) {
            Get get = new Get(Bytes.toBytes(rowKey));
            Result result = table.get(get);
            if (result.isEmpty()) {
                System.out.println("未找到: " + rowKey);
                return;
            }
            System.out.println("行: " + rowKey);
            for (Cell cell : result.listCells()) {
                String family = Bytes.toString(CellUtil.cloneFamily(cell));
                String qualifier = Bytes.toString(CellUtil.cloneQualifier(cell));
                String value = Bytes.toString(CellUtil.cloneValue(cell));
                long ts = cell.getTimestamp();
                System.out.printf("  %s:%s = %s (ts=%d)%n", family, qualifier, value, ts);
            }
        }
    }

    /** 读取指定列 */
    public static void getRowWithColumn(String tableName, String rowKey,
                                        String cf, String qualifier) throws IOException {
        try (Table table = getTable(tableName)) {
            Get get = new Get(Bytes.toBytes(rowKey));
            get.addColumn(Bytes.toBytes(cf), Bytes.toBytes(qualifier));
            Result result = table.get(get);
            byte[] value = result.getValue(Bytes.toBytes(cf), Bytes.toBytes(qualifier));
            System.out.println(rowKey + " → " + cf + ":" + qualifier + " = "
                    + (value == null ? "null" : Bytes.toString(value)));
        }
    }

    /** 删除一行 */
    public static void deleteRow(String tableName, String rowKey) throws IOException {
        try (Table table = getTable(tableName)) {
            Delete delete = new Delete(Bytes.toBytes(rowKey));
            table.delete(delete);
            System.out.println("删除成功: " + rowKey);
        }
    }

    /** 扫描全表 */
    public static void scanTable(String tableName) throws IOException {
        try (Table table = getTable(tableName);
             ResultScanner scanner = table.getScanner(new Scan())) {
            for (Result result : scanner) {
                String rowKey = Bytes.toString(result.getRow());
                System.out.println("行: " + rowKey);
                for (Cell cell : result.listCells()) {
                    String family = Bytes.toString(CellUtil.cloneFamily(cell));
                    String qualifier = Bytes.toString(CellUtil.cloneQualifier(cell));
                    String value = Bytes.toString(CellUtil.cloneValue(cell));
                    System.out.printf("  %s:%s = %s%n", family, qualifier, value);
                }
            }
        }
    }
```

### 2.4 过滤器实战

过滤器（Filter）是 HBase 实现**服务端过滤**的核心机制，在数据返回到客户端之前完成过滤，大幅减少网络传输。

```java
    // ========== 过滤器 ==========

    /** 单列值过滤器：按列值精确匹配 */
    public static void filterBySingleColumnValue(String tableName, String cf,
                                                 String qualifier, String value) throws IOException {
        try (Table table = getTable(tableName)) {
            Scan scan = new Scan();
            SingleColumnValueFilter filter = new SingleColumnValueFilter(
                    Bytes.toBytes(cf),
                    Bytes.toBytes(qualifier),
                    CompareOperator.EQUAL,
                    Bytes.toBytes(value)
            );
            filter.setFilterIfMissing(true);         // 不包含该列的行直接跳过
            scan.setFilter(filter);
            try (ResultScanner scanner = table.getScanner(scan)) {
                for (Result result : scanner) {
                    System.out.println("匹配行: " + Bytes.toString(result.getRow()));
                }
            }
        }
    }

    /** 行键前缀过滤器 */
    public static void filterByRowPrefix(String tableName, String prefix) throws IOException {
        try (Table table = getTable(tableName)) {
            Scan scan = new Scan();
            // 方式1：通过 RowFilter 用正则匹配
            RowFilter filter = new RowFilter(CompareOperator.EQUAL,
                    new BinaryPrefixComparator(Bytes.toBytes(prefix)));
            scan.setFilter(filter);
            // 方式2：更高效的做法是直接设置 StartRow / EndRow
            // scan.withStartRow(Bytes.toBytes(prefix));
            // scan.withStopRow(Bytes.toBytes(prefix + "\u0000"));
            try (ResultScanner scanner = table.getScanner(scan)) {
                for (Result result : scanner) {
                    System.out.println("匹配行: " + Bytes.toString(result.getRow()));
                }
            }
        }
    }

    /** 列族过滤器：只返回指定列族 */
    public static void filterByFamily(String tableName, String cf) throws IOException {
        try (Table table = getTable(tableName)) {
            Scan scan = new Scan();
            FamilyFilter filter = new FamilyFilter(CompareOperator.EQUAL,
                    new BinaryComparator(Bytes.toBytes(cf)));
            scan.setFilter(filter);
            try (ResultScanner scanner = table.getScanner(scan)) {
                for (Result result : scanner) {
                    System.out.println("行: " + Bytes.toString(result.getRow()));
                    for (Cell cell : result.listCells()) {
                        System.out.printf("  %s:%s = %s%n",
                                Bytes.toString(CellUtil.cloneFamily(cell)),
                                Bytes.toString(CellUtil.cloneQualifier(cell)),
                                Bytes.toString(CellUtil.cloneValue(cell)));
                    }
                }
            }
        }
    }

    /** 多条件组合过滤器 */
    public static void filterWithMultipleConditions(String tableName, String cf,
                                                    String qual1, String val1,
                                                    String qual2, String val2) throws IOException {
        try (Table table = getTable(tableName)) {
            Scan scan = new Scan();
            FilterList filterList = new FilterList(FilterList.Operator.MUST_PASS_ALL);
            filterList.addFilter(new SingleColumnValueFilter(
                    Bytes.toBytes(cf), Bytes.toBytes(qual1),
                    CompareOperator.EQUAL, Bytes.toBytes(val1)));
            filterList.addFilter(new SingleColumnValueFilter(
                    Bytes.toBytes(cf), Bytes.toBytes(qual2),
                    CompareOperator.EQUAL, Bytes.toBytes(val2)));
            scan.setFilter(filterList);
            try (ResultScanner scanner = table.getScanner(scan)) {
                for (Result result : scanner) {
                    System.out.println("匹配行: " + Bytes.toString(result.getRow()));
                }
            }
        }
    }
```

**过滤器类型速查**：

| 过滤器 | 用途 | 比较器 |
|--------|------|--------|
| `SingleColumnValueFilter` | 按某列的值过滤 | `BinaryComparator` / `RegexStringComparator` / `SubstringComparator` |
| `RowFilter` | 按行键过滤 | 同上 |
| `FamilyFilter` | 按列族过滤 | 同上 |
| `QualifierFilter` | 按列名过滤 | 同上 |
| `ValueFilter` | 按单元值过滤 | 同上 |
| `ColumnPrefixFilter` | 按列名前缀匹配 | 构造时直接指定前缀 |
| `MultipleColumnPrefixFilter` | 匹配多个列名前缀 | 构造时指定前缀数组 |
| `FilterList` | 组合多个过滤器 | `MUST_PASS_ALL`（AND）或 `MUST_PASS_ONE`（OR） |

### 2.5 批量操作

```java
    // ========== 批量操作 ==========

    /** 批量写入多行 */
    public static void putMultipleRows(String tableName, String cf,
                                       String... rowKeyAndKVs) throws IOException {
        try (Table table = getTable(tableName)) {
            List<Put> puts = new ArrayList<>();
            // 参数格式: rowKey, col, val, col, val,  rowKey2, col, val, ...
            int i = 0;
            while (i < rowKeyAndKVs.length) {
                String rowKey = rowKeyAndKVs[i++];
                Put put = new Put(Bytes.toBytes(rowKey));
                while (i < rowKeyAndKVs.length && !rowKeyAndKVs[i].startsWith("row")) {
                    put.addColumn(Bytes.toBytes(cf),
                            Bytes.toBytes(rowKeyAndKVs[i]),
                            Bytes.toBytes(rowKeyAndKVs[i + 1]));
                    i += 2;
                }
                puts.add(put);
            }
            table.put(puts);
            System.out.println("批量写入 " + puts.size() + " 行成功");
        }
    }

    /** 批量删除（按行键列表） */
    public static void deleteMultipleRows(String tableName, String... rowKeys) throws IOException {
        try (Table table = getTable(tableName)) {
            List<Delete> deletes = new ArrayList<>();
            for (String rowKey : rowKeys) {
                deletes.add(new Delete(Bytes.toBytes(rowKey)));
            }
            table.delete(deletes);
            System.out.println("批量删除 " + deletes.size() + " 行成功");
        }
    }

    /** 批量混合操作（Put + Delete） */
    public static void batchOperations(String tableName) throws IOException {
        try (Table table = getTable(tableName)) {
            List<Row> batch = new ArrayList<>();
            batch.add(new Put(Bytes.toBytes("row-batch-1"))
                    .addColumn(Bytes.toBytes("info"), Bytes.toBytes("name"), Bytes.toBytes("BatchUser")));
            batch.add(new Delete(Bytes.toBytes("row-batch-2")));
            batch.add(new Get(Bytes.toBytes("row-batch-3")));
            Object[] results = new Object[batch.size()];
            table.batch(batch, results);
            for (int i = 0; i < results.length; i++) {
                System.out.println("结果 " + i + ": " + results[i]);
            }
        }
    }

    // ========== 关闭连接 ==========
    public static void close() throws IOException {
        if (connection != null && !connection.isClosed()) {
            connection.close();
            System.out.println("连接已关闭");
        }
    }
}
```

> `table.batch()` 支持在同一批次中混合不同的操作类型（Put / Get / Delete），且保证原子性——要么全部成功，要么全部失败。

---

## 三、实战演示：用户行为日志

将上述 API 整合为一个完整的场景——假设需要存储和查询用户行为日志：

```java
public static void main(String[] args) throws Exception {
    // 1. 创建表
    createTable("user_behavior", "info", "event");

    // 2. 写入数据
    putBatch("user_behavior", "user001_1641600000", "info", "name", "Alice");
    // 补充 event 列族数据
    Put put = new Put(Bytes.toBytes("user001_1641600000"));
    put.addColumn(Bytes.toBytes("event"), Bytes.toBytes("type"), Bytes.toBytes("click"));
    put.addColumn(Bytes.toBytes("event"), Bytes.toBytes("page"), Bytes.toBytes("/home"));
    try (Table table = getTable("user_behavior")) {
        table.put(put);
    }

    // 3. 查询
    getRow("user_behavior", "user001_1641600000");

    // 4. 使用过滤器查询所有 click 事件
    System.out.println("--- click 事件过滤 ---");
    filterBySingleColumnValue("user_behavior", "event", "type", "click");

    // 5. 清理
    dropTable("user_behavior");
    close();
}
```

---

## 总结

本文通过 Java API 实战，覆盖了 HBase 开发中最常用的操作模式：

| 场景 | 核心类 | 关键方法 |
|------|--------|----------|
| 表结构管理 | `Admin` | `createTable`, `disableTable`, `deleteTable` |
| 单行写入/读取/删除 | `Table` | `put`, `get`, `delete` |
| 范围扫描 | `Table` + `Scan` | `getScanner` |
| 服务端过滤 | `Filter` 子类 | `setFilter` |
| 批量操作 | `Table` | `batch`, `put(List)`, `delete(List)` |

**三个关键要点**：

1. **RowKey 设计决定一切**——HBase 按 RowKey 字典序存储和查询，合理的 RowKey 设计是性能的基石
2. **善用过滤器**——在服务端过滤数据，避免将大量数据拉到客户端再过滤
3. **Connection 是线程安全的**——全局共享一个 Connection，Table 按需创建

对于有 Java 和 Hadoop 基础的开发者来说，HBase 的 API 设计直观，CRUD 风格与传统数据库差异不大，核心需要理解的是其**分布式存储模型**和**RowKey 驱动的查询模式**。
