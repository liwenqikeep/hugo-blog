---
title: "Hadoop（一）：核心概念与 HDFS 架构详解"
date: 2022-01-05
draft: false
categories: ["大数据"]
tags: ["Hadoop", "HDFS", "MapReduce", "YARN", "大数据", "分布式"]
toc: true
---

## 前言

Hadoop 是 Apache 基金会旗下的分布式系统基础架构，由 Doug Cutting 受 Google 三篇论文（GFS、MapReduce、BigTable）启发而创建。它解决了单机无法存储和处理海量数据的问题，是大数据生态的基石。

对于有 Java 基础的开发者来说，理解 Hadoop 的核心思想——**分而治之**和**移动计算而非移动数据**——是入门大数据的第一步。

本文从整体架构出发，重点深入 HDFS 的原理与 Java 实战，最后简要介绍 MapReduce 和 YARN。

<!--more-->

## 一、Hadoop 生态概览

Hadoop 生态的核心由三大组件构成：

| 组件 | 功能 | 类比 |
|------|------|------|
| **HDFS** | 分布式文件存储 | 将一个大文件切碎存到多台机器上 |
| **MapReduce** | 分布式计算框架 | 把计算任务分到数据所在的机器上执行 |
| **YARN** | 资源管理与调度 | 集群的"操作系统"，管理 CPU 和内存 |

三者协作关系：

```
┌─────────────────────────────────────────┐
│              MapReduce                   │
│          (分布式计算框架)                  │
├─────────────────────────────────────────┤
│                YARN                      │
│       (资源调度 / 集群管理)                │
├─────────────────────────────────────────┤
│                HDFS                      │
│          (分布式文件存储)                  │
├─────────────────────────────────────────┤
│             服务器集群                     │
│     ┌─────┐ ┌─────┐ ┌─────┐ ┌─────┐    │
│     │Node1│ │Node2│ │Node3│ │Node4│    │
│     └─────┘ └─────┘ └─────┘ └─────┘    │
└─────────────────────────────────────────┘
```

---

## 二、HDFS 架构深度解析

HDFS（Hadoop Distributed File System）是 Hadoop 的存储层，设计目标是在廉价硬件上存储海量数据（GB ~ PB 级）。

### 2.1 整体架构

HDFS 采用 **Master/Slave** 架构：

```
                  ┌─────────────┐
                  │  NameNode   │  ← Master（管理元数据）
                  │  (仅一个)    │
                  └──────┬──────┘
                         │ 管理元数据
         ┌───────────────┼───────────────┐
         │               │               │
    ┌────▼────┐    ┌────▼────┐    ┌────▼────┐
    │ DataNode│    │ DataNode│    │ DataNode│  ← Slaves（存数据块）
    │ Node1   │    │ Node2   │    │ Node3   │
    └─────────┘    └─────────┘    └─────────┘
         │               │               │
    ┌────┴────┐    ┌────┴────┐    ┌────┴────┐
    │ B1  B2  │    │ B3  B4  │    │ B1  B3  │  ← Block（默认128MB）
    │ B4  B5  │    │ B2  B5  │    │ B4  B5  │     副本数=3
    └─────────┘    └─────────┘    └─────────┘
```

**核心角色**：

| 角色 | 职责 | 特点 |
|------|------|------|
| **NameNode** | 管理文件系统的命名空间（目录树、文件名→Block 映射） | 仅一个，内存中全量元数据，是 HDFS 的单点 |
| **DataNode** | 存储实际数据块（Block），响应读写请求 | 多个，定期向 NameNode 发送心跳和 Block 报告 |
| **Secondary NameNode** | 辅助合并 NameNode 的 edits 日志到 fsimage | 不是热备，只是 checkpoint 辅助角色 |

### 2.2 文件读写流程

#### 读文件流程

```
Client                    NameNode
  │                          │
  │  1. 请求读取 /foo/bar    │
  │─────────────────────────▶│
  │  2. 返回 Block 位置列表  │
  │◀─────────────────────────│
  │                          │
  │  3. 从最近的 DataNode    │
  │     读取 Block 数据      │
  │ ┌─────┐ ┌─────┐ ┌─────┐ │
  │ │DN-1 │ │DN-2 │ │DN-3 │ │
  │ └─────┘ └─────┘ └─────┘ │
  │  4. 合并 Block 得到文件  │
```

