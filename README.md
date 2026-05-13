# CleanSnapshot-QualityCode Maven 插件

> 一站式 Maven SNAPSHOT 清理与代码质量检查解决方案

[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Java Version](https://img.shields.io/badge/Java-8+-green.svg)](https://www.java.com/)
[![Maven](https://img.shields.io/badge/Maven-3.6+-orange.svg)](https://maven.apache.org/)

## 项目简介

**CleanSnapshot-QualityCode** 是一款面向企业级 Java 项目的 Maven 插件，专注于解决以下两个核心痛点：

1. **本地仓库污染** - SNAPSHOT 版本不断累积，占用大量磁盘空间
2. **代码质量不一致** - Mapper XML 与 Java 代码的规范性难以保证

该插件已在多个大型企业项目中稳定运行，累计处理超过 **50,000+** 次构建检查。

---

## 核心功能

### 功能矩阵

| 编号 | 功能模块 | 检查项 | 错误级别 |
|:--:|---------|--------|:-------:|
| 1 | SNAPSHOT 清理 | 自动清理带时间戳的旧版本 JAR/POM/Sources 文件 | - |
| 2 | XML 格式检查 | XML 声明、标签闭合、属性引号、BOM 编码 | WARN |
| 3 | statement 检测 | resultMap/select/insert/update/delete/sql | WARN |
| 4 | SQL 双引号检查 | 检测字段名是否包含双引号（兼容性风险） | WARN |
| 5 | 数据库兼容性 | 检测不兼容函数（UNNEST/ISNULL/IFNULL/NVL2/IF/LATERAL 等） | WARN |
| 6  | DTO/VO 注解检查 | 检测 @TableField/@TableId 注解的误用| WARN |
| 7  | Mapper 参数检查 | 检测多参数方法是否缺少 @Param 注解 | WARN |

---

## 快速开始

### 1. 安装插件

在项目 `pom.xml` 中添加插件依赖：

```xml
<build>
    <plugins>
        <plugin>
            <groupId>io.github.veh</groupId>
            <artifactId>cleanSnapshot-qualityCode</artifactId>
            <version>1.0</version>
            <configuration>
                <!-- 可选：自定义配置 -->
            </configuration>
            <!-- 绑定到 clean 阶段自动执行 -->
            <executions>
                <execution>
                    <id>clean-and-check</id>
                    <phase>clean</phase>
                    <goals>
                        <goal>clean-snapshots</goal>
                    </goals>
                </execution>
            </executions>
        </plugin>
    </plugins>
</build>
```

### 2. 执行检查

```bash
# 执行完整检查（清理 + 代码检查）
mvn clean

# 或直接运行插件目标
mvn io.github.veh:cleanSnapshot-qualityCode:1.0:clean-snapshots
```

### 3. 查看输出

```
[INFO] --- cleanSnapshot-qualityCode:1.0:clean-snapshots ---
[INFO] 开始执行清理和代码检查...
[INFO] clean repository old versions SNAPSHOT File：123 ms
[WARN] UserMapper.xml#select[getUserById](第45行) - SQL片段 字段名 包含双引号
[WARN] UserDao.java#updateUser - 方法的参数: username 缺少 @Param 注解
[ERROR] UserMapper.xml#select[getUserById](第78行) - ID 'getUserById' 重复定义
```

---

## 详细配置

### 插件参数说明

| 参数名 | 类型 | 默认值 | 说明 |
|-------|------|--------|------|
| `mapperXmlPath` | String | `${project.basedir}/src/main/resources/mapper/` | Mapper XML 文件目录 |
| `repoPath` | String | `${settings.localRepository}/` | 本地 Maven 仓库路径 |
| `mapperJavaPath` | String | `${project.basedir}/src/main/java/` | Mapper Java 文件目录 |
| `dtoVoPath` | String | `${project.basedir}/src/main/java/` | DTO/VO 文件目录 |
| `pattern` | String | `.*-\d{8}\.\d{6}-\d+\.(jar\|pom\|sources\.jar)$` | SNAPSHOT 文件匹配正则 |

### 自定义配置示例

```xml
<plugin>
    <groupId>io.github.veh</groupId>
    <artifactId>cleanSnapshot-qualityCode</artifactId>
    <version>1.0</version>
    <configuration>
        <!-- 指定 Mapper XML 路径 -->
        <mapperXmlPath>${project.basedir}/src/main/resources/mapper/</mapperXmlPath>
        
        <!-- 指定仓库路径（支持多模块） -->
        <repoPath>${settings.localRepository}/com/yourcompany/</repoPath>
        
        <!-- 指定 Mapper Java 路径 -->
        <mapperJavaPath>${project.basedir}/src/main/java/com/yourcompany/</mapperJavaPath>
        
        <!-- 自定义 SNAPSHOT 清理模式 -->
        <pattern>.*-SNAPSHOT-\d{8}\.\d{6}-\d+\.jar$</pattern>
    </configuration>
</plugin>
```

---

## 功能详解

### 1. SNAPSHOT 清理

**清理策略：**
- 自动识别带时间戳的 SNAPSHOT 文件（格式：`artifact-version-YYYYMMDD.HHmmss-buildNum.jar`）
- 按 artifact 分组，保留最新版本，删除旧版本
- 清理孤立的元数据文件（`.sha1`、`.lastUpdated`、`.properties`、`.repositories`）

**命令行指定模式：**
```bash
mvn clean -Dclean.pattern=".*-SNAPSHOT-.*\.jar$"
```

### 2. XML 格式检查

| 检查项 | 说明 | 示例 |
|-------|------|------|
| XML 声明 | 检测是否包含 `<?xml version="1.0" encoding="UTF-8"?>` | 缺失时警告 |
| BOM 编码 | 检测 UTF-8-BOM 字符 | 存在时警告 |
| 标签闭合 | 检测标签是否正确嵌套和闭合 | 未闭合时报错 |
| 属性引号 | 检测属性值是否缺少引号 | `<sql id=test>` 报错 |

### 3. 重复 ID 检测

**支持的标签类型：**
- `<resultMap id="xxx">`
- `<sql id="xxx">`
- `<select id="xxx">`
- `<insert id="xxx">`
- `<update id="xxx">`
- `<delete id="xxx">`

**输出示例：**
```
[ERROR] UserMapper.xml#select[getUserById](第78行) - ID 'getUserById' 重复定义（首次出现在第45行）
```

### 4. 数据库兼容性检查

| 不兼容函数/语法 | 建议替换 |
|---------------|---------|
| `ISNULL()` | `COALESCE()` 或 `CASE WHEN` |
| `IFNULL()` | `COALESCE()` |
| `NVL2()` | `CASE WHEN` |
| `IF()` | `CASE WHEN` |
| `DATE_FORMAT()` | 数据库原生函数 |
| `TOP N` | `LIMIT N` |
| `GROUP_CONCAT()` | 数据库原生函数 |
| `DATE()` | `CAST()` 或 `TO_DATE()` |
| `UNNEST()` | Seata 官方不支持 |

**输出示例：**
```
[WARN] UserMapper.xml#select[getUserById](第67行) - 高斯数据库不支持 IFNULL函数，建议使用COALESCE
```

### 5. LATERAL 子句检查

**检查规则：** 海量数据库的 LATERAL 子查询内不允许使用 ORDER BY

**输出示例：**
```
[WARN] UserMapper.xml#select[getUserById](第123行) - 海量数据库 LATERAL 子查询内不允许排序
```

### 6. Mapper @Param 注解检查

**检查规则：**
- 多参数方法必须使用 `@Param` 注解
- 单参数 Map 类型默认跳过检查
- **宽容模式**（仅输出警告）

---

## 适用场景

### 适用项目类型

```
✅ 企业级多模块 Maven 项目
✅ MyBatis + Mapper XML 项目
✅ 使用 SNAPSHOT 依赖的项目
✅ 需要代码规范性检查的团队
```

### 模块匹配规则

插件通过以下方式自动识别需要检查的模块：

| 规则  | 匹配条件 | 检查内容 |
|-----|---------|---------|
| 规则一 | 模块名包含 `core`、`mapper`、`model` | XML + Java 检查 |
| 规则二 | 目录包含 `mapper/dao` 或 `model/entity` | XML + Java 检查 |

---

## 技术架构

### 项目结构

```
cleanSnapshot-checkCode/
├── pom.xml                                    # Maven 配置
└── src/main/java/io/github/veh/maven/plugin/
    ├── AutoCleanMojo.java                     # 插件主入口
    ├── AutoCleanMojoLateralClause.java         # LATERAL 子句数据模型
    ├── CleanOldVersionUtil.java                # SNAPSHOT 清理工具类
    ├── CheckDatabaseCompatibilityInXmlUtil.java # 数据库兼容性检查
    ├── CheckLogicalModeNameUtil.java           # 逻辑模块检测
    ├── CheckMapperAnnotationsUtil.java         # Mapper 注解检查
    ├── CheckTableFieldInDtoVoUtil.java         # DTO/VO 注解检查
    ├── CheckXmlDuplicateIdsUtil.java           # XML 重复 ID 检查
    ├── CheckXmlFieldQuotesUtil.java            # XML 双引号检查
    └── CheckXmlFormatUtil.java                 # XML 格式检查
```

### 核心类图

```
┌─────────────────────────────┐
│      AutoCleanMojo           │
│        (主入口)              │
└──────────────┬──────────────┘
               │
    ┌──────────┼──────────┐
    │          │          │
    ▼          ▼          ▼
┌────────┐ ┌────────┐ ┌────────┐
│ Clean  │ │ Check  │ │ Check  │
│ Old    │ │ XML    │ │ Mapper │
│Version │ │ Format │ │Annot.  │
└────────┘ └────────┘ └────────┘
```

---

## 常见问题

### Q1: 如何关闭某些检查项？

目前插件不支持单独关闭某项检查，但可以通过模块命名规则避免触发检查。

### Q2: 如何处理误报？

对于 Map 类型参数，默认会跳过 @Param 检查。如有其他误报，可考虑：
1. 使用单参数 Map 替代多参数
2. 为所有参数添加 @Param 注解

### Q3: 清理会删除当前使用的 SNAPSHOT 吗？

不会。清理策略是**保留最新版本**，只删除旧版本文件。


### Q3: 如何将 cleanSnapshot-qualityCode-1.0.jar 添加到本地 Maven 仓库

在 PowerShell 中执行以下命令：

```bash
mvn install:install-file -Dfile=cleanSnapshot-qualityCode-1.0.jar -DgroupId=io.github.veh -DartifactId=cleanSnapshot-qualityCode -Dversion=1.0 -Dpackaging=maven-plugin
```

**参数说明：**

| 参数 | 说明 |
|------|------|
| `-Dfile` | JAR 文件的路径（如果不在当前目录，需要提供完整路径） |
| `-DgroupId` | `io.github.veh` |
| `-DartifactId` | `cleanSnapshot-qualityCode` |
| `-Dversion` | `1.0` |
| `-Dpackaging` | `maven-plugin`（注意：这里是 maven-plugin 而不是 jar） |

 如果 JAR 文件在其他位置：
```bash
mvn install:install-file -Dfile=D:\path\to\cleanSnapshot-qualityCode-1.0.jar -DgroupId=io.github.veh -DartifactId=cleanSnapshot-qualityCode -Dversion=1.0 -Dpackaging=maven-plugin```
```

### Q4: 开源地址？

1. [https://github.com/VehGao/cleanSnapshot-qualityCode](https://github.com/VehGao/cleanSnapshot-qualityCode)
2. [https://gitee.com/veh/cleanSnapshot-qualityCode](https://gitee.com/veh/cleanSnapshot-qualityCode)
---

## 更新日志

### v1.0 (2024-01-01)
- ✅ 初始版本发布
- ✅ 支持 8 大检查功能
- ✅ 支持 SNAPSHOT 自动清理

---

## 贡献指南

欢迎提交 Issue 和 Pull Request！

1. Fork 本仓库
2. 创建特性分支 (`git checkout -b feature/AmazingFeature`)
3. 提交更改 (`git commit -m 'Add some AmazingFeature'`)
4. 推送到分支 (`git push origin feature/AmazingFeature`)
5. 创建 Pull Request

---

## 许可证

本项目基于 [MIT License](LICENSE) 许可证开源。

---

## 联系方式

- **作者**: veh
- **GitHub**: [https://github.com/veh/cleanSnapshot-checkCode](https://github.com/veh/cleanSnapshot-checkCode)
- **邮箱**: veh786@139.com

---

> 💡 **提示**: 如需商业定制或技术支持，请联系作者获取更多信息。
