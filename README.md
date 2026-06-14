# 鸭压 Android（Java 版）

“鸭压”是一个面向 Android 的本地相册清理与压缩工具，目前这个仓库保存的是 Java + XML 重构版本。

当前版本重点能力：

- 浏览本地图片和视频
- 相册权限申请与媒体扫描缓存
- 图片压缩与系统相册保存
- 视频压缩接入 Media3 Transformer
- 任务队列、完成任务列表与回收流程
- 原文件/压缩文件统一保存到系统相册“压缩对照”

## 技术栈

- Android Java
- XML UI
- Material Components
- AndroidX RecyclerView
- AndroidX Media3 Transformer
- Gradle Kotlin DSL

## 环境要求

- Android Studio 新版本
- JDK 11
- `minSdk 26`
- `targetSdk 36`
- `compileSdk 36`

## 当前实现进度

项目已经完成这些阶段：

- 阶段 1 到阶段 6：基础框架、扫描、预览、任务队列、图片压缩、系统回收站回收
- 阶段 8 大部分：视频压缩正式接入、视频设置、自定义参数、完成任务视频预览与回收链路

仍在持续打磨的方向：

- 阶段 7：集成验收与问题修复
- 阶段 8 剩余细节：视频压缩体验、兼容性与交互收口

详细实施过程见 [JAVA_REFACTOR_PLAN.md](./JAVA_REFACTOR_PLAN.md)。

## 已支持的核心流程

1. 打开应用并授予媒体读取权限
2. 浏览本地图片/视频并查看预览
3. 将图片或视频加入压缩队列
4. 将图片或视频加入回收队列
5. 执行图片压缩或视频压缩
6. 在“压缩对照”相册中查看原始版本和压缩版本
7. 在任务队列和已完成任务页面中继续预览、回收、重新压缩

## 本地运行

在 Android Studio 中直接打开 `code/` 目录即可。

命令行构建示例：

```bash
cd code
./gradlew assembleDebug
```

## 仓库结构

```text
Android_homework/
├── JAVA_REFACTOR_PLAN.md
├── README.md
└── code/
    ├── app/
    ├── build.gradle.kts
    ├── gradle.properties
    └── settings.gradle.kts
```

## 说明

这个仓库当前以功能迭代和真实开发过程记录为主，提交历史中保留了每一阶段的安卓重构轨迹，方便继续开发、验收和回溯。