关键点：
- Client 先问 NameNode 元数据（文件由哪些 Block 组成，各 Block 在哪些 DataNode）
- 然后直接从 DataNode 读取数据，**不经过 NameNode**
- 选择距离最近的 DataNode（网络拓扑感知）

#### 写文件流程

```
Client                    NameNode
  │                          │
  │  1. 请求创建文件         │
  │─────────────────────────▶│
  │  2. 确认可写，返回       │
  │◀─────────────────────────│
  │                          │
  │  3. 按 Block 写入        │
  │     先写 Block1          │
  │ ────▶ DN-1 ───▶ DN-2 ───▶ DN-3  (Pipeline 写入)
  │     再写 Block2          │
  │ ────▶ DN-2 ───▶ DN-3 ───▶ DN-1
  │                          │
  │  4. 所有 Block 写完后    │
  │     通知 NameNode 关闭   │
  │─────────────────────────▶│
```

关键点：
- 数据以 **Pipeline（管道）** 方式依次写入副本节点
- 副本数默认 3，第一个副本写在 Client 所在节点，第二个写在不同机架，第三个在同一机架的另一节点

### 2.3 副本策略与数据可靠性

HDFS 的默认副本策略：

```
副本1: Client 所在节点（同一机架）
副本2: 不同机架的任意节点
副本3: 同一机架的另一节点
```

这种策略在**可靠性**与**写入性能**之间取得了平衡：

- 跨机架副本防止整个机架断电
- 同一机架的两个副本之间写入网络开销小
- 读数据时可以从同机架读取，减少跨机架带宽

**可靠性机制**：
- **心跳检测**：DataNode 每 3 秒向 NameNode 发送心跳，超时 10 分钟标记为死亡
- **Block 副本校验**：DataNode 定期上报 Block 列表，NameNode 对比发现副本不足时自动复制
- **数据完整性**：写入时计算校验和（CRC32C），读取时验证

### 2.4 Java API 操作 HDFS 实战

对于 Java 开发者来说，通过 Hadoop 客户端 API 操作 HDFS 是最直接的入门方式。

#### 添加依赖

```xml
<dependency>
    <groupId>org.apache.hadoop</groupId>
    <artifactId>hadoop-client</artifactId>
    <version>3.3.1</version>
</dependency>
```

#### 完整示例代码

```java
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.*;
import java.io.*;
import java.net.URI;

public class HdfsClientDemo {

    private static FileSystem getFileSystem() throws Exception {
        Configuration conf = new Configuration();
        // 指定 HDFS 地址（实际使用时替换为你的集群地址）
        conf.set("fs.defaultFS", "hdfs://localhost:9000");
        // 设置用户（HDFS 权限校验用）
        System.setProperty("HADOOP_USER_NAME", "hadoop");
        return FileSystem.get(new URI("hdfs://localhost:9000"), conf, "hadoop");
    }

    /** 创建目录 */
    public static void mkdir(String dir) throws Exception {
        FileSystem fs = getFileSystem();
        Path path = new Path(dir);
        if (fs.mkdirs(path)) {
            System.out.println("目录创建成功: " + dir);
        }
        fs.close();
    }

    /** 上传本地文件到 HDFS */
    public static void upload(String localPath, String hdfsPath) throws Exception {
        FileSystem fs = getFileSystem();
        Path src = new Path(localPath);
        Path dst = new Path(hdfsPath);
        fs.copyFromLocalFile(src, dst);
        System.out.println("上传完成: " + localPath + " → " + hdfsPath);
        fs.close();
    }

    /** 下载 HDFS 文件到本地 */
    public static void download(String hdfsPath, String localPath) throws Exception {
        FileSystem fs = getFileSystem();
        Path src = new Path(hdfsPath);
        Path dst = new Path(localPath);
        fs.copyToLocalFile(false, src, dst, true);
        System.out.println("下载完成: " + hdfsPath + " → " + localPath);
        fs.close();
    }

    /** 列出目录下所有文件（包含 Block 信息） */
    public static void listFiles(String dir) throws Exception {
        FileSystem fs = getFileSystem();
        Path path = new Path(dir);
        RemoteIterator<LocatedFileStatus> iterator = fs.listFiles(path, true);
        while (iterator.hasNext()) {
            LocatedFileStatus status = iterator.next();
            System.out.println("文件: " + status.getPath());
            System.out.println("  Block 大小: " + status.getBlockSize() / 1024 / 1024 + "MB");
            System.out.println("  副本数: " + status.getReplication());
            System.out.println("  文件大小: " + status.getLen() + " bytes");
            // 打印每个 Block 的位置信息
            BlockLocation[] blocks = status.getBlockLocations();
            for (BlockLocation blk : blocks) {
                System.out.println("  Block 位于: " + String.join(",", blk.getHosts()));
            }
        }
        fs.close();
    }

    /** 读取文件内容 */
    public static void read(String hdfsPath) throws Exception {
        FileSystem fs = getFileSystem();
        Path path = new Path(hdfsPath);
        FSDataInputStream in = fs.open(path);
        BufferedReader reader = new BufferedReader(new InputStreamReader(in));
        String line;
        while ((line = reader.readLine()) != null) {
            System.out.println(line);
        }
        reader.close();
        fs.close();
    }

    public static void main(String[] args) throws Exception {
        // 演示：依次执行常见操作
        mkdir("/user/hadoop/test");
        upload("README.txt", "/user/hadoop/test/README.txt");
        listFiles("/user/hadoop/test");
        read("/user/hadoop/test/README.txt");
        download("/user/hadoop/test/README.txt", "./download_README.txt");
    }
}
```

代码要点：
- `FileSystem` 是 HDFS 操作的核心入口，所有操作都通过它完成
- `copyFromLocalFile` / `copyToLocalFile` 封装了上传下载的完整流程
- `listFiles` 返回 `LocatedFileStatus`，包含 Block 分布信息，体现了 HDFS 的分布式特性
- 实际生产环境中建议使用连接池管理 `FileSystem` 实例

---

## 三、MapReduce 简要概述

MapReduce 是 Hadoop 的分布式计算框架，核心思想源自函数式编程：

1. **Map（映射）**：将输入数据切分成独立的小块，并行处理
2. **Shuffle（洗牌）**：框架自动将 Map 输出按键分组、排序、传输到 Reducer
3. **Reduce（归约）**：对分组后的数据进行聚合计算

**经典 WordCount 示例**：

```
输入: "hello world hello hadoop"

Map 阶段:
  "hello"  → (hello, 1)
  "world"  → (world, 1)
  "hello"  → (hello, 1)
  "hadoop" → (hadoop, 1)

Shuffle 阶段（框架自动完成）:
  (hello, [1, 1])  (hadoop, [1])  (world, [1])

Reduce 阶段:
  (hello, 2)  (hadoop, 1)  (world, 1)
```

开发者只需编写 Map 和 Reduce 逻辑，**并行计算、容错、数据分发**全部由框架自动完成。

---

## 四、YARN 简要概述

YARN（Yet Another Resource Negotiator）是 Hadoop 2.x 引入的资源管理层，将资源管理和作业调度分离：

```
┌──────────────────────────────────────────┐
│              ResourceManager              │  ← 全局资源调度
│  ┌──────────┐  ┌───────────────────────┐  │
│  │ Scheduler │  │ ApplicationsManager  │  │
│  └──────────┘  └───────────────────────┘  │
├──────────────────────────────────────────┤
│  NodeManager    NodeManager   NodeManager │  ← 每节点管理
│  ┌───────┐     ┌───────┐     ┌───────┐   │
│  │Container│     │Container│     │Container│  ← 资源容器
│  └───────┘     └───────┘     └───────┘   │
│  ┌───────┐     ┌───────┐                 │
│  │Container│     │Container│                 │
│  └───────┘     └───────┘                 │
└──────────────────────────────────────────┘
```

YARN 的核心优势在于，它不再局限于 MapReduce，**Spark、Flink、Flink 等计算框架都可以运行在 YARN 上**，实现了计算资源与计算框架的解耦。

---

## 总结

本文从 Hadoop 生态概览出发，重点深入了 HDFS 的架构原理和 Java 实战操作，并简要介绍了 MapReduce 和 YARN 的核心思想。

三个关键 takeaways：

1. **HDFS** 将大文件切分为 Block（默认 128MB），通过多副本机制保障数据可靠性
2. **MapReduce** 践行"计算向数据移动"的理念，框架自动处理并行和容错
3. **YARN** 统一管理集群资源，让多种计算框架共享同一集群

对于 Java 开发者而言，HDFS 的客户端 API 设计直观，只需引入 `hadoop-client` 依赖即可像操作本地文件一样操作分布式文件系统。这也是进入大数据世界的第一步。
